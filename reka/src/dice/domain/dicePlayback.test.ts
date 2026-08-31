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
  type DiceHistoryEntry,
  type DiceHistoryFilters,
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

function valueRollAggregate(
  toolName: 'rollDamage' | 'rollSanLoss' | 'rollHealing',
  type: 'DAMAGE' | 'SAN_LOSS' | 'HEALING',
  entries: Array<{ name: string; result: DiceResult }>,
): DiceRollAggregate {
  return {
    summary: {
      id: 9300,
      conversationId: 1,
      reason: '数值结算',
      totalResult: '已完成',
      roundCount: 1,
      status: 'COMPLETED',
      toolName,
    },
    results: entries.map((entry, index) => ({
      id: 9310 + index,
      summaryId: 9300,
      roundNo: 1,
      displayOrder: index + 1,
      displayType: type,
      reason: '数值结算',
      resultData: entry.result,
      resolution: { type, outcome: { characterName: entry.name } },
      resolvedAt: '2026-08-12T12:00:00',
    })),
    semanticResult: '已完成',
  }
}

function combatCheckResult(result: number): DiceResult {
  return {
    formula: '1D100$',
    modules: [{
      expression: '1D100$',
      diceCount: 1,
      diceSides: 100,
      modifier: 'NORMAL',
      dice: [
        { sides: 10, value: result % 10, role: 'PERCENTILE_ONES', selected: true },
        { sides: 10, value: Math.floor(result / 10), role: 'PERCENTILE_TENS', selected: true },
      ],
      result,
    }],
    result,
  }
}

test('opens a single firearm attack as a semantic combat check', () => {
  const aggregate: DiceRollAggregate = {
    summary: {
      id: 24,
      conversationId: 4,
      reason: '本用步枪射击阿尔法',
      totalResult: '本向阿尔法射击：失败',
      roundCount: 1,
      status: 'COMPLETED',
      toolName: 'requestFirearmAttack',
    },
    results: [{
      id: 32,
      summaryId: 24,
      roundNo: 1,
      displayOrder: 1,
      displayType: 'FIREARM_ATTACK',
      reason: '本用步枪射击阿尔法',
      resultData: combatCheckResult(69),
      resolution: {
        type: 'FIREARM_ATTACK',
        rule: {
          skillName: '射击:步枪/霰弹枪',
          targetCharacterName: '阿尔法',
        },
        outcome: {
          characterName: '本',
          targetCharacterName: '阿尔法',
          category: 'FAILURE',
          rank: 'FAILURE',
        },
      },
      resolvedAt: '2026-08-18T00:18:28',
    }],
    semanticResult: '本向阿尔法射击：失败',
  }

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'multiplayer-check')
  assert.deepEqual(summary.groups, [{
    label: '本',
    expression: '射击:步枪/霰弹枪 → 阿尔法',
    result: '69 · 失败',
    diceCount: 2,
  }])
  assert.equal(summary.resultValue, '本向阿尔法射击：失败')
})

test('opens melee attack and defense rolls as an opposed combat check', () => {
  const aggregate: DiceRollAggregate = {
    summary: {
      id: 25,
      conversationId: 4,
      reason: '阿尔法用伸缩警棍袭击本',
      totalResult: '阿尔法近战攻击获胜',
      roundCount: 1,
      status: 'COMPLETED',
      toolName: 'requestMeleeAttack',
    },
    results: [
      {
        id: 33,
        summaryId: 25,
        roundNo: 1,
        displayOrder: 1,
        displayType: 'MELEE_ATTACK',
        resultData: combatCheckResult(32),
        resolution: {
          type: 'MELEE_ATTACK',
          rule: { checkName: '斗殴' },
          outcome: {
            characterName: '阿尔法', category: 'SUCCESS', rank: 'REGULAR', winner: true,
          },
        },
        resolvedAt: '2026-08-18T00:30:00',
      },
      {
        id: 34,
        summaryId: 25,
        roundNo: 1,
        displayOrder: 2,
        displayType: 'MELEE_ATTACK',
        resultData: combatCheckResult(72),
        resolution: {
          type: 'MELEE_ATTACK',
          rule: { checkName: '闪避' },
          outcome: {
            characterName: '本', category: 'FAILURE', rank: 'FAILURE', winner: false,
          },
        },
        resolvedAt: '2026-08-18T00:30:00',
      },
    ],
    semanticResult: '阿尔法近战攻击获胜',
  }

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'opposed-check')
  assert.deepEqual(
    request.presentation?.groups.map(({ label, checkName, winner }) => ({ label, checkName, winner })),
    [
      { label: '阿尔法', checkName: '斗殴', winner: true },
      { label: '本', checkName: '闪避', winner: false },
    ],
  )
  assert.equal(summary.resultValue, '阿尔法近战攻击获胜')

  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((request: DicePlaybackRequest) => string)
    | undefined
  assert.equal(
    resolveWindowClass?.(request),
    'dice-player-window dice-player-window--opposed',
  )
})

