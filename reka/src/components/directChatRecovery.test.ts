import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Character, ChatHistory, GenerationErrorDetail, SingleChatRuntime, UserWorld } from '../api/types.ts'

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

async function mountDirectChat(acitvePushStatus = false, reloadCharacters: () => Promise<void> = async () => undefined) {
  const { api } = await import('../api/client.ts')
  const { useDirectChat } = await import('../composables/useDirectChat.ts')
  const { createCharacterData } = await import('../composables/characterData.ts')
  const { computed, createRenderer, defineComponent, h, ref } = await import('vue')
  const worldState = ref<UserWorld>({ id: 3, worldId: 2, name: '测试世界', acitvePushStatus })
  const world = computed(() => worldState.value)
  const settingsSaving = ref(false)
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
      const data = createCharacterData({ worldId: computed(() => world.value?.id ?? null), sessionKey: () => '', characters, templates: ref([]) })
      chat = useDirectChat({ world, characters, saveCharacterModel: data.saveModel, settingsSaving, reloadCharacters })
      return () => h('div')
    },
  }))
  app.mount({})
  return { api, chat, app, characters, worldState, settingsSaving }
}


function storage() {
  const data = new Map<string, string>()
  return { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) }, removeItem: (key: string) => { data.delete(key) }, key: (i: number) => [...data.keys()][i] ?? null, get length() { return data.size } }
}
function event(controller: ReadableStreamDefaultController, type: string, content: string, sequence: number, errorDetail?: GenerationErrorDetail) {
  controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ type, content, sequence, errorDetail })}\n\n`))
}
async function settle() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)) }

test('withdrawal HTTP request includes the expected message ID', async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage }
  t.after(() => Object.assign(globalThis, original))
  let requested = false
  Object.assign(globalThis, { localStorage: storage(), fetch: async (url: string, init: RequestInit) => {
    const request = new URL(String(url), 'http://localhost')
    assert.equal(init.method, 'POST')
    assert.equal(request.pathname, '/api/history/withdraw')
    assert.deepEqual(Object.fromEntries(request.searchParams), { userworldid: '3', characterid: '7', expectedMessageId: '20' })
    requested = true
    return Response.json({ code: 1 })
  } })
  const { api } = await import('../api/client.ts')
  await api.withdrawMessage(3, 7, 20)
  assert.equal(requested, true)
})

test('withdraws a standalone care message without requiring a user message in the loaded page', async () => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis, withdrawMessage: api.withdrawMessage }
  let withdrawn = false
  api.history = async () => withdrawn ? [] : [{ id: 20, type: 'assistant', content: '主动关怀', userWorldId: 3, characterId: 7 }]
  api.modelApis = async () => []
  api.withdrawMessage = async (worldId, characterId, expectedMessageId) => {
    assert.equal(worldId, 3); assert.equal(characterId, 7)
    assert.equal(expectedMessageId, 20)
    withdrawn = true
  }
  try {
    await chat.selectCharacter(7)
    assert.equal(chat.canWithdraw.value, true)
    await chat.withdraw()
    assert.equal(withdrawn, true)
    assert.deepEqual(chat.messages.value, [])
    assert.equal(chat.canWithdraw.value, false)
    assert.equal(chat.input.value, '')
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const draft of ['', '新草稿']) test(`withdraws the latest user anchor without overwriting drafts (draft=${Boolean(draft)})`, async (t) => {
  const originalWindow = globalThis.window
  Object.assign(globalThis, { window: { setTimeout, clearTimeout } })
  t.after(() => { globalThis.window = originalWindow })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(api, 'modelApis', async () => [])
  let withdrawn = false
  t.mock.method(api, 'history', async () => withdrawn ? [] : [
    { id: 10, type: 'assistant', content: '关怀' },
    { id: 20, type: 'user', content: '问题' },
    { id: 21, type: 'assistant', userMessageId: 20, content: '回复' },
  ])
  t.mock.method(api, 'withdrawMessage', async (worldId: number, characterId: number, expectedMessageId: number) => {
    assert.deepEqual([worldId, characterId, expectedMessageId], [3, 7, 20])
    withdrawn = true
  })
  await chat.selectCharacter(7)
  chat.messages.value.push({ id: 'error', role: 'assistant', content: '旧错误', complete: false })
  chat.input.value = draft
  await chat.withdraw()
  assert.equal(withdrawn, true)
  assert.equal(chat.input.value, draft || '问题')
})

test('failed history refresh after withdrawal blocks further withdrawals until a successful reload', async (t) => {
  const originalWindow = globalThis.window
  Object.assign(globalThis, { window: { setTimeout, clearTimeout } })
  t.after(() => { globalThis.window = originalWindow })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(api, 'modelApis', async () => [])
  let withdrawals = 0; let refreshFails = true
  const older = { id: 10, type: 'user', content: '更早一轮' }
  t.mock.method(api, 'history', async () => {
    if (withdrawals && refreshFails) throw new Error('历史读取暂时失败')
    return withdrawals ? [older] : [older, { id: 20, type: 'user', content: '最后一轮' },
      { id: 21, type: 'assistant', userMessageId: 20, content: '最后一轮回复' }]
  })
  t.mock.method(api, 'withdrawMessage', async () => { withdrawals++ })
  await chat.selectCharacter(7); await chat.withdraw()
  assert.equal(chat.input.value, '最后一轮')
  assert.equal(chat.canWithdraw.value, false)
  assert.ok(!chat.messages.value.some(message => message.historyId === 20 || message.userMessageId === 20))
  assert.match(notice.title + notice.message, /加载失败|刷新失败/)
  await chat.withdraw()
  assert.equal(withdrawals, 1)
  refreshFails = false
  await chat.selectCharacter(7)
  assert.equal(chat.canWithdraw.value, true)
})

test('a rejected stale withdrawal refreshes the target before the next attempt', async (t) => {
  const originalWindow = globalThis.window
  Object.assign(globalThis, { window: { setTimeout, clearTimeout } })
  t.after(() => { globalThis.window = originalWindow })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(api, 'modelApis', async () => [])
  let stale = true
  t.mock.method(api, 'history', async () => [{ id: stale ? 20 : 30, type: 'user', content: '问题' }])
  const targets: number[] = []
  t.mock.method(api, 'withdrawMessage', async (_world: number, _character: number, expectedMessageId: number) => {
    targets.push(expectedMessageId)
    if (stale) { stale = false; throw new Error('聊天记录已变化，请刷新后重试') }
  })
  await chat.selectCharacter(7); await chat.withdraw()
  assert.deepEqual(chat.messages.value.map(message => message.historyId), [30])
  assert.match(notice.message, /聊天记录已变化/)
  await chat.withdraw()
  assert.deepEqual(targets, [20, 30])
})

for (const persisted of [false, true]) test(`expired resume recovers only an unsaved draft (persisted=${persisted})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async (_url: string, init: RequestInit) => {
      if (init.method === 'POST') throw new TypeError('Failed to fetch')
      return new Response('data: {"type":"generation.expired"}\n\n')
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  let reads = 0
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'history', async () => ++reads > 1 && persisted
    ? [{ id: 20, type: 'user', content: '不能丢失的长消息' }] : [])
  await chat.selectCharacter(7); chat.input.value = '不能丢失的长消息'; await chat.send()
  assert.equal(chat.input.value, persisted ? '' : '不能丢失的长消息')
  assert.equal(sessionStorage.length, 0)
})

