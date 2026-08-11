import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import type { DiceResult, DiceRollAggregate, GroupMessage } from '../../api/types.ts'
import * as diceState from './dicePlayback.ts'
import {
  createDiceAggregatePlaybackRequest,
  createDicePlaybackRequest,
  createDicePlayerSummary,
  createDicePlayerStatus,
  createGroupOutcomeVisibility,
  type DicePlaybackRequest,
} from './dicePlayback.ts'
import {
  createDiceAggregateFixture as createDiceDebugAggregatePreset,
  createDiceResultFixture as createDiceDebugPreset,
} from '../../../test/fixtures/dice.ts'

type DiceMessagePresentation = {
  title: string
  statusLabel: string
  tone: string
}

function diceMessagePresentation(
  aggregate: ReturnType<typeof createDiceDebugAggregatePreset>,
): DiceMessagePresentation | undefined {
  const createPresentation = Reflect.get(diceState, 'createDiceMessagePresentation') as
    | ((value: typeof aggregate) => DiceMessagePresentation)
    | undefined
  return createPresentation?.(aggregate)
}

test('shows pending dice messages in gray regardless of their eventual result type', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.status = 'PENDING'
  aggregate.results[0]!.resolvedAt = undefined
  aggregate.results[0]!.resultData!.result = undefined

  assert.deepEqual(diceMessagePresentation(aggregate), {
    title: '争夺手枪',
    statusLabel: '未投掷',
    tone: 'pending',
  })
})

test('keeps a completed historical round settled while its summary waits on a later round', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.status = 'PENDING'

  assert.equal(diceMessagePresentation(aggregate)?.statusLabel, '林恩获胜')
})

test('shows a persisted single-check rank on the refreshed dice message', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [aggregate.results[0]!]
  Object.assign(aggregate.results[0]!.resolution?.outcome || {}, {
    category: 'SUCCESS',
    rank: 'REGULAR',
  })

  assert.deepEqual(diceMessagePresentation(aggregate), {
    title: '搜索废弃宅邸',
    statusLabel: '常规成功',
    tone: 'success',
  })
})

test('uses personalized window tones before generic result tones', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.toolName = 'rollDamage'
  aggregate.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, { groupRule: 'ALL_SUCCESS' })
  })

  assert.equal(diceMessagePresentation(aggregate)?.tone, 'damage')
})

test('uses yellow for completed opposed checks', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')

  assert.equal(diceMessagePresentation(aggregate)?.tone, 'opposed')
})

test('uses green or red for single and merged check outcomes', () => {
  const successful = createDiceDebugAggregatePreset('multiplayer-check')
  successful.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, { groupRule: 'ANY_SUCCESS' })
  })
  const failed = createDiceDebugAggregatePreset('multiplayer-check')
  failed.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, {
      groupRule: 'ALL_SUCCESS',
      outcome: { ...detail.resolution?.outcome, category: 'FAILURE' },
    })
  })
  const single = createDiceDebugAggregatePreset('multiplayer-check')
  single.results = [single.results[0]!]

  assert.equal(diceMessagePresentation(successful)?.tone, 'success')
  assert.equal(diceMessagePresentation(failed)?.tone, 'failure')
  assert.equal(diceMessagePresentation(single)?.tone, 'success')
})

test('uses blue for separate multiplayer results', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, { groupRule: 'SEPARATE' })
  })

  assert.equal(diceMessagePresentation(aggregate)?.tone, 'default')
})

test('offers continue only when the current playback owns the first completion', () => {
  const shouldOfferContinue = Reflect.get(diceState, 'shouldOfferDiceContinue') as
    | ((request: DicePlaybackRequest | null, status: string, hasPendingResults: boolean) => boolean)
    | undefined
  const firstPlayback = { offerContinueAfterComplete: true } as DicePlaybackRequest
  const historicalPlayback = { offerContinueAfterComplete: false } as DicePlaybackRequest

  assert.equal(shouldOfferContinue?.(firstPlayback, 'COMPLETED', false), true)
  assert.equal(shouldOfferContinue?.(historicalPlayback, 'COMPLETED', false), false)
  assert.equal(shouldOfferContinue?.(firstPlayback, 'PENDING', true), false)
})