test('keeps the completed attack visible before offering its newly-created damage roll', () => {
  const createPostRollPlan = Reflect.get(diceState, 'createDicePostRollPlaybackPlan') as
    | ((aggregate: DiceRollAggregate, rolledResultId: number) => {
        playbackAggregate: DiceRollAggregate
        queuedAggregate: DiceRollAggregate | null
      })
    | undefined
  const aggregate: DiceRollAggregate = {
    summary: {
      id: 25,
      conversationId: 4,
      reason: '用折刀刺击邪教徒',
      totalResult: '林恩近战攻击获胜\n邪教徒生命-3',
      roundCount: 2,
      status: 'PENDING',
      toolName: 'requestMeleeAttack',
    },
    results: [
      {
        id: 33,
        summaryId: 25,
        roundNo: 1,
        displayOrder: 1,
        displayType: 'MELEE_ATTACK',
        resultData: combatCheckResult(32),
        resolution: {
          type: 'MELEE_ATTACK',
          rule: { checkName: '斗殴' },
          outcome: {
            characterName: '林恩', category: 'SUCCESS', rank: 'REGULAR', winner: true,
          },
        },
        resolvedAt: '2026-08-18T00:30:00',
      },
      {
        id: 34,
        summaryId: 25,
        roundNo: 1,
        displayOrder: 2,
        displayType: 'MELEE_ATTACK',
        resultData: combatCheckResult(72),
        resolution: {
          type: 'MELEE_ATTACK',
          rule: { checkName: '闪避' },
          outcome: {
            characterName: '邪教徒', category: 'FAILURE', rank: 'FAILURE', winner: false,
          },
        },
        resolvedAt: '2026-08-18T00:30:00',
      },
      {
        id: 35,
        summaryId: 25,
        roundNo: 2,
        displayOrder: 1,
        displayType: 'DAMAGE',
        reason: '折刀命中邪教徒',
        resultData: createDiceDebugPreset('standard'),
        resolution: { type: 'DAMAGE' },
      },
    ],
    semanticResult: '林恩近战攻击获胜\n邪教徒生命-3',
  }

  const plan = createPostRollPlan?.(aggregate, 33)

  assert.deepEqual(plan?.playbackAggregate.results.map((detail) => detail.id), [33, 34])
  assert.equal(Reflect.get(plan || {}, 'rolledGroupIndex'), 0)
  assert.equal(plan?.playbackAggregate.semanticResult, '林恩近战攻击获胜')
  assert.equal(diceState.isDiceAggregatePending(plan!.playbackAggregate), false)
  assert.deepEqual(plan?.queuedAggregate?.results.map((detail) => detail.id), [35])
  assert.equal(diceState.isDiceAggregatePending(plan!.queuedAggregate!), true)
})

test('splits an automatically completed melee check and damage into two ordered playbacks', () => {
  const splitByRound = Reflect.get(diceState, 'splitDiceAggregateByRound') as
    | ((aggregate: DiceRollAggregate) => DiceRollAggregate[])
    | undefined
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.roundCount = 2
  aggregate.summary.totalResult = '林恩获胜\n邪教徒生命-3'
  aggregate.semanticResult = '林恩获胜\n邪教徒生命-3'
  aggregate.results.push({
    id: 9200,
    summaryId: aggregate.summary.id,
    roundNo: 2,
    displayOrder: 1,
    displayType: 'DAMAGE',
    reason: '折刀命中邪教徒',
    resultData: createDiceDebugPreset('standard'),
    resolution: {
      type: 'DAMAGE',
      outcome: { characterName: '邪教徒', rawDamage: 3 },
    },
    resolvedAt: '2026-08-18T00:30:01',
  })

  const rounds = splitByRound?.(aggregate)

  assert.deepEqual(rounds?.map((round) => round.results.map((detail) => detail.id)), [
    [9201, 9202],
    [9200],
  ])
  assert.deepEqual(rounds?.map((round) => diceState.createDiceMessagePresentation(round).title), [
    '争夺手枪',
    '折刀命中邪教徒',
  ])
  assert.deepEqual(rounds?.map((round) => round.semanticResult), [
    '林恩获胜',
    '邪教徒生命-3',
  ])
  assert.deepEqual(rounds?.map((round) => round.summary.totalResult), [
    '林恩获胜',
    '邪教徒生命-3',
  ])
  const opposedRequest = rounds?.[0]
    ? diceState.createDiceMessagePlaybackRequest(0, rounds[0], 'classic')
    : undefined
  assert.equal(opposedRequest?.presentation?.resultValue, '林恩获胜')
})

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