for (const fails of [false, true]) test(`withdrawal discards stale care responses and catches up afterward (fails=${fails})`, async () => {
  await import('vue')
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window, document: globalThis.document }
  const page = new EventTarget() as EventTarget & { visibilityState: string }
  page.visibilityState = 'visible'
  const care = { id: 20, userWorldId: 3, characterId: 7, type: 'assistant', content: '旧关怀' }
  const other = { ...care, id: 30, content: '新关怀' }
  let finishOldPoll!: (response: Response) => void
  let finishWithdrawal!: () => void
  let withdrawn = false
  const polls: Array<{ url: string; signal: AbortSignal }> = []
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), document: page,
    window: { setTimeout, clearTimeout }, fetch: async (url: string, init: RequestInit) => {
      polls.push({ url: String(url), signal: init.signal as AbortSignal })
      if (polls.length === 1) return new Promise<Response>(resolve => { finishOldPoll = resolve })
      return Response.json({ code: 1, data: { messages: withdrawn ? [other] : [care, other], nextCursor: 30, hasMore: false } })
    },
  })
  localStorage.setItem('galchat.care-cursor::3', '10')
  const { api, chat, app } = await mountDirectChat(true)
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis, withdrawMessage: api.withdrawMessage }
  api.history = async () => withdrawn ? [] : [care]
  api.modelApis = async () => []
  api.withdrawMessage = () => new Promise<void>((resolve, reject) => {
    finishWithdrawal = () => {
      if (fails) reject(new Error('撤回失败'))
      else { withdrawn = true; resolve() }
    }
  })
  try {
    await chat.selectCharacter(7)
    const withdrawing = chat.withdraw()
    page.dispatchEvent(new Event('visibilitychange')); await settle()
    assert.equal(polls.length, 1, 'foreground events must not restart polling during withdrawal')
    finishWithdrawal(); await withdrawing; await settle()
    assert.equal(polls[0]!.signal.aborted, true)
    assert.equal(polls.length, 2)
    assert.match(polls[1]!.url, /after=10/, 'the cancelled page must not advance the cursor')
    finishOldPoll(Response.json({ code: 1, data: { messages: [care], nextCursor: 20, hasMore: false } }))
    await settle()
    assert.deepEqual(chat.messages.value.map(message => message.historyId), fails ? [20, 30] : [30])
    assert.equal(localStorage.getItem('galchat.care-cursor::3'), '30')
  } finally {
    app.unmount()
    finishOldPoll?.(Response.json({ code: 1, data: { messages: [], nextCursor: 10, hasMore: false } }))
    finishWithdrawal?.()
    await settle()
    clearTimeout(notice.timer); Object.assign(api, old); Object.assign(globalThis, original)
  }
})

test('linked replies, local errors and unpersisted user messages cannot enable withdrawal', async () => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage() })
  const { api, chat, app } = await mountDirectChat()
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => [{ id: 21, userMessageId: 20, type: 'assistant', content: '关联回复' }]
  api.modelApis = async () => []
  try {
    await chat.selectCharacter(7)
    chat.messages.value.push({ id: 'local-error', role: 'assistant', content: '生成失败', complete: false })
    chat.messages.value.push({ id: 'local-user', role: 'user', content: '未保存的输入' })
    assert.equal(chat.canWithdraw.value, false)
  } finally { app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('a rejected send preserves the server error and draft without resuming a nonexistent generation', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  const urls: string[] = []
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout }, fetch: async (url: string, init: RequestInit) => {
    urls.push(String(url))
    return init.method === 'POST'
      ? Response.json({ code: 0, msg: '世界模板与用户世界不匹配' })
      : new Response('data: {"type":"generation.expired"}\n\n')
  } })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7); chat.input.value = '保留这段输入'; await chat.send()
    assert.deepEqual(urls, ['/api/ai/chat'])
    assert.equal(chat.input.value, '保留这段输入')
    assert.equal(chat.generationFailureOpen.value, true)
    assert.match(chat.generationFailure.value!.message, /世界模板与用户世界不匹配/)
    assert.equal(sessionStorage.length, 0)
    assert.equal(chat.loading.sending, false)
    assert.deepEqual(chat.messages.value, [])
    assert.equal(chat.canRetryGenerationFailure.value, true)
    await chat.retryGenerationFailure()
    assert.deepEqual(urls, ['/api/ai/chat', '/api/ai/chat'], 'a rejected unsaved message must not withdraw an older round')
    assert.equal(chat.input.value, '保留这段输入')
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

