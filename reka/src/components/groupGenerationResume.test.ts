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
        eventType: 'message.completed', conversationId: 7, turnId: 42,
        replyStepId: 2, messageId: 11, content: '流中的B',
      })
      onEvent({
        eventType: 'dice_roll.created', conversationId: 7,
        diceRoll: {
          summary: { id: 501, conversationId: 7, status: 'PENDING' },
          results: [],
        },
      })
      onEvent({ eventType: 'stream.caught_up', conversationId: 7 })
      onEvent({ eventType: 'turn.completed', conversationId: 7, turnId: 42 })
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
    workspace.conversations.value = [{
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '调查', status: 'active',
    }]

    await workspace.selectConversation(7)
    await waitFor(() => resumedWith === 'generation-7')
    await waitFor(() => session.getItem('galchat:generation:7') === null)

    assert.deepEqual(
      workspace.messages.value.map(({ id, content, status }) => ({ id, content, status })),
      [
        { id: 10, content: '流中的A', status: 'completed' },
        { id: 11, content: '流中的B', status: 'completed' },
      ],
    )
    assert.equal(workspace.latestDiceRoll.value?.summary.id, 501)
    assert.deepEqual(workspace.incomingDiceRolls.value, [])
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
  const originalConversation = api.conversation
  const originalContinue = streamTrpgTurn.continue
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  try {
    let storedWhileConnecting = false
    streamTrpgTurn.continue = async (conversationId, clientRequestId, onEvent) => {
      storedWhileConnecting = session.getItem(`galchat:generation:${conversationId}`) === clientRequestId
      onEvent({ eventType: 'stream.caught_up', conversationId })
      onEvent({ eventType: 'turn.completed', conversationId, turnId: 42 })
    }
    api.conversation = async () => ({ id: 7, userWorldId: 3, worldId: 2, mode: 'trpg', title: '调查', status: 'active' })
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
    streamTrpgTurn.continue = async (conversationId, clientRequestId, onEvent) => {
      interruptedId = clientRequestId
      assert.equal(session.getItem(`galchat:generation:${conversationId}`), clientRequestId)
      onEvent({
        eventType: 'reply.started', conversationId, turnId: 43,
        replyStepId: 9, messageId: 100,
      })
    }

    await workspace.startTrpgTurn()

    assert.equal(session.getItem('galchat:generation:7'), interruptedId)
  } finally {
    api.conversation = originalConversation
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

for (const completionStatus of [undefined, 'pending', 'failed'] as const) {
test(`keeps the generation error dialog open after ${completionStatus || 'normal turn'} state sync and allows retry`, async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const local = storage()
  const session = storage()
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: local,
    sessionStorage: session,
  })
  const originalConversation = api.conversation
  const originalContinue = streamTrpgTurn.continue
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  const originalCombat = api.combatOverview
  try {
    const requestIds: string[] = []
    let completionReady = false
    streamTrpgTurn.continue = async (conversationId, clientRequestId, onEvent) => {
      requestIds.push(clientRequestId)
      if (requestIds.length > 1) {
        completionReady = true
        onEvent({ eventType: 'stream.caught_up', conversationId })
        onEvent({ eventType: 'turn.completed', conversationId, turnId: 42 })
        return
      }
      onEvent({
        eventType: 'generation.failed', conversationId,
        turnId: 42, replyStepId: 9, messageId: 100,
        error: '模型调用失败',
        errorDetail: {
          errorId: 'error-1', code: 'GENERATION_FAILED',
          category: 'GENERATION', message: '模型调用失败',
          retryable: true, occurredAt: '2026-08-27T00:00:00Z',
          operation: 'continue-trpg-turn',
          request: { method: 'POST' }, response: { eventCount: 2 },
          stack: 'java.lang.IllegalStateException: model failed',
        },
      })
    }
    api.conversation = async () => ({ id: 7, userWorldId: 3, worldId: 2, mode: 'trpg', title: '调查', status: completionReady ? 'closed' : 'active', completionStatus: completionReady ? 'ready' : completionStatus })
    api.groupMessages = async () => [{
      id: 100, conversationId: 7, turnId: 42, replyStepId: 9,
      speakerType: 'kp', speakerName: 'KP', messageKind: 'dialogue',
      content: '', sequenceNo: 10, status: 'failed',
    }]
    api.replyPlan = async () => [{ id: 20, source: 'SCENE', displayName: '密道', items: [] }]
    api.currentTurn = async () => ({
      turnId: 42, planId: 20, planSource: 'SCENE', status: 'failed',
      waitingForUser: false, sceneOptions: {},
      steps: [{ stepId: 9, itemOrder: 1, actorType: 'kp', status: 'failed' }],
    })
    api.combatOverview = async () => []

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
      mode: 'trpg', title: '调查', status: 'active', completionStatus,
    }]
    workspace.selectedConversationId.value = 7
    workspace.currentTurn.value = {
      turnId: 42, planId: 20, planSource: 'SCENE', status: 'running',
      waitingForUser: false, sceneOptions: {},
      steps: [{ stepId: 9, itemOrder: 1, actorType: 'kp', status: 'running' }],
    }

    await workspace.startTrpgTurn()

    assert.equal(workspace.currentTurn.value?.status, 'failed')
    assert.equal(workspace.messages.value[0]?.status, 'failed')
    assert.equal(workspace.generationFailureOpen.value, true, 'state refresh must not dismiss the generation error')
    assert.equal(workspace.generationFailure.value?.detail.errorId, 'error-1')
    assert.equal(session.getItem('galchat:generation:7'), null)
    assert.equal([...Array.from({ length: local.length }, (_, index) => local.key(index))]
      .some((key) => key?.includes('error-1')), false)

    const retry = (workspace as unknown as Record<string, unknown>)
      .retryGenerationFailure
    assert.equal(typeof retry, 'function')
    await (retry as () => Promise<void>)()
    assert.equal(requestIds.length, 2)
    assert.equal(workspace.selectedConversation.value?.status, 'closed')
    assert.equal(workspace.selectedConversation.value?.completionStatus, 'ready')
    assert.equal(workspace.generationFailureOpen.value, false)
    assert.notEqual(requestIds[0], requestIds[1])
    assert.equal(workspace.generationFailureOpen.value, false)
  } finally {
    api.conversation = originalConversation
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalMessages
    api.replyPlan = originalPlans
    api.currentTurn = originalTurn
    api.combatOverview = originalCombat
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})
}

