import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Conversation, GroupChatEvent, GroupMessage } from '../api/types.ts'

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

for (const scenario of [undefined, 'pending', 'failed', 'checkpoint-unavailable'] as const) {
const checkpointUnavailable = scenario === 'checkpoint-unavailable'
const completionStatus = checkpointUnavailable ? undefined : scenario
test(`keeps the generation error dialog open after ${scenario || 'normal turn'} state sync`, async () => {
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
        ...(checkpointUnavailable ? {} : { turnId: 42, replyStepId: 9, messageId: 100 }),
        error: checkpointUnavailable ? '请使用「回退至上一轮」恢复后继续。' : '模型调用失败',
        errorDetail: {
          errorId: 'error-1', code: checkpointUnavailable ? 'TURN_CHECKPOINT_UNAVAILABLE' : 'GENERATION_FAILED',
          category: 'GENERATION', message: checkpointUnavailable ? '请使用「回退至上一轮」恢复后继续。' : '模型调用失败',
          retryable: !checkpointUnavailable, occurredAt: '2026-08-27T00:00:00Z',
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

    if (checkpointUnavailable) {
      assert.equal(workspace.generationFailure.value?.detail.code, 'TURN_CHECKPOINT_UNAVAILABLE')
      assert.equal(workspace.generationFailure.value?.detail.retryable, false)
      assert.equal(workspace.generationFailure.value?.message, '请使用「回退至上一轮」恢复后继续。')
      await workspace.retryGenerationFailure()
      assert.equal(requestIds.length, 1)
      assert.equal(workspace.generationFailureOpen.value, true, 'rollback guidance must remain visible')
      return
    }

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

test('refresh discards debug details and resyncs an expired generation as retryable', async t => {
  const { api, streamGroupGeneration } = await import('../api/client.ts')
  t.mock.method(api, 'actorRuntimes', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
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
  const originalMessages = api.groupMessages
  let savedRounds = [[1]]
  try {
    api.groupMessages = async () => savedRounds.map((roundNos, index) => ({
      id: 312 + index, conversationId: 4, speakerType: 'kp', messageKind: 'dice_roll',
      sequenceNo: 24 + index, status: 'completed', content: JSON.stringify({ summaryId: 25, roundNos }),
    }))
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

    // Legacy conversations can have a separate message for the follow-up round.
    savedRounds = [[1], [2]]
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

    // A new automatic follow-up belongs only to the latest of those messages.
    api.diceSummary = async () => ({ ...refreshed.summary, roundCount: 3 })
    api.diceResults = async () => [...refreshed.results, {
      id: 37, summaryId: 25, roundNo: 3, displayType: 'MAJOR_WOUND_CON',
    }]
    savedRounds = [[1], [2, 3]]
    await workspace.refreshDiceRoll(25)
    await workspace.refreshDiceRoll(25)
    assert.deepEqual(workspace.messages.value.map((message) => message.diceRoundNos), [[1], [2, 3]])
    assert.deepEqual(
      workspace.messages.value.map((message) => message.diceRoll?.results.map((detail) => detail.id)),
      [[33], [35, 37]],
    )
  } finally {
    api.diceSummary = originalSummary
    api.diceResults = originalResults
    api.groupMessages = originalMessages
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

for (const missingCheckpoint of [false, true]) {
test(`ordinary group retry ${missingCheckpoint ? 'offers withdrawal when checkpoint is missing' : 'keeps completed replies without resending the user message'}`, async () => {
  const { api } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previous = { window: globalThis.window, localStorage: globalThis.localStorage,
    sessionStorage: globalThis.sessionStorage, fetch: globalThis.fetch }
  Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(), sessionStorage: storage() })
  const original = { conversation: api.conversation, groupMessages: api.groupMessages,
    replyPlan: api.replyPlan, currentTurn: api.currentTurn, withdrawGroupTurn: api.withdrawGroupTurn }
  try {
    const conversation: Conversation = { id: 7, userWorldId: 3, worldId: 2, mode: 'chat' as const, title: '群聊', status: 'active' }
    const userMessage: GroupMessage = { id: 100, conversationId: 7, turnId: 42, speakerType: 'user',
      messageKind: 'dialogue', content: '开始', sequenceNo: 1, status: 'completed' }
    const completed: GroupMessage = { id: 101, conversationId: 7, turnId: 42, replyStepId: 8, speakerType: 'character',
      messageKind: 'dialogue', content: 'A已完成', sequenceNo: 2, status: 'completed' }
    const failed: GroupMessage = { id: 102, conversationId: 7, turnId: 42, replyStepId: 9, speakerType: 'character',
      messageKind: 'dialogue', content: 'B未完成', sequenceNo: 3, status: 'failed' }
    const failedTurn = { turnId: 42, planSource: 'USER' as const, status: 'failed', waitingForUser: false,
      sceneOptions: {}, steps: [{ stepId: 8, itemOrder: 1, actorType: 'character', status: 'completed' },
        { stepId: 9, itemOrder: 2, actorType: 'character', status: 'failed' }] }
    let withdrawn = false
    let requestedBody: Record<string, unknown> | null = null
    const requests: string[] = []
    api.conversation = async () => conversation
    api.replyPlan = async () => []
    api.currentTurn = async () => !withdrawn && missingCheckpoint ? failedTurn : null
    api.groupMessages = async () => withdrawn ? [] : missingCheckpoint ? [userMessage, completed, failed]
      : [userMessage, completed, { ...failed, id: 103, status: 'completed', content: 'B重新完成' }]
    api.withdrawGroupTurn = async (id, expectedTurnId) => {
      assert.equal(id, 7)
      assert.equal(expectedTurnId, 42)
      withdrawn = true
      return userMessage
    }
    globalThis.fetch = async (url, init) => {
      requests.push(String(url))
      assert.match(String(url), /\/conversations\/7\/turns\/42\/retry$/)
      requestedBody = JSON.parse(String(init?.body))
      const events = missingCheckpoint ? [{ eventType: 'generation.failed', conversationId: 7,
        error: '请使用「撤回本轮对话」后重新发送。', errorDetail: {
          errorId: 'missing-chat-checkpoint', code: 'GROUP_CHECKPOINT_UNAVAILABLE', category: 'GENERATION',
          message: '请使用「撤回本轮对话」后重新发送。', retryable: false, occurredAt: '2026-09-29T00:00:00Z',
          operation: 'retry-group-turn', request: {}, response: {}, stack: '' },
      }] : [{ eventType: 'turn.accepted', conversationId: 7, turnId: 42 },
        { eventType: 'turn.completed', conversationId: 7, turnId: 42 }]
      return new Response(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''),
        { headers: { 'Content-Type': 'text/event-stream' } })
    }
    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
      createElement: () => ({}), createText: text => ({ text }), createComment: text => ({ text }),
      setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
      parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
    })
    renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } })).mount({})
    workspace.conversations.value = [conversation]
    workspace.selectedConversationId.value = 7
    workspace.currentTurn.value = failedTurn
    workspace.messages.value = [userMessage, completed, failed]

    await workspace.retryGroupTurn()

    assert.equal(requests.length, 1)
    assert.deepEqual(Object.keys(requestedBody!), ['clientRequestId'])
    assert.deepEqual(workspace.messages.value.slice(0, 2), [userMessage, completed])
    if (missingCheckpoint) {
      assert.equal(workspace.generationFailureOpen.value, true)
      assert.equal(workspace.generationFailure.value?.detail.code, 'GROUP_CHECKPOINT_UNAVAILABLE')
      assert.equal(workspace.generationFailure.value?.turnId, 42)
      assert.equal(workspace.generationFailure.value?.detail.retryable, false)
      await workspace.withdrawGenerationFailure()
      assert.equal(withdrawn, true)
      assert.equal(workspace.messages.value.length, 0)
      assert.equal(workspace.currentTurn.value, null)
      assert.equal(workspace.generationFailureOpen.value, false)
    } else {
      assert.equal(workspace.messages.value[2]?.content, 'B重新完成')
      assert.equal(workspace.currentTurn.value, null)
    }
  } finally {
    Object.assign(api, original)
    Object.assign(globalThis, previous)
  }
})
}