test('uses distinct persistent card tones for a single critical success or fumble', () => {
  const critical = createDiceDebugAggregatePreset('multiplayer-check')
  critical.results = [critical.results[2]!]
  const fumble = createDiceDebugAggregatePreset('multiplayer-check')
  fumble.results = [fumble.results[1]!]
  Object.assign(fumble.results[0]!.resolution?.outcome || {}, { category: 'FUMBLE' })

  assert.equal(diceMessagePresentation(critical)?.tone, 'critical-success')
  assert.equal(diceMessagePresentation(fumble)?.tone, 'fumble')
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
    | ((request: DicePlaybackRequest | null, status: string, hasPendingResults: boolean, hasQueuedRoll?: boolean) => boolean)
    | undefined
  const firstPlayback = { offerContinueAfterComplete: true } as DicePlaybackRequest
  const historicalPlayback = { offerContinueAfterComplete: false } as DicePlaybackRequest

  assert.equal(shouldOfferContinue?.(firstPlayback, 'COMPLETED', false), true)
  assert.equal(shouldOfferContinue?.(historicalPlayback, 'COMPLETED', false), false)
  assert.equal(shouldOfferContinue?.(firstPlayback, 'PENDING', true), false)
  assert.equal(shouldOfferContinue?.(firstPlayback, 'PENDING', true, true), true)
  assert.equal(shouldOfferContinue?.(historicalPlayback, 'PENDING', true, true), false)
})

test('auto plays a live non-user roll after a short idle with character groups staggered', () => {
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
    initialAnimation: request && Reflect.get(request, 'initialAnimation'),
    offerContinueAfterComplete: request?.offerContinueAfterComplete,
  }, {
    id: 9,
    mode: 'play',
    autoPlay: true,
    autoPlayDelayMs: 500,
    initialAnimation: {
      groups: [
        { moduleStart: 0, moduleCount: 1, startDelayMs: 0 },
        { moduleStart: 1, moduleCount: 1, startDelayMs: 280 },
        { moduleStart: 2, moduleCount: 1, startDelayMs: 560 },
      ],
    },
    offerContinueAfterComplete: true,
  })
  assert.deepEqual(request && createAutoPlayPlan?.(request), {
    phase: 'idle',
    delayMs: 500,
  })
})

test('starts the user character first and staggers every other character afterwards', () => {
  const createInitialAnimation = Reflect.get(diceState, 'createDiceInitialAnimationPlan') as
    | ((request: DicePlaybackRequest, primaryGroupIndex?: number) => {
      groups: Array<{ moduleStart: number; moduleCount: number; startDelayMs: number }>
    })
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  const request = diceState.createDiceMessagePlaybackRequest(10, aggregate, 'classic')

  assert.equal(typeof createInitialAnimation, 'function')
  assert.deepEqual(createInitialAnimation?.(request, 1), {
    groups: [
      { moduleStart: 0, moduleCount: 1, startDelayMs: 220 },
      { moduleStart: 1, moduleCount: 1, startDelayMs: 0 },
      { moduleStart: 2, moduleCount: 1, startDelayMs: 440 },
    ],
  })
})

test('uses character timing only for the first performance and leaves replay unchanged', () => {
  const resolveAnimationGroups = Reflect.get(diceState, 'resolveDiceAnimationGroups') as
    | ((request: DicePlaybackRequest, replay: boolean) => unknown)
    | undefined
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  const request = diceState.createDiceMessagePlaybackRequest(10, aggregate, 'classic')
  const initialAnimation = diceState.createDiceInitialAnimationPlan(request, 0)
  const timedRequest = { ...request, initialAnimation }

  assert.equal(typeof resolveAnimationGroups, 'function')
  assert.deepEqual(resolveAnimationGroups?.(timedRequest, false), initialAnimation.groups)
  assert.equal(resolveAnimationGroups?.(timedRequest, true), undefined)
})