test('auto plays a live non-user roll after one second of idle spin', () => {
  const createIncomingRequest = Reflect.get(diceState, 'createIncomingDiceMessagePlaybackRequest') as
    | ((previousId: number, aggregate: DiceRollAggregate, skin: 'classic') => DicePlaybackRequest)
    | undefined
  const createAutoPlayPlan = Reflect.get(diceState, 'createDiceAutoPlayPlan') as
    | ((request: DicePlaybackRequest) => { phase: string; delayMs: number })
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createIncomingRequest?.(8, aggregate, 'classic')

  assert.deepEqual({
    id: request?.id,
    mode: request?.mode,
    autoPlay: request?.autoPlay,
    autoPlayDelayMs: request?.autoPlayDelayMs,
    offerContinueAfterComplete: request?.offerContinueAfterComplete,
  }, {
    id: 9,
    mode: 'play',
    autoPlay: true,
    autoPlayDelayMs: 1_000,
    offerContinueAfterComplete: true,
  })
  assert.deepEqual(request && createAutoPlayPlan?.(request), {
    phase: 'idle',
    delayMs: 1_000,
  })
})

test('keeps a live roll containing a user die waiting for the click', () => {
  const createIncomingRequest = Reflect.get(diceState, 'createIncomingDiceMessagePlaybackRequest') as
    | ((previousId: number, aggregate: DiceRollAggregate, skin: 'classic') => DicePlaybackRequest)
    | undefined
  const createAutoPlayPlan = Reflect.get(diceState, 'createDiceAutoPlayPlan') as
    | ((request: DicePlaybackRequest) => { phase: string; delayMs: number })
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.status = 'PENDING'
  aggregate.results[0]!.resolvedAt = undefined
  aggregate.results[0]!.characterId = undefined

  const request = createIncomingRequest?.(3, aggregate, 'classic')

  assert.deepEqual({
    mode: request?.mode,
    autoPlay: request?.autoPlay,
    autoPlayDelayMs: request?.autoPlayDelayMs,
    offerContinueAfterComplete: request?.offerContinueAfterComplete,
  }, {
    mode: 'pending',
    autoPlay: false,
    autoPlayDelayMs: undefined,
    offerContinueAfterComplete: true,
  })
  assert.deepEqual(request && createAutoPlayPlan?.(request), {
    phase: 'ready',
    delayMs: 0,
  })
})

test('reconceals every participant whenever a mixed pending roll is reopened', () => {
  const createPreparedResult = Reflect.get(diceState, 'createDicePlayerPreparedResult') as
    | ((request: DicePlaybackRequest) => DiceResult)
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.status = 'PENDING'
  aggregate.results[0]!.resolvedAt = undefined
  aggregate.results[0]!.characterId = undefined
  aggregate.results[0]!.resultData!.result = undefined
  aggregate.results[0]!.resultData!.modules[0]!.result = undefined
  aggregate.results[0]!.resultData!.modules[0]!.dice.forEach((die) => { die.value = undefined })
  const request = diceState.createIncomingDiceMessagePlaybackRequest(12, aggregate, 'classic')

  const firstOpen = createPreparedResult?.(request)
  const reopened = createPreparedResult?.(request)

  assert.deepEqual(firstOpen?.modules.map((module) => module.result), [0, 0, 0])
  assert.deepEqual(reopened?.modules.map((module) => module.result), [0, 0, 0])
  assert.deepEqual(firstOpen?.modules.flatMap((module) => module.dice.map((die) => die.value)), [0, 0, 0, 0, 0, 0])
  assert.deepEqual(reopened, firstOpen)
  assert.deepEqual(request.result.modules.map((module) => module.result), [undefined, 78, 1])
})

test('keeps every participant in the animation after the user roll resolves', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  const request = diceState.createDiceMessagePlaybackRequest(20, aggregate, 'classic')

  assert.deepEqual(request.result.modules.map((module) => module.result), [27, 78, 1])
  assert.equal(request.result.modules.length, 3)
})