test('refresh discards debug details and resyncs an expired generation as retryable', async () => {
  const { api, streamGroupGeneration } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const session = storage([['galchat:generation:7', 'expired-7']])
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: session,
  })
  const originalConversation = api.conversation
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  const originalCombat = api.combatOverview
  const originalResume = streamGroupGeneration.resume
  try {
    api.conversation = async () => ({
      id: 7, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '调查', status: 'active',
    })
    api.groupMessages = async () => [{
      id: 100, conversationId: 7, turnId: 42, replyStepId: 9,
      speakerType: 'kp', speakerName: 'KP', messageKind: 'dialogue',
      content: '', sequenceNo: 10, status: 'failed',
    }]
    api.replyPlan = async () => [{ id: 20, source: 'SCENE', displayName: '密道', items: [] }]
    let currentTurnReads = 0
    api.currentTurn = async () => ({
      turnId: 42, planId: 20, planSource: 'SCENE',
      status: currentTurnReads++ === 0 ? 'running' : 'failed',
      waitingForUser: false, sceneOptions: {},
      steps: [{
        stepId: 9, itemOrder: 1, actorType: 'kp',
        status: currentTurnReads === 1 ? 'running' : 'failed',
      }],
    })
    api.combatOverview = async () => []
    streamGroupGeneration.resume = async (conversationId, clientRequestId, onEvent) => {
      assert.equal(conversationId, 7)
      assert.equal(clientRequestId, 'expired-7')
      onEvent({
        eventType: 'generation.failed', conversationId,
        error: '生成连接已失效，请重试此行动轮',
        errorDetail: {
          errorId: 'must-disappear', code: 'GENERATION_FAILED',
          category: 'RECOVERY', message: '失联', retryable: true,
          occurredAt: '2026-08-27T00:00:00Z', operation: 'resume',
          request: {}, response: {}, stack: 'debug stack',
        },
      })
    }

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
    await workspace.selectConversation(7)
    await waitFor(() => session.getItem('galchat:generation:7') === null)
    await waitFor(() => workspace.currentTurn.value?.status === 'failed')

    assert.equal(workspace.generationFailure.value, null)
    assert.equal(workspace.generationFailureOpen.value, false)
    assert.equal(workspace.messages.value[0]?.status, 'failed')
  } finally {
    api.conversation = originalConversation
    api.groupMessages = originalMessages
    api.replyPlan = originalPlans
    api.currentTurn = originalTurn
    api.combatOverview = originalCombat
    streamGroupGeneration.resume = originalResume
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('refreshes only each dice message’s own rounds while returning the full group for playback', async () => {
  const { api } = await import('../api/client.ts')
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
  const originalSummary = api.diceSummary
  const originalResults = api.diceResults
  try {
    api.diceSummary = async () => ({
      id: 25,
      conversationId: 4,
      reason: '近战攻击',
      roundCount: 2,
      status: 'COMPLETED',
    })
    api.diceResults = async () => [
      {
        id: 33,
        summaryId: 25,
        roundNo: 1,
        displayOrder: 1,
        displayType: 'MELEE_ATTACK',
        resultData: { formula: '1D100', modules: [], result: 25 },
        resolvedAt: '2026-08-18T02:28:30',
      },
      {
        id: 35,
        summaryId: 25,
        roundNo: 2,
        displayOrder: 1,
        displayType: 'DAMAGE',
        resultData: { formula: '1D6+1D4', modules: [], result: 3 },
        resolvedAt: '2026-08-18T02:28:35',
      },
    ]

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
    workspace.messages.value = [{
      id: 312,
      conversationId: 4,
      speakerType: 'kp',
      messageKind: 'dice_roll',
      content: '',
      sequenceNo: 24,
      status: 'completed',
      diceRoundNos: [1],
      diceRoll: {
        summary: { id: 25, conversationId: 4, roundCount: 1, status: 'PENDING' },
        results: [{
          id: 33,
          summaryId: 25,
          roundNo: 1,
          displayOrder: 1,
          displayType: 'MELEE_ATTACK',
          resultData: { formula: '1D100', modules: [], result: 25 },
        }],
      },
    }]

    const refreshed = await workspace.refreshDiceRoll(25)

    assert.deepEqual(workspace.messages.value[0]?.diceRoundNos, [1])
    assert.deepEqual(
      workspace.messages.value[0]?.diceRoll?.results.map((detail) => detail.id),
      [33],
    )
    assert.equal(workspace.messages.value[0]?.diceRoll?.results[0]?.resolvedAt, '2026-08-18T02:28:30')
    assert.equal(workspace.messages.value[0]?.diceRoll?.summary.status, 'COMPLETED')
    assert.deepEqual(
      refreshed.results.map((detail) => detail.id),
      [33, 35],
    )
    assert.deepEqual(workspace.latestDiceRoll.value, refreshed)

    // The follow-up round gets its own message when the stream resumes.
    workspace.messages.value.push({
      id: 313,
      conversationId: 4,
      speakerType: 'kp',
      messageKind: 'dice_roll',
      content: '',
      sequenceNo: 25,
      status: 'completed',
      diceRoundNos: [2],
      diceRoll: {
        summary: refreshed.summary,
        results: [{ ...refreshed.results[1]!, resolvedAt: undefined }],
      },
    })

    await workspace.refreshDiceRoll(25)

    assert.deepEqual(workspace.messages.value.map((message) => message.diceRoundNos), [[1], [2]])
    assert.deepEqual(
      workspace.messages.value.map((message) => message.diceRoll?.results.map((detail) => detail.id)),
      [[33], [35]],
    )
    assert.equal(workspace.messages.value[1]?.diceRoll?.results[0]?.resolvedAt, '2026-08-18T02:28:35')

    // Messages without explicit round metadata retain the rounds they already carry.
    delete workspace.messages.value[0]!.diceRoundNos
    await workspace.refreshDiceRoll(25)
    assert.deepEqual(workspace.messages.value.map((message) => message.diceRoundNos), [[1], [2]])
    assert.deepEqual(
      workspace.messages.value.map((message) => message.diceRoll?.results.map((detail) => detail.id)),
      [[33], [35]],
    )
  } finally {
    api.diceSummary = originalSummary
    api.diceResults = originalResults
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

for (const scenario of ['replay', 'reenter'] as const) {
  test(`history pagination stays consistent when ${scenario} happens during dice hydration`, async () => {
    const { api, streamGroupGeneration } = await import('../api/client.ts')
    const { useWorkspace } = await import('../composables/useWorkspace.ts')
    const { createRenderer, defineComponent, h } = await import('vue')
    const globals = { window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
    Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage(), sessionStorage: storage([['galchat:generation:7', 'active']]) })
    const savedApi = { conversation: api.conversation, groupMessages: api.groupMessages, replyPlan: api.replyPlan, currentTurn: api.currentTurn,
      actorRuntimes: api.actorRuntimes, modelApis: api.modelApis, combatOverview: api.combatOverview, investigatorCards: api.investigatorCards,
      diceSummary: api.diceSummary, diceResults: api.diceResults }
    const savedResume = streamGroupGeneration.resume
    let releaseDice!: (value: Awaited<ReturnType<typeof api.diceSummary>>) => void
    let hydrating = false
    let receive!: Parameters<typeof streamGroupGeneration.resume>[2]
    let finishConnection!: () => void
    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {}, createElement: () => ({}), createText: text => ({ text }),
      createComment: text => ({ text }), setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
      parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
    })
    const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
    app.mount({})
    try {
      api.conversation = async () => ({ id: 7, userWorldId: 3, worldId: 2, mode: 'chat', title: '群聊', status: 'active' })
      api.groupMessages = async (_id, before) => before == null
        ? Array.from({ length: 50 }, (_, i) => ({ id: i + 100, conversationId: 7, speakerType: 'character' as const, messageKind: 'dialogue' as const,
          content: '已有消息', sequenceNo: i + 100, status: 'completed' as const }))
        : [
          { id: 98, conversationId: 7, speakerType: 'kp', messageKind: 'dice_roll', content: '{"summaryId":25,"roundNos":[1]}', sequenceNo: 98, status: 'completed' },
          { id: 99, conversationId: 7, replyStepId: 1, speakerType: 'character', messageKind: 'dialogue', content: '旧历史快照', sequenceNo: 99, status: 'completed' },
        ]
      api.replyPlan = async () => []; api.currentTurn = async () => null; api.actorRuntimes = async () => []
      api.modelApis = async () => []; api.combatOverview = async () => []; api.investigatorCards = async () => []
      api.diceSummary = async () => { hydrating = true; return new Promise(resolve => { releaseDice = resolve }) }
      api.diceResults = async () => []
      streamGroupGeneration.resume = async (_id, _request, onEvent, _after, signal) => new Promise<void>((resolve, reject) => {
        receive = onEvent; finishConnection = resolve
        signal?.addEventListener('abort', () => reject(signal.reason), { once: true })
      })
      workspace.conversations.value = [await api.conversation(7)]
      await workspace.selectConversation(7)
      const older = workspace.loadOlderGroupMessages()
      await waitFor(() => hydrating)
      if (scenario === 'replay') {
        receive({ eventType: 'reply.started', conversationId: 7, messageId: 99, replyStepId: 1, sequence: 99, eventSequence: 1 })
        receive({ eventType: 'message.delta', conversationId: 7, messageId: 99, replyStepId: 1, delta: '流中的最新内容', eventSequence: 2 })
      } else {
        // Returning to the same conversation must invalidate the earlier page request too.
        await workspace.selectConversation(7)
      }
      releaseDice({ id: 25, conversationId: 7, status: 'COMPLETED' })
      await older
      if (scenario === 'replay') {
        assert.equal(workspace.messages.value.filter(message => message.id === 99).length, 1)
        assert.equal(workspace.messages.value.find(message => message.id === 99)?.content, '流中的最新内容')
        assert.equal(workspace.messages.value.length, 52)
        assert.deepEqual(workspace.messages.value.slice(0, 2).map(message => message.id), [98, 99])
      } else {
        assert.equal(workspace.messages.value.length, 50, 'old pagination must not modify the reloaded conversation')
        assert.equal(workspace.hasOlderGroupMessages.value, true, 'old pagination must not replace the new pagination state')
      }
      receive({ eventType: 'turn.completed', conversationId: 7, eventSequence: 3 })
      finishConnection()
      await waitFor(() => !workspace.loading.sending)
    } finally {
      app.unmount(); Object.assign(api, savedApi); streamGroupGeneration.resume = savedResume; Object.assign(globalThis, globals)
    }
  })
}

for (const mode of ['chat', 'trpg'] as const) {
  test(`${mode} reloads persisted state when the completed stream has already been released`, async () => {
    const { api, streamGroupGeneration } = await import('../api/client.ts')
    const { useWorkspace } = await import('../composables/useWorkspace.ts')
    const { createRenderer, defineComponent, h } = await import('vue')
    const globals = { window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
    const session = storage([['galchat:generation:7', 'finished']])
    Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage(), sessionStorage: session })
    const savedApi = { conversation: api.conversation, groupMessages: api.groupMessages, replyPlan: api.replyPlan,
      currentTurn: api.currentTurn, actorRuntimes: api.actorRuntimes, modelApis: api.modelApis,
      combatOverview: api.combatOverview, investigatorCards: api.investigatorCards }
    const savedResume = streamGroupGeneration.resume
    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
      createElement: () => ({}), createText: (text) => ({ text }), createComment: (text) => ({ text }),
      setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null, nextSibling: () => null,
    })
    const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
    app.mount({})
    try {
      let persisted = false
      let connections = 0
      api.conversation = async () => ({ id: 7, userWorldId: 3, worldId: 2, mode, title: '调查', status: 'active' })
      api.groupMessages = async () => [{ id: 10, conversationId: 7, turnId: 42, replyStepId: 1,
        speakerType: 'character', messageKind: 'dialogue', content: persisted ? '落库的最终回复' : '',
        sequenceNo: 10, status: persisted ? 'completed' : 'streaming' }]
      api.replyPlan = async () => []
      api.currentTurn = async () => null
      api.actorRuntimes = async () => []
      api.modelApis = async () => []
      api.combatOverview = async () => []
      api.investigatorCards = async () => []
      streamGroupGeneration.resume = async (_id, requestId, onEvent, after) => {
        assert.equal(requestId, 'finished')
        connections++
        if (connections === 1) {
          onEvent({ eventType: 'message.delta', conversationId: 7, replyStepId: 1, messageId: 10, delta: '部分', eventSequence: 1 })
          throw new Error('connection dropped')
        }
        assert.equal(after, 1)
        // A duplicate transport event must not be appended twice.
        onEvent({ eventType: 'message.delta', conversationId: 7, replyStepId: 1, messageId: 10, delta: '部分', eventSequence: 1 })
        assert.equal(workspace.messages.value[0]?.content, '部分')
        persisted = true
        onEvent({ eventType: 'generation.completed', conversationId: 7 })
      }
      workspace.conversations.value = [await api.conversation(7)]
      await workspace.selectConversation(7)
      await waitFor(() => !workspace.loading.sending)
      assert.equal(connections, 2)
      assert.equal(session.getItem('galchat:generation:7'), null)
      assert.deepEqual(workspace.messages.value.map(m => [m.content, m.status]), [['落库的最终回复', 'completed']])
      assert.equal(workspace.generationFailureOpen.value, false)
    } finally { app.unmount(); Object.assign(api, savedApi); streamGroupGeneration.resume = savedResume; Object.assign(globalThis, globals) }
  })
}