test('a rejected background send restores its own draft without changing the active character draft', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let rejectSend!: () => void
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async () => new Promise<Response>(resolve => {
      rejectSend = () => resolve(Response.json({ code: 0, msg: '当前会话正在生成回复' }))
    }),
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7)
    chat.input.value = '发送给 A 的原文'
    const sending = chat.send(); await settle()
    await chat.selectCharacter(8)
    chat.input.value = '留给 B 的草稿'
    rejectSend(); await sending
    assert.equal(chat.input.value, '留给 B 的草稿')
    await chat.selectCharacter(7)
    assert.equal(chat.input.value, '发送给 A 的原文')
    assert.deepEqual(chat.messages.value, [])
    assert.equal(chat.loading.sending, false)
    assert.equal(sessionStorage.length, 0)
    await chat.selectCharacter(8)
    assert.equal(chat.input.value, '留给 B 的草稿')
  } finally { rejectSend?.(); app.unmount(); clearTimeout(notice.timer); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const persisted of ['none', 'anchor', 'history'] as const) test(`a failed generation restores only a confirmed unsent message (persisted=${persisted})`, async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async () => new Response([
      { type: 'generation.started', content: '请保留原文', sequence: 1 },
      ...(persisted === 'anchor' ? [{ type: 'generation.user', content: '20', sequence: 2 }] : []),
      { type: 'generation.failed', content: '角色回复生成失败', sequence: 3 },
    ].map(chunk => `data: ${JSON.stringify(chunk)}\n\n`).join('')),
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  const previous = { id: 10, type: 'user', content: '请保留原文' }
  let reads = 0
  api.history = async () => ++reads === 1 || persisted === 'none' ? [previous]
    : [previous, { id: 20, type: 'user', content: '请保留原文' }]
  api.modelApis = async () => []
  try {
    await chat.selectCharacter(7)
    chat.input.value = '请保留原文'
    await chat.send()
    assert.equal(chat.input.value, persisted === 'none' ? '请保留原文' : '')
    assert.equal(chat.messages.value.filter(message => message.role === 'user').length, persisted === 'none' ? 1 : 2)
    assert.equal(chat.messages.value.at(-1)?.content, '角色回复生成失败')
    assert.equal(chat.generationFailureOpen.value, true)
    assert.equal(chat.generationFailure.value?.userMessageId, persisted === 'anchor' ? 20 : undefined)
    assert.equal(chat.generationFailure.value?.retry, persisted === 'none' ? 'send' : persisted === 'anchor' ? 'withdraw' : null)
    assert.equal(chat.canRetryGenerationFailure.value, persisted !== 'history')
    assert.equal(chat.loading.sending, false)
    assert.equal(sessionStorage.length, 0)
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const initialHistoryFails of [true, false]) test(`retry cannot withdraw an unrelated identical message (initialHistoryFails=${initialHistoryFails})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let sends = 0
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async () => {
      sends++
      return new Response('data: {"type":"generation.failed","content":"模型配置读取失败","sequence":1}\n\n')
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  t.mock.method(api, 'modelApis', async () => [])
  const history: ChatHistory[] = [
    { id: 10, type: 'user', content: '继续' },
    { id: 11, type: 'assistant', userMessageId: 10, content: '另一轮成功的回复' },
  ]
  let reads = 0
  t.mock.method(api, 'history', async () => {
    if (++reads === 1) {
      if (initialHistoryFails) throw new Error('首次历史读取失败')
      return [] // Another tab sends the identical message after this snapshot.
    }
    return history
  })
  const withdrawn: number[] = []
  t.mock.method(api, 'withdrawMessage', async (_world: number, _character: number, id: number) => { withdrawn.push(id) })
  await chat.selectCharacter(7)
  chat.input.value = '继续'; await chat.send()
  assert.equal(chat.generationFailure.value?.userMessageId, undefined)
  assert.equal(chat.canRetryGenerationFailure.value, false)
  await chat.retryGenerationFailure()
  assert.deepEqual(withdrawn, [])
  assert.equal(sends, 1)
  assert.ok(chat.messages.value.some(message => message.historyId === 11 && message.content === '另一轮成功的回复'))
})

for (const saved of [true, false]) test(`history recovery restores failed generation retry (saved=${saved})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  const requests: Array<Record<string, unknown>> = []
  let history: ChatHistory[] = []
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async (_url: string, init: RequestInit) => {
      requests.push(JSON.parse(String(init.body)))
      if (requests.length > 1) {
        history = [{ id: 30, type: 'user', content: '需要重试的消息' },
          { id: 31, type: 'assistant', userMessageId: 30, content: '重试成功' }]
        return new Response('data: {"type":"generation.completed","sequence":1}\n\n')
      }
      if (saved) history = [{ id: 20, type: 'user', content: '需要重试的消息' }]
      return new Response([
        ...(saved ? [{ type: 'generation.user', content: '20', sequence: 1 }] : []),
        { type: 'generation.failed', content: '模型故障', sequence: 2 },
      ].map(chunk => `data: ${JSON.stringify(chunk)}\n\n`).join(''))
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  t.mock.method(api, 'modelApis', async () => [])
  let reads = 0
  t.mock.method(api, 'history', async () => {
    if (++reads === 2) throw new Error('临时断网')
    return history
  })
  const withdrawn: number[] = []
  t.mock.method(api, 'withdrawMessage', async (_world: number, _character: number, id: number) => {
    withdrawn.push(id); history = []
  })
  await chat.selectCharacter(7)
  chat.input.value = '需要重试的消息'; await chat.send()
  assert.equal(chat.input.value, '', 'an unavailable history cannot prove the message was unsent')
  assert.equal(chat.canRetryGenerationFailure.value, false)
  await chat.selectCharacter(7)
  assert.equal(chat.canRetryGenerationFailure.value, true)
  assert.equal(chat.generationFailure.value?.detail.retryable, true)
  await chat.retryGenerationFailure()
  assert.deepEqual(withdrawn, saved ? [20] : [])
  assert.equal(requests.length, 2)
  assert.equal(requests[1]?.message, '需要重试的消息')
  assert.notEqual(requests[0]?.clientRequestId, requests[1]?.clientRequestId)
  assert.deepEqual(chat.messages.value.filter(message => message.complete !== false).map(message => message.content),
    ['需要重试的消息', '重试成功'])
})

for (const recovery of ['immediate', 'history-fails', 'outside-page', 'new-draft'] as const) test(`lost withdrawal response recovers without withdrawing twice (${recovery})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  const requests: Array<Record<string, unknown>> = []
  let history: ChatHistory[] = []
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async (_url: string, init: RequestInit) => {
      requests.push(JSON.parse(String(init.body)))
      if (requests.length === 1) {
        history = [{ id: 20, type: 'user', content: '原消息\n第二行' }]
        return new Response('data: {"type":"generation.user","content":"20","sequence":1}\n\ndata: {"type":"generation.failed","content":"模型故障","sequence":2}\n\n')
      }
      history = [{ id: 200, type: 'user', content: '原消息\n第二行' },
        { id: 201, type: 'assistant', userMessageId: 200, content: '重试成功' }]
      return new Response('data: {"type":"generation.completed","sequence":1}\n\n')
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  t.mock.method(api, 'modelApis', async () => [])
  let refreshFails = false
  t.mock.method(api, 'history', async () => {
    if (refreshFails) throw new Error('历史读取失败')
    return history
  })
  const withdrawals: number[] = []
  t.mock.method(api, 'withdrawMessage', async (_world: number, _character: number, id: number) => {
    withdrawals.push(id)
    history = recovery === 'outside-page'
      ? Array.from({ length: 30 }, (_, i) => ({ id: 100 + i, type: 'user', content: '后来的一轮' })) : []
    refreshFails = recovery === 'history-fails'
    throw new TypeError('Failed to fetch') // The server committed, but its response was lost.
  })
  await chat.selectCharacter(7)
  chat.input.value = '原消息\n第二行'; await chat.send()
  if (recovery === 'new-draft') chat.input.value = '另一个尚未发送的草稿'
  await chat.retryGenerationFailure()
  if (recovery === 'history-fails' || recovery === 'outside-page') {
    assert.equal(requests.length, 1, 'do not resend while withdrawal is unconfirmed')
    assert.equal(chat.canRetryGenerationFailure.value, false)
    await chat.retryGenerationFailure()
    assert.equal(requests.length, 1)
    refreshFails = false; history = []
    await chat.selectCharacter(7)
    assert.equal(chat.canRetryGenerationFailure.value, true)
    assert.equal(chat.input.value, '原消息\n第二行')
    await chat.retryGenerationFailure()
  }
  assert.deepEqual(withdrawals, [20])
  assert.equal(requests.length, 2)
  assert.equal(requests[1]?.message, '原消息\n第二行')
  assert.notEqual(requests[1]?.clientRequestId, requests[0]?.clientRequestId)
  assert.equal(chat.input.value, recovery === 'new-draft' ? '另一个尚未发送的草稿' : '')
  assert.deepEqual(chat.messages.value.map(message => message.content), ['原消息\n第二行', '重试成功'])
})

for (const { failures, saved } of [{ failures: 1, saved: false }, { failures: 2, saved: false }, { failures: 1, saved: true }]) test(`successful retry clears only its own error bubbles (failures=${failures}, saved=${saved})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let posts = 0
  let withdrawn = false
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async () => {
      posts++
      return new Response(posts <= failures
        ? `${saved ? 'data: {"type":"generation.user","content":"10","sequence":1}\n\n' : ''}data: {"type":"generation.failed","content":"本轮错误${posts}","sequence":2}\n\n`
        : 'data: {"type":"generation.completed","sequence":1}\n\n')
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'history', async () => posts > failures ? [
    { id: 20, type: 'user', content: '问题' },
    { id: 21, type: 'assistant', userMessageId: 20, content: '成功回复' },
  ] : saved && posts > 0 && !withdrawn ? [{ id: 10, type: 'user', content: '问题' }] : [])
  t.mock.method(api, 'withdrawMessage', async (_world: number, _character: number, id: number) => {
    assert.equal(id, 10); withdrawn = true
  })
  await chat.selectCharacter(7)
  chat.messages.value.push({ id: 'unrelated-error', role: 'assistant', content: '其他轮次的错误', complete: false })
  chat.input.value = '问题'; await chat.send()
  for (let attempt = 1; attempt <= failures; attempt++) {
    assert.ok(chat.messages.value.some(message => message.content === `本轮错误${attempt}`))
    await chat.retryGenerationFailure()
  }
  assert.equal(chat.generationFailure.value, null)
  assert.deepEqual(chat.messages.value.map(message => message.content), ['问题', '成功回复', '其他轮次的错误'])
  await chat.selectCharacter(7)
  assert.deepEqual(chat.messages.value.map(message => message.content), ['问题', '成功回复', '其他轮次的错误'])
})