test('opens pending, animated, and settled dice requests in distinct player states', () => {
  const initialState = Reflect.get(diceState, 'createDicePlayerInitialState') as
    | ((mode: string, kind?: string, groupRule?: string) => {
      phase: string
      groupOutcomePhase: string
    })
    | undefined

  assert.deepEqual(initialState?.('pending'), {
    phase: 'ready',
    groupOutcomePhase: 'concealed',
  })
  assert.deepEqual(initialState?.('play'), {
    phase: 'loading',
    groupOutcomePhase: 'concealed',
  })
  assert.deepEqual(initialState?.('settled', 'multiplayer-check', 'SEPARATE'), {
    phase: 'complete',
    groupOutcomePhase: 'individual',
  })
  assert.deepEqual(initialState?.('settled', 'opposed-check'), {
    phase: 'complete',
    groupOutcomePhase: 'merged',
  })
})

test('reads a persisted dice summary reference without accepting malformed chat content', () => {
  const parseSummaryId = Reflect.get(diceState, 'parseDiceMessageSummaryId') as
    | ((content: string) => number | undefined)
    | undefined

  assert.equal(parseSummaryId?.('{"summaryId":42,"roundNos":[1]}'), 42)
  assert.equal(parseSummaryId?.('{"summaryId":0,"roundNos":[1]}'), undefined)
  assert.equal(parseSummaryId?.('not-json'), undefined)
})

test('hydrates a persisted dice message into a renderable aggregate', async () => {
  const hydrateMessage = Reflect.get(diceState, 'hydrateDiceMessage') as
    | ((message: GroupMessage, load: (id: number) => Promise<unknown>) => Promise<GroupMessage>)
    | undefined
  const message: GroupMessage = {
    id: 10,
    conversationId: 1,
    speakerType: 'kp',
    messageKind: 'dice_roll',
    content: '{"summaryId":42,"roundNos":[1]}',
    sequenceNo: 1,
    status: 'completed',
  }
  const aggregate = createDiceDebugAggregatePreset('opposed-check')

  const hydrated = await hydrateMessage?.(message, async (id) => {
    assert.equal(id, 42)
    return aggregate
  })

  assert.equal(hydrated?.content, '')
  assert.deepEqual(hydrated?.diceRoundNos, [1])
  assert.deepEqual(hydrated?.diceRoll, aggregate)
})

test('hydrates only the rounds referenced by each persisted dice message', async () => {
  const hydrateMessage = Reflect.get(diceState, 'hydrateDiceMessage') as
    | ((message: GroupMessage, load: (id: number) => Promise<unknown>) => Promise<GroupMessage>)
    | undefined
  const message: GroupMessage = {
    id: 11,
    conversationId: 1,
    speakerType: 'kp',
    messageKind: 'dice_roll',
    content: '{"summaryId":42,"roundNos":[2]}',
    sequenceNo: 2,
    status: 'completed',
  }
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.results = [
    { ...aggregate.results[0]!, id: 1, roundNo: 1, reason: '争夺手枪' },
    { ...aggregate.results[1]!, id: 2, roundNo: 2, reason: '手枪伤害' },
  ]

  const hydrated = await hydrateMessage?.(message, async () => aggregate)

  assert.deepEqual(hydrated?.diceRoll?.results.map((detail) => detail.id), [2])
  assert.equal(diceMessagePresentation(hydrated!.diceRoll!)?.title, '手枪伤害')
})

test('lists every hydrated dice message in reverse current-chat position', () => {
  const listDiceMessages = Reflect.get(diceState, 'listDiceMessagesNewestFirst') as
    | ((messages: GroupMessage[]) => GroupMessage[])
    | undefined
  const first = createDiceDebugAggregatePreset('multiplayer-check')
  const latest = createDiceDebugAggregatePreset('opposed-check')
  const messages: GroupMessage[] = [
    {
      id: 10, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll',
      content: '', sequenceNo: 900, status: 'completed', diceRoll: first,
    },
    {
      id: 20, conversationId: 1, speakerType: 'narrator', messageKind: 'narration',
      content: '中间的叙事', sequenceNo: 1, status: 'completed',
    },
    {
      id: 30, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll',
      content: '', sequenceNo: 100, status: 'completed', diceRoll: latest,
    },
  ]

  assert.equal(typeof listDiceMessages, 'function')
  assert.deepEqual(listDiceMessages?.(messages).map((message) => message.id), [30, 10])
  assert.deepEqual(messages.map((message) => message.id), [10, 20, 30])
})