for (const operation of ['plan', 'runtime'] as const) {
  for (const navigation of ['stay', 'other', 'reenter'] as const) {
    test(`${operation} save only updates the originating conversation view (${navigation})`, async () => {
      const { api } = await import('../api/client.ts')
      const { useWorkspace } = await import('../composables/useWorkspace.ts')
      const { createRenderer, defineComponent, h } = await import('vue')
      const globals = { window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
      Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage(), sessionStorage: storage() })
      const savedApi = { ...api }
      let workspace!: ReturnType<typeof useWorkspace>
      const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
        patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
        createElement: () => ({}), createText: text => ({ text }), createComment: text => ({ text }),
        setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
        parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
      })
      const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
      app.mount({})
      try {
        api.conversation = async id => ({ id, userWorldId: 3, worldId: 2, mode: 'chat', title: String(id), status: 'active' })
        api.groupMessages = async () => []; api.currentTurn = async () => null; api.modelApis = async () => []
        api.replyPlan = async id => [{ source: 'USER', displayName: `loaded-${id}`, items: [{ order: 1, actorType: 'character', actorId: 101 }] }]
        api.actorRuntimes = async () => [{ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiAvailable: true }]
        let release!: () => void
        const response = new Promise<void>(resolve => { release = resolve })
        api.saveReplyPlan = async id => {
          assert.equal(id, 7)
          await response
          return { source: 'USER', displayName: 'saved-7', items: [{ order: 1, actorType: 'character', actorId: 101 }] }
        }
        api.saveActorRuntime = async id => {
          assert.equal(id, 7)
          await response
          return { actorType: 'character', actorId: 101, controlMode: 'MANUAL', modelApiAvailable: true }
        }
        workspace.selectedWorldId.value = 3
        workspace.conversations.value = [await api.conversation(7), await api.conversation(8)]
        await workspace.selectConversation(7)
        const pending = operation === 'plan' ? workspace.savePlan()
          : workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MANUAL' })
        if (navigation !== 'stay') await workspace.selectConversation(8)
        if (navigation === 'reenter') await workspace.selectConversation(7)
        release()
        await pending
        if (operation === 'plan') {
          assert.equal(workspace.replyPlan.value.displayName, navigation === 'stay' ? 'saved-7' : `loaded-${navigation === 'other' ? 8 : 7}`)
        } else {
          assert.equal(workspace.actorRuntimes.value[0]?.controlMode, navigation === 'stay' ? 'MANUAL' : 'MODEL')
        }
      } finally { app.unmount(); Object.assign(api, savedApi); Object.assign(globalThis, globals) }
    })
  }
}

async function groupMutationFixture(t: import('node:test').TestContext) {
  const { api } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const globals = { window: globalThis.window, localStorage: globalThis.localStorage,
    sessionStorage: globalThis.sessionStorage, fetch: globalThis.fetch }
  Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(), sessionStorage: storage() })
  t.after(() => Object.assign(globalThis, globals))
  let workspace!: ReturnType<typeof useWorkspace>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
    createElement: () => ({}), createText: text => ({ text }), createComment: text => ({ text }),
    setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
    parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
  app.mount({})
  t.after(() => app.unmount())
  const conversations: Conversation[] = [7, 8].map(id => ({ id, userWorldId: 3, worldId: 2,
    mode: 'chat', title: String(id), status: 'active', characterIds: [101, 102] }))
  t.mock.method(api, 'conversation', async (id: number) => conversations.find(item => item.id === id)!)
  t.mock.method(api, 'conversations', async () => conversations.filter(item => item.id !== 7))
  t.mock.method(api, 'groupMessages', async (id: number): Promise<GroupMessage[]> => [{ id: id * 10,
    conversationId: id, turnId: 42, speakerType: 'user', messageKind: 'dialogue', content: `history-${id}`, sequenceNo: 1, status: 'completed' }])
  t.mock.method(api, 'replyPlan', async () => [{ source: 'USER', displayName: '群聊',
    items: [{ order: 1, actorType: 'character', actorId: 101 }] }])
  t.mock.method(api, 'currentTurn', async () => null)
  t.mock.method(api, 'actorRuntimes', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'characters', async () => [])
  workspace.selectedWorldId.value = 3
  workspace.conversations.value = conversations
  await workspace.selectConversation(7)
  return { workspace, api }
}

test('group mutation: reopening a saved reply plan keeps omitted members available', async t => {
  const { workspace } = await groupMutationFixture(t)
  workspace.characters.value = [101, 102].map(characterId => ({ userWorldId: 3, characterId, characterName: String(characterId) }))
  await workspace.selectConversation(8)
  await workspace.selectConversation(7)
  assert.deepEqual(workspace.availablePlanCharacters.value.map(item => item.characterId), [102])
  workspace.addPlanItem(102)
  assert.deepEqual(workspace.replyPlan.value.items.map(item => item.actorId), [101, 102])
})