for (const newerDraft of ['', 'A 的新草稿']) test(`a failed background generation preserves conversation drafts (newerDraft=${Boolean(newerDraft)})`, async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async () => new Response(new ReadableStream({ start(c) { controller = c } })),
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await chat.selectCharacter(7)
    chat.input.value = '发送给 A 的原文'
    const sending = chat.send(); await settle()
    chat.input.value = newerDraft
    await chat.selectCharacter(8)
    chat.input.value = 'B 的草稿'
    event(controller, 'generation.failed', '当前单聊正在处理中', 1); controller.close()
    await sending
    assert.equal(chat.input.value, 'B 的草稿')
    await chat.selectCharacter(7)
    assert.equal(chat.input.value, newerDraft || '发送给 A 的原文')
    await chat.selectCharacter(8)
    assert.equal(chat.input.value, 'B 的草稿')
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const scope of ['same-character', 'other-character', 'other-world', 'logout'] as const) test(`model selection updates the current scoped character after a list refresh (${scope})`, async () => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  const { api, chat, app, characters, worldState } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis, updateCharacterModel: api.updateCharacterModel }
  api.history = async () => []; api.modelApis = async () => []
  let finish!: (runtime: SingleChatRuntime) => void
  api.updateCharacterModel = async () => new Promise(resolve => { finish = resolve })
  try {
    await chat.selectCharacter(7)
    const changing = chat.selectModel(9)
    characters.value = characters.value.map(character => ({ ...character }))
    if (scope === 'other-character') await chat.selectCharacter(8)
    if (scope === 'other-world') {
      chat.close()
      worldState.value = { id: 4, worldId: 2, name: '另一个世界' }
      characters.value = characters.value.map(character => ({ ...character, userWorldId: 4 }))
      await chat.selectCharacter(7)
    }
    if (scope === 'logout') { chat.close(); chat.clearDrafts() }
    finish({ modelApiId: 9, modelApiName: '新模型', modelApiAvailable: true })
    await changing
    assert.equal(characters.value.find(character => character.characterId === 7)?.modelApiId,
      scope === 'other-world' || scope === 'logout' ? undefined : 9)
    assert.equal(characters.value.find(character => character.characterId === 8)?.modelApiId, undefined)
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const outcome of ['saved', 'failed'] as const) test(`sending preserves the draft until model selection settles (${outcome})`, async t => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(api, 'history', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
  let finish!: () => void
  t.mock.method(api, 'updateCharacterModel', async () => {
    await new Promise<void>(resolve => { finish = resolve })
    if (outcome === 'failed') throw new Error('模型保存失败')
    return { modelApiId: 9, modelApiName: '新模型', modelApiAvailable: true }
  })
  const sent: string[] = []
  t.mock.method(globalThis, 'fetch', async (_url: string, init: RequestInit) => {
    sent.push(JSON.parse(String(init.body)).message)
    return new Response('data: {"type":"generation.completed"}\n\n')
  })
  await chat.selectCharacter(7)
  chat.input.value = '切换后发送'
  const saving = chat.selectModel(9)
  try {
    await chat.send()
    assert.deepEqual(sent, [])
    assert.equal(chat.input.value, '切换后发送')
    assert.equal(chat.loading.sending, false)
  } finally { finish(); await saving }
  await chat.send()
  assert.deepEqual(sent, ['切换后发送'])
  assert.equal(chat.input.value, '')
})