test('finds the chat element that owns a tool dice message id', () => {
  const findElement = Reflect.get(diceState, 'findDiceMessageElement') as
    | (<T extends { dataset: { messageId?: string } }>(elements: T[], messageId: number) => T | undefined)
    | undefined
  const first = { dataset: { messageId: '10' }, label: '第一条' }
  const target = { dataset: { messageId: '30' }, label: '目标骰子' }

  assert.equal(typeof findElement, 'function')
  assert.equal(findElement?.([first, target], 30), target)
  assert.equal(findElement?.([first, target], 99), undefined)
})

test('creates a multiplayer check playback from backend-shaped roll details', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.toolName = 'requestCheck'
  aggregate.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, { groupRule: 'ANY_SUCCESS' })
  })

  const request = createDiceAggregatePlaybackRequest(6, aggregate, 'galaxy')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.id, 7)
  assert.equal(request.toolName, 'requestCheck')
  assert.equal(request.reason, '搜索废弃宅邸')
  assert.ok(aggregate.results.every((detail) => detail.summaryId === aggregate.summary.id))
  assert.equal(request.result.modules.length, 3)
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '侦查', result: '27 · 成功', diceCount: 2 },
    { label: '陈默', expression: '侦查', result: '78 · 失败', diceCount: 2 },
    { label: '苏婉', expression: '侦查', result: '1 · 大成功', diceCount: 2 },
  ])
  assert.deepEqual(request.presentation?.groups.map((group) => group.success), [true, false, true])
  assert.equal(summary.resultLabel, '任一成功')
  assert.equal(summary.resultValue, '成功')
  assert.equal(summary.formulaLabel, '检定项目')
  assert.equal(summary.formulaValue, '3 人参与 · 侦查 · 任一成功即通过')
})

test('creates a persisted single check through the same participant presentation as multiplayer checks', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [aggregate.results[0]!]
  Object.assign(aggregate.results[0]!.resolution || {}, {
    groupRule: 'SEPARATE',
    outcome: {
      characterName: '林恩',
      checkName: '侦查',
      category: 'SUCCESS',
      rank: 'REGULAR',
    },
  })
  const createMessagePlayback = Reflect.get(diceState, 'createDiceMessagePlaybackRequest') as
    | ((previousId: number, value: DiceRollAggregate, skin: 'classic') => ReturnType<typeof createDiceAggregatePlaybackRequest>)
    | undefined

  assert.equal(typeof createMessagePlayback, 'function')
  const request = createMessagePlayback?.(3, aggregate, 'classic')
  const summary = request
    ? createDicePlayerSummary(request.result, request.skin, request.presentation)
    : undefined

  assert.equal(request?.id, 4)
  assert.equal(request?.presentation?.kind, 'multiplayer-check')
  assert.equal(request?.presentation?.groups.length, 1)
  assert.deepEqual(summary?.groups, [
    { label: '林恩', expression: '侦查', result: '27 · 常规成功', diceCount: 2 },
  ])
  assert.equal(summary?.modifierLabel, '单人检定')
  assert.equal(summary?.formulaValue, '1 人参与 · 侦查 · 分别展示')
})

test('keeps ordinary single dice outside the participant outcome presentation', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [{
    ...aggregate.results[0]!,
    displayType: 'DAMAGE',
    resolution: { type: 'DAMAGE', outcome: { damage: 4 } },
  }]
  const createMessagePlayback = Reflect.get(diceState, 'createDiceMessagePlaybackRequest') as
    | ((previousId: number, value: DiceRollAggregate, skin: 'classic') => ReturnType<typeof createDicePlaybackRequest>)
    | undefined

  const request = createMessagePlayback?.(0, aggregate, 'classic')

  assert.equal(request?.presentation, undefined)
  assert.equal(request?.result.result, 27)
})

test('applies the selected damage window style to aggregate debug playback', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createDiceAggregatePlaybackRequest(
    0,
    aggregate,
    'classic',
    'ANY_SUCCESS',
    'rollDamage',
  )

  assert.equal(request.toolName, 'rollDamage')
})

test('resolves rollDamage to the static damage window class', () => {
  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((toolName?: string) => string)
    | undefined

  assert.equal(typeof resolveWindowClass, 'function')
  assert.equal(resolveWindowClass?.(), 'dice-player-window')
  assert.equal(
    resolveWindowClass?.('rollDamage'),
    'dice-player-window dice-player-window--damage',
  )
})