for (const failLoad of [false, true]) {
  test(`reply settings cannot reuse another conversation during an incomplete load (failed=${failLoad})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    const previousPlan = workspace.replyPlan.value
    let release!: () => void
    t.mock.method(api, 'groupMessages', async () => {
      await new Promise<void>(resolve => { release = resolve })
      if (failLoad) throw new Error('history unavailable')
      return []
    })
    const selecting = workspace.selectConversation(8)
    const cleared = { plans: [...workspace.replyPlans.value], items: [...workspace.replyPlan.value.items], participants: [...workspace.participantIds.value] }
    const writes: number[] = []
    t.mock.method(api, 'saveReplyPlan', async (id: number, payload: any) => { writes.push(id); return payload })
    const runtimeWrites: number[] = []
    const runtime = { actorType: 'character' as const, actorId: 101, controlMode: 'MANUAL' as const }
    t.mock.method(api, 'saveActorRuntime', async (id: number) => { runtimeWrites.push(id); return { ...runtime, modelApiAvailable: true } })
    // Even a stale editor's payload must not be persisted to the new conversation.
    workspace.replyPlan.value = previousPlan
    await workspace.savePlan()
    await workspace.saveActorRuntime(runtime)
    release()
    await selecting
    if (failLoad) {
      await workspace.savePlan()
      await workspace.saveActorRuntime(runtime)
    }
    assert.deepEqual(cleared, { plans: [], items: [], participants: [] })
    assert.deepEqual(writes, [])
    assert.deepEqual(runtimeWrites, [])
    t.mock.method(api, 'groupMessages', async () => [])
    await workspace.selectConversation(8)
    await workspace.savePlan()
    await workspace.saveActorRuntime(runtime)
    assert.deepEqual(writes, [8], 'settings remain editable after a successful load')
    assert.deepEqual(runtimeWrites, [8])
  })
}

for (const navigation of ['stay', 'conversation', 'world'] as const) {
  test(`group mutation: deletion preserves the current view after ${navigation}`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    let release!: () => void
    t.mock.method(api, 'deleteConversation', async (id: number) => {
      assert.equal(id, 7)
      await new Promise<void>(resolve => { release = resolve })
    })
    const pending = workspace.deleteConversation()
    if (navigation !== 'stay') await workspace.selectConversation(8)
    if (navigation === 'world') {
      workspace.selectedWorldId.value = 4
      workspace.conversations.value = [{ id: 9, userWorldId: 4, worldId: 2, mode: 'chat', title: 'new world', status: 'active' }]
      workspace.selectedConversationId.value = 9
    }
    const messages = [...workspace.messages.value]
    release()
    const shouldLeaveConversation = await pending
    if (navigation === 'stay') {
      assert.equal(workspace.selectedConversationId.value, null)
      assert.deepEqual(workspace.messages.value, [])
      assert.equal(shouldLeaveConversation, true)
    } else {
      assert.equal(workspace.selectedConversationId.value, navigation === 'world' ? 9 : 8)
      assert.deepEqual(workspace.messages.value, messages)
      assert.equal(shouldLeaveConversation, false)
    }
    assert.deepEqual(workspace.conversations.value.map(item => item.id), navigation === 'world' ? [9] : [8])
  })
}

for (const outcome of ['completed', 'rejected', 'generation-failed'] as const) {
  test(`group mutation: history read failure preserves the ${outcome} send outcome`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    globalThis.fetch = async () => outcome === 'rejected'
      ? new Response('request rejected', { status: 400 })
      : new Response([
        { eventType: 'turn.accepted', conversationId: 7, turnId: 42 },
        { eventType: outcome === 'completed' ? 'turn.completed' : 'generation.failed',
          conversationId: 7, turnId: 42, error: outcome === 'generation-failed' ? 'model failed' : undefined },
      ].map(event => `data: ${JSON.stringify(event)}\n\n`).join(''))
    t.mock.method(api, 'groupMessages', async () => { throw new Error('history read failed') })
    workspace.messageInput.value = 'hello'
    await workspace.sendMessage()
    assert.equal(workspace.messageInput.value, outcome === 'rejected' ? 'hello' : '')
    assert.equal(workspace.replyTurnState.value?.phase, outcome === 'completed' ? 'completed' : 'failed')
    if (outcome === 'generation-failed') assert.equal(workspace.replyTurnState.value?.error, 'model failed')
    assert.equal(workspace.messages.value.some(item => item.content === 'hello'), outcome !== 'rejected')
    assert.equal(workspace.loading.sending, false)
  })
}

for (const phase of ['world', 'details', 'reenter'] as const) {
  test(`world loading ignores an obsolete ${phase} response`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    let release!: () => void
    let started!: () => void
    const entered = new Promise<void>(resolve => { started = resolve })
    const blocked = new Promise<void>(resolve => { release = resolve })
    let first = true
    t.mock.method(api, 'userWorld', async (id: number) => {
      if (id === 3 && phase !== 'details' && first) { first = false; started(); await blocked }
      return { id, worldId: id + 100, myWorld: true }
    })
    t.mock.method(api, 'characters', async (id: number) => [{ userWorldId: id, characterId: id * 100, characterName: String(id) }])
    t.mock.method(api, 'conversations', async (id: number) => [{ id: id + 10, userWorldId: id, worldId: id + 100,
      mode: 'chat', title: String(id), status: 'active' }])
    t.mock.method(api, 'worldSave', async () => null)
    t.mock.method(api, 'characterTemplates', async (id: number) => {
      if (id === 103 && phase === 'details' && first) { first = false; started(); await blocked }
      return [{ id, characterName: `template-${id}` }]
    })
    t.mock.method(api, 'worldDetails', async (id: number) => [{ id, title: String(id), content: String(id) }])
    t.mock.method(api, 'conversation', async (id: number) => ({ id, userWorldId: id - 10, worldId: id + 90,
      mode: 'chat', title: String(id), status: 'active' }))
    const old = workspace.selectWorld(3)
    await entered
    await workspace.selectWorld(4)
    if (phase === 'reenter') {
      await workspace.selectWorld(3)
      workspace.conversations.value.push({ id: 19, userWorldId: 3, worldId: 103, mode: 'chat', title: 'chosen', status: 'active' })
      await workspace.selectConversation(19)
    }
    release()
    const navigate = await old
    const worldId = phase === 'reenter' ? 3 : 4
    assert.equal(workspace.selectedWorldId.value, worldId)
    assert.equal(workspace.selectedConversationId.value, phase === 'reenter' ? 19 : 14)
    assert.deepEqual(workspace.characters.value.map(item => item.userWorldId), [worldId])
    assert.deepEqual(workspace.characterTemplates.value.map(item => item.id), [worldId + 100])
    assert.deepEqual(workspace.details.value.map(item => item.id), [worldId + 100])
    assert.equal(navigate, false)
    assert.equal(workspace.loading.workspace, false)
  })
}

for (const navigation of ['stay', 'world', 'reenter', 'logout'] as const) {
  test(`character refresh preserves the current world after ${navigation}`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    let release!: () => void
    let first = true
    t.mock.method(api, 'characters', async (id: number) => {
      if (first) {
        first = false
        await new Promise<void>(resolve => { release = resolve })
        return [{ userWorldId: id, characterId: 7, characterName: '旧请求' }]
      }
      return [{ userWorldId: id, characterId: 8, characterName: '当前角色' }]
    })
    t.mock.method(api, 'userWorld', async (id: number) => ({ id }))
    t.mock.method(api, 'conversations', async () => [])
    t.mock.method(api, 'worldSave', async () => null)
    const refreshing = workspace.reloadCharacters()
    if (navigation === 'world' || navigation === 'reenter') await workspace.selectWorld(4)
    if (navigation === 'reenter') await workspace.selectWorld(3)
    if (navigation === 'logout') workspace.logout()
    release(); await refreshing
    assert.deepEqual(workspace.characters.value.map(item => [item.userWorldId, item.characterId]),
      navigation === 'stay' ? [[3, 7]] : navigation === 'world' ? [[4, 8]] : navigation === 'reenter' ? [[3, 8]] : [])
  })
}

for (const newestFails of [false, true]) test(`only the newest same-world character refresh can replace the list (fails=${newestFails})`, async t => {
  const { workspace, api } = await groupMutationFixture(t)
  workspace.characters.value = [{ userWorldId: 3, characterId: 7, characterName: '当前角色', modelApiId: 9, favorValue: 50 }]
  let release!: () => void
  let calls = 0
  t.mock.method(api, 'characters', async () => {
    if (++calls === 1) {
      await new Promise<void>(resolve => { release = resolve })
      return [{ userWorldId: 3, characterId: 7, characterName: '旧数据', modelApiId: 4, favorValue: 10 }]
    }
    if (newestFails) throw new Error('刷新失败')
    return [{ userWorldId: 3, characterId: 7, characterName: '新数据', modelApiId: 99, favorValue: 75 }]
  })
  const old = workspace.reloadCharacters()
  if (newestFails) await assert.rejects(workspace.reloadCharacters(), /刷新失败/)
  else await workspace.reloadCharacters()
  release(); await old
  assert.deepEqual(workspace.characters.value.map(character => [character.modelApiId, character.favorValue]),
    newestFails ? [[9, 50]] : [[99, 75]])
})

test('an obsolete world failure cannot clear the new loading indicator', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  let rejectOld!: (error: Error) => void
  let finishNew!: () => void
  t.mock.method(api, 'userWorld', async (id: number) => {
    if (id === 3) await new Promise<void>((_resolve, reject) => { rejectOld = reject })
    else await new Promise<void>(resolve => { finishNew = resolve })
    return { id }
  })
  t.mock.method(api, 'characters', async () => [])
  t.mock.method(api, 'conversations', async () => [])
  t.mock.method(api, 'worldSave', async () => null)
  const old = workspace.selectWorld(3)
  const current = workspace.selectWorld(4)
  rejectOld(new Error('old load failed'))
  await old
  assert.equal(workspace.loading.workspace, true)
  finishNew()
  assert.equal(await current, true)
  assert.equal(workspace.loading.workspace, false)
})

for (const mode of ['chat', 'trpg'] as const) {
  for (const failure of ['rejected', 'generation'] as const) for (const draft of ['', '检查窗户']) {
    test(`${mode} restores only rejected input after ${failure} failure (draft=${Boolean(draft)})`, async t => {
      const { workspace, api } = await groupMutationFixture(t)
      const { notice } = await import('../composables/useNotice.ts')
      t.after(() => clearTimeout(notice.timer))
      workspace.conversations.value[0]!.mode = mode
      const turn = { turnId: 42, stepId: 99, status: 'waiting_input', inputType: 'message' as const, waitingForUser: true,
        sceneOptions: {}, steps: [{ stepId: 99, itemOrder: 1, actorType: 'user', status: 'waiting_input' }] }
      workspace.currentTurn.value = turn
      t.mock.method(api, 'groupMessages', async () => [])
      t.mock.method(api, 'combatOverview', async () => [])
      t.mock.method(api, 'investigatorCards', async () => [])
      let respond!: (response: Response) => void
      globalThis.fetch = async () => new Promise<Response>(resolve => { respond = resolve })
      workspace.messageInput.value = '打开门'
      const sending = workspace.sendMessage()
      await waitFor(() => Boolean(respond))
      workspace.messageInput.value = draft
      respond(failure === 'rejected' ? Response.json({ code: 0, msg: '会话忙碌' })
        : new Response('data: {"eventType":"generation.failed","turnId":42,"error":"模型失败"}\n\n'))
      await sending
      assert.equal(workspace.messageInput.value, draft || (failure === 'rejected' ? '打开门' : ''))
    })
  }
}

for (const mode of ['chat', 'trpg'] as const) {
  for (const outcome of ['recovered', 'pending', 'failed', 'switched'] as const) {
    test(`initial history failure permits ${mode} submissions only after replay recovery (${outcome})`, async t => {
      const { workspace, api } = await groupMutationFixture(t)
      const { streamGroupGeneration, streamTrpgTurn } = await import('../api/client.ts')
      const { notice } = await import('../composables/useNotice.ts')
      t.after(() => clearTimeout(notice.timer))
      workspace.conversations.value[0]!.mode = mode
      t.mock.method(api, 'conversation', async (id: number) => ({ id, userWorldId: 3, worldId: 2,
        mode, title: String(id), status: 'active', characterIds: [102] }))
      const runtime = { actorType: 'character' as const, actorId: 102, controlMode: 'MANUAL' as const, modelApiAvailable: true }
      t.mock.method(api, 'actorRuntimes', async () => [runtime])
      t.mock.method(api, 'combatOverview', async () => [])
      t.mock.method(api, 'investigatorCards', async () => [])
      const recovered: GroupMessage = { id: 90, conversationId: 7, turnId: 42,
        speakerType: 'user', messageKind: 'dialogue', content: 'recovered history', sequenceNo: 1, status: 'completed' }
      let reads = 0
      let release!: () => void
      t.mock.method(api, 'groupMessages', async (id: number) => {
        if (id !== 7) return []
        if (++reads === 1) throw new Error('initial history unavailable')
        if (reads === 2) {
          await new Promise<void>(resolve => { release = resolve })
        }
        if (outcome === 'failed') throw new Error('recovery history unavailable')
        return [recovered]
      })
      sessionStorage.setItem('galchat:generation:7', 'recovery-request')
      t.mock.method(streamGroupGeneration, 'resume', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
        receive({ eventType: 'generation.completed', conversationId: 7, turnId: 42, eventSequence: 1 })
      })
      await workspace.selectConversation(7)
      await waitFor(() => !!release)
      assert.equal(workspace.conversationReady.value, false)
      let requests = 0
      globalThis.fetch = async () => {
        requests++
        return new Response('data: {"eventType":"generation.completed","conversationId":7,"turnId":43}\n\n')
      }
      t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
        requests++
        receive({ eventType: 'generation.completed', conversationId: 7, turnId: 43 })
      })
      if (outcome === 'recovered') {
        release()
        await waitFor(() => !workspace.loading.sending)
        assert.equal(workspace.messages.value[0]?.content, 'recovered history')
      }
      workspace.messageInput.value = 'new draft'
      const send = () => mode === 'chat' ? workspace.sendMessage() : workspace.startTrpgTurn()
      const sending = send()
      if (outcome !== 'recovered') {
        await new Promise(resolve => setTimeout(resolve, 0))
        assert.equal(requests, 0, 'must wait for authoritative history')
        if (outcome === 'switched') await workspace.selectConversation(8)
        release()
      }
      await sending
      await waitFor(() => !workspace.loading.sending)
      const success = outcome === 'recovered' || outcome === 'pending'
      assert.equal(requests, success ? 1 : 0)
      if (success) {
        assert.equal(workspace.conversationReady.value, true)
        assert.deepEqual(workspace.participantIds.value, [102])
        assert.deepEqual(workspace.actorRuntimes.value, [runtime])
      }
      if (outcome === 'failed') {
        assert.equal(workspace.conversationReady.value, false)
        await send()
        assert.equal(requests, 0, 'failed recovery must keep submissions blocked')
        assert.equal(workspace.messageInput.value, 'new draft')
      }
      if (outcome === 'switched') assert.equal(workspace.selectedConversationId.value, 8)
    })
  }
}

for (const mode of ['chat', 'manual', 'trpg'] as const) {
  for (const accepted of [false, true]) for (const draft of ['', '新草稿']) {
    test(`${mode} SSE failure restores only unaccepted input (accepted=${accepted}, draft=${Boolean(draft)})`, async t => {
      const { workspace, api } = await groupMutationFixture(t)
      workspace.conversations.value[0]!.mode = mode === 'trpg' ? 'trpg' : 'chat'
      if (mode !== 'chat') workspace.currentTurn.value = {
        turnId: 42, stepId: 99, status: 'waiting_input', inputType: 'message', waitingForUser: true,
        sceneOptions: {}, steps: [{ stepId: 99, itemOrder: 1, actorType: mode === 'manual' ? 'character' : 'user', actorId: mode === 'manual' ? 101 : undefined, status: 'waiting_input' }],
      }
      const persisted: GroupMessage = { id: 71, conversationId: 7, turnId: 43,
        speakerType: mode === 'manual' ? 'character' : 'user', messageKind: 'dialogue',
        content: '打开门', sequenceNo: 2, status: 'completed' }
      t.mock.method(api, 'groupMessages', async () => accepted ? [persisted] : [])
      t.mock.method(api, 'combatOverview', async () => [])
      t.mock.method(api, 'investigatorCards', async () => [])
      let respond!: (response: Response) => void
      globalThis.fetch = async () => new Promise<Response>(resolve => { respond = resolve })
      workspace.messageInput.value = '打开门'
      const sending = workspace.sendMessage()
      await waitFor(() => Boolean(respond))
      workspace.messageInput.value = draft
      const events = [
        ...(accepted ? [{ eventType: 'turn.accepted', conversationId: 7, turnId: 43, messageId: 71 }] : []),
        { eventType: 'generation.failed', conversationId: 7, error: accepted ? '模型失败' : '会话忙碌' },
      ]
      respond(new Response(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('')))
      await sending
      assert.equal(workspace.messageInput.value, draft || (accepted ? '' : '打开门'))
      assert.deepEqual(workspace.messages.value.map(message => message.id), accepted ? [71] : [])
    })
  }
}

test('a pre-acceptance SSE rejection restores the draft even when history synchronization fails', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  t.mock.method(api, 'groupMessages', async () => { throw new Error('history unavailable') })
  globalThis.fetch = async () => new Response('data: {"eventType":"generation.failed","error":"会话忙碌"}\n\n')
  workspace.messageInput.value = '未发送的消息'
  await workspace.sendMessage()
  assert.equal(workspace.messageInput.value, '未发送的消息')
  assert.deepEqual(workspace.messages.value.map(message => message.id), [70])
})

test('a reconnect failure marker without turn metadata does not restore a persisted message', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { streamGroupGeneration } = await import('../api/client.ts')
  globalThis.fetch = async () => { throw new TypeError('connection lost after submission') }
  t.mock.method(streamGroupGeneration, 'resume', async (...[_id, _request, receive]: Parameters<typeof streamGroupGeneration.resume>) => {
    receive({ eventType: 'generation.failed', conversationId: 7, error: '生成已中断' })
  })
  t.mock.method(api, 'groupMessages', async (): Promise<GroupMessage[]> => [{
    id: 71, conversationId: 7, turnId: 43, speakerType: 'user', messageKind: 'dialogue',
    content: '已经保存的消息', sequenceNo: 2, status: 'completed',
  }])
  workspace.messageInput.value = '已经保存的消息'
  await workspace.sendMessage()
  assert.equal(workspace.messageInput.value, '')
  assert.equal(workspace.messages.value[0]?.content, '已经保存的消息')
})

for (const refreshFails of [false, true]) for (const draft of ['', '新草稿']) test(`group withdrawal restores the actual trigger only if empty (refresh fails=${refreshFails}, draft=${Boolean(draft)})`, async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  workspace.messageInput.value = draft
  t.mock.method(api, 'withdrawGroupTurn', async () => ({ id: 10, conversationId: 7, turnId: 42,
    speakerType: 'user', messageKind: 'dialogue', content: '真正撤回的消息', sequenceNo: 1, status: 'completed' }))
  t.mock.method(api, 'groupMessages', async () => { if (refreshFails) throw new Error('历史刷新失败'); return [] })
  await workspace.withdrawGroupTurn().catch(() => undefined)
  assert.equal(workspace.messageInput.value, draft || '真正撤回的消息')
})

test('a rejected group withdrawal does not restore any message', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  t.mock.method(api, 'withdrawGroupTurn', async () => { throw new Error('撤回失败') })
  workspace.messageInput.value = '原草稿'
  await assert.rejects(workspace.withdrawGroupTurn())
  assert.equal(workspace.messageInput.value, '原草稿')
})

test('failed KP inquiry keeps its question and inquiry composer selected', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { streamTrpgTurn } = await import('../api/client.ts')
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  workspace.conversations.value[0]!.mode = 'trpg'
  workspace.currentTurn.value = { turnId: 42, stepId: 99, status: 'waiting_input', inputType: 'message',
    waitingForUser: true, canAskKp: true, sceneOptions: {}, steps: [] }
  t.mock.method(api, 'combatOverview', async () => [])
  t.mock.method(api, 'investigatorCards', async () => [])
  t.mock.method(streamTrpgTurn, 'inquiry', async (...args: Parameters<typeof streamTrpgTurn.inquiry>) => {
    args[4]({ eventType: 'generation.failed', turnId: 42, error: '模型失败' })
  })
  workspace.inquiryInput.value = '门是什么材质？'
  workspace.composerIntent.value = 'inquiry'
  await workspace.askKp()
  assert.equal(workspace.inquiryInput.value, '门是什么材质？')
  assert.equal(workspace.composerIntent.value, 'inquiry')
})

test('group withdrawal restores only the original conversation draft after navigation', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  let finish!: (message: GroupMessage) => void
  t.mock.method(api, 'withdrawGroupTurn', async () => new Promise<GroupMessage>(resolve => { finish = resolve }))
  const withdrawing = workspace.withdrawGroupTurn()
  await workspace.selectConversation(8)
  workspace.messageInput.value = '另一会话的草稿'
  finish({ id: 10, conversationId: 7, turnId: 42, speakerType: 'user', messageKind: 'dialogue',
    content: '原会话消息', sequenceNo: 1, status: 'completed' })
  await withdrawing
  assert.equal(workspace.messageInput.value, '另一会话的草稿')
  await workspace.selectConversation(7)
  assert.equal(workspace.messageInput.value, '原会话消息')
})

test('TRPG withdrawal does not put history into the composer', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  workspace.conversations.value[0]!.mode = 'trpg'
  t.mock.method(api, 'withdrawGroupTurn', async () => { throw new Error('TRPG cannot withdraw') })
  workspace.messageInput.value = '当前行动'
  await workspace.withdrawGroupTurn()
  assert.equal(workspace.messageInput.value, '当前行动')
})

test('a group send transport failure retains the existing draft recovery', async t => {
  const { workspace } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
  workspace.messageInput.value = '发送失败时保留的原文'
  await workspace.sendMessage()
  assert.equal(workspace.messageInput.value, '发送失败时保留的原文')
})

for (const failure of ['response', 'refresh']) test(`group retry after ${failure} failure must not withdraw an older round`, async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  let turns = [41, 42]
  let historyFail = false
  const removed: number[] = []
  const targets: Array<number | undefined> = []
  const row = (turnId: number): GroupMessage => ({ id: turnId * 10, conversationId: 7, turnId,
    speakerType: 'user', content: `round-${turnId}`, messageKind: 'dialogue', sequenceNo: turnId, status: 'completed' })
  workspace.messages.value = turns.map(row)
  t.mock.method(api, 'groupMessages', async () => {
    if (historyFail) throw new TypeError('Failed to fetch')
    return turns.map(row)
  })
  t.mock.method(api, 'withdrawGroupTurn', async (_id: number, expected: number) => {
    targets.push(expected)
    const latest = turns.at(-1)!
    if (expected != null && expected !== latest) throw new Error('群聊记录已变化')
    turns.pop(); removed.push(latest)
    if (removed.length === 1) {
      if (failure === 'response') throw new TypeError('Failed to fetch')
      historyFail = true
    }
    return row(latest)
  })
  await workspace.withdrawGroupTurn().catch(() => undefined)
  assert.deepEqual(workspace.messages.value.map(row => row.turnId), [41, 42])
  historyFail = false
  await workspace.withdrawGroupTurn().catch(() => undefined)
  assert.deepEqual(removed, [42], `second click unexpectedly removed older turn; request targets=${JSON.stringify(targets)}`)
})

test('group withdrawal refreshes favor and the conversation preview', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  workspace.characters.value = [{ userWorldId: 3, characterId: 101, characterName: '角色', favorValue: 70 }]
  workspace.conversations.value[0]!.lastChatContent = '将被撤回的回复'
  t.mock.method(api, 'withdrawGroupTurn', async () => ({ id: 420, conversationId: 7, turnId: 42,
    speakerType: 'user', content: '本轮原文', messageKind: 'dialogue', sequenceNo: 42, status: 'completed' }))
  t.mock.method(api, 'characters', async () => [{ userWorldId: 3, characterId: 101, characterName: '角色', favorValue: 60 }])
  t.mock.method(api, 'conversation', async () => ({ ...workspace.conversations.value[0], lastChatContent: '上一轮回复' }))
  t.mock.method(api, 'groupMessages', async () => [])
  await workspace.withdrawGroupTurn()
  assert.deepEqual({ favor: workspace.characters.value[0]!.favorValue, preview: workspace.conversations.value[0]!.lastChatContent },
    { favor: 60, preview: '上一轮回复' })
})

test('group send must wait for an outstanding withdrawal', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  let finish!: () => void
  t.mock.method(api, 'withdrawGroupTurn', async () => { await new Promise<void>(resolve => { finish = resolve }); return null })
  const posts: string[] = []
  t.mock.method(globalThis, 'fetch', async (url: string | URL | Request) => {
    posts.push(String(url))
    return Response.json({ code: 0, msg: '当前群聊正在处理中' })
  })
  const withdrawal = workspace.withdrawGroupTurn()
  assert.equal(workspace.loading.withdrawing, true)
  workspace.messageInput.value = '新一轮问题'
  await workspace.sendMessage()
  assert.equal(workspace.messageInput.value, '新一轮问题')
  finish(); await withdrawal
  assert.equal(workspace.loading.withdrawing, false)
  assert.deepEqual(posts, [], 'send request was issued while withdrawal was still pending')
})

for (const outcome of ['completed', 'failed', 'retry', 'resume', 'history-failed'] as const) {
  test(`group character data refreshes after ${outcome} generation`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { streamGroupGeneration } = await import('../api/client.ts')
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    workspace.characters.value = [{ userWorldId: 3, characterId: 101, characterName: '角色', favorValue: 10, userInfoPrompt: '旧信息' }]
    let savedPrompt = '旧信息\n用户喜欢红茶'
    t.mock.method(api, 'characters', async (worldId: number) => {
      assert.equal(worldId, 3)
      return [{ userWorldId: 3, characterId: 101, characterName: '角色', favorValue: 15, userInfoPrompt: savedPrompt }]
    })
    t.mock.method(api, 'updatePrompt', async (_worldId: number, _characterId: number, prompt: string) => { savedPrompt = prompt })
    if (outcome === 'history-failed') t.mock.method(api, 'groupMessages', async () => { throw new Error('history unavailable') })
    globalThis.fetch = async () => new Response([
      { eventType: 'turn.accepted', conversationId: 7, turnId: 43, messageId: 71, sequence: 2 },
      { eventType: outcome === 'failed' ? 'generation.failed' : 'generation.completed', conversationId: 7, turnId: 43 },
    ].map(event => `data: ${JSON.stringify(event)}\n\n`).join(''))
    if (outcome === 'retry') {
      workspace.currentTurn.value = { turnId: 43, status: 'failed', waitingForUser: false, sceneOptions: {}, steps: [] }
      await workspace.retryGroupTurn()
    } else if (outcome === 'resume') {
      sessionStorage.setItem('galchat:generation:7', 'resume-profile')
      t.mock.method(streamGroupGeneration, 'resume', async (...args: Parameters<typeof streamGroupGeneration.resume>) => {
        args[2]({ eventType: 'generation.completed', conversationId: 7, turnId: 43 })
      })
      await workspace.selectConversation(7)
      await waitFor(() => !workspace.loading.sending)
    } else {
      workspace.messageInput.value = '我喜欢红茶'
      await workspace.sendMessage()
    }
    assert.equal(workspace.characters.value[0]!.favorValue, 15)
    // The profile editor starts with the displayed data and saves the whole note.
    await workspace.updateCharacterSettings(3, 101, {
      userInfoPrompt: workspace.characters.value[0]!.userInfoPrompt + '\n称呼我小明',
    })
    assert.equal(savedPrompt, '旧信息\n用户喜欢红茶\n称呼我小明')
  })
}

test('group character refresh failure does not restore an already sent message', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  t.mock.method(api, 'characters', async () => { throw new Error('profile unavailable') })
  globalThis.fetch = async () => new Response('data: {"eventType":"generation.completed","conversationId":7,"turnId":43}\n\n')
  workspace.messageInput.value = '已发送的消息'
  await workspace.sendMessage()
  assert.equal(workspace.messageInput.value, '')
  assert.equal(workspace.loading.sending, false)
  assert.equal(workspace.messages.value[0]?.content, 'history-7')
  assert.equal(notice.title, '角色资料刷新失败')
})

test('late group character refresh cannot replace another world profile', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  let finish!: (rows: import('../api/types.ts').Character[]) => void
  t.mock.method(api, 'characters', async () => new Promise(resolve => { finish = resolve }))
  globalThis.fetch = async () => new Response('data: {"eventType":"generation.completed","conversationId":7,"turnId":43}\n\n')
  workspace.messageInput.value = '你好'
  const sending = workspace.sendMessage()
  await waitFor(() => Boolean(finish))
  workspace.selectedWorldId.value = 4
  workspace.characters.value = [{ userWorldId: 4, characterId: 101, characterName: '另一个世界', favorValue: 90 }]
  finish([{ userWorldId: 3, characterId: 101, characterName: '旧世界', favorValue: 15 }])
  await sending
  assert.equal(workspace.characters.value[0]!.favorValue, 90)
})

for (const mode of ['chat', 'trpg'] as const) for (const syncFails of [false, true]) {
  test(`${mode} manual speech replaces its optimistic message before the round ends (sync fails=${syncFails})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    workspace.conversations.value[0]!.mode = mode
    workspace.currentTurn.value = {
      turnId: 42, stepId: 99, status: 'waiting_input', inputType: 'message', waitingForUser: true,
      sceneOptions: {}, steps: [{ stepId: 99, itemOrder: 1, actorType: 'character', actorId: 101, status: 'waiting_input' }],
    }
    // Identical words by the same actor in another step must remain a separate message.
    const earlier: GroupMessage = { id: 70, conversationId: 7, turnId: 41, replyStepId: 98,
      speakerType: 'character', speakerId: 101, speakerName: '调查员', messageKind: 'dialogue',
      content: '打开门', sequenceNo: 1, status: 'completed' }
    const submitted: GroupMessage = { ...earlier, id: 71, turnId: 42, replyStepId: 99, sequenceNo: 2 }
    const following: GroupMessage = { ...submitted, id: 72, replyStepId: 100, sequenceNo: 3 }
    workspace.messages.value = [earlier]
    t.mock.method(api, 'groupMessages', async () => {
      if (syncFails) throw new Error('history unavailable')
      return [earlier, submitted, following]
    })
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    let controller!: ReadableStreamDefaultController<Uint8Array>
    let requested = false
    globalThis.fetch = async () => {
      requested = true
      return new Response(new ReadableStream<Uint8Array>({ start(value) { controller = value } }))
    }
    const sendEvent = (event: Record<string, unknown>) => controller.enqueue(
      new TextEncoder().encode(`data: ${JSON.stringify({ conversationId: 7, turnId: 42, ...event })}\n\n`))
    workspace.messageInput.value = '打开门'
    const sending = workspace.sendMessage()
    try {
      await waitFor(() => requested)
      assert.equal(workspace.messages.value.length, 2)
      assert.ok(workspace.messages.value[1]!.id < 0)
      sendEvent({ eventType: 'turn.accepted', replyStepId: 99, messageId: 71, sequence: 2 })
      if (mode === 'chat') sendEvent({ eventType: 'message.completed', replyStepId: 99, messageId: 71, sequence: 2,
        speaker: { type: 'character', id: 101, name: '调查员' }, content: '打开门' })
      await waitFor(() => workspace.messages.value.some(message => message.id === 71))
      assert.equal(workspace.loading.sending, true)
      assert.deepEqual(workspace.messages.value.map(message => ({ id: message.id, step: message.replyStepId, sequence: message.sequenceNo })),
        [{ id: 70, step: 98, sequence: 1 }, { id: 71, step: 99, sequence: 2 }])
      // A replay and subsequent AI speech must neither duplicate nor consume the manual speech.
      sendEvent({ eventType: 'turn.accepted', replyStepId: 99, messageId: 71, sequence: 2 })
      if (mode === 'chat') sendEvent({ eventType: 'message.completed', replyStepId: 99, messageId: 71, content: '打开门' })
      sendEvent({ eventType: 'reply.started', replyStepId: 100, messageId: 72, sequence: 3,
        speaker: { type: 'character', id: 101, name: '调查员' } })
      sendEvent({ eventType: 'message.delta', replyStepId: 100, messageId: 72, delta: '打开门' })
      sendEvent({ eventType: 'message.completed', replyStepId: 100, messageId: 72, content: '打开门' })
      await waitFor(() => workspace.messages.value.some(message => message.id === 72 && message.status === 'completed'))
      assert.deepEqual(workspace.messages.value.map(message => message.id), [70, 71, 72])
      assert.deepEqual(workspace.messages.value.map(message => message.content), ['打开门', '打开门', '打开门'])
    } finally {
      sendEvent({ eventType: 'turn.completed' })
      controller.close()
      await sending
    }
    assert.deepEqual(workspace.messages.value.map(message => message.id), [70, 71, 72])
  })
}

