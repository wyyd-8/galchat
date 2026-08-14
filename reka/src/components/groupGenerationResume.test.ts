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
    if (/^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
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

test('rebuilds the current generation from the replay stream after refresh', async () => {
  const { api, streamGroupGeneration } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const local = storage()
  const session = storage([['galchat:generation:7', 'generation-7']])
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: local,
    sessionStorage: session,
  })

  const originalConversation = api.conversation
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  const originalResume = streamGroupGeneration.resume
  try {
    api.conversation = async () => ({
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'chat', title: '调查', status: 'active',
    })
    api.groupMessages = async () => [
      {
        id: 10, conversationId: 7, turnId: 42, replyStepId: 1,
        speakerType: 'character', speakerId: 101,
        messageKind: 'dialogue', content: '数据库中的A',
        sequenceNo: 10, status: 'completed',
      },
      {
        id: 11, conversationId: 7, turnId: 42, replyStepId: 2,
        speakerType: 'character', speakerId: 102,
        messageKind: 'dialogue', content: '',
        sequenceNo: 11, status: 'streaming',
      },
    ]
    api.replyPlan = async () => [{ source: 'USER', displayName: '群聊', items: [] }]
    api.currentTurn = async () => null
    let resumedWith = ''
    streamGroupGeneration.resume = async (conversationId, clientRequestId, onEvent) => {
      assert.equal(conversationId, 7)
      resumedWith = clientRequestId
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 1, messageId: 10, sequence: 10,
        speaker: { type: 'character', id: 101, name: 'A' },
      })
      onEvent({
        eventType: 'message.delta', conversationId: 7, turnId: 42,
        replyStepId: 1, messageId: 10, delta: '流中的A',
      })
      onEvent({
        eventType: 'message.completed', conversationId: 7, turnId: 42,
        replyStepId: 1, messageId: 10, content: '流中的A',
      })
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 2, messageId: 11, sequence: 11,
        speaker: { type: 'character', id: 102, name: 'B' },
      })
      onEvent({
        eventType: 'message.delta', conversationId: 7, turnId: 42,
        replyStepId: 2, messageId: 11, delta: '流中的B',
      })
      onEvent({
        eventType: 'dice_roll.created', conversationId: 7,
        diceRoll: {
          summary: { id: 501, conversationId: 7, status: 'PENDING' },
          results: [],
        },
      })
      onEvent({ eventType: 'stream.caught_up', conversationId: 7 })
    }

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {},
      insert(child, parent) {
        const children = (parent.children ||= []) as Array<Record<string, unknown>>
        children.push(child); child.parent = parent
      },
      remove() {}, createElement: () => ({}), createText: (text) => ({ text }),
      createComment: (text) => ({ text }), setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})

    await workspace.selectConversation(7)
    await waitFor(() => resumedWith === 'generation-7')
    await waitFor(() => session.getItem('galchat:generation:7') === null)

    assert.deepEqual(
      workspace.messages.value.map(({ id, content, status }) => ({ id, content, status })),
      [
        { id: 10, content: '流中的A', status: 'completed' },
        { id: 11, content: '流中的B', status: 'streaming' },
      ],
    )
    assert.equal(workspace.latestDiceRoll.value?.summary.id, 501)
    assert.equal(workspace.incomingDiceRoll.value, null)
  } finally {
    api.conversation = originalConversation
    api.groupMessages = originalMessages
    api.replyPlan = originalPlans
    api.currentTurn = originalTurn
    streamGroupGeneration.resume = originalResume
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('clears a completed generation but retains an interrupted generation for reconnect', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const session = storage()
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: session,
  })
  const originalContinue = streamTrpgTurn.continue
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  try {
    let storedWhileConnecting = false
    streamTrpgTurn.continue = async (conversationId, clientRequestId, onEvent) => {
      storedWhileConnecting = session.getItem(`galchat:generation:${conversationId}`) === clientRequestId
      onEvent({ eventType: 'stream.caught_up', conversationId })
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'USER', displayName: '群聊', items: [] }]
    api.currentTurn = async () => null

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
      createElement: () => ({}), createText: (text) => ({ text }),
      createComment: (text) => ({ text }), setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})
    workspace.conversations.value = [{
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '调查', status: 'active',
    }]
    workspace.selectedConversationId.value = 7

    await workspace.startTrpgTurn()

    assert.equal(storedWhileConnecting, true)
    assert.equal(session.getItem('galchat:generation:7'), null)

    let interruptedId = ''
    streamTrpgTurn.continue = async (conversationId, clientRequestId) => {
      interruptedId = clientRequestId
      assert.equal(session.getItem(`galchat:generation:${conversationId}`), clientRequestId)
      throw new Error('connection interrupted')
    }

    await workspace.startTrpgTurn()

    assert.equal(session.getItem('galchat:generation:7'), interruptedId)
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalMessages
    api.replyPlan = originalPlans
    api.currentTurn = originalTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

function storage(entries: Array<[string, string]> = []): Storage {
  const values = new Map(entries)
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
    clear: () => values.clear(),
    key: (index) => [...values.keys()][index] ?? null,
    get length() { return values.size },
  }
}

async function waitFor(predicate: () => boolean) {
  const deadline = Date.now() + 1000
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('timed out waiting for condition')
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}
