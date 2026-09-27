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
  const characters = ref<Character[]>([{ userWorldId: 3, characterId: 7, characterName: '测试角色' }, { userWorldId: 3, characterId: 8, characterName: '角色B' }])
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


function storage() {
  const data = new Map<string, string>()
  return { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) }, removeItem: (key: string) => { data.delete(key) }, key: (i: number) => [...data.keys()][i] ?? null, get length() { return data.size } }
}
function event(controller: ReadableStreamDefaultController, type: string, content: string, sequence: number) {
  controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ type, content, sequence })}\n\n`))
}
async function settle() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)) }

test('keeps concurrent character replies, errors and sending flags in their own conversations', async () => {
  const requests: Array<{ controller: ReadableStreamDefaultController; payload: any }> = []
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async (_url: string, init: RequestInit) => new Response(new ReadableStream({ start(controller) { requests.push({ controller, payload: JSON.parse(String(init.body)) }) } })) })
  const { api, chat, app } = await mountDirectChat()
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = 'A的问题'; const a = chat.send(); await settle()
    await chat.selectCharacter(8)
    assert.equal(chat.loading.sending, false, 'A must not block B')
    chat.input.value = 'B的问题'; const b = chat.send(); await settle()
    assert.equal(requests.length, 2)
    event(requests[0]!.controller, 'response', 'A的回复', 1)
    event(requests[0]!.controller, 'generation.completed', '', 2); requests[0]!.controller.close()
    await a
    assert.equal(chat.loading.sending, true, 'A completion must not unlock B')
    assert.deepEqual(chat.messages.value.map(m => m.content), ['B的问题'])
    api.history = async (_world, character) => character === 7
      ? [{ id: 10, type: 'USER', content: 'A的问题' }, { id: 11, type: 'ASSISTANT', content: 'A的回复' }]
      : [{ id: 12, type: 'USER', content: 'B的问题' }]
    await chat.selectCharacter(7)
    assert.deepEqual(chat.messages.value.map(m => m.content), ['A的问题', 'A的回复'])
    event(requests[1]!.controller, 'generation.failed', 'B生成失败', 1); requests[1]!.controller.close(); await b
    assert.deepEqual(chat.messages.value.map(m => m.content), ['A的问题', 'A的回复'])
    await chat.selectCharacter(8)
    assert.ok(chat.messages.value.some(m => m.content.includes('B生成失败')))
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('resumes after refresh without duplicating persisted user, thinking or assistant messages', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  let resumeUrl = ''
  let resumed!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async (url: string, init: RequestInit) => {
    if (init.method === 'POST') return new Response(new ReadableStream({ start(c) { controller = c } }))
    resumeUrl = String(url)
    return new Response(new ReadableStream({ start(c) {
      resumed = c
      c.enqueue(new TextEncoder().encode([
      { type: 'generation.started', content: '问题', sequence: 1 },
      { type: 'generation.user', content: '20', sequence: 2 },
      { type: 'tool', content: '调用了工具', sequence: 3 },
      { type: 'response', content: '完整回复', sequence: 4 },
    ].map(e => `data: ${JSON.stringify(e)}\n\n`).join('')))
    } }))
  } })
  const first = await mountDirectChat()
  const old = { history: first.api.history, modelApis: first.api.modelApis }
  first.api.history = async () => []; first.api.modelApis = async () => []
  let second: Awaited<ReturnType<typeof mountDirectChat>> | undefined
  try {
    await first.chat.selectCharacter(7); first.chat.input.value = '问题'; const sending = first.chat.send(); await settle()
    first.app.unmount(); controller.close(); await sending
    first.api.history = async () => [
      { id: 10, type: 'ASSISTANT', content: '较早消息' },
      { id: 20, type: 'USER', content: '问题' },
      { type: 'tool' },
      { id: 21, userMessageId: 20, type: 'ASSISTANT', content: '完整回复' },
      { id: 30, type: 'USER', content: '另一窗口的后续问题' },
      { id: 31, userMessageId: 30, type: 'ASSISTANT', content: '后续回复' },
    ]
    second = await mountDirectChat(); await second.chat.selectCharacter(7); await settle()
    assert.match(resumeUrl, /\/ai\/chat\/3\/7\/generations\//)
    assert.deepEqual(second.chat.messages.value.map(m => m.content), ['较早消息', '问题', '调用了工具', '完整回复', '另一窗口的后续问题', '后续回复'])
    assert.equal(second.chat.loading.sending, true)
    event(resumed, 'generation.completed', '', 5); resumed.close(); await settle()
    assert.deepEqual(second.chat.messages.value.map(m => m.content), ['较早消息', '问题', '调用了1次工具', '完整回复', '另一窗口的后续问题', '后续回复'])
    assert.equal(second.chat.loading.sending, false)
    assert.equal(sessionStorage.length, 0)
  } finally { second?.app.unmount(); Object.assign(first.api, old); Object.assign(globalThis, original) }
})

test('reconnects an interrupted stream from the last event and does not duplicate replayed deltas', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  const urls: string[] = []
  let resumed!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async (url: string, init: RequestInit) => {
    urls.push(String(url))
    if (init.method === 'POST') return new Response(new ReadableStream({ start(c) { controller = c } }))
    return new Response(new ReadableStream({ start(c) {
      resumed = c
      c.enqueue(new TextEncoder().encode([
      { type: 'response', content: '前半', sequence: 2 },
      { type: 'response', content: '后半', sequence: 3 },
    ].map(e => `data: ${JSON.stringify(e)}\n\n`).join('')))
    } }))
  } })
  const { api, chat, app } = await mountDirectChat(); const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = '问题'; const sending = chat.send(); await settle()
    event(controller, 'generation.started', '问题', 1); event(controller, 'response', '前半', 2)
    await settle(); controller.error(new Error('disconnected'))
    for (let i = 0; i < 200 && !resumed; i++) await new Promise(resolve => setTimeout(resolve, 10))
    assert.ok(resumed, 'stream should reconnect within two seconds')
    await settle()
    assert.equal(urls.length, 2)
    assert.match(urls[1]!, /after=2$/)
    assert.deepEqual(chat.messages.value.map(m => m.content), ['问题', '前半后半'])
    api.history = async () => [{ id: 20, type: 'USER', content: '问题' }, { id: 21, type: 'ASSISTANT', content: '前半后半', userMessageId: 20 }]
    event(resumed, 'generation.completed', '', 4); resumed.close(); await sending
    assert.deepEqual(chat.messages.value.map(m => m.content), ['问题', '前半后半'])
    assert.equal(sessionStorage.length, 0)
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('expired replay reloads authoritative history and releases the pending request', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { window: { setTimeout, clearTimeout }, localStorage: storage(), sessionStorage: storage(), fetch: async (_url: string, init: RequestInit) => {
    if (init.method === 'POST') return new Response(new ReadableStream({ start(c) { controller = c } }))
    return new Response('data: {"type":"generation.expired"}\n\n')
  } })
  const first = await mountDirectChat(); const old = { history: first.api.history, modelApis: first.api.modelApis }
  first.api.history = async () => []; first.api.modelApis = async () => []
  let second: Awaited<ReturnType<typeof mountDirectChat>> | undefined
  try {
    await first.chat.selectCharacter(7); first.chat.input.value = '问题'; const sending = first.chat.send(); await settle()
    first.app.unmount(); controller.close(); await sending
    first.api.history = async () => [{ id: 21, type: 'ASSISTANT', content: '已保存回复' }]
    second = await mountDirectChat(); await second.chat.selectCharacter(7); await settle()
    assert.deepEqual(second.chat.messages.value.map(m => m.content), ['已保存回复'])
    assert.equal(second.chat.loading.sending, false); assert.equal(sessionStorage.length, 0)
  } finally { second?.app.unmount(); Object.assign(first.api, old); Object.assign(globalThis, original) }
})

test('logout discards pending streams and their late success or error callbacks', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async () => new Response(new ReadableStream({ start(c) { controller = c } })) })
  const { api, chat, app } = await mountDirectChat(); const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = '旧账号的问题'; const sending = chat.send(); await settle()
    chat.close(); chat.clearDrafts(); await chat.selectCharacter(8)
    event(controller, 'response', '旧账号回复', 1); event(controller, 'generation.failed', '旧账号错误', 2); controller.close(); await sending
    assert.deepEqual(chat.messages.value, []); assert.equal(chat.loading.sending, false)
    assert.equal(sessionStorage.length, 0)
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('re-entering an idle conversation refreshes history changed by another tab or a world restore', async () => {
  const { api, chat, app } = await mountDirectChat(); const old = { history: api.history, modelApis: api.modelApis }
  api.modelApis = async () => []
  try {
    api.history = async () => [{ id: 21, type: 'ASSISTANT', content: '读档前的消息' }]
    await chat.selectCharacter(7); chat.close()
    api.history = async () => [{ id: 10, type: 'ASSISTANT', content: '存档中的消息' }]
    await chat.selectCharacter(7)
    assert.deepEqual(chat.messages.value.map(m => m.content), ['存档中的消息'])
  } finally { app.unmount(); Object.assign(api, old) }
})

test('world restore invalidates pending in-page replies and clears their recovery markers', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async () => new Response(new ReadableStream({ start(c) { controller = c } })) })
  const { api, chat, app } = await mountDirectChat(); const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = '读档前问题'; const sending = chat.send(); await settle()
    chat.invalidateWorld(3)
    api.history = async () => [{ id: 10, type: 'ASSISTANT', content: '存档消息' }]
    await chat.selectCharacter(7)
    event(controller, 'response', '过期回复', 1); event(controller, 'generation.completed', '', 2); controller.close(); await sending
    assert.deepEqual(chat.messages.value.map(m => m.content), ['存档消息'])
    assert.equal(sessionStorage.length, 0)
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('an old account HTTP 401 cannot log out the newly authenticated account', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, window: globalThis.window }
  let resolve!: (response: Response) => void
  let unauthorizedEvents = 0
  Object.assign(globalThis, { localStorage: storage(), window: { dispatchEvent() { unauthorizedEvents++ } }, fetch: async () => new Promise<Response>(r => { resolve = r }) })
  const { api, app } = await mountDirectChat()
  try {
    localStorage.setItem('galchat.token', 'old-token')
    const loading = api.modelApis()
    localStorage.setItem('galchat.token', 'new-token')
    resolve(new Response('', { status: 401 }))
    await assert.rejects(loading)
    assert.equal(localStorage.getItem('galchat.token'), 'new-token')
    assert.equal(unauthorizedEvents, 0)
  } finally { app.unmount(); Object.assign(globalThis, original) }
})

test('completed generation releases live state and displays only authoritative persisted history', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async () => new Response(new ReadableStream({ start(c) { controller = c } })) })
  const { api, chat, app } = await mountDirectChat(); const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = '问题'; const sending = chat.send(); await settle()
    event(controller, 'response', '流中的内容', 1); await settle()
    assert.ok(chat.messages.value.some(m => m.content === '流中的内容'))
    api.history = async () => [{ id: 20, type: 'USER', content: '问题' }, { id: 21, userMessageId: 20, type: 'ASSISTANT', content: '数据库中的回复' }]
    event(controller, 'generation.completed', '', 2); controller.close(); await sending
    assert.deepEqual(chat.messages.value.map(m => m.content), ['问题', '数据库中的回复'])
    assert.equal(chat.loading.sending, false); assert.equal(sessionStorage.length, 0)
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})