test('keeps every module of a plain single-character roll in one animation group', () => {
  const request = createDicePlaybackRequest(
    0,
    createDiceDebugPreset('group'),
    'classic',
  )

  assert.deepEqual(diceState.createDiceInitialAnimationPlan(request), {
    groups: [{ moduleStart: 0, moduleCount: 2, startDelayMs: 0 }],
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

test('creates one tool history entry with its own locator for every dice round', () => {
  const listDiceHistoryEntries = Reflect.get(diceState, 'listDiceHistoryEntriesNewestFirst') as
    | ((messages: GroupMessage[]) => Array<{ messageId: number; aggregate: DiceRollAggregate }>)
    | undefined
  const older = createDiceDebugAggregatePreset('multiplayer-check')
  const latest = createDiceDebugAggregatePreset('opposed-check')
  latest.results = [
    { ...latest.results[0]!, id: 301, roundNo: 1 },
    { ...latest.results[1]!, id: 302, roundNo: 2 },
  ]
  const messages: GroupMessage[] = [
    {
      id: 10, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll',
      content: '', sequenceNo: 10, status: 'completed', diceRoll: older,
    },
    {
      id: 30, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll',
      content: '', sequenceNo: 30, status: 'completed', diceRoll: latest,
    },
  ]

  assert.equal(typeof listDiceHistoryEntries, 'function')
  const entries = listDiceHistoryEntries?.(messages)
  assert.deepEqual(entries?.map((entry) => ({
    messageId: entry.messageId,
    roundNo: entry.aggregate.results[0]?.roundNo,
  })), [
    { messageId: 30, roundNo: 2 },
    { messageId: 30, roundNo: 1 },
    { messageId: 10, roundNo: 1 },
  ])
})

test('filters tool dice history by title, dice category, and result kind', () => {
  const listDiceHistoryEntries = Reflect.get(diceState, 'listDiceHistoryEntriesNewestFirst') as
    | ((messages: GroupMessage[]) => DiceHistoryEntry[])
    | undefined
  const filterDiceHistoryEntries = Reflect.get(diceState, 'filterDiceHistoryEntries') as
    | ((entries: DiceHistoryEntry[], filters: DiceHistoryFilters) => DiceHistoryEntry[])
    | undefined

  const success = createDiceDebugAggregatePreset('multiplayer-check')
  success.summary = {
    ...success.summary,
    id: 100,
    reason: '林恩检查门锁',
    toolName: 'requestCheck',
  }
  success.results = [{
    ...success.results[0]!,
    id: 101,
    summaryId: 100,
    reason: '林恩检查门锁',
    resolvedAt: '2026-08-20T12:31:00Z',
  }]

  const failure = createDiceDebugAggregatePreset('multiplayer-check')
  failure.summary = {
    ...failure.summary,
    id: 200,
    reason: '陈默聆听走廊',
    toolName: 'requestCheck',
  }
  failure.results = [{
    ...failure.results[1]!,
    id: 201,
    summaryId: 200,
    reason: '陈默聆听走廊',
    resolvedAt: '2026-08-20T12:32:00Z',
  }]

  const damage = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '汤普森',
    result: { formula: '1D6', modules: [], result: 5 },
  }])
  damage.summary = {
    ...damage.summary,
    id: 300,
    reason: '短刀命中汤普森',
  }
  damage.results = damage.results.map((detail) => ({
    ...detail,
    id: 301,
    summaryId: 300,
    reason: '短刀命中汤普森',
    resolvedAt: '2026-08-20T12:33:00Z',
  }))

  const messages: GroupMessage[] = [success, failure, damage].map((diceRoll, index) => ({
    id: (index + 1) * 10,
    conversationId: 1,
    speakerType: 'kp',
    messageKind: 'dice_roll',
    content: '',
    sequenceNo: index + 1,
    status: 'completed',
    diceRoll,
  }))

  assert.equal(typeof listDiceHistoryEntries, 'function')
  assert.equal(typeof filterDiceHistoryEntries, 'function')
  const entries = listDiceHistoryEntries?.(messages) || []
  assert.deepEqual(
    filterDiceHistoryEntries?.(entries, { query: '  汤普森 ' }).map((entry) => entry.messageId),
    [30],
  )
  assert.deepEqual(
    filterDiceHistoryEntries?.(entries, { category: '普通检定' }).map((entry) => entry.messageId),
    [20, 10],
  )
  assert.deepEqual(
    filterDiceHistoryEntries?.(entries, { resultKind: 'success' }).map((entry) => entry.messageId),
    [10],
  )
  assert.deepEqual(
    filterDiceHistoryEntries?.(entries, { resultKind: 'failure' }).map((entry) => entry.messageId),
    [20],
  )
  assert.deepEqual(
    filterDiceHistoryEntries?.(entries, { resultKind: 'numeric' }).map((entry) => entry.messageId),
    [30],
  )
})

test('labels a weapon follow-up round as damage instead of its parent attack category', () => {
  const listDiceHistoryEntries = Reflect.get(diceState, 'listDiceHistoryEntriesNewestFirst') as
    | ((messages: GroupMessage[]) => Array<{
      category?: string
      occurredAt?: string
      statusLabel?: string
      aggregate: DiceRollAggregate
    }>)
    | undefined
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.toolName = 'requestMeleeAttack'
  aggregate.results = [
    {
      ...aggregate.results[0]!, id: 401, roundNo: 1, displayType: 'MELEE_ATTACK',
      resolution: { ...aggregate.results[0]!.resolution, type: 'MELEE_ATTACK' },
      resolvedAt: '2026-08-20T12:40:00Z',
    },
    {
      ...aggregate.results[1]!, id: 402, roundNo: 2, displayType: 'DAMAGE',
      resultData: { formula: '1D6', modules: [], result: 5 },
      resolution: { type: 'DAMAGE', outcome: { characterName: '汤普森' } },
      resolvedAt: '2026-08-20T12:41:00Z',
    },
  ]
  const entries = listDiceHistoryEntries?.([{
    id: 40, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll',
    content: '', sequenceNo: 40, status: 'completed', diceRoll: aggregate,
  }])

  assert.deepEqual(entries?.map((entry) => ({
    roundNo: entry.aggregate.results[0]?.roundNo,
    category: entry.category,
    occurredAt: entry.occurredAt,
    statusLabel: entry.statusLabel,
  })), [
    { roundNo: 2, category: '伤害结算', occurredAt: '2026-08-20T12:41:00Z', statusLabel: '-5' },
    { roundNo: 1, category: '对抗检定', occurredAt: '2026-08-20T12:40:00Z', statusLabel: '成功' },
  ])
})

test('shows signed numeric values without a points suffix on value-roll cards', () => {
  const damage = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '林恩', result: { formula: '5', modules: [], result: 5 },
  }])
  const healing = valueRollAggregate('rollHealing', 'HEALING', [{
    name: '林恩', result: { formula: '3', modules: [], result: 3 },
  }])
  const zero = valueRollAggregate('rollHealing', 'HEALING', [{
    name: '林恩', result: { formula: '0', modules: [], result: 0 },
  }])

  assert.equal(diceMessagePresentation(damage)?.statusLabel, '-5')
  assert.equal(diceMessagePresentation(healing)?.statusLabel, '+3')
  assert.equal(diceMessagePresentation(zero)?.statusLabel, '0')
})