test('resolves sanity check and sanity loss tools to the same sanity window class', () => {
  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((toolName?: string) => string)
    | undefined

  assert.equal(
    resolveWindowClass?.('requestSanCheck'),
    'dice-player-window dice-player-window--sanity',
  )
  assert.equal(
    resolveWindowClass?.('rollSanLoss'),
    'dice-player-window dice-player-window--sanity',
  )
})

test('resolves rollHealing to the static healing window class', () => {
  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((toolName?: string) => string)
    | undefined

  assert.equal(
    resolveWindowClass?.('rollHealing'),
    'dice-player-window dice-player-window--healing',
  )
})

test('resolves requestPushedCheck to the static pushed-check window class', () => {
  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((toolName?: string) => string)
    | undefined

  assert.equal(
    resolveWindowClass?.('requestPushedCheck'),
    'dice-player-window dice-player-window--pushed-check',
  )
})

test('fails an all-success group check when any participant fails', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic', 'ALL_SUCCESS')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.groupRule, 'ALL_SUCCESS')
  assert.equal(summary.resultLabel, '全部成功')
  assert.equal(summary.resultValue, '失败')
  assert.equal(summary.formulaValue, '3 人参与 · 侦查 · 全部成功才通过')
})

test('reads the group rule from backend roll details', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results.forEach((detail) => {
    Object.assign(detail.resolution || {}, { groupRule: 'ALL_SUCCESS' })
  })

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')

  assert.equal(request.presentation?.groupRule, 'ALL_SUCCESS')
  assert.equal(request.presentation?.resultValue, '失败')
})

test('defaults legacy group checks to separate presentation', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')

  assert.equal(request.presentation?.groupRule, 'SEPARATE')
  assert.equal(request.presentation?.resultLabel, '分别结果')
  assert.equal(request.presentation?.resultValue, aggregate.semanticResult)
})

test('passes an all-success group check when every participant succeeds', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results.forEach((detail) => {
    if (detail.resolution?.outcome) detail.resolution.outcome.category = 'SUCCESS'
  })

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic', 'ALL_SUCCESS')

  assert.equal(request.presentation?.resultValue, '成功')
})

test('shows participant boxes without outcomes before the roll', () => {
  assert.deepEqual(createGroupOutcomeVisibility('concealed'), {
    showIndividuals: true,
    revealIndividualResults: false,
    showTransition: false,
    showFinal: false,
    highlightWinner: false,
  })
})

test('keeps individual group results beside the final result after the merge', () => {
  assert.deepEqual(createGroupOutcomeVisibility('individual'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: false,
    showFinal: false,
    highlightWinner: false,
  })
  assert.deepEqual(createGroupOutcomeVisibility('merging'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: true,
    showFinal: true,
    highlightWinner: true,
  })
  assert.deepEqual(createGroupOutcomeVisibility('merged'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: true,
    showFinal: true,
    highlightWinner: true,
  })
})

test('keeps separate group results unmerged after rolling', () => {
  assert.deepEqual(createGroupOutcomeVisibility('merged', 'SEPARATE'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: false,
    showFinal: false,
    highlightWinner: false,
  })
})

test('keeps opposed results visible while the winner is highlighted', () => {
  assert.deepEqual(createGroupOutcomeVisibility('highlighted'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: false,
    showFinal: false,
    highlightWinner: true,
  })
})

test('creates an opposed check playback that reveals the winner after both rolls', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')

  const request = createDiceAggregatePlaybackRequest(2, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.ok(aggregate.results.every((detail) => detail.summaryId === aggregate.summary.id))
  assert.equal(request.result.modules.length, 2)
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '格斗', result: '35 · 成功', diceCount: 2 },
    { label: '陈默', expression: '闪避', result: '40 · 成功', diceCount: 2 },
  ])
  assert.deepEqual(request.presentation?.groups.map((group) => group.winner), [true, false])
  assert.equal(summary.modifierLabel, '对抗检定')
  assert.equal(summary.resultLabel, '对抗结果')
  assert.equal(summary.resultValue, '林恩获胜')
  assert.equal(summary.formulaValue, '林恩（格斗） vs 陈默（闪避）')
})

