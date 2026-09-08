import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Conversation, DiceRollAggregate, GroupChatEvent, GroupMessage } from '../api/types.ts'

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

const diceRoll: DiceRollAggregate = {
  summary: {
    id: 501,
    conversationId: 7,
    reason: '侦查足迹',
    status: 'COMPLETED',
  },
  results: [],
}

const diceMessage: GroupMessage = {
  id: 100,
  conversationId: 7,
  turnId: 42,
  replyStepId: 9,
  speakerType: 'kp',
  messageKind: 'dice_roll',
  content: '',
  sequenceNo: 10,
  diceRoll,
  status: 'completed',
}

test('keeps earlier dice rounds visible after streaming a follow-up round and syncing history', async (t) => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { splitDiceAggregateByRound } = await import('../dice/domain/dicePlayback.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const storage = new Map<string, string>()
  const webStorage = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
    key: (index: number) => [...storage.keys()][index] ?? null,
    get length() { return storage.size },
  }
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: webStorage,
    sessionStorage: webStorage,
  })
  t.after(() => Object.assign(globalThis, {
    window: previousWindow, localStorage: previousLocalStorage,
    sessionStorage: previousSessionStorage,
  }))

  let workspace!: ReturnType<typeof useWorkspace>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
    createElement: () => ({}), createText: (text) => ({ text }),
    createComment: (text) => ({ text }), setText(node, text) { node.text = text },
    setElementText(node, text) { node.text = text },
    parentNode: (node) => node.parent as Record<string, unknown> | null,
    nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({
    setup() { workspace = useWorkspace(); return () => h('div') },
  }))
  app.mount({})
  t.after(() => app.unmount())
  const conversation: Conversation = {
    id: 7, userWorldId: 3, worldId: 2,
    mode: 'trpg', title: '旧宅调查', status: 'active',
  }
  workspace.conversations.value = [conversation]
  workspace.selectedConversationId.value = 7

  let round = 1
  const results = [
    { id: 601, summaryId: 501, roundNo: 1, reason: '理智检定' },
    { id: 602, summaryId: 501, roundNo: 2, reason: '理智损失' },
  ]
  t.mock.method(api, 'conversation', async () => conversation)
  t.mock.method(api, 'replyPlan', async () => [{ source: 'USER', displayName: '群聊', items: [] }])
  t.mock.method(api, 'currentTurn', async () => null)
  t.mock.method(api, 'combatOverview', async () => [])
  t.mock.method(api, 'investigatorCards', async () => [])
  t.mock.method(api, 'diceSummary', async () => ({ ...diceRoll.summary, roundCount: round }))
  t.mock.method(api, 'diceResults', async () => results.slice(0, round))
  t.mock.method(api, 'groupMessages', async () => results.slice(0, round).map((result) => ({
    ...diceMessage, id: 99 + result.roundNo, sequenceNo: 9 + result.roundNo,
    diceRoll: undefined, content: JSON.stringify({ summaryId: 501, roundNos: [result.roundNo] }),
  })))
  t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _requestId: string, onEvent: (event: GroupChatEvent) => void) => {
    const event = {
      conversationId: 7, turnId: 42, replyStepId: 9,
      messageId: 99 + round, sequence: 9 + round,
      speaker: { type: 'kp', name: 'KP' },
    }
    onEvent({ ...event, eventType: 'reply.started', messageKind: 'dialogue' })
    onEvent({ ...event, eventType: 'dice_roll.created', toolName: round === 1 ? 'requestSanCheck' : 'rollSanLoss',
      diceRoll: { summary: { ...diceRoll.summary, roundCount: round }, results: [results[round - 1]!] },
    })
    onEvent({ ...event, eventType: 'message.completed', messageKind: 'dice_roll' })
    onEvent({ eventType: 'turn.paused', conversationId: 7, turnId: 42 })
    assert.deepEqual(workspace.messages.value.map((message) =>
      splitDiceAggregateByRound(message.diceRoll!).map((card) => card.results[0]!.id)),
    round === 1 ? [[601]] : [[601], [602]])
  })

  assert.equal(await workspace.startTrpgTurn(), true)
  assert.deepEqual(workspace.messages.value[0]?.diceRoll?.results.map((result) => result.id), [601])
  round = 2
  assert.equal(await workspace.startTrpgTurn(), true)
  assert.deepEqual(workspace.messages.value.map((message) => ({
    id: message.id,
    cards: splitDiceAggregateByRound(message.diceRoll!).map((card) => card.results[0]!.id),
  })), [{ id: 100, cards: [601] }, { id: 101, cards: [602] }])
})