test('formats dice history timestamps as local hours and minutes', () => {
  const formatDiceHistoryTime = Reflect.get(diceState, 'formatDiceHistoryTime') as
    | ((value?: string, timeZone?: string) => string)
    | undefined

  assert.equal(typeof formatDiceHistoryTime, 'function')
  assert.equal(formatDiceHistoryTime?.('2026-08-20T12:34:00Z', 'Asia/Shanghai'), '20:34')
  assert.equal(formatDiceHistoryTime?.(undefined, 'Asia/Shanghai'), '')
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

test('preserves critical success and fumble tones for participant effects', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [aggregate.results[2]!, aggregate.results[1]!]
  Object.assign(aggregate.results[1]!.resolution?.outcome || {}, { category: 'FUMBLE' })

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')

  assert.deepEqual(
    request.presentation?.groups.map((group) => ({
      label: group.label,
      outcomeTone: group.outcomeTone,
    })),
    [
      { label: '陈默', outcomeTone: 'fumble' },
      { label: '苏婉', outcomeTone: 'critical-success' },
    ],
  )
})

test('creates local outcome effect plans from the shared participant groups', () => {
  const createPlan = Reflect.get(diceState, 'createDiceOutcomeVfxPlan') as
    | ((presentation: ReturnType<typeof createDiceAggregatePlaybackRequest>['presentation']) => Array<{
      tone: string
      moduleStart: number
      moduleCount: number
      scope: string
    }>)
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [aggregate.results[2]!, aggregate.results[1]!]
  Object.assign(aggregate.results[1]!.resolution?.outcome || {}, { category: 'FUMBLE' })
  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')

  assert.equal(typeof createPlan, 'function')
  assert.deepEqual(createPlan?.(request.presentation), [
    { tone: 'fumble', moduleStart: 0, moduleCount: 1, scope: 'local' },
    { tone: 'critical-success', moduleStart: 1, moduleCount: 1, scope: 'local' },
  ])

  const singlePresentation = {
    ...request.presentation!,
    groups: [request.presentation!.groups[1]!],
  }
  assert.deepEqual(createPlan?.(singlePresentation), [
    { tone: 'critical-success', moduleStart: 1, moduleCount: 1, scope: 'stage' },
  ])
})

test('maps each special participant outcome onto only its corresponding dice modules', () => {
  const createToneMap = Reflect.get(diceState, 'createDiceModuleOutcomeToneMap') as
    | ((presentation: ReturnType<typeof createDiceAggregatePlaybackRequest>['presentation']) => Record<number, string>)
    | undefined
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')
  const presentation = {
    ...request.presentation!,
    groups: [
      { ...request.presentation!.groups[0]!, outcomeTone: 'critical-success' as const, moduleStart: 0, moduleCount: 2 },
      { ...request.presentation!.groups[1]!, outcomeTone: 'failure' as const, moduleStart: 2, moduleCount: 1 },
      { ...request.presentation!.groups[2]!, outcomeTone: 'fumble' as const, moduleStart: 3, moduleCount: 2 },
    ],
  }

  assert.equal(typeof createToneMap, 'function')
  assert.deepEqual(createToneMap?.(presentation), {
    0: 'critical-success',
    1: 'critical-success',
    3: 'fumble',
    4: 'fumble',
  })
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

test('keeps each check difficulty available for the player difficulty badge', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  const difficulties = ['REGULAR', 'HARD', 'EXTREME'] as const
  aggregate.results.forEach((detail, index) => {
    Object.assign(detail.resolution!, { difficulty: difficulties[index] })
  })

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')

  assert.deepEqual(
    request.presentation?.groups.map((group) => ({
      difficulty: group.difficulty,
      difficultyLabel: group.difficultyLabel,
    })),
    [
      { difficulty: 'REGULAR', difficultyLabel: '普通' },
      { difficulty: 'HARD', difficultyLabel: '困难' },
      { difficulty: 'EXTREME', difficultyLabel: '极难' },
    ],
  )
})

test('uses the single-participant check interface for a major-wound CON roll', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.reason = '本重伤CON检定'
  aggregate.summary.status = 'PENDING'
  aggregate.results = [{
    ...aggregate.results[0]!,
    displayType: 'MAJOR_WOUND_CON',
    reason: '本重伤CON检定',
    resolvedAt: undefined,
    resultData: createDiceDebugPreset('normal-percentile'),
    resolution: {
      type: 'MAJOR_WOUND_CON',
      characterName: '本',
    },
  }]
  aggregate.results[0]!.resultData!.result = undefined
  aggregate.results[0]!.resultData!.modules.forEach((module) => {
    module.result = undefined
    module.dice.forEach((die) => { die.value = undefined })
  })

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'multiplayer-check')
  assert.equal(request.presentation?.groups[0]?.checkName, 'CON')
  assert.deepEqual(summary.groups, [
    { label: '本', expression: 'CON', result: '— · 已结算', diceCount: 2 },
  ])
  assert.equal(diceState.resolveDicePlayerMode({ ...request, mode: 'pending' }), 'pending')
})