test('shared SSE reader closes a failed subscription before recovery can open another one', async () => {
  const { streamGroupGeneration } = await import('../api/client.ts')
  const globals = { fetch: globalThis.fetch, localStorage: globalThis.localStorage }
  let cancelled = false
  Object.assign(globalThis, {
    localStorage: storage(),
    fetch: async () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('data: {"eventType":"message.delta","delta":"partial"}\n\n')) },
      cancel() { cancelled = true },
    })),
  })
  try {
    await assert.rejects(streamGroupGeneration.resume(7, 'request', () => { throw new Error('render failure') }), /render failure/)
    assert.equal(cancelled, true)
  } finally { Object.assign(globalThis, globals) }
})

test('re-entering a group cancels its old subscription and ignores that connection’s late events', async () => {
  const { api, streamGroupGeneration } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const globals = { window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  const session = storage([['galchat:generation:7', 'active']])
  Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage(), sessionStorage: session })
  const savedApi = { conversation: api.conversation, groupMessages: api.groupMessages, replyPlan: api.replyPlan, currentTurn: api.currentTurn,
    actorRuntimes: api.actorRuntimes, modelApis: api.modelApis, combatOverview: api.combatOverview, investigatorCards: api.investigatorCards }
  const savedResume = streamGroupGeneration.resume
  const connections: Array<{ signal: AbortSignal; receive: Parameters<typeof streamGroupGeneration.resume>[2]; finish: () => void }> = []
  let workspace!: ReturnType<typeof useWorkspace>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {}, createElement: () => ({}), createText: text => ({ text }),
    createComment: text => ({ text }), setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
    parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
  app.mount({})
  try {
    api.conversation = async () => ({ id: 7, userWorldId: 3, worldId: 2, mode: 'chat', title: '群聊', status: 'active' })
    api.groupMessages = async () => [{ id: 10, conversationId: 7, replyStepId: 1, speakerType: 'character', messageKind: 'dialogue', content: '', sequenceNo: 10, status: 'streaming' }]
    api.replyPlan = async () => []; api.currentTurn = async () => null; api.actorRuntimes = async () => []
    api.modelApis = async () => []; api.combatOverview = async () => []; api.investigatorCards = async () => []
    streamGroupGeneration.resume = async (_id, _request, receive, _after, signal) => new Promise<void>((resolve, reject) => {
      assert.ok(signal)
      connections.push({ signal, receive, finish: resolve })
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    })
    workspace.conversations.value = [await api.conversation(7)]
    await workspace.selectConversation(7)
    assert.equal(connections.length, 1)
    await workspace.selectConversation(7)
    assert.equal(connections.length, 2)
    assert.equal(connections[0]!.signal.aborted, true)
    connections[0]!.receive({ eventType: 'message.delta', messageId: 10, replyStepId: 1, delta: '旧连接', eventSequence: 1 })
    connections[1]!.receive({ eventType: 'message.delta', messageId: 10, replyStepId: 1, delta: '当前连接', eventSequence: 1 })
    assert.equal(workspace.messages.value[0]?.content, '当前连接')
    assert.equal(workspace.loading.sending, true)
    connections[1]!.receive({ eventType: 'turn.completed', conversationId: 7, eventSequence: 2 })
    connections[1]!.finish()
    await waitFor(() => !workspace.loading.sending)
    assert.equal(session.getItem('galchat:generation:7'), null)
  } finally { app.unmount(); Object.assign(api, savedApi); streamGroupGeneration.resume = savedResume; Object.assign(globalThis, globals) }
})