for (const mode of ['chat', 'manual', 'trpg'] as const) {
  test(`queued ${mode} send waits for initial history and uses the loaded turn exactly once`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const turn = mode === 'chat' ? null : {
      turnId: 42, stepId: 99, status: 'waiting_input', inputType: 'message', waitingForUser: true,
      sceneOptions: {}, steps: [{ stepId: 99, itemOrder: 1, actorType: 'character', actorId: 101, status: 'waiting_input' }],
    }
    workspace.conversations.value[1]!.mode = mode === 'trpg' ? 'trpg' : 'chat'
    t.mock.method(api, 'currentTurn', async () => turn)
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    let release!: (messages: GroupMessage[]) => void
    let reads = 0
    const sent: GroupMessage = { id: 81, conversationId: 8, turnId: 42, speakerType: mode === 'chat' ? 'user' : 'character', messageKind: 'dialogue', content: '打开门', sequenceNo: 2, status: 'completed' }
    t.mock.method(api, 'groupMessages', async () => ++reads === 1
      ? new Promise<GroupMessage[]>(resolve => { release = resolve }) : [sent])
    const requests: string[] = []
    globalThis.fetch = async (url) => {
      requests.push(String(url))
      return new Response('data: {"eventType":"turn.accepted","conversationId":8,"turnId":42,"messageId":81}\n\ndata: {"eventType":"turn.completed","conversationId":8,"turnId":42}\n\n')
    }
    const selecting = workspace.selectConversation(8)
    workspace.messageInput.value = '打开门'
    const sending = workspace.sendMessage()
    const duplicate = workspace.sendMessage()
    await new Promise(resolve => setTimeout(resolve, 0))
    const beforeLoad = { requests: [...requests], draft: workspace.messageInput.value }
    release([])
    await Promise.all([selecting, sending, duplicate])
    assert.deepEqual(beforeLoad, { requests: [], draft: '打开门' })
    assert.equal(requests.length, 1)
    if (mode !== 'chat') assert.match(requests[0]!, /turns\/42\/steps\/99/)
    assert.deepEqual(workspace.messages.value.map(message => message.id), [81])
  })
}