test('uses the single-participant check interface for an unconscious-recovery CON roll', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.reason = '林恩尝试脱离昏迷'
  aggregate.summary.status = 'PENDING'
  aggregate.summary.toolName = 'systemUnconsciousRecoveryCon'
  aggregate.results = [{
    ...aggregate.results[0]!,
    displayType: 'UNCONSCIOUS_RECOVERY_CON',
    reason: '林恩尝试脱离昏迷',
    resolvedAt: undefined,
    resultData: createDiceDebugPreset('normal-percentile'),
    resolution: {
      type: 'UNCONSCIOUS_RECOVERY_CON',
      characterName: '林恩',
    },
  }]
  aggregate.results[0]!.resultData!.result = undefined
  aggregate.results[0]!.resultData!.modules.forEach((module) => {
    module.result = undefined
    module.dice.forEach((die) => { die.value = undefined })
  })

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'multiplayer-check')
  assert.equal(request.presentation?.groups[0]?.checkName, 'CON')
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: 'CON', result: '— · 已结算', diceCount: 2 },
  ])
  assert.equal(summary.modifierLabel, '单人检定')
  assert.equal(diceState.resolveDicePlayerMode({ ...request, mode: 'pending' }), 'pending')
})

test('uses pending participant display fields instead of the roll title', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.summary.reason = '追踪受伤足迹并观察周围环境'
  aggregate.summary.status = 'PENDING'
  aggregate.results = [aggregate.results[0]!]
  aggregate.results[0]!.reason = aggregate.summary.reason
  aggregate.results[0]!.resolvedAt = undefined
  aggregate.results[0]!.resultData!.result = undefined
  Object.assign(aggregate.results[0]!.resolution!, {
    characterName: '康特·奈尔',
    checkName: '侦查',
    outcome: undefined,
  })

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.deepEqual(summary.groups, [
    { label: '康特·奈尔', expression: '侦查', result: '— · 已结算', diceCount: 2 },
  ])
})

test('uses the participant value presentation for an ordinary single damage die', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results = [{
    ...aggregate.results[0]!,
    displayType: 'DAMAGE',
    resolution: { type: 'DAMAGE', outcome: { characterName: '林恩' } },
  }]
  const createMessagePlayback = Reflect.get(diceState, 'createDiceMessagePlaybackRequest') as
    | ((previousId: number, value: DiceRollAggregate, skin: 'classic') => ReturnType<typeof createDicePlaybackRequest>)
    | undefined

  const request = createMessagePlayback?.(0, aggregate, 'classic')
  const summary = request
    ? createDicePlayerSummary(request.result, request.skin, request.presentation)
    : undefined

  assert.equal(request?.presentation?.kind, 'value-roll')
  assert.deepEqual(summary?.groups, [
    { label: '林恩', expression: '1D100', result: '-27', diceCount: 2 },
  ])
})