for (const action of ['send', 'retry'] as const) test(`settings saves block ${action} without clearing the draft`, async t => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const { api, chat, app, settingsSaving } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(api, 'history', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
  const sent: string[] = []
  t.mock.method(globalThis, 'fetch', async (_url: string, init: RequestInit) => {
    sent.push(JSON.parse(String(init.body)).message)
    return sent.length === 1 ? Response.json({ code: 0, msg: '暂时无法生成' })
      : new Response('data: {"type":"generation.completed"}\n\n')
  })
  await chat.selectCharacter(7)
  chat.input.value = '保存后发送'
  await chat.send()
  assert.equal(chat.canRetryGenerationFailure.value, true)
  settingsSaving.value = true
  if (action === 'send') await chat.send()
  else await chat.retryGenerationFailure()
  assert.deepEqual(sent, ['保存后发送'])
  assert.equal(chat.canRetryGenerationFailure.value, false)
  assert.equal(chat.input.value, '保存后发送')
  settingsSaving.value = false
  assert.equal(chat.canRetryGenerationFailure.value, true)
  await chat.retryGenerationFailure()
  assert.deepEqual(sent, ['保存后发送', '保存后发送'])
  assert.equal(chat.input.value, '')
})

test('a retry rechecks settings saves after its withdrawal finishes', async t => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const { api, chat, app, settingsSaving } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  let history: ChatHistory[] = []
  t.mock.method(api, 'history', async () => history)
  t.mock.method(api, 'modelApis', async () => [])
  let sends = 0
  t.mock.method(globalThis, 'fetch', async () => {
    if (++sends > 1) return new Response('data: {"type":"generation.completed"}\n\n')
    history = [{ id: 20, type: 'user', content: '原问题' }]
    return new Response('data: {"type":"generation.user","content":"20","sequence":1}\n\ndata: {"type":"generation.failed","content":"失败","sequence":2}\n\n')
  })
  let finish!: () => void
  t.mock.method(api, 'withdrawMessage', async () => {
    await new Promise<void>(resolve => { finish = resolve })
    history = []
  })
  await chat.selectCharacter(7)
  chat.input.value = '原问题'
  await chat.send()
  const retry = chat.retryGenerationFailure()
  settingsSaving.value = true
  finish(); await retry
  assert.equal(sends, 1)
  assert.equal(chat.input.value, '原问题')
  settingsSaving.value = false
  await chat.retryGenerationFailure()
  assert.equal(sends, 2)
})

for (const modelState of ['failed', 'pending'] as const) test(`history loads independently when the model list is ${modelState}`, async () => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  let finishModels: (() => void) | undefined
  api.history = async () => [{ id: 10, type: 'user', content: '已有聊天记录' }]
  api.modelApis = () => modelState === 'failed'
    ? Promise.reject(new Error('模型列表暂时不可用'))
    : new Promise(resolve => { finishModels = () => resolve([]) })
  let selection: Promise<void> | undefined
  try {
    selection = chat.selectCharacter(7); await settle()
    assert.deepEqual(chat.messages.value.map(message => message.content), ['已有聊天记录'])
    assert.equal(chat.loading.history, false)
    assert.equal(chat.canWithdraw.value, true)
    if (modelState === 'failed') assert.match(notice.message, /模型列表/)
  } finally {
    finishModels?.(); await selection; app.unmount(); clearTimeout(notice.timer)
    Object.assign(api, old); Object.assign(globalThis, original)
  }
})