for (const outcome of ['failed', 'switched', 'reopened'] as const) {
  test(`queued send is cancelled and preserves drafts when history is ${outcome}`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    let release!: () => void
    let first = true
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    t.mock.method(api, 'groupMessages', async () => {
      if (first) {
        first = false
        await new Promise<void>(resolve => { release = resolve })
        if (outcome === 'failed') throw new Error('history unavailable')
      }
      return []
    })
    let requests = 0
    globalThis.fetch = async () => { requests++; return new Response('data: {"eventType":"generation.completed"}\n\n') }
    const selecting = workspace.selectConversation(8)
    workspace.messageInput.value = '待发送草稿'
    const sending = workspace.sendMessage()
    if (outcome !== 'failed') {
      await workspace.selectConversation(7)
      if (outcome === 'reopened') await workspace.selectConversation(8)
    }
    release()
    await Promise.all([selecting, sending])
    assert.equal(requests, 0)
    if (outcome === 'switched') await workspace.selectConversation(8)
    assert.equal(workspace.messageInput.value, '待发送草稿')
  })
}

for (const accepted of [false, true]) {
  test(`TRPG start reports streamed failure (accepted=${accepted})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    workspace.conversations.value[0]!.mode = 'trpg'
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    const { streamTrpgTurn } = await import('../api/client.ts')
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
      if (accepted) receive({ eventType: 'turn.accepted', conversationId: 7, turnId: 42 })
      receive({ eventType: 'generation.failed', conversationId: 7, ...(accepted ? { turnId: 42 } : {}), error: 'cannot start' })
    })
    assert.equal(await workspace.startTrpgTurn(), false)
  })
}

test('TRPG start also waits until its initial history is ready', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  workspace.conversations.value[1]!.mode = 'trpg'
  t.mock.method(api, 'combatOverview', async () => [])
  t.mock.method(api, 'investigatorCards', async () => [])
  let release!: () => void
  let first = true
  t.mock.method(api, 'groupMessages', async () => {
    if (first) { first = false; await new Promise<void>(resolve => { release = resolve }) }
    return []
  })
  let requests = 0
  const { streamTrpgTurn } = await import('../api/client.ts')
  t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
    requests++
    receive({ eventType: 'turn.completed', conversationId: 8, turnId: 42 })
  })
  const selecting = workspace.selectConversation(8)
  const starting = workspace.startTrpgTurn()
  const requestsBeforeLoad = requests
  release()
  await selecting
  assert.equal(await starting, true)
  assert.equal(requestsBeforeLoad, 0)
  assert.equal(requests, 1)
})

for (const mode of ['chat', 'trpg'] as const) {
  test(`history synchronization keeps ${mode} sends queued despite an old conversation finishing`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { streamTrpgTurn } = await import('../api/client.ts')
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    workspace.conversations.value.forEach(c => { c.mode = mode })
    const originalConversation = api.conversation
    t.mock.method(api, 'conversation', async (id: number) => ({ ...await originalConversation(id), mode }))
    const releases = new Map<number, () => void>()
    let defer = true
    let requests = 0
    t.mock.method(api, 'groupMessages', async (id: number): Promise<GroupMessage[]> => {
      const snapshot = requests
      if (defer) await new Promise<void>(resolve => { releases.set(id, resolve) })
      return [{ id: snapshot + 100, conversationId: id, speakerType: 'user', messageKind: 'dialogue',
        content: `turn-${snapshot}`, sequenceNo: snapshot, status: 'completed' }]
    })
    globalThis.fetch = async () => {
      requests++
      return new Response('data: {"eventType":"generation.completed"}\n\n')
    }
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
      requests++
      receive({ eventType: 'generation.completed' })
    })
    const send = () => mode === 'chat' ? workspace.sendMessage() : workspace.startTrpgTurn()
    workspace.messageInput.value = 'first'
    const oldSend = send()
    await waitFor(() => releases.has(7))
    defer = false
    await workspace.selectConversation(8)
    defer = true
    workspace.messageInput.value = 'second'
    const newSend = send()
    await waitFor(() => releases.has(8))
    releases.get(7)!()
    await oldSend
    const stillBusy = workspace.loading.sending
    defer = false
    workspace.messageInput.value = 'third'
    const queued = send()
    const duplicate = send()
    await new Promise(resolve => setTimeout(resolve, 0))
    const beforeSync = { requests, draft: workspace.messageInput.value }
    defer = false
    releases.get(8)!()
    await Promise.all([newSend, queued, duplicate])
    assert.equal(stillBusy, true, 'old request must not unlock the new conversation')
    assert.deepEqual(beforeSync, { requests: 2, draft: 'third' })
    assert.equal(requests, 3, 'queued submission runs exactly once after history is ready')
    assert.deepEqual(workspace.messages.value.map(message => message.content), ['turn-3'])
  })
}

for (const outcome of ['failed', 'switched', 'edited'] as const) {
  test(`history synchronization cancels a queued send when ${outcome}`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    let release!: () => void
    let first = true
    t.mock.method(api, 'groupMessages', async () => {
      if (first) {
        first = false
        await new Promise<void>(resolve => { release = resolve })
        if (outcome === 'failed') throw new Error('history unavailable')
      }
      return []
    })
    let requests = 0
    globalThis.fetch = async () => {
      requests++
      return new Response('data: {"eventType":"generation.completed"}\n\n')
    }
    workspace.messageInput.value = 'first'
    const sending = workspace.sendMessage()
    await waitFor(() => !!release)
    workspace.messageInput.value = 'queued draft'
    const queued = workspace.sendMessage()
    if (outcome === 'switched') await workspace.selectConversation(8)
    if (outcome === 'edited') workspace.messageInput.value = 'edited draft'
    release()
    await Promise.all([sending, queued])
    assert.equal(requests, 1)
    if (outcome === 'switched') await workspace.selectConversation(7)
    assert.equal(workspace.messageInput.value, outcome === 'edited' ? 'edited draft' : 'queued draft')
  })
}


for (const failSave of [false, true]) {
  test(`reply order rejects edits and duplicate saves until completion (failure=${failSave})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    workspace.addPlanItem(102)
    let release!: () => void
    const save = t.mock.method(api, 'saveReplyPlan', async (_id: number, payload: any) => {
      await new Promise<void>(resolve => { release = resolve })
      if (failSave) throw new Error('save unavailable')
      return payload
    })
    const pending = workspace.savePlan().catch(() => false)
    workspace.movePlanItem(0, 1)
    workspace.deletePlanItem(0)
    workspace.addPlanItem(103)
    // A duplicate must finish immediately, without submitting or queuing a write.
    const duplicate = workspace.savePlan()
    assert.equal(save.mock.callCount(), 1)
    assert.deepEqual(workspace.replyPlan.value.items.map(item => item.actorId), [101, 102])
    assert.equal(workspace.savingReplyPlan.value, true)
    release()
    await Promise.all([pending, duplicate])
    assert.equal(workspace.savingReplyPlan.value, false)
    workspace.movePlanItem(0, 1)
    assert.deepEqual(workspace.replyPlan.value.items.map(item => item.actorId), [102, 101])
    t.mock.method(api, 'saveReplyPlan', async (_id: number, payload: any) => payload)
    await workspace.savePlan()
  })

  test(`actor model rejects duplicate saves until completion (failure=${failSave})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    let release!: () => void
    const save = t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
      if (payload.actorId === 101) await new Promise<void>(resolve => { release = resolve })
      if (failSave && payload.actorId === 101) throw new Error('save unavailable')
      return { ...payload, modelApiAvailable: true }
    })
    const payload = { actorType: 'character' as const, actorId: 101, controlMode: 'MODEL' as const, modelApiId: 1 }
    const pending = workspace.saveActorRuntime(payload)
    const duplicate = workspace.saveActorRuntime({ ...payload, modelApiId: 2 })
    assert.equal(save.mock.callCount(), 1)
    assert.deepEqual(workspace.savingActorKeys.value, ['character:101'])
    await workspace.saveActorRuntime({ ...payload, actorId: 102 })
    assert.equal(save.mock.callCount(), 2, 'another actor remains independent')
    release()
    await Promise.all([pending, duplicate])
    assert.deepEqual(workspace.savingActorKeys.value, [])
    t.mock.method(api, 'saveActorRuntime', async (_id: number, value: any) => ({ ...value, modelApiAvailable: true }))
    await workspace.saveActorRuntime({ ...payload, modelApiId: 2 })
    assert.equal(workspace.actorRuntimes.value.find(item => item.actorId === 101)?.modelApiId, 2)
  })
}

test('pending settings locks follow their conversation across navigation', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  let releasePlan!: () => void
  let releaseActor!: () => void
  t.mock.method(api, 'saveReplyPlan', async (_id: number, payload: any) => {
    await new Promise<void>(resolve => { releasePlan = resolve })
    return payload
  })
  t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
    await new Promise<void>(resolve => { releaseActor = resolve })
    return { ...payload, modelApiAvailable: true }
  })
  const pendingPlan = workspace.savePlan()
  const pendingActor = workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiId: 1 })
  await workspace.selectConversation(8)
  assert.equal(workspace.savingReplyPlan.value, false)
  assert.deepEqual(workspace.savingActorKeys.value, [])
  await workspace.selectConversation(7)
  assert.equal(workspace.savingReplyPlan.value, true)
  assert.deepEqual(workspace.savingActorKeys.value, ['character:101'])
  releasePlan(); releaseActor()
  await Promise.all([pendingPlan, pendingActor])
  assert.equal(workspace.savingReplyPlan.value, false)
  assert.deepEqual(workspace.savingActorKeys.value, [])
})

for (const kind of ['chat-plan', 'chat-model', 'trpg-model'] as const) {
  for (const outcome of ['success', 'failure', 'switched', 'edited'] as const) {
    test(`generation waits for ${kind} settings (${outcome})`, async t => {
      const { workspace, api } = await groupMutationFixture(t)
      const { streamTrpgTurn } = await import('../api/client.ts')
      const { notice } = await import('../composables/useNotice.ts')
      t.after(() => clearTimeout(notice.timer))
      const trpg = kind === 'trpg-model'
      if (trpg) workspace.conversations.value[0]!.mode = 'trpg'
      t.mock.method(api, 'combatOverview', async () => [])
      t.mock.method(api, 'investigatorCards', async () => [])
      let release!: () => void
      let persisted = 'old'
      t.mock.method(api, kind === 'chat-plan' ? 'saveReplyPlan' : 'saveActorRuntime', async (_id: number, payload: any) => {
        await new Promise<void>(resolve => { release = resolve })
        if (outcome === 'failure') throw new Error('settings unavailable')
        persisted = 'new'
        return { ...payload, modelApiAvailable: true }
      })
      const saving = (kind === 'chat-plan' ? workspace.savePlan()
        : workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiId: 2 })).catch(() => undefined)
      const used: string[] = []
      globalThis.fetch = async () => {
        used.push(persisted)
        return new Response('data: {"eventType":"generation.completed","conversationId":7,"turnId":43}\n\n')
      }
      t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: GroupChatEvent) => void) => {
        used.push(persisted)
        receive({ eventType: 'generation.completed', conversationId: 7, turnId: 43 })
      })
      workspace.messageInput.value = 'queued message'
      const submit = () => trpg ? workspace.startTrpgTurn('retained direction') : workspace.sendMessage()
      const sending = submit()
      const duplicate = submit()
      await new Promise(resolve => setTimeout(resolve, 0))
      const beforeSave = [...used]
      if (outcome === 'switched') {
        await workspace.selectConversation(8)
        await workspace.selectConversation(7)
      }
      if (outcome === 'edited') workspace.messageInput.value = 'changed draft'
      release()
      await Promise.all([saving, sending, duplicate])
      assert.deepEqual(beforeSave, [], 'must not start generation with old settings')
      const cancelled = outcome === 'failure' || outcome === 'switched' || (!trpg && outcome === 'edited')
      assert.deepEqual(used, cancelled ? [] : ['new'], 'queued duplicate must not create a second generation')
      if (outcome === 'failure') {
        assert.equal(workspace.messageInput.value, 'queued message')
        assert.equal(workspace.generationFailed.value, true, 'cancel automatic progression after settings failure')
        assert.equal(workspace.loading.sending, false)
      }
      if (!trpg && outcome === 'edited') assert.equal(workspace.messageInput.value, 'changed draft')
    })
  }
}

for (const action of ['retryGroup', 'selection', 'endExploration', 'inquiry', 'retryStep', 'trpgMessage'] as const) {
  for (const failSave of [false, true]) test(`${action} waits for model save (failure=${failSave})`, async t => {
    const { workspace, api } = await groupMutationFixture(t)
    const { notice } = await import('../composables/useNotice.ts')
    t.after(() => clearTimeout(notice.timer))
    if (action !== 'retryGroup') workspace.conversations.value[0]!.mode = 'trpg'
    workspace.currentTurn.value = { turnId: 42, stepId: 99, status: action === 'retryGroup' ? 'failed' : 'waiting_input',
      waitingForUser: true, canAskKp: true, inputType: action === 'selection' ? 'selection' : 'message',
      sceneOptions: {}, steps: [{ stepId: 99, itemOrder: 1, actorType: 'user', status: 'waiting_input' }] }
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    let release!: () => void
    t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
      await new Promise<void>(resolve => { release = resolve })
      if (failSave) throw new Error('model save failed')
      return { ...payload, modelApiAvailable: true }
    })
    const saving = workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiId: 2 })
    const requests: string[] = []
    globalThis.fetch = async (url: any) => {
      requests.push(String(url))
      return new Response('data: {"eventType":"generation.completed","conversationId":7,"turnId":42}\n\n')
    }
    workspace.inquiryInput.value = 'Where is the door?'
    workspace.messageInput.value = 'Open the door'
    const sending = action === 'retryGroup' ? workspace.retryGroupTurn()
      : action === 'selection' ? workspace.selectSceneOption('A')
      : action === 'endExploration' ? workspace.endExploration()
      : action === 'inquiry' ? workspace.askKp()
      : action === 'trpgMessage' ? workspace.sendMessage()
      : workspace.retryStep({ ...workspace.messages.value[0]!, turnId: 42, replyStepId: 99 })
    await new Promise(resolve => setTimeout(resolve, 0))
    const beforeSave = [...requests]
    release()
    await Promise.all([saving, sending])
    assert.deepEqual(beforeSave, [])
    assert.equal(requests.length, failSave ? 0 : 1)
    if (failSave) assert.equal(workspace.inquiryInput.value, 'Where is the door?')
  })
}

test('queued generation waits for all actors and settings added while waiting', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  const releases = new Map<number, () => void>()
  t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
    await new Promise<void>(resolve => { releases.set(payload.actorId, resolve) })
    return { ...payload, modelApiAvailable: true }
  })
  const save = (actorId: number) => workspace.saveActorRuntime({ actorType: 'character', actorId, controlMode: 'MODEL', modelApiId: 2 })
  const first = save(101)
  let requests = 0
  globalThis.fetch = async () => { requests++; return new Response('data: {"eventType":"generation.completed"}\n\n') }
  workspace.messageInput.value = 'queued'
  const sending = workspace.sendMessage()
  const second = save(102)
  releases.get(101)!()
  await first
  await new Promise(resolve => setTimeout(resolve, 0))
  const beforeSecond = requests
  releases.get(102)!()
  await Promise.all([second, sending])
  assert.equal(beforeSecond, 0)
  assert.equal(requests, 1)
})

test('a later actor save failure cancels a generation already waiting for another actor', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  let release!: () => void
  t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
    if (payload.actorId === 102) throw new Error('second actor save failed')
    await new Promise<void>(resolve => { release = resolve })
    return { ...payload, modelApiAvailable: true }
  })
  const save = (actorId: number) => workspace.saveActorRuntime({ actorType: 'character', actorId, controlMode: 'MODEL', modelApiId: 2 })
  const first = save(101)
  let requests = 0
  globalThis.fetch = async () => { requests++; return new Response('data: {"eventType":"generation.completed"}\n\n') }
  workspace.messageInput.value = 'retained'
  const sending = workspace.sendMessage()
  await save(102)
  release()
  await Promise.all([first, sending])
  assert.equal(requests, 0)
  assert.equal(workspace.messageInput.value, 'retained')
})

test('pending saves in another conversation do not delay sending here', async t => {
  const { workspace, api } = await groupMutationFixture(t)
  const { notice } = await import('../composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  let release!: () => void
  t.mock.method(api, 'saveActorRuntime', async (_id: number, payload: any) => {
    await new Promise<void>(resolve => { release = resolve })
    return { ...payload, modelApiAvailable: true }
  })
  const saving = workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiId: 2 })
  await workspace.selectConversation(8)
  const requests: string[] = []
  globalThis.fetch = async (url: any) => { requests.push(String(url)); return new Response('data: {"eventType":"generation.completed"}\n\n') }
  workspace.messageInput.value = 'B message'
  const sending = workspace.sendMessage()
  try {
    await waitFor(() => requests.length > 0)
    assert.match(requests[0]!, /conversations\/8\//)
  } finally { release(); await Promise.all([saving, sending]) }
})