test('keeps damage and stun duration in one value-roll presentation', () => {
  const aggregate = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '林恩',
    result: { formula: '1D3', modules: [], result: 2 },
  }])
  aggregate.results.push({
    id: 9311,
    summaryId: 9300,
    roundNo: 1,
    displayOrder: 2,
    displayType: 'STUN_DURATION',
    reason: '电击',
    resultData: {
      formula: '1D6',
      modules: [{
        expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL',
        dice: [{ sides: 6, value: 4, role: 'NORMAL', selected: true }], result: 4,
      }],
      result: 4,
    },
    resolution: { type: 'STUN_DURATION', outcome: { characterName: '林恩' } },
    resolvedAt: '2026-08-20T12:00:00',
  })
  aggregate.semanticResult = '林恩生命-2；林恩被眩晕4回合'

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'value-roll')
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '1D3', result: '-2', diceCount: 0 },
    { label: '林恩', expression: '1D6', result: '4回合', diceCount: 1 },
  ])
})

test('creates a single constant damage result with a negative placeholder card', () => {
  const aggregate = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '林恩',
    result: { formula: '4', modules: [], result: 4 },
  }])

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.kind, 'value-roll')
  assert.deepEqual(request.result.modules.map((module) => ({
    placeholder: module.placeholder,
    diceCount: module.dice.length,
    result: module.result,
  })), [
    { placeholder: true, diceCount: 0, result: 4 },
  ])
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '4', result: '-4', diceCount: 0 },
  ])
})

test('uses the damage window for a damage round created by a weapon tool', () => {
  const aggregate = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '林恩',
    result: { formula: '1D4', modules: [], result: 3 },
  }])
  aggregate.summary.toolName = 'requestMeleeAttack'

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const resolveWindowClass = Reflect.get(diceState, 'createDicePlayerWindowClass') as
    | ((request: DicePlaybackRequest) => string)
    | undefined

  assert.equal(
    resolveWindowClass?.(request),
    'dice-player-window dice-player-window--damage',
  )
})

test('keeps mixed value results aligned when one participant has no physical dice', () => {
  const aggregate = valueRollAggregate('rollSanLoss', 'SAN_LOSS', [
    { name: '林恩', result: { formula: '4', modules: [], result: 4 } },
    {
      name: '陈默',
      result: {
        formula: '1D6 + 1D4',
        modules: [
          {
            expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL',
            dice: [{ sides: 6, value: 4, role: 'NORMAL', selected: true }], result: 4,
          },
          {
            expression: '1D4', diceCount: 1, diceSides: 4, modifier: 'NORMAL',
            dice: [{ sides: 4, value: 2, role: 'NORMAL', selected: true }], result: 2,
          },
        ],
        result: 6,
      },
    },
  ])

  const request = diceState.createDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.deepEqual(request.result.modules.map((module) => module.placeholder === true), [true, false, false])
  assert.deepEqual(request.presentation?.groups.map((group) => ({
    moduleStart: group.moduleStart,
    moduleCount: group.moduleCount,
  })), [
    { moduleStart: 0, moduleCount: 1 },
    { moduleStart: 1, moduleCount: 2 },
  ])
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '4', result: '-4', diceCount: 0 },
    { label: '陈默', expression: '1D6 + 1D4', result: '-6', diceCount: 2 },
  ])
})

test('never offers a roll action when every value result is a constant placeholder', () => {
  const allPlaceholders = diceState.createDiceMessagePlaybackRequest(
    0,
    valueRollAggregate('rollHealing', 'HEALING', [
      { name: '林恩', result: { formula: '2', modules: [], result: 2 } },
      { name: '陈默', result: { formula: '0', modules: [], result: 0 } },
    ]),
    'classic',
  )
  const mixed = diceState.createDiceMessagePlaybackRequest(
    1,
    valueRollAggregate('rollHealing', 'HEALING', [{
      name: '林恩',
      result: createDiceDebugPreset('group'),
    }]),
    'classic',
  )
  const allPlaceholderSummary = createDicePlayerSummary(
    allPlaceholders.result,
    allPlaceholders.skin,
    allPlaceholders.presentation,
  )

  assert.deepEqual(allPlaceholderSummary.groups.map((group) => group.result), ['+2', '0'])
  assert.equal(diceState.shouldShowDiceRollAction('complete', allPlaceholders.result), false)
  assert.equal(diceState.shouldShowDiceRollAction('ready', allPlaceholders.result), false)
  assert.equal(diceState.shouldShowDiceRollAction('complete', mixed.result), true)
})