test('creates a new immutable playback request when the same result is replayed', () => {
  const result = createDiceDebugPreset('group')

  const first = createDicePlaybackRequest(0, result, 'galaxy')
  const second = createDicePlaybackRequest(first.id, result, 'galaxy')
  result.modules[0].dice[0].value = 1

  assert.equal(first.id, 1)
  assert.equal(second.id, 2)
  assert.equal(first.skin, 'galaxy')
  assert.equal(first.result.modules[0].dice[0].value, 4)
})

test('offers the four persisted account dice skins with their player labels', () => {
  const options = Reflect.get(diceState, 'DICE_SKIN_OPTIONS')

  assert.deepEqual(options, [
    { value: 'classic', label: '经典' },
    { value: 'galaxy', label: '星穹' },
    { value: 'moonwhite', label: '月白冰晶' },
    { value: 'cinnabar', label: '朱砂鎏金' },
  ])
})

test('parses supported account dice skins and falls back to classic for unknown values', () => {
  const resolveDiceSkin = Reflect.get(diceState, 'resolveDiceSkin') as
    | ((value: unknown) => string)
    | undefined

  assert.deepEqual(
    ['classic', 'galaxy', 'moonwhite', 'cinnabar'].map((value) => resolveDiceSkin?.(value)),
    ['classic', 'galaxy', 'moonwhite', 'cinnabar'],
  )
  assert.equal(resolveDiceSkin?.('future-skin'), 'classic')
  assert.equal(resolveDiceSkin?.(''), 'classic')
  assert.equal(resolveDiceSkin?.(null), 'classic')
})

test('normalizes an unrecognized account skin before creating a playback request', () => {
  const createRequest = createDicePlaybackRequest as unknown as (
    previousId: number,
    result: DiceResult,
    skin: unknown,
  ) => DicePlaybackRequest

  const request = createRequest(0, createDiceDebugPreset('group'), 'removed-skin')

  assert.equal(request.skin, 'classic')
})

test('creates a playback request from a Vue reactive result', () => {
  const formResult = reactive(createDiceDebugPreset('normal-percentile'))

  const request = createDicePlaybackRequest(4, formResult, 'classic')

  assert.equal(request.id, 5)
  assert.equal(request.result.formula, '1D100')
  assert.equal(request.result.modules[0].dice[0].value, 7)
})

test('keeps the production roll reason in an immutable playback request', () => {
  const result = createDiceDebugPreset('normal-percentile')

  const request = createDicePlaybackRequest(
    8,
    result,
    'classic',
    '侦查检定',
    'requestCheck',
  )
  result.formula = 'changed after playback'

  assert.equal(request.id, 9)
  assert.equal(request.reason, '侦查检定')
  assert.equal(request.toolName, 'requestCheck')
  assert.equal(request.result.formula, '1D100')
})

test('summarizes a playback result for the player window', () => {
  const summary = createDicePlayerSummary(createDiceDebugPreset('group'), 'moonwhite')

  assert.deepEqual(summary, {
    skinLabel: '月白冰晶',
    moduleLabel: '2 组判定',
    diceLabel: '5 颗骰子',
    modifierLabel: '常规判定',
    selectionLabel: '5 颗全部计入',
    groups: [
      { label: '第 1 组', expression: '3D6', result: 15, diceCount: 3 },
      { label: '第 2 组', expression: '2D8', result: 10, diceCount: 2 },
    ],
    equation: '3D6 + 2D8 = 25',
    resultLabel: '最终结果',
    resultValue: 25,
    formulaLabel: '判定公式',
    formulaValue: '3D6 + 2D8',
  })
})

test('explains percentile modifiers and discarded dice in the player window', () => {
  const summary = createDicePlayerSummary(createDiceDebugPreset('double-advantage'), 'classic')

  assert.equal(summary.modifierLabel, '双奖励骰')
  assert.equal(summary.selectionLabel, '2 颗计入 · 2 颗舍弃')
})

