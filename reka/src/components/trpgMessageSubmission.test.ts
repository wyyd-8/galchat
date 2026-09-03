import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'

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

test('submits a clarification through the waiting user step', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: storage(),
  })

  const originalMessage = streamTrpgTurn.message
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  try {
    let submitted: unknown
    streamTrpgTurn.message = async (conversationId, turnId, stepId, payload, onEvent) => {
      submitted = { conversationId, turnId, stepId, content: payload.content }
      onEvent({ eventType: 'stream.caught_up', conversationId })
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'SCENE', displayName: '书房', items: [] }]
    api.currentTurn = async () => null

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {},
      insert(child, parent) { child.parent = parent },
      remove() {},
      createElement: () => ({}),
      createText: (text) => ({ text }),
      createComment: (text) => ({ text }),
      setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})

    workspace.conversations.value = [{
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '旧宅调查', status: 'active',
    }]
    workspace.selectedConversationId.value = 7
    workspace.currentTurn.value = {
      turnId: 42,
      status: 'waiting_input',
      stepId: 99,
      actionType: 'trpg_interaction_response',
      inputType: 'clarification',
      waitingForUser: true,
      sceneOptions: {},
      steps: [{ stepId: 99, itemOrder: 2, actorType: 'user', status: 'waiting_input' }],
    }
    workspace.messageInput.value = '我会使用随身携带的撬棍破门。'

    await workspace.sendMessage()

    assert.deepEqual(submitted, {
      conversationId: 7,
      turnId: 42,
      stepId: 99,
      content: '我会使用随身携带的撬棍破门。',
    })
    assert.equal(workspace.messageInput.value, '')
  } finally {
    streamTrpgTurn.message = originalMessage
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('submits a KP inquiry and returns the composer to action mode', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: storage(),
  })

  const originalInquiry = streamTrpgTurn.inquiry
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  try {
    let submitted: unknown
    streamTrpgTurn.inquiry = async (conversationId, turnId, stepId, payload, onEvent) => {
      submitted = { conversationId, turnId, stepId, question: payload.question }
      onEvent({ eventType: 'stream.caught_up', conversationId })
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'SCENE', displayName: '深夜街道', items: [] }]
    api.currentTurn = async () => null

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {},
      insert(child, parent) { child.parent = parent },
      remove() {},
      createElement: () => ({}),
      createText: (text) => ({ text }),
      createComment: (text) => ({ text }),
      setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})

    workspace.conversations.value = [{
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '旧宅调查', status: 'active',
    }]
    workspace.selectedConversationId.value = 7
    workspace.currentTurn.value = {
      turnId: 42,
      status: 'waiting_input',
      stepId: 99,
      actionType: 'trpg_scene',
      inputType: 'message',
      waitingForUser: true,
      canAskKp: true,
      sceneOptions: {},
      steps: [{ stepId: 99, itemOrder: 1, actorType: 'user', status: 'waiting_input' }],
    }
    workspace.composerIntent.value = 'inquiry'
    workspace.inquiryInput.value = '街上有正在经过的空载出租车吗？'

    await workspace.askKp()

    assert.deepEqual(submitted, {
      conversationId: 7,
      turnId: 42,
      stepId: 99,
      question: '街上有正在经过的空载出租车吗？',
    })
    assert.equal(workspace.inquiryInput.value, '')
    assert.equal(workspace.composerIntent.value, 'action')
  } finally {
    streamTrpgTurn.inquiry = originalInquiry
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('starts a new TRPG turn with the temporary investigator direction', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: storage(),
  })

  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  try {
    let submittedDirection: unknown
    streamTrpgTurn.continue = async (...args: any[]) => {
      submittedDirection = args[3]
      args[2]({ eventType: 'stream.caught_up', conversationId: args[0] })
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'SCENE', displayName: '书房', items: [] }]
    api.currentTurn = async () => null

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {},
      insert(child, parent) { child.parent = parent },
      remove() {},
      createElement: () => ({}),
      createText: (text) => ({ text }),
      createComment: (text) => ({ text }),
      setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})

    workspace.conversations.value = [{
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '旧宅调查', status: 'active',
    }]
    workspace.selectedConversationId.value = 7

    const succeeded = await workspace.startTrpgTurn('优先确认地下室入口。')

    assert.equal(submittedDirection, '优先确认地下室入口。')
    assert.equal(succeeded, true)
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

function storage(): Storage {
  const values = new Map<string, string>()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
    clear: () => values.clear(),
    key: (index) => [...values.keys()][index] ?? null,
    get length() { return values.size },
  }
}
