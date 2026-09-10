import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Character, UserWorld } from '../api/types.ts'

const sourceRoot = new URL('../', import.meta.url)
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}.ts`, sourceRoot).href, shortCircuit: true }
    }
    if (context.parentURL?.startsWith(sourceRoot.href)
      && /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
      return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (!url.endsWith('/api/client.ts')) return nextLoad(url, context)
    const source = readFileSync(new URL(url), 'utf8')
    return {
      format: 'module-typescript',
      shortCircuit: true,
      source: source.replace('import.meta.env.VITE_API_BASE_URL', 'undefined'),
    }
  },
})

async function mountDirectChat() {
  const { api } = await import('../api/client.ts')
  const { useDirectChat } = await import('../composables/useDirectChat.ts')
  const { computed, createRenderer, defineComponent, h, ref } = await import('vue')
  const world = computed<UserWorld>(() => ({ id: 3, worldId: 2, name: '测试世界', thinkStatus: true }))
  const characters = ref<Character[]>([{ userWorldId: 3, characterId: 7, characterName: '测试角色' }])
  let chat!: ReturnType<typeof useDirectChat>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {},
    insert(child, parent) {
      const children = (parent.children ||= []) as Array<Record<string, unknown>>
      children.push(child)
      child.parent = parent
    },
    remove() {},
    createElement: () => ({}),
    createText: (text) => ({ text }),
    createComment: (text) => ({ text }),
    setText(node, text) { node.text = text },
    setElementText(node, text) { node.text = text },
    parentNode: (node) => node.parent as Record<string, unknown> | null,
    nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({
    setup() {
      chat = useDirectChat({ world, characters, reloadCharacters: async () => undefined })
      return () => h('div')
    },
  }))
  app.mount({})
  return { api, chat, app }
}

test('rebuilds every stored reasoning step in one process with the tool summary first', async () => {
  const { api, chat, app } = await mountDirectChat()
  const originalHistory = api.history
  const originalModelApis = api.modelApis
  try {
    api.history = async () => [
      { id: 10, type: 'USER', content: '发生了什么？' },
      { type: 'thinking', content: '先确认现场。', userMessageId: 10, stepNo: 1 },
      { type: 'tool', content: '' },
      { type: 'tool', content: '' },
      { type: 'thinking', content: '工具完成，继续判断。', userMessageId: 10, stepNo: 2 },
      { id: 11, type: 'ASSISTANT', content: '门外没有人。', userMessageId: 10, stepNo: 2 },
    ]
    api.modelApis = async () => []

    await chat.selectCharacter(7)

    assert.deepEqual(chat.messages.value.map(({ role, content }) => ({ role, content })), [
      { role: 'user', content: '发生了什么？' },
      { role: 'thinking', content: '调用了2次工具\n\n先确认现场。工具完成，继续判断。' },
      { role: 'assistant', content: '门外没有人。' },
    ])
  } finally {
    api.history = originalHistory
    api.modelApis = originalModelApis
    app.unmount()
  }
})

test('appends live tool calls inside the current reasoning process', async () => {
  const previousFetch = globalThis.fetch
  const previousLocalStorage = globalThis.localStorage
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
    fetch: async () => new Response([
      'data: {"type":"thinking","content":"先判断"}',
      'data: {"type":"tool","content":"调用了工具"}',
      'data: {"type":"thinking","content":"继续判断"}',
      'data: {"type":"response","content":"结果"}',
      '',
    ].join('\n')),
  })
  const { api, chat, app } = await mountDirectChat()
  const originalHistory = api.history
  const originalModelApis = api.modelApis
  try {
    api.history = async () => []
    api.modelApis = async () => []
    await chat.selectCharacter(7)
    chat.input.value = '查看现场'

    await chat.send()

    assert.deepEqual(chat.messages.value.map(({ role, content }) => ({ role, content })), [
      { role: 'user', content: '查看现场' },
      { role: 'thinking', content: '先判断\n\n调用了工具\n\n继续判断' },
      { role: 'assistant', content: '结果' },
    ])
  } finally {
    api.history = originalHistory
    api.modelApis = originalModelApis
    app.unmount()
    Object.assign(globalThis, { fetch: previousFetch, localStorage: previousLocalStorage })
  }
})


test('restores a character draft on return and clears it when the account signs out', async () => {
  const { api, chat, app } = await mountDirectChat()
  const originalHistory = api.history
  const originalModelApis = api.modelApis
  try {
    api.history = async () => []
    api.modelApis = async () => []
    await chat.selectCharacter(7)
    chat.input.value = '尚未发出的问候'
    chat.close()
    assert.equal(chat.input.value, '')
    await chat.selectCharacter(7)
    assert.equal(chat.input.value, '尚未发出的问候')
    chat.clearDrafts()
    chat.close()
    await chat.selectCharacter(7)
    assert.equal(chat.input.value, '')
  } finally {
    api.history = originalHistory
    api.modelApis = originalModelApis
    app.unmount()
  }
})