test('loading older history does not discard a pending model list', async () => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage() })
  const { api, chat, app } = await mountDirectChat()
  const old = { history: api.history, modelApis: api.modelApis }
  const models = [{ id: 5, name: '测试模型', baseUrl: 'https://example.com', modelName: 'test', requestOverrides: {},
    apiKeyHint: '****', status: 'UNTESTED', chatCapability: 'UNKNOWN', streamingCapability: 'UNKNOWN',
    toolCallingCapability: 'UNKNOWN', reasoningOutputStatus: 'UNKNOWN' }] satisfies Awaited<ReturnType<typeof api.modelApis>>
  let finishModels!: () => void
  api.modelApis = () => new Promise(resolve => { finishModels = () => resolve(models) })
  api.history = async (_world, _character, _size, before) => before ? []
    : Array.from({ length: 30 }, (_, index) => ({ id: index + 10, type: 'user', content: '消息' }))
  try {
    await chat.selectCharacter(7)
    await chat.loadEarlier()
    finishModels(); await settle()
    assert.deepEqual(chat.modelApis.value, models)
  } finally { finishModels?.(); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

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

function historyRounds(start: number, count: number): ChatHistory[] {
  return Array.from({ length: count }, (_, i) => {
    const id = start + i * 2
    return [
      { id, type: 'user', content: `问题${id}` },
      { type: 'thinking', userMessageId: id, stepNo: 1, content: `思考${id}` },
      { type: 'tool' },
      { id: id + 1, type: 'assistant', userMessageId: id, stepNo: 1, content: `回答${id}` },
    ]
  }).flat()
}

test('direct history counts primary messages and uses their IDs to paginate expanded rounds', async (t) => {
  const { api, chat, app } = await mountDirectChat()
  t.after(() => app.unmount())
  t.mock.method(api, 'modelApis', async () => [])
  const cursors: Array<number | undefined> = []
  t.mock.method(api, 'history', async (_world: number, _character: number, _size?: number, before?: number) => {
    cursors.push(before)
    if (before == null) return [...historyRounds(100, 29), { id: 158, type: 'assistant', content: '主动回复' }]
    if (before === 100) return historyRounds(40, 30)
    return historyRounds(20, 5)
  })
  await chat.selectCharacter(7)
  assert.equal(chat.hasOlderMessages.value, true)
  await chat.loadEarlier()
  assert.equal(chat.hasOlderMessages.value, true)
  await chat.loadEarlier()
  assert.equal(chat.hasOlderMessages.value, false)
  assert.deepEqual(cursors, [undefined, 100, 40])
  assert.equal(chat.messages.value[0]?.historyId, 20)
})

test('30 expanded history entries from a short primary page do not advertise another page', async (t) => {
  const { api, chat, app } = await mountDirectChat()
  t.after(() => app.unmount())
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'history', async () => historyRounds(10, 10).filter(item => item.type !== 'tool'))
  await chat.selectCharacter(7)
  assert.equal(chat.hasOlderMessages.value, false)
})

test('completed direct replies preserve older pages and their exhausted cursor while replacing the latest page', async (t) => {
  const globals = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), fetch: async () => new Response(new ReadableStream({ start(c) { controller = c } })) })
  t.after(() => Object.assign(globalThis, globals))
  const { api, chat, app } = await mountDirectChat()
  t.after(() => app.unmount())
  t.mock.method(api, 'modelApis', async () => [])
  let completed = false
  t.mock.method(api, 'history', async (_world: number, _character: number, _size?: number, before?: number) => {
    if (before != null) return historyRounds(80, 10)
    return historyRounds(completed ? 102 : 100, 30)
  })
  await chat.selectCharacter(7)
  await chat.loadEarlier()
  assert.equal(chat.hasOlderMessages.value, false)
  chat.input.value = '新问题'
  const sending = chat.send()
  await settle()
  event(controller, 'response', '流中的临时回复', 1)
  completed = true
  event(controller, 'generation.completed', '', 2)
  controller.close()
  await sending
  assert.equal(chat.messages.value[0]?.historyId, 80)
  assert.equal(chat.messages.value.filter(item => item.role === 'user').length, 41)
  assert.equal(chat.messages.value.filter(item => item.role === 'thinking').length, 41)
  assert.equal(chat.hasOlderMessages.value, false)
  assert.equal(chat.messages.value.at(-1)?.content, '回答160')
  assert.ok(!chat.messages.value.some(item => item.content === '流中的临时回复'))
  assert.equal(new Set(chat.messages.value.map(item => item.id)).size, chat.messages.value.length)
  await chat.selectCharacter(7)
  assert.equal(chat.messages.value[0]?.historyId, 102, 're-enter still reloads authoritative history')
})