for (const mode of ['chat', 'trpg'] as const) {
  test(`${mode} sync retains loaded older pages and respects an empty server history`, async (t) => {
    const { api, streamTrpgTurn } = await import('../api/client.ts')
    const { useWorkspace } = await import('../composables/useWorkspace.ts')
    const { createRenderer, defineComponent, h } = await import('vue')
    const globals = { fetch: globalThis.fetch, window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
    Object.assign(globalThis, {
      window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage(), sessionStorage: storage(),
      fetch: async () => new Response('data: {"eventType":"turn.completed","conversationId":7,"turnId":42}\n\n'),
    })
    t.after(() => Object.assign(globalThis, globals))
    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {}, createElement: () => ({}), createText: text => ({ text }),
      createComment: text => ({ text }), setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
      parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
    })
    const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
    app.mount({})
    t.after(() => app.unmount())
    const conversation = { id: 7, userWorldId: 3, worldId: 2, mode, title: '群聊', status: 'active' as const }
    workspace.conversations.value = [conversation]
    const records = Array.from({ length: 112 }, (_, i) => ({ id: i + 1, conversationId: 7, speakerType: 'user' as const,
      messageKind: 'dialogue' as const, content: `消息${i + 1}`, sequenceNo: i + 1, status: 'completed' }))
    let completed = false
    let empty = false
    t.mock.method(api, 'conversation', async () => conversation)
    t.mock.method(api, 'groupMessages', async (_id: number, before?: number) => {
      if (empty) return []
      if (before != null) return records.filter(item => item.id < before).slice(-50)
      return completed ? records.slice(-50).map(item => ({ ...item, content: `已保存${item.id}` })) : records.slice(60, 110)
    })
    t.mock.method(api, 'replyPlan', async () => [{ source: 'USER', displayName: '群聊', items: [] }])
    t.mock.method(api, 'currentTurn', async () => null)
    t.mock.method(api, 'actorRuntimes', async () => [])
    t.mock.method(api, 'modelApis', async () => [])
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: { eventType: string; conversationId: number; turnId: number }) => void) => {
      receive({ eventType: 'turn.paused', conversationId: 7, turnId: 42 })
    })
    await workspace.selectConversation(7)
    await workspace.loadOlderGroupMessages()
    await workspace.loadOlderGroupMessages()
    assert.equal(workspace.messages.value.length, 110)
    assert.equal(workspace.hasOlderGroupMessages.value, false)
    const sync = async () => {
      if (mode === 'trpg') assert.equal(await workspace.startTrpgTurn(), true)
      else { workspace.messageInput.value = '新问题'; await workspace.sendMessage() }
    }
    completed = true
    await sync()
    assert.deepEqual(workspace.messages.value.map(item => item.id), Array.from({ length: 112 }, (_, i) => i + 1))
    assert.equal(workspace.messages.value.find(item => item.id === 100)?.content, '已保存100')
    assert.equal(workspace.hasOlderGroupMessages.value, false)
    empty = true
    await sync()
    assert.deepEqual(workspace.messages.value, [])
    assert.equal(workspace.hasOlderGroupMessages.value, false)
  })
}