test('opens single and multiplayer constant values as settled results', () => {
  const single = diceState.createDiceMessagePlaybackRequest(
    0,
    valueRollAggregate('rollDamage', 'DAMAGE', [
      { name: '林恩', result: { formula: '4', modules: [], result: 4 } },
    ]),
    'classic',
  )
  const multiple = diceState.createDiceMessagePlaybackRequest(
    1,
    valueRollAggregate('rollHealing', 'HEALING', [
      { name: '林恩', result: { formula: '2', modules: [], result: 2 } },
      { name: '陈默', result: { formula: '0', modules: [], result: 0 } },
    ]),
    'classic',
  )

  assert.equal(diceState.resolveDicePlayerMode({ ...single, mode: 'play' }), 'settled')
  assert.equal(diceState.resolveDicePlayerMode({ ...multiple, mode: 'pending' }), 'settled')
  assert.deepEqual(
    diceState.createDicePlayerPreparedResult({ ...multiple, mode: 'pending' }),
    multiple.result,
  )
})

test('offers continue immediately for the first live result without physical dice', () => {
  const aggregate = valueRollAggregate('rollDamage', 'DAMAGE', [{
    name: '林恩',
    result: { formula: '4', modules: [], result: 4 },
  }])
  const request = diceState.createIncomingDiceMessagePlaybackRequest(0, aggregate, 'classic')
  const shouldOfferOnOpen = Reflect.get(diceState, 'shouldOfferDiceContinueOnOpen') as
    | ((request: DicePlaybackRequest, status: string, pending: boolean, queued?: boolean) => boolean)
    | undefined

  assert.equal(shouldOfferOnOpen?.(request, 'COMPLETED', false), true)
})

test('keeps mixed constant and physical dice values in their requested playback mode', () => {
  const mixed = diceState.createDiceMessagePlaybackRequest(
    0,
    valueRollAggregate('rollSanLoss', 'SAN_LOSS', [
      { name: '林恩', result: { formula: '4', modules: [], result: 4 } },
      { name: '陈默', result: createDiceDebugPreset('group') },
    ]),
    'classic',
  )

  assert.equal(diceState.resolveDicePlayerMode({ ...mixed, mode: 'pending' }), 'pending')
  assert.equal(diceState.shouldShowDiceRollAction('ready', mixed.result), true)
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
  assert.equal(summary.resultHeadline, '林恩胜出')
  assert.equal(summary.resultDetail, '格斗 · 成功')
  assert.equal(summary.resultTone, 'winner')
  assert.equal(summary.formulaValue, '林恩（格斗） vs 陈默（闪避）')
})

test('summarizes an opposed check with only failed rolls as having no winner', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.totalResult = '林恩失败；陈默失败'
  aggregate.semanticResult = '林恩失败；陈默失败'
  for (const detail of aggregate.results) {
    Object.assign(detail.resolution?.outcome || {}, {
      category: 'FAILURE',
      rank: 'FAILURE',
      winner: false,
    })
  }

  const request = createDiceAggregatePlaybackRequest(2, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(summary.resultValue, '林恩失败；陈默失败')
  assert.equal(summary.resultHeadline, '无人胜出')
  assert.equal(summary.resultDetail, '双方检定均失败')
  assert.equal(summary.resultTone, 'no-winner')
})

test('keeps a drawn opposed check distinct from a no-winner failure', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')
  aggregate.summary.totalResult = '平局'
  aggregate.semanticResult = '平局'
  for (const detail of aggregate.results) {
    Object.assign(detail.resolution?.outcome || {}, { winner: false })
  }

  const request = createDiceAggregatePlaybackRequest(2, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(summary.resultHeadline, '平局')
  assert.equal(summary.resultDetail, '对抗未分出胜负')
  assert.equal(summary.resultTone, 'draw')
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

test('shows the check target before a participant result is revealed', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  const resolution = aggregate.results[0]!.resolution as unknown as Record<string, unknown>
  resolution.targetValue = 30
  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic')
  const group = request.presentation?.groups[0]
  const createGroupResultDisplay = Reflect.get(diceState, 'createDiceGroupResultDisplay') as
    | ((target: typeof group, result: string, revealed: boolean) => { label?: string, value: string } | undefined)
    | undefined

  assert.equal(group?.targetValue, 30)
  assert.deepEqual(createGroupResultDisplay?.(group, '27 · 成功', false), {
    label: '目标',
    value: '30',
  })
  assert.deepEqual(createGroupResultDisplay?.(group, '27 · 成功', true), {
    label: '成功',
    value: '27',
  })
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