test('polls on world entry and preserves care messages received during a streamed reply', async () => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window, document: globalThis.document }
  const page = new EventTarget() as EventTarget & { visibilityState: string }
  page.visibilityState = 'visible'
  const requests: Array<{ url: string; body: Record<string, unknown> }> = []
  const careQueries: string[] = []
  let carePage = { messages: [] as ChatHistory[], nextCursor: 0, hasMore: false }
  let controller!: ReadableStreamDefaultController
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), document: page,
    window: { setTimeout, clearTimeout },
    fetch: async (url: string, init: RequestInit) => {
      if (String(url).includes('/history/care')) {
        careQueries.push(String(url)); return Response.json({ code: 1, data: carePage })
      }
      requests.push({ url: String(url), body: JSON.parse(String(init.body)) })
      return new Response(new ReadableStream({ start(c) { controller = c } }))
    },
  })
  const { api, chat, app } = await mountDirectChat(true)
  const { notice } = await import('../composables/useNotice.ts')
  const old = { history: api.history, modelApis: api.modelApis }
  api.history = async () => []; api.modelApis = async () => []
  try {
    await settle(); assert.equal(careQueries.length, 1, 'entering a world checks without opening a character')
    await chat.selectCharacter(7)
    chat.input.value = '第一行\n第二行'
    const sending = chat.send(); await settle()
    assert.equal(requests.length, 1)
    assert.equal(requests[0]!.url, '/api/ai/chat')
    assert.equal(requests[0]!.body.message, '第一行\n第二行')
    assert.ok(requests[0]!.body.clientRequestId)
    const care: ChatHistory = { id: 30, userWorldId: 3, characterId: 7, type: 'assistant', content: '记得休息\n早点睡' }
    carePage = { messages: [care], nextCursor: 30, hasMore: false }
    page.dispatchEvent(new Event('visibilitychange')); await settle()
    page.dispatchEvent(new Event('visibilitychange')); await settle()
    assert.match(careQueries.at(-1)!, /after=30/)
    event(controller, 'generation.user', '20', 1)
    event(controller, 'response', '角色的回答', 2); await settle()
    assert.equal(chat.messages.value.filter(message => message.historyId === 30).length, 1)
    assert.equal(chat.messages.value.at(-1)!.content, care.content)
    const savedHistory = [
      { id: 20, type: 'user', content: '第一行\n第二行' },
      { id: 21, userMessageId: 20, type: 'assistant', content: '角色的回答' }, care,
    ]
    let finishHistory!: (items: ChatHistory[]) => void
    api.history = () => new Promise(resolve => { finishHistory = resolve })
    event(controller, 'generation.completed', '', 3); controller.close(); await settle()
    const laterCare = { ...care, id: 40, content: '还有一条提醒' }
    carePage = { messages: [laterCare], nextCursor: 40, hasMore: false }
    page.dispatchEvent(new Event('visibilitychange')); await settle()
    finishHistory(savedHistory); await sending
    assert.deepEqual(chat.messages.value.map(message => message.content), ['第一行\n第二行', '角色的回答', care.content, laterCare.content], 'a history request must not erase care arriving after its snapshot')
    chat.close()
    carePage = { messages: [], nextCursor: 40, hasMore: false }
    page.dispatchEvent(new Event('visibilitychange')); await settle()
    assert.equal(careQueries.length, 5, 'leaving the character does not disable world notifications')
  } finally { clearTimeout(notice.timer); app.unmount(); Object.assign(api, old); Object.assign(globalThis, original) }
})

for (const scenario of ['success', 'withdraw-fails', 'stale', 'switch', 'refresh-fails'] as const) test(`failed direct reply retries safely (${scenario})`, async (t) => {
  const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  let controller!: ReadableStreamDefaultController
  const requests: Array<Record<string, unknown>> = []
  const order: string[] = []
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout },
    fetch: async (_url: string, init: RequestInit) => {
      order.push('send')
      requests.push(JSON.parse(String(init.body)))
      return new Response(new ReadableStream({ start(c) { controller = c } }))
    },
  })
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer); Object.assign(globalThis, original) })
  t.mock.method(api, 'modelApis', async () => [])
  let history: ChatHistory[] = []
  let refreshFails = false
  t.mock.method(api, 'history', async () => {
    if (refreshFails) throw new Error('历史读取失败')
    return history
  })
  let finishWithdrawal!: () => void
  t.mock.method(api, 'withdrawMessage', async (world: number, character: number, expected: number) => {
    assert.deepEqual([world, character, expected], [3, 7, 20])
    order.push('withdraw')
    await new Promise<void>(resolve => { finishWithdrawal = resolve })
    if (scenario === 'withdraw-fails') throw new Error('撤回失败')
    history = []
    refreshFails = scenario === 'refresh-fails'
  })
  await chat.selectCharacter(7)
  chat.input.value = '原消息\n第二行'
  const sending = chat.send(); await settle()
  event(controller, 'generation.user', '20', 1)
  history = [{ id: 20, type: 'user', content: '原消息\n第二行' }]
  const detail: GenerationErrorDetail = {
    errorId: 'server-error-123', code: 'GENERATION_FAILED', category: 'GENERATION', message: '模型错误',
    retryable: true, occurredAt: '2026-09-29T01:00:00Z', operation: 'send-direct-message',
    request: { method: 'POST', path: '/ai/chat', body: requests[0] },
    response: { eventCount: 2, events: [{ type: 'generation.user', content: '20' }] },
    stack: 'IllegalStateException: 模型错误\nCaused by: UnauthorizedException: 401 API key expired',
  }
  event(controller, 'generation.failed', '旧的通用提示', 2, detail); controller.close(); await sending
  assert.equal(chat.generationFailureOpen.value, true)
  assert.equal(chat.generationFailure.value?.message, '模型错误')
  assert.deepEqual(chat.generationFailure.value?.detail, detail)
  assert.equal((chat.generationFailure.value?.detail.request.body as Record<string, unknown>)?.message, '原消息\n第二行')
  assert.equal(chat.canRetryGenerationFailure.value, true)
  if (scenario === 'stale') {
    history.push({ id: 30, type: 'user', content: '别的页面发来的新一轮' })
    await chat.selectCharacter(7)
    assert.equal(chat.canRetryGenerationFailure.value, false)
    await chat.retryGenerationFailure()
    assert.deepEqual(order, ['send'])
    return
  }
  chat.input.value = '另一个尚未发送的草稿'
  const retry = chat.retryGenerationFailure(); await settle()
  await chat.retryGenerationFailure()
  assert.deepEqual(order, ['send', 'withdraw'])
  if (scenario === 'switch') await chat.selectCharacter(8)
  finishWithdrawal(); await settle()
  if (scenario === 'switch' || scenario === 'refresh-fails') {
    await retry
    assert.deepEqual(order, ['send', 'withdraw'])
    if (scenario === 'switch') {
      assert.equal(chat.generationFailure.value, null)
      assert.equal(chat.generationFailureOpen.value, false)
    } else {
      assert.equal(chat.canRetryGenerationFailure.value, false)
      await chat.retryGenerationFailure()
      assert.deepEqual(order, ['send', 'withdraw'])
    }
    refreshFails = false
    await chat.selectCharacter(7)
    assert.equal(chat.canRetryGenerationFailure.value, true)
    const next = chat.retryGenerationFailure(); await settle()
    assert.deepEqual(order, ['send', 'withdraw', 'send'], 'successful withdrawal must not be repeated')
    assert.equal(requests[1]?.message, '原消息\n第二行')
    event(controller, 'generation.completed', '', 1); controller.close(); await next
    return
  }
  if (scenario === 'success') {
    assert.deepEqual(order, ['send', 'withdraw', 'send'])
    assert.equal(requests[1]?.message, '原消息\n第二行')
    assert.notEqual(requests[1]?.clientRequestId, requests[0]?.clientRequestId)
    event(controller, 'generation.user', '30', 1)
    history = [{ id: 30, type: 'user', content: '原消息\n第二行' }, { id: 31, type: 'assistant', userMessageId: 30, content: '成功回复' }]
    event(controller, 'generation.completed', '', 2); controller.close()
  }
  await retry
  assert.equal(chat.input.value, '另一个尚未发送的草稿')
  if (scenario === 'withdraw-fails') assert.equal(requests.length, 1)
  else {
    assert.equal(chat.generationFailure.value, null)
    assert.deepEqual(chat.messages.value.map(m => m.content), ['原消息\n第二行', '成功回复'])
  }
})