test('keeps continued streaming output below the dice message from the same reply step', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    window: {
      addEventListener() {},
      clearTimeout,
      setTimeout,
    },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
  })

  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  try {
    let workspace!: ReturnType<typeof useWorkspace>
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
    renderer.createApp(defineComponent({
      setup() {
        workspace = useWorkspace()
        return () => h('div')
      },
    })).mount({})
    workspace.conversations.value = [{
      id: 7,
      userWorldId: 3,
      worldId: 2,
      mode: 'trpg',
      title: '旧宅调查',
      status: 'active',
    }]
    workspace.selectedConversationId.value = 7
    workspace.messages.value = [{ ...diceMessage }]

    let streamingSnapshot: Array<Pick<GroupMessage, 'id' | 'messageKind' | 'content'>> = []
    streamTrpgTurn.continue = async (_id, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
      })
      onEvent({
        eventType: 'message.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
        delta: '骰点显示，足迹通向阁楼。',
      })
      streamingSnapshot = workspace.messages.value.map(({ id, messageKind, content }) => ({
        id, messageKind, content,
      }))
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'USER', displayName: '群聊', items: [] }]
    api.currentTurn = async () => null

    await workspace.startTrpgTurn()

    assert.deepEqual(streamingSnapshot, [
      { id: 100, messageKind: 'dice_roll', content: '' },
      { id: 101, messageKind: 'dialogue', content: '骰点显示，足迹通向阁楼。' },
    ])
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
    })
  }
})

test('retains every dice event received in the same rendering batch', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const storage = new Map<string, string>()
  const webStorage = {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
    key: (index: number) => [...storage.keys()][index] ?? null,
    get length() { return storage.size },
  }
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: webStorage,
    sessionStorage: webStorage,
  })

  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  const originalCombatOverview = api.combatOverview
  const originalInvestigatorCards = api.investigatorCards
  try {
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
      mode: 'trpg', title: '旧宅调查', status: 'active',
    }]
    workspace.selectedConversationId.value = 7

    streamTrpgTurn.continue = async (_id, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'dice_roll.created', conversationId: 7,
        diceRoll: { summary: { id: 501, conversationId: 7, status: 'COMPLETED' }, results: [] },
      })
      onEvent({
        eventType: 'dice_roll.created', conversationId: 7,
        diceRoll: { summary: { id: 502, conversationId: 7, status: 'COMPLETED' }, results: [] },
      })
      onEvent({ eventType: 'turn.completed', conversationId: 7, turnId: 42 })
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'USER', displayName: '群聊', items: [] }]
    api.currentTurn = async () => null
    api.combatOverview = async () => []
    api.investigatorCards = async () => []

    await workspace.startTrpgTurn()

    assert.deepEqual(
      workspace.incomingDiceRolls.value.map((aggregate) => aggregate.summary.id),
      [501, 502],
    )
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    api.combatOverview = originalCombatOverview
    api.investigatorCards = originalInvestigatorCards
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('keeps reasoning isolated by message across a parent child parent sequence', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    window: {
      addEventListener() {},
      clearTimeout,
      setTimeout,
    },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
  })

  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  try {
    let workspace!: ReturnType<typeof useWorkspace>
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
    renderer.createApp(defineComponent({
      setup() {
        workspace = useWorkspace()
        return () => h('div')
      },
    })).mount({})
    workspace.conversations.value = [{
      id: 7,
      userWorldId: 3,
      worldId: 2,
      mode: 'trpg',
      title: '旧宅调查',
      status: 'active',
    }]
    workspace.selectedConversationId.value = 7

    let reasoningSnapshot: Record<number, string> = {}
    streamTrpgTurn.continue = async (_id, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'character', id: 21, name: 'A' },
      })
      onEvent({
        eventType: 'reasoning.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        delta: '父步骤询问前的思考',
      })
      onEvent({
        eventType: 'message.completed', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        content: '窗户现在开着吗？',
      })
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 10, messageId: 102, sequence: 12,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
      })
      onEvent({
        eventType: 'reasoning.delta', conversationId: 7, turnId: 42,
        replyStepId: 10, messageId: 102, sequence: 12,
        delta: '子步骤的思考',
      })
      onEvent({
        eventType: 'message.completed', conversationId: 7, turnId: 42,
        replyStepId: 10, messageId: 102, sequence: 12,
        content: '窗户半开着。',
      })
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 103, sequence: 13,
        messageKind: 'dialogue', speaker: { type: 'character', id: 21, name: 'A' },
      })
      onEvent({
        eventType: 'reasoning.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 103, sequence: 13,
        delta: '父步骤恢复后的思考',
      })
      reasoningSnapshot = { ...workspace.reasoning }
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'SCENE', displayName: '旧宅', items: [] }]
    api.currentTurn = async () => null

    await workspace.startTrpgTurn()

    assert.deepEqual(reasoningSnapshot, {
      101: '父步骤询问前的思考',
      102: '子步骤的思考',
      103: '父步骤恢复后的思考',
    })
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
    })
  }
})