test('uses singular labels and a pending equation when a result has not settled', () => {
  const result: DiceResult = {
    formula: '1D20',
    modules: [{
      expression: '1D20', diceCount: 1, diceSides: 20, modifier: 'NORMAL',
      dice: [{ sides: 20, value: 12, role: 'NORMAL', selected: true }],
    }],
  }

  assert.deepEqual(createDicePlayerSummary(result, 'classic'), {
    skinLabel: '经典',
    moduleLabel: '1 组判定',
    diceLabel: '1 颗骰子',
    modifierLabel: '常规判定',
    selectionLabel: '1 颗全部计入',
    groups: [{ label: '第 1 组', expression: '1D20', result: '—', diceCount: 1 }],
    equation: '1D20 = —',
    resultLabel: '最终结果',
    resultValue: '—',
    formulaLabel: '判定公式',
    formulaValue: '1D20',
  })
})

test('keeps the final result concealed until the dice finish rolling', () => {
  assert.deepEqual(createDicePlayerStatus('loading'), {
    label: '正在准备',
    hint: '加载骰子与判定桌面',
    revealResult: false,
    showDieValues: false,
    actionLabel: '准备骰子',
    actionDisabled: true,
  })
  assert.deepEqual(createDicePlayerStatus('playing'), {
    label: '正在投掷',
    hint: '结果将在骰子停稳后揭晓',
    revealResult: false,
    showDieValues: false,
    actionLabel: '正在掷骰',
    actionDisabled: true,
  })
  assert.deepEqual(createDicePlayerStatus('complete'), {
    label: '判定完成',
    hint: '最终点数已锁定',
    revealResult: true,
    showDieValues: true,
    actionLabel: '重放动画',
    actionDisabled: false,
  })
  assert.equal(createDicePlayerStatus('error').revealResult, true)
  assert.equal(createDicePlayerStatus('error').showDieValues, false)
})

test('shows a concealed ready state after every die has been preloaded', () => {
  const ready = createDicePlayerStatus('ready')

  assert.deepEqual(ready, {
    label: '准备就绪',
    hint: '骰子正在待机，点击掷骰开始判定',
    revealResult: false,
    showDieValues: false,
    actionLabel: '掷骰',
    actionDisabled: false,
  })
})

test('creates a renderable standby snapshot without revealing unresolved dice values', () => {
  const createStandbyResult = Reflect.get(diceState, 'createStandbyDiceResult') as
    | ((result: DiceResult) => DiceResult)
    | undefined
  const pending: DiceResult = {
    formula: '1D6 + 1D100#',
    modules: [
      {
        expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL',
        dice: [{ sides: 6, role: 'NORMAL', selected: true }],
      },
      {
        expression: '1D100#', diceCount: 1, diceSides: 100, modifier: 'ADVANTAGE',
        dice: [
          { sides: 10, role: 'PERCENTILE_ONES', selected: true },
          { sides: 10, role: 'PERCENTILE_TENS', selected: false },
          { sides: 10, role: 'PERCENTILE_TENS', selected: false },
        ],
      },
    ],
  }

  assert.deepEqual(createStandbyResult?.(pending), {
    formula: '1D6 + 1D100#',
    result: 0,
    modules: [
      {
        expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL', result: 0,
        dice: [{ sides: 6, value: 1, role: 'NORMAL', selected: true }],
      },
      {
        expression: '1D100#', diceCount: 1, diceSides: 100, modifier: 'ADVANTAGE', result: 0,
        dice: [
          { sides: 10, value: 0, role: 'PERCENTILE_ONES', selected: true },
          { sides: 10, value: 0, role: 'PERCENTILE_TENS', selected: true },
          { sides: 10, value: 0, role: 'PERCENTILE_TENS', selected: false },
        ],
      },
    ],
  })
  assert.equal(pending.modules[0]!.dice[0]!.value, undefined)
  assert.deepEqual(pending.modules[1]!.dice.map((die) => die.selected), [true, false, false])
})

test('locks the roll control only while dice are loading or rolling', () => {
  assert.deepEqual(
    ['loading', 'playing', 'complete'].map((phase) => {
      const state = createDicePlayerStatus(phase as 'loading' | 'playing' | 'complete') as ReturnType<typeof createDicePlayerStatus> & {
        actionLabel: string
        actionDisabled: boolean
      }
      return [state.actionLabel, state.actionDisabled]
    }),
    [
      ['准备骰子', true],
      ['正在掷骰', true],
      ['重放动画', false],
    ],
  )
})