for (const outcome of ['rejected', 'lost-response', 'saved-model-failure'] as const) test(`direct drafts wait for withdrawal after ${outcome}`, async t => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  let saved = false
  t.mock.method(api, 'history', async () => saved ? [{ id: 20, type: 'user', content: '已提交的消息' }] : [])
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(globalThis, 'fetch', async () => {
    if (outcome === 'rejected') return Response.json({ code: 0, msg: '请求被拒绝' })
    saved = true
    if (outcome === 'lost-response') throw new TypeError('Failed to fetch')
    return new Response('data: {"type":"generation.user","content":"20","sequence":1}\n\ndata: {"type":"generation.failed","content":"模型失败","sequence":2}\n\n')
  })
  await chat.selectCharacter(7)
  chat.input.value = '已提交的消息'; await chat.send()
  assert.equal(chat.input.value, outcome === 'rejected' ? '已提交的消息' : '')
  assert.equal(chat.generationFailureOpen.value, outcome !== 'lost-response')
  if (outcome === 'saved-model-failure') {
    t.mock.method(api, 'withdrawMessage', async () => { saved = false })
    await chat.withdraw()
    assert.equal(chat.input.value, '已提交的消息')
  }
})

for (const outcome of ['success', 'rejected', 'lost-response'] as const) test(`direct withdrawal dismisses failure details after ${outcome}`, async t => {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const { api, chat, app } = await mountDirectChat()
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => { app.unmount(); clearTimeout(notice.timer) })
  let saved = true
  t.mock.method(api, 'history', async () => saved ? [{ id: 20, type: 'user', content: '撤回的原文' }] : [])
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'withdrawMessage', async () => {
    if (outcome === 'rejected') throw new Error('无法撤回')
    saved = false
    if (outcome === 'lost-response') throw new TypeError('Failed to fetch')
  })
  await chat.selectCharacter(7)
  chat.generationFailureOpen.value = true
  Object.assign(notice, { open: false, title: '', message: '' })
  await chat.withdraw()
  assert.equal(chat.generationFailureOpen.value, false)
  if (outcome !== 'rejected') {
    assert.equal(chat.input.value, '撤回的原文')
    assert.equal(notice.open, false, 'confirmed withdrawal stays silent even if the response was lost')
  }
})

async function withdrawalRefreshFixture(t: import('node:test').TestContext, reloadCharacters?: () => Promise<void>) {
  const original = { localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage, window: globalThis.window }
  Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout } })
  t.after(() => Object.assign(globalThis, original))
  const fixture = await mountDirectChat(false, reloadCharacters)
  const { notice } = await import('../composables/useNotice.ts')
  Object.assign(notice, { open: false, title: '', message: '' })
  t.after(() => { fixture.app.unmount(); clearTimeout(notice.timer) })
  t.mock.method(fixture.api, 'modelApis', async () => [])
  return { ...fixture, notice }
}

for (const refreshFails of [false, true]) test(`confirmed withdrawal with character refresh failure=${refreshFails}`, async t => {
  const { api, chat, notice } = await withdrawalRefreshFixture(t, async () => {
    if (refreshFails) throw new Error('角色信息读取失败')
  })
  const older = { id: 10, type: 'user', content: '更早一轮' }
  let saved = true
  const withdrawals: number[] = []
  t.mock.method(api, 'history', async () => saved ? [older, { id: 20, type: 'user', content: '本轮原文' }] : [older])
  t.mock.method(api, 'withdrawMessage', async (_w: number, _c: number, id: number) => { withdrawals.push(id); saved = false })
  await chat.selectCharacter(7)
  await chat.withdraw()
  assert.deepEqual(withdrawals, [20])
  assert.equal(chat.input.value, '本轮原文')
  assert.equal(chat.canWithdraw.value, true)
  assert.notEqual(notice.title, '撤回失败', 'a successful withdrawal must not be reported as failed')
})

test('successful withdrawal still refreshes characters when its history refresh fails', async t => {
  let refreshes = 0
  const { api, chat } = await withdrawalRefreshFixture(t, async () => { refreshes++ })
  let withdrawn = false
  let historyFails = true
  t.mock.method(api, 'history', async () => {
    if (withdrawn && historyFails) throw new Error('历史读取失败')
    return withdrawn ? [] : [{ id: 20, type: 'user', content: '撤回文本' }]
  })
  t.mock.method(api, 'withdrawMessage', async () => { withdrawn = true })
  await chat.selectCharacter(7)
  await chat.withdraw()
  historyFails = false
  chat.close(); await chat.selectCharacter(7)
  assert.ok(refreshes > 0, 'character favor/preview remains stale even after reopening the chat')
})