test('reuses a failed KP message when continuing the same reply step', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    window: {
      addEventListener() {},
      clearTimeout,
      setTimeout,
    },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
    sessionStorage: {
      getItem: () => null,
      setItem() {},
      removeItem() {},
      clear() {},
      key: () => null,
      length: 0,
    },
  })

  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  const originalCombatOverview = api.combatOverview
  try {
    let workspace!: ReturnType<typeof useWorkspace>
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
    renderer.createApp(defineComponent({
      setup() {
        workspace = useWorkspace()
        return () => h('div')
      },
    })).mount({})
    workspace.conversations.value = [{
      id: 7,
      userWorldId: 3,
      worldId: 2,
      mode: 'trpg',
      title: '旧宅调查',
      status: 'active',
    }]
    workspace.selectedConversationId.value = 7
    workspace.messages.value = [{
      id: 100,
      conversationId: 7,
      turnId: 42,
      replyStepId: 9,
      speakerType: 'kp',
      speakerName: 'KP',
      messageKind: 'dialogue',
      content: '旧的失败内容',
      sequenceNo: 10,
      status: 'failed',
    }]
    workspace.reasoning[100] = '旧的思考内容'

    let streamingSnapshot: Array<Pick<GroupMessage, 'id' | 'replyStepId' | 'content' | 'status'>> = []
    let streamingReasoning = ''
    streamTrpgTurn.continue = async (_id, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
      })
      onEvent({
        eventType: 'reasoning.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
        delta: '新的思考内容',
      })
      onEvent({
        eventType: 'message.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'kp', name: 'KP' },
        delta: '新的回复内容',
      })
      streamingSnapshot = workspace.messages.value.map(({ id, replyStepId, content, status }) => ({
        id, replyStepId, content, status,
      }))
      streamingReasoning = workspace.reasoning[101] || ''
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'COMBAT', displayName: '战斗第2轮', items: [] }]
    api.currentTurn = async () => null
    api.combatOverview = async () => []

    await workspace.startTrpgTurn()

    assert.deepEqual(streamingSnapshot, [{
      id: 101,
      replyStepId: 9,
      content: '新的回复内容',
      status: 'streaming',
    }])
    assert.equal(streamingReasoning, '新的思考内容')
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    api.combatOverview = originalCombatOverview
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

test('replaces the failed message while retrying the same reply step', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    window: {
      addEventListener() {},
      clearTimeout,
      setTimeout,
    },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
    sessionStorage: {
      getItem: () => null,
      setItem() {},
      removeItem() {},
      clear() {},
      key: () => null,
      length: 0,
    },
  })

  const originalRetry = streamTrpgTurn.retry
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  const originalCombatOverview = api.combatOverview
  try {
    let workspace!: ReturnType<typeof useWorkspace>
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
    renderer.createApp(defineComponent({
      setup() {
        workspace = useWorkspace()
        return () => h('div')
      },
    })).mount({})
    workspace.conversations.value = [{
      id: 7,
      userWorldId: 3,
      worldId: 2,
      mode: 'trpg',
      title: '旧宅调查',
      status: 'active',
    }]
    workspace.selectedConversationId.value = 7
    const failedMessage: GroupMessage = {
      id: 100,
      conversationId: 7,
      turnId: 42,
      replyStepId: 9,
      speakerType: 'character',
      speakerId: 101,
      speakerName: 'A',
      messageKind: 'dialogue',
      content: '旧的失败内容',
      sequenceNo: 10,
      status: 'failed',
    }
    workspace.messages.value = [failedMessage]

    let streamingSnapshot: Array<Pick<GroupMessage, 'id' | 'replyStepId' | 'content' | 'status'>> = []
    streamTrpgTurn.retry = async (_id, _turnId, _stepId, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'reply.started', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'character', id: 101, name: 'A' },
      })
      onEvent({
        eventType: 'message.delta', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'character', id: 101, name: 'A' },
        delta: '新的重试内容',
      })
      streamingSnapshot = workspace.messages.value.map(({ id, replyStepId, content, status }) => ({
        id, replyStepId, content, status,
      }))
      onEvent({
        eventType: 'message.completed', conversationId: 7, turnId: 42,
        replyStepId: 9, messageId: 101, sequence: 11,
        messageKind: 'dialogue', speaker: { type: 'character', id: 101, name: 'A' },
        content: '新的重试内容',
      })
    }
    api.groupMessages = async () => [{
      ...failedMessage,
      id: 101,
      content: '新的重试内容',
      sequenceNo: 11,
      status: 'completed',
    }]
    api.replyPlan = async () => [{ source: 'SCENE', displayName: '旧宅', items: [] }]
    api.currentTurn = async () => null
    api.combatOverview = async () => []

    await workspace.retryStep(failedMessage)

    assert.deepEqual(streamingSnapshot, [{
      id: 101,
      replyStepId: 9,
      content: '新的重试内容',
      status: 'streaming',
    }])
  } finally {
    streamTrpgTurn.retry = originalRetry
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    api.combatOverview = originalCombatOverview
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})
