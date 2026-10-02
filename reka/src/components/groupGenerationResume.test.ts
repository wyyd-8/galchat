import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Conversation, GroupMessage } from '../api/types.ts'

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
      assert.equal(requestIds.length, 1)
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
    conversationId: id, speakerType: 'user', messageKind: 'dialogue', content: `history-${id}`, sequenceNo: 1, status: 'completed' }])
  t.mock.method(api, 'replyPlan', async () => [{ source: 'USER', displayName: '群聊',
    items: [{ order: 1, actorType: 'character', actorId: 101 }] }])
  t.mock.method(api, 'currentTurn', async () => null)
  t.mock.method(api, 'actorRuntimes', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
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
      return { id, worldId: id + 100 }
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
