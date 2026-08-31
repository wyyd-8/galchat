import type { DiceResult, DiceRollAggregate, DiceRollDetail, GroupMessage } from '../../api/types'

export const DICE_SKIN_OPTIONS = [
  { value: 'classic', label: '经典' },
  { value: 'galaxy', label: '星穹' },
  { value: 'moonwhite', label: '月白冰晶' },
  { value: 'cinnabar', label: '朱砂鎏金' },
] as const

export type DiceSkin = typeof DICE_SKIN_OPTIONS[number]['value']
export type DicePlayerPhase = 'idle' | 'loading' | 'ready' | 'playing' | 'complete' | 'error'
export type DicePlaybackMode = 'pending' | 'play' | 'settled'
export type DiceGroupRule = 'ANY_SUCCESS' | 'ALL_SUCCESS' | 'SEPARATE'
export type DiceCheckDifficulty = 'REGULAR' | 'HARD' | 'EXTREME'
export type DiceGroupOutcomePhase = 'concealed' | 'individual' | 'highlighted' | 'merging' | 'merged'
export type DicePlayerWindowTone = 'damage' | 'sanity' | 'healing' | 'pushed-check' | 'opposed'
export type DiceOpposedResultTone = 'winner' | 'draw' | 'no-winner'
export type DiceOutcomeTone = 'critical-success' | 'success' | 'failure' | 'fumble' | 'none'
export type DiceSpecialOutcomeTone = Extract<DiceOutcomeTone, 'critical-success' | 'fumble'>
export type DiceMessageTone = 'pending' | 'damage' | 'sanity' | 'healing' | 'pushed-check'
  | 'opposed' | 'critical-success' | 'success' | 'failure' | 'fumble' | 'default'
export interface DiceMessagePresentation {
  title: string
  statusLabel: string
  tone: DiceMessageTone
}
export interface DicePlaybackGroupPresentation {
  label: string
  checkName: string
  difficulty?: DiceCheckDifficulty
  difficultyLabel?: string
  targetValue?: number
  outcomeLabel: string
  outcomeTone: DiceOutcomeTone
  success: boolean
  winner?: boolean
  moduleStart: number
  moduleCount: number
  rollResult?: number
}
export interface DiceGroupResultDisplay {
  label?: string
  value: string
}
export interface DicePlaybackPresentation {
  kind: 'multiplayer-check' | 'opposed-check' | 'value-roll'
  resultLabel: string
  resultValue: string
  resultHeadline?: string
  resultDetail?: string
  resultTone?: DiceOpposedResultTone
  formulaLabel: string
  formulaValue: string
  groups: DicePlaybackGroupPresentation[]
  groupRule?: DiceGroupRule
}
export interface DiceAnimationGroupTiming {
  moduleStart: number
  moduleCount: number
  startDelayMs: number
}
export interface DiceInitialAnimationPlan {
  groups: DiceAnimationGroupTiming[]
}
export interface DicePlaybackRequest {
  id: number
  result: DiceResult
  skin: DiceSkin
  reason?: string
  toolName?: string
  presentation?: DicePlaybackPresentation
  mode?: DicePlaybackMode
  autoPlay?: boolean
  autoPlayDelayMs?: number
  initialAnimation?: DiceInitialAnimationPlan
  offerContinueAfterComplete?: boolean
  windowTone?: DicePlayerWindowTone
}
export interface DicePlayerSummary {
  skinLabel: string
  moduleLabel: string
  diceLabel: string
  modifierLabel: string
  selectionLabel: string
  groups: DicePlayerGroupSummary[]
  equation: string
  resultLabel: string
  resultValue: number | string
  resultHeadline?: string
  resultDetail?: string
  resultTone?: DiceOpposedResultTone
  formulaLabel: string
  formulaValue: string
}
export interface DicePlayerGroupSummary {
  label: string
  expression: string
  result: number | string
  diceCount: number
}
export interface DicePlayerStatus {
  label: string
  hint: string
  revealResult: boolean
  showDieValues: boolean
  actionLabel: string
  actionDisabled: boolean
}

const DICE_SKIN_VALUES = new Set<string>(DICE_SKIN_OPTIONS.map((option) => option.value))

export function resolveDiceSkin(value: unknown): DiceSkin {
  return typeof value === 'string' && DICE_SKIN_VALUES.has(value)
    ? value as DiceSkin
    : 'classic'
}

export function createDicePlayerWindowClass(
  value?: string | Pick<DicePlaybackRequest, 'toolName' | 'windowTone'>,
): string {
  const toolName = typeof value === 'string' ? value : value?.toolName
  const windowTone = typeof value === 'string' ? undefined : value?.windowTone
  if (windowTone) return `dice-player-window dice-player-window--${windowTone}`
  if (toolName === 'rollDamage') return 'dice-player-window dice-player-window--damage'
  if (toolName === 'requestSanCheck' || toolName === 'rollSanLoss') {
    return 'dice-player-window dice-player-window--sanity'
  }
  if (toolName === 'rollHealing') return 'dice-player-window dice-player-window--healing'
  if (toolName === 'requestPushedCheck') return 'dice-player-window dice-player-window--pushed-check'
  return 'dice-player-window'
}

export function createDicePlaybackRequest(
  previousId: number,
  result: DiceResult,
  skin: unknown,
  reason?: string,
  toolName?: string,
): DicePlaybackRequest {
  const snapshot = JSON.parse(JSON.stringify(result)) as DiceResult
  return { id: previousId + 1, result: snapshot, skin: resolveDiceSkin(skin), reason, toolName }
}

const CHECK_OUTCOME_LABELS: Record<string, string> = {
  CRITICAL_SUCCESS: '大成功',
  EXTREME_SUCCESS: '极难成功',
  HARD_SUCCESS: '困难成功',
  SUCCESS: '成功',
  FAILURE: '失败',
  FUMBLE: '大失败',
}

const CHECK_RANK_LABELS: Record<string, string> = {
  CRITICAL: '大成功',
  EXTREME: '极难成功',
  HARD: '困难成功',
  REGULAR: '常规成功',
}

const SUCCESSFUL_CHECK_OUTCOMES = new Set([
  'CRITICAL_SUCCESS',
  'EXTREME_SUCCESS',
  'HARD_SUCCESS',
  'SUCCESS',
])

const PARTICIPANT_CHECK_TYPES = new Set([
  'CHECK',
  'SAN_CHECK',
  'OPPOSED_CHECK',
  'FIREARM_ATTACK',
  'MELEE_ATTACK',
  'MAJOR_WOUND_CON',
  'UNCONSCIOUS_RECOVERY_CON',
])
const CHECK_TYPE_NAMES: Record<string, string> = {
  MAJOR_WOUND_CON: 'CON',
  UNCONSCIOUS_RECOVERY_CON: 'CON',
}
const CHECK_DIFFICULTY_LABELS: Record<DiceCheckDifficulty, string> = {
  REGULAR: '普通',
  HARD: '困难',
  EXTREME: '极难',
}
const VALUE_ROLL_TYPES = new Set([
  'DAMAGE', 'STUN_DURATION', 'SAN_LOSS', 'HEALING',
])

export const DICE_HISTORY_CATEGORY_OPTIONS = [
  '普通检定', '群体检定', '对抗检定', '孤注一掷', '理智检定',
  '理智损失', '伤害结算', '治疗恢复', '其他',
] as const
export type DiceHistoryCategory = typeof DICE_HISTORY_CATEGORY_OPTIONS[number]
export type DiceHistoryResultKind = 'numeric' | 'success' | 'failure' | 'other'

function checkOutcomeLabel(outcome: Record<string, unknown>): string {
  const category = typeof outcome.category === 'string' ? outcome.category : undefined
  if (!category) return '已结算'
  if (category === 'SUCCESS' && typeof outcome.rank === 'string') {
    return CHECK_RANK_LABELS[outcome.rank] || CHECK_OUTCOME_LABELS[category]
  }
  return CHECK_OUTCOME_LABELS[category] || category
}

function checkOutcomeTone(outcome: Record<string, unknown>): DiceOutcomeTone {
  const category = typeof outcome.category === 'string' ? outcome.category : undefined
  const rank = typeof outcome.rank === 'string' ? outcome.rank : undefined
  if (category === 'CRITICAL_SUCCESS' || (category === 'SUCCESS' && rank === 'CRITICAL')) {
    return 'critical-success'
  }
  if (category === 'FUMBLE') return 'fumble'
  if (category && SUCCESSFUL_CHECK_OUTCOMES.has(category)) return 'success'
  if (category === 'FAILURE') return 'failure'
  return 'none'
}

const PERSONALIZED_MESSAGE_TONES: Record<string, DiceMessageTone> = {
  rollDamage: 'damage',
  requestSanCheck: 'sanity',
  rollSanLoss: 'sanity',
  rollHealing: 'healing',
  requestPushedCheck: 'pushed-check',
}

function latestDiceDetails(aggregate: DiceRollAggregate): DiceRollDetail[] {
  const latestRound = Math.max(1, ...aggregate.results.map((detail) => detail.roundNo || 1))
  return aggregate.results.filter((detail) => (detail.roundNo || 1) === latestRound)
}

export function isDiceAggregatePending(aggregate: DiceRollAggregate): boolean {
  const details = latestDiceDetails(aggregate)
  return details.length
    ? details.some((detail) => !detail.resolvedAt)
    : aggregate.summary.status === 'PENDING'
}

function diceRoundResultLine(
  aggregate: DiceRollAggregate,
  value: string | undefined,
  roundNo: number,
): string | undefined {
  if (!value) return undefined
  const lines = value.split(/\r?\n/).map((line) => line.trim())
  const aggregateRounds = new Set(aggregate.results.map((detail) => detail.roundNo || 1))
  const index = aggregateRounds.size === 1 && lines.length === 1 ? 0 : roundNo - 1
  return lines[index] || undefined
}

function diceAggregateForRound(
  aggregate: DiceRollAggregate,
  roundNo: number,
  results: DiceRollDetail[],
): DiceRollAggregate {
  const totalResult = diceRoundResultLine(
    aggregate, aggregate.summary.totalResult, roundNo,
  )
  const semanticResult = diceRoundResultLine(
    aggregate, aggregate.semanticResult, roundNo,
  ) ?? totalResult
  return {
    ...aggregate,
    summary: { ...aggregate.summary, totalResult },
    results,
    semanticResult,
  }
}

export function splitDiceAggregateByRound(
  aggregate: DiceRollAggregate,
): DiceRollAggregate[] {
  const roundNos = [...new Set(aggregate.results.map((detail) => detail.roundNo || 1))]
    .sort((left, right) => left - right)
  return roundNos.map((roundNo) => diceAggregateForRound(
    aggregate,
    roundNo,
    aggregate.results.filter((detail) => (detail.roundNo || 1) === roundNo),
  ))
}

export interface DicePostRollPlaybackPlan {
  playbackAggregate: DiceRollAggregate
  queuedAggregate: DiceRollAggregate | null
  rolledGroupIndex: number
}

export function createDicePostRollPlaybackPlan(
  aggregate: DiceRollAggregate,
  rolledResultId: number,
): DicePostRollPlaybackPlan {
  const rolledResult = aggregate.results.find((detail) => detail.id === rolledResultId)
  if (!rolledResult) throw new Error('刷新后找不到刚完成的掷骰结果')
  const rolledRound = rolledResult.roundNo || 1
  const playbackResults = aggregate.results.filter((detail) => (
    (detail.roundNo || 1) === rolledRound && Boolean(detail.resolvedAt)
  ))
  if (!playbackResults.length) throw new Error('刚完成的掷骰结果尚未结算')
  const orderedPlaybackResults = [...playbackResults].sort(
    (left, right) => (left.displayOrder || 0) - (right.displayOrder || 0) || left.id - right.id,
  )
  const rolledGroupIndex = orderedPlaybackResults.findIndex((detail) => detail.id === rolledResultId)
  if (rolledGroupIndex < 0) throw new Error('刚完成的掷骰结果不在当前播放轮次中')
  const queuedResults = aggregate.results.filter((detail) => {
    const round = detail.roundNo || 1
    return round > rolledRound || (round === rolledRound && !detail.resolvedAt)
  })
  return {
    playbackAggregate: diceAggregateForRound(
      aggregate, rolledRound, playbackResults,
    ),
    queuedAggregate: queuedResults.length
      ? { ...aggregate, summary: { ...aggregate.summary }, results: queuedResults }
      : null,
    rolledGroupIndex,
  }
}

export function createDiceMessagePresentation(
  aggregate: DiceRollAggregate,
): DiceMessagePresentation {
  const pending = isDiceAggregatePending(aggregate)
  const title = latestDiceDetails(aggregate)[0]?.reason
    || aggregate.summary.reason
    || '掷骰判定'
  if (pending) {
    return {
      title,
      statusLabel: '未投掷',
      tone: 'pending',
    }
  }

  const personalizedTone = aggregate.summary.toolName
    ? PERSONALIZED_MESSAGE_TONES[aggregate.summary.toolName]
    : undefined
  const details = latestDiceDetails(aggregate)
  const aggregateResult = aggregate.semanticResult || aggregate.summary.totalResult || '已完成'
  const opposed = details.length > 0
    && details.every((detail) => detail.resolution?.type === 'OPPOSED_CHECK')
  const groupRule = details.map((detail) => detail.resolution?.groupRule)
    .find((rule): rule is DiceGroupRule => Boolean(rule))
  const categories = details.map((detail) => detail.resolution?.outcome?.category)
    .filter((category): category is string => typeof category === 'string')
  let tone: DiceMessageTone = personalizedTone || 'default'
  if (!personalizedTone) {
    if (opposed) {
      tone = 'opposed'
    } else if (details.length === 1 && categories.length === 1) {
      const singleTone = checkOutcomeTone(details[0]!.resolution?.outcome || {})
      tone = singleTone === 'none' ? 'default' : singleTone
    } else if (details.length > 1 && groupRule && groupRule !== 'SEPARATE'
        && categories.length === details.length) {
      const succeeded = groupRule === 'ALL_SUCCESS'
        ? categories.every((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
        : categories.some((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
      tone = succeeded ? 'success' : 'failure'
    }
  }
  let statusLabel = '已投掷'
  const valueDetails = details.filter((detail) => VALUE_ROLL_TYPES.has(
    detail.resolution?.type || detail.displayType || '',
  ))
  if (valueDetails.length === 1 && details.length === 1) {
    statusLabel = signedValue(
      valueDetails[0]!.resolution?.type || valueDetails[0]!.displayType,
      valueDetails[0]!.resultData?.result,
    )
  } else if (valueDetails.length === details.length && details.length > 1) {
    statusLabel = '分别结果'
  } else if (opposed) {
    statusLabel = aggregateResult
  } else if (details.length === 1 && details[0]!.resolution?.outcome) {
    statusLabel = checkOutcomeLabel(details[0]!.resolution!.outcome!)
  } else if (details.length > 1 && groupRule && groupRule !== 'SEPARATE'
      && categories.length === details.length) {
    const succeeded = groupRule === 'ALL_SUCCESS'
      ? categories.every((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
      : categories.some((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
    statusLabel = succeeded ? '成功' : '失败'
  } else if (details.length > 1 && groupRule === 'SEPARATE') {
    statusLabel = '分别结果'
  }
  return {
    title,
    statusLabel,
    tone,
  }
}

export function shouldOfferDiceContinue(
  request: DicePlaybackRequest | null,
  summaryStatus: string,
  hasPendingResults: boolean,
  hasQueuedRoll = false,
): boolean {
  return request?.offerContinueAfterComplete === true
    && (hasQueuedRoll || (summaryStatus === 'COMPLETED' && !hasPendingResults))
}

export function shouldOfferDiceContinueOnOpen(
  request: DicePlaybackRequest,
  summaryStatus: string,
  hasPendingResults: boolean,
  hasQueuedRoll = false,
): boolean {
  return resolveDicePlayerMode(request) === 'settled'
    && shouldOfferDiceContinue(request, summaryStatus, hasPendingResults, hasQueuedRoll)
}

export function createDiceAutoPlayPlan(
  request: DicePlaybackRequest,
): { phase: 'idle' | 'ready'; delayMs: number } {
  const delayMs = request.autoPlay
    ? Math.max(0, request.autoPlayDelayMs || 0)
    : 0
  return {
    phase: delayMs > 0 ? 'idle' : 'ready',
    delayMs,
  }
}

export function createDiceInitialAnimationPlan(
  request: DicePlaybackRequest,
  primaryGroupIndex?: number,
): DiceInitialAnimationPlan {
  const sourceGroups = request.presentation?.groups.length
    ? request.presentation.groups.map((group, sourceIndex) => ({
        sourceIndex,
        moduleStart: group.moduleStart,
        moduleCount: group.moduleCount,
      }))
    : [{ sourceIndex: 0, moduleStart: 0, moduleCount: request.result.modules.length }]
  const physicalGroups = sourceGroups.filter((group) => request.result.modules
    .slice(group.moduleStart, group.moduleStart + group.moduleCount)
    .some((module) => module.dice.length > 0))
  if (!physicalGroups.length) return { groups: [] }

  const primary = primaryGroupIndex === undefined
    ? undefined
    : physicalGroups.find((group) => group.sourceIndex === primaryGroupIndex)
  if (!primary) {
    const interval = physicalGroups.length <= 1
      ? 0
      : Math.round(Math.max(140, Math.min(280, 1_000 / (physicalGroups.length - 1))))
    return {
      groups: physicalGroups.map((group, index) => ({
        moduleStart: group.moduleStart,
        moduleCount: group.moduleCount,
        startDelayMs: index * interval,
      })),
    }
  }

  const otherGroups = physicalGroups.filter((group) => group !== primary)
  const interval = otherGroups.length
    ? Math.round(Math.max(120, Math.min(220, 500 / otherGroups.length)))
    : 0
  let otherIndex = 0
  return {
    groups: physicalGroups.map((group) => ({
      moduleStart: group.moduleStart,
      moduleCount: group.moduleCount,
      startDelayMs: group === primary ? 0 : ++otherIndex * interval,
    })),
  }
}

export function resolveDiceAnimationGroups(
  request: DicePlaybackRequest,
  replay: boolean,
): DiceAnimationGroupTiming[] | undefined {
  return replay ? undefined : request.initialAnimation?.groups
}

export function createDicePlayerInitialState(
  mode: DicePlaybackMode,
  presentationKind?: DicePlaybackPresentation['kind'],
  groupRule?: DiceGroupRule,
): { phase: DicePlayerPhase; groupOutcomePhase: DiceGroupOutcomePhase } {
  if (mode === 'pending') return { phase: 'ready', groupOutcomePhase: 'concealed' }
  if (mode === 'play') return { phase: 'loading', groupOutcomePhase: 'concealed' }
  return {
    phase: 'complete',
    groupOutcomePhase: presentationKind === 'multiplayer-check' && groupRule === 'SEPARATE'
      ? 'individual'
      : presentationKind ? 'merged' : 'individual',
  }
}

export function createStandbyDiceResult(result: DiceResult): DiceResult {
  return {
    ...result,
    result: 0,
    modules: result.modules.map((module) => {
      let selectedTens = false
      return {
        ...module,
        result: 0,
        dice: module.dice.map((die) => {
          const percentile = die.role === 'PERCENTILE_ONES' || die.role === 'PERCENTILE_TENS'
          const selected = die.role === 'PERCENTILE_TENS'
            ? !selectedTens
            : die.selected
          if (die.role === 'PERCENTILE_TENS' && selected) selectedTens = true
          return {
            ...die,
            value: percentile ? 0 : 1,
            selected,
          }
        }),
      }
    }),
  }
}

export function createDicePlayerPreparedResult(request: DicePlaybackRequest): DiceResult {
  return resolveDicePlayerMode(request) === 'pending'
    ? createStandbyDiceResult(request.result)
    : request.result
}

export function resolveDicePlayerMode(request: DicePlaybackRequest): DicePlaybackMode {
  const hasPhysicalDice = request.result.modules.some((module) => module.dice.length > 0)
  return hasPhysicalDice ? request.mode || 'play' : 'settled'
}

interface DiceMessageReference {
  summaryId: number
  roundNos: number[]
}

function parseDiceMessageReference(content: string): DiceMessageReference | undefined {
  try {
    const parsed = JSON.parse(content) as { summaryId?: unknown; roundNos?: unknown }
    const summaryId = typeof parsed.summaryId === 'number'
      && Number.isInteger(parsed.summaryId)
      && parsed.summaryId > 0
      ? parsed.summaryId
      : undefined
    if (!summaryId) return undefined
    const roundNos = Array.isArray(parsed.roundNos)
      ? parsed.roundNos.filter((round): round is number => (
        typeof round === 'number' && Number.isInteger(round) && round > 0
      ))
      : []
    return { summaryId, roundNos }
  } catch {
    return undefined
  }
}

export function parseDiceMessageSummaryId(content: string): number | undefined {
  return parseDiceMessageReference(content)?.summaryId
}

export async function hydrateDiceMessage(
  message: GroupMessage,
  loadAggregate: (summaryId: number) => Promise<DiceRollAggregate>,
): Promise<GroupMessage> {
  if (message.messageKind !== 'dice_roll' || message.diceRoll) return message
  const reference = parseDiceMessageReference(message.content)
  if (!reference) return message
  const loaded = await loadAggregate(reference.summaryId)
  const diceRoundNos = reference.roundNos.length
    ? reference.roundNos
    : [...new Set(loaded.results.map((detail) => detail.roundNo || 1))]
  const rounds = new Set(diceRoundNos)
  const diceRoll = rounds.size
    ? { ...loaded, results: loaded.results.filter((detail) => rounds.has(detail.roundNo || 1)) }
    : loaded
  return { ...message, content: '', diceRoll, diceRoundNos }
}

export function listDiceMessagesNewestFirst(messages: GroupMessage[]): GroupMessage[] {
  return [...messages].reverse().filter((message) => (
    message.messageKind === 'dice_roll' && Boolean(message.diceRoll)
  ))
}

export interface DiceHistoryEntry {
  messageId: number
  aggregate: DiceRollAggregate
  title: string
  statusLabel: string
  tone: DiceMessageTone
  category: DiceHistoryCategory
  resultKind: DiceHistoryResultKind
  occurredAt?: string
}

export interface DiceHistoryFilters {
  query?: string
  category?: DiceHistoryCategory | ''
  resultKind?: DiceHistoryResultKind | ''
}

function diceHistoryResolutionTypes(aggregate: DiceRollAggregate): string[] {
  return latestDiceDetails(aggregate).map((detail) => (
    detail.resolution?.type || detail.displayType || ''
  )).filter(Boolean)
}

function diceHistoryCategory(aggregate: DiceRollAggregate): DiceHistoryCategory {
  const types = diceHistoryResolutionTypes(aggregate)
  if (types.length && types.every((type) => type === 'DAMAGE' || type === 'STUN_DURATION')) {
    return '伤害结算'
  }
  if (types.length && types.every((type) => type === 'SAN_LOSS')) return '理智损失'
  if (types.length && types.every((type) => type === 'HEALING')) return '治疗恢复'
  if (types.length && types.every((type) => type === 'OPPOSED_CHECK' || type === 'MELEE_ATTACK')) {
    return '对抗检定'
  }
  if (types.length && types.every((type) => type === 'SAN_CHECK')) return '理智检定'

  const toolCategory: Partial<Record<string, DiceHistoryCategory>> = {
    requestCheck: '普通检定',
    requestGroupCheck: '群体检定',
    requestOpposedCheck: '对抗检定',
    requestPushedCheck: '孤注一掷',
    requestSanCheck: '理智检定',
    rollSanLoss: '理智损失',
    rollDamage: '伤害结算',
    rollHealing: '治疗恢复',
    requestFirearmAttack: '普通检定',
    requestMeleeAttack: '对抗检定',
  }
  const toolName = aggregate.summary.toolName
  if (toolName && toolCategory[toolName]) return toolCategory[toolName]!
  if (types.length && types.every((type) => type === 'CHECK' || type === 'FIREARM_ATTACK')) {
    return '普通检定'
  }
  return '其他'
}

function diceHistoryResultKind(aggregate: DiceRollAggregate): DiceHistoryResultKind {
  const types = diceHistoryResolutionTypes(aggregate)
  if (types.length && types.every((type) => VALUE_ROLL_TYPES.has(type))) return 'numeric'
  const tone = createDiceMessagePresentation(aggregate).tone
  if (tone === 'critical-success' || tone === 'success') return 'success'
  if (tone === 'failure' || tone === 'fumble') return 'failure'
  return 'other'
}

function diceHistoryOccurredAt(aggregate: DiceRollAggregate, message: GroupMessage): string | undefined {
  const detailTimes = aggregate.results.flatMap((detail) => [
    detail.resolvedAt, detail.updatedAt, detail.createdAt,
  ]).filter((value): value is string => Boolean(value))
  return detailTimes.sort().at(-1)
    || aggregate.summary.updatedAt
    || aggregate.summary.createdAt
    || message.createdAt
}

export function listDiceHistoryEntriesNewestFirst(messages: GroupMessage[]): DiceHistoryEntry[] {
  return listDiceMessagesNewestFirst(messages).flatMap((message) => (
    splitDiceAggregateByRound(message.diceRoll!).reverse().map((aggregate) => {
      const presentation = createDiceMessagePresentation(aggregate)
      return {
        messageId: message.id,
        aggregate,
        title: presentation.title,
        statusLabel: presentation.statusLabel,
        tone: presentation.tone,
        category: diceHistoryCategory(aggregate),
        resultKind: diceHistoryResultKind(aggregate),
        occurredAt: diceHistoryOccurredAt(aggregate, message),
      }
    })
  ))
}

function normalizedDiceHistoryQuery(value?: string): string {
  return (value || '').normalize('NFKC').trim().toLocaleLowerCase('zh-CN')
}

export function filterDiceHistoryEntries(
  entries: DiceHistoryEntry[],
  filters: DiceHistoryFilters,
): DiceHistoryEntry[] {
  const query = normalizedDiceHistoryQuery(filters.query)
  return entries.filter((entry) => (
    (!query || normalizedDiceHistoryQuery(entry.title).includes(query))
      && (!filters.category || entry.category === filters.category)
      && (!filters.resultKind || entry.resultKind === filters.resultKind)
  ))
}

export function formatDiceHistoryTime(
  value?: string,
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date).filter((part) => part.type !== 'literal')
    .map((part) => [part.type, part.value]))
  return `${parts.hour}:${parts.minute}`
}

export function findDiceMessageElement<T extends { dataset: { messageId?: string } }>(
  elements: Iterable<T>,
  messageId: number,
): T | undefined {
  return Array.from(elements).find((element) => element.dataset.messageId === String(messageId))
}

function aggregateGroup(
  detail: DiceRollDetail & { resultData: DiceResult },
  index: number,
  moduleStart: number,
): DicePlaybackGroupPresentation {
  const outcome = detail.resolution?.outcome || {}
  const rule = detail.resolution?.rule || {}
  const resolutionType = detail.resolution?.type || detail.displayType
  const ruleCheckName = typeof rule.checkName === 'string'
    ? rule.checkName
    : typeof rule.skillName === 'string'
      ? rule.skillName
      : resolutionType ? CHECK_TYPE_NAMES[resolutionType] : undefined
  const targetName = typeof outcome.targetCharacterName === 'string'
    ? outcome.targetCharacterName
    : typeof rule.targetCharacterName === 'string' ? rule.targetCharacterName : undefined
  const combatCheckName = resolutionType === 'FIREARM_ATTACK' && targetName
    ? `${ruleCheckName || '射击'} → ${targetName}`
    : ruleCheckName
  const savedDifficulty = detail.resolution?.difficulty ?? rule.difficulty
  const difficulty = typeof savedDifficulty === 'string'
    && savedDifficulty in CHECK_DIFFICULTY_LABELS
    ? savedDifficulty as DiceCheckDifficulty
    : undefined
  const targetValue = detail.resolution?.targetValue
  return {
    label: typeof outcome.characterName === 'string'
      ? outcome.characterName
      : detail.resolution?.characterName
        ? detail.resolution.characterName
      : detail.reason || `参与者 ${index + 1}`,
    checkName: typeof outcome.checkName === 'string'
      ? outcome.checkName
      : detail.resolution?.checkName || combatCheckName || detail.displayType || '检定',
    difficulty,
    difficultyLabel: difficulty ? CHECK_DIFFICULTY_LABELS[difficulty] : undefined,
    targetValue: typeof targetValue === 'number' && Number.isFinite(targetValue)
      ? targetValue
      : undefined,
    outcomeLabel: checkOutcomeLabel(outcome),
    outcomeTone: checkOutcomeTone(outcome),
    success: typeof outcome.category === 'string'
      && SUCCESSFUL_CHECK_OUTCOMES.has(outcome.category),
    moduleStart,
    moduleCount: detail.resultData.modules.length,
    rollResult: detail.resultData.result,
  }
}

export function createDiceGroupResultDisplay(
  group: DicePlaybackGroupPresentation | undefined,
  result: number | string,
  revealed: boolean,
): DiceGroupResultDisplay | undefined {
  if (revealed) {
    const value = String(result)
    const separatorIndex = value.indexOf(' · ')
    if (separatorIndex < 0) return { value }
    return {
      label: value.slice(separatorIndex + 3),
      value: value.slice(0, separatorIndex),
    }
  }
  if (typeof group?.targetValue !== 'number') return undefined
  return { label: '目标', value: String(group.targetValue) }
}

function signedValue(type: string | undefined, value: number | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '?'
  if (type === 'STUN_DURATION') return `${value}回合`
  if (value === 0) return '0'
  return `${type === 'HEALING' ? '+' : '-'}${Math.abs(value)}`
}

function valuePlaceholder(
  detail: DiceRollDetail & { resultData: DiceResult },
): DiceResult['modules'][number] {
  return {
    expression: detail.resultData.formula,
    diceCount: 0,
    diceSides: 0,
    modifier: 'NORMAL',
    dice: [],
    result: detail.resultData.result,
    placeholder: true,
  }
}

function createDiceValuePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  details: Array<DiceRollDetail & { resultData: DiceResult }>,
  skin: unknown,
): DicePlaybackRequest {
  const modules: DiceResult['modules'] = []
  const groups = details.map((detail, index) => {
    const moduleStart = modules.length
    const detailModules = detail.resultData.modules.length
      ? detail.resultData.modules
      : [valuePlaceholder(detail)]
    modules.push(...detailModules)
    const outcome = detail.resolution?.outcome || {}
    return {
      label: typeof outcome.characterName === 'string'
        ? outcome.characterName
        : detail.reason || `参与者 ${index + 1}`,
      checkName: detail.resultData.formula,
      outcomeLabel: signedValue(detail.resolution?.type || detail.displayType, detail.resultData.result),
      outcomeTone: 'none' as const,
      success: false,
      moduleStart,
      moduleCount: detailModules.length,
      rollResult: detail.resultData.result,
    }
  })
  const result: DiceResult = {
    formula: details.map((detail) => detail.resultData.formula).join(' / '),
    modules,
  }
  return {
    id: previousId + 1,
    result: JSON.parse(JSON.stringify(result)) as DiceResult,
    skin: resolveDiceSkin(skin),
    reason: aggregate.summary.reason,
    toolName: aggregate.summary.toolName,
    presentation: {
      kind: 'value-roll',
      resultLabel: '分别结果',
      resultValue: aggregate.semanticResult || aggregate.summary.totalResult || '已完成',
      formulaLabel: '参与者',
      formulaValue: `${groups.length} 人参与`,
      groups,
      groupRule: 'SEPARATE',
    },
  }
}

export function createGroupOutcomeVisibility(
  phase: DiceGroupOutcomePhase,
  groupRule?: DiceGroupRule,
) {
  const permitsAggregate = groupRule !== 'SEPARATE'
  const revealsAggregate = permitsAggregate && (phase === 'merging' || phase === 'merged')
  return {
    showIndividuals: true,
    revealIndividualResults: phase !== 'concealed',
    showTransition: revealsAggregate,
    showFinal: revealsAggregate,
    highlightWinner: permitsAggregate && (phase === 'highlighted' || revealsAggregate),
  }
}

export function createDiceOutcomeVfxPlan(presentation?: DicePlaybackPresentation) {
  if (!presentation || presentation.kind === 'value-roll') return []
  const scope = presentation.groups.length === 1 ? 'stage' as const : 'local' as const
  return presentation.groups.flatMap((group) => (
    group.outcomeTone === 'critical-success' || group.outcomeTone === 'fumble'
      ? [{
          tone: group.outcomeTone,
          moduleStart: group.moduleStart,
          moduleCount: group.moduleCount,
          scope,
        }]
      : []
  ))
}

export function createDiceModuleOutcomeToneMap(
  presentation?: DicePlaybackPresentation,
): Record<number, DiceSpecialOutcomeTone> {
  const tones: Record<number, DiceSpecialOutcomeTone> = {}
  if (!presentation || presentation.kind === 'value-roll') return tones
  presentation.groups.forEach((group) => {
    if (group.outcomeTone !== 'critical-success' && group.outcomeTone !== 'fumble') return
    for (let offset = 0; offset < group.moduleCount; offset += 1) {
      tones[group.moduleStart + offset] = group.outcomeTone
    }
  })
  return tones
}

export function createDiceAggregatePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  skin: unknown,
  groupRule?: DiceGroupRule,
  toolName?: string,
): DicePlaybackRequest {
  const resolved = aggregate.results
    .filter((detail): detail is DiceRollDetail & { resultData: DiceResult } => Boolean(detail.resultData))
  if (!resolved.length) throw new Error('这组检定还没有可播放的掷骰结果')
  const latestRound = Math.max(...resolved.map((detail) => detail.roundNo || 1))
  const details = resolved
    .filter((detail) => (detail.roundNo || 1) === latestRound)
    .sort((left, right) => (left.displayOrder || 0) - (right.displayOrder || 0) || left.id - right.id)
  const opposed = details.every((detail) => detail.resolution?.type === 'OPPOSED_CHECK')
    || (details.length > 1 && details.every((detail) => (
      detail.resolution?.type || detail.displayType
    ) === 'MELEE_ATTACK'))
  const effectiveGroupRule = groupRule
    ?? details.map((detail) => detail.resolution?.groupRule).find((rule): rule is DiceGroupRule =>
      rule === 'ANY_SUCCESS' || rule === 'ALL_SUCCESS' || rule === 'SEPARATE')
    ?? 'SEPARATE'
  const aggregateResult = aggregate.semanticResult || aggregate.summary.totalResult || '已完成'
  const winnerName = opposed ? aggregateResult.match(/^(.+?)获胜(?:；|$)/)?.[1] : undefined
  let moduleStart = 0
  const groups = details.map((detail, index) => {
    const group = aggregateGroup(detail, index, moduleStart)
    moduleStart += detail.resultData.modules.length
    const outcomeWinner = detail.resolution?.outcome?.winner
    return opposed
      ? {
          ...group,
          winner: typeof outcomeWinner === 'boolean'
            ? outcomeWinner
            : group.label === winnerName,
        }
      : group
  })
  const checkNames = [...new Set(groups.map((group) => group.checkName))]
  const groupSucceeded = effectiveGroupRule === 'ALL_SUCCESS'
    ? groups.every((group) => group.success)
    : groups.some((group) => group.success)
  const resultValue = opposed
    ? aggregateResult
    : effectiveGroupRule === 'SEPARATE'
      ? aggregateResult
      : groupSucceeded ? '成功' : '失败'
  const winningGroup = opposed ? groups.find((group) => group.winner) : undefined
  const opposedDraw = opposed && /^平局(?:；|$)/.test(aggregateResult)
  const opposedResultSummary = winningGroup
    ? {
        resultHeadline: `${winningGroup.label}胜出`,
        resultDetail: `${winningGroup.checkName} · ${winningGroup.outcomeLabel}`,
        resultTone: 'winner' as const,
      }
    : opposedDraw
      ? {
          resultHeadline: '平局',
          resultDetail: '对抗未分出胜负',
          resultTone: 'draw' as const,
        }
      : opposed
        ? {
            resultHeadline: '无人胜出',
            resultDetail: groups.every((group) => !group.success)
              ? `${groups.length === 2 ? '双方' : '所有参与者'}检定均失败`
              : '对抗未分出胜负',
            resultTone: 'no-winner' as const,
          }
        : undefined
  const formulaValue = opposed
    ? groups.map((group) => `${group.label}（${group.checkName}）`).join(' vs ')
    : `${groups.length} 人参与 · ${checkNames.join(' / ')} · ${effectiveGroupRule === 'ALL_SUCCESS'
      ? '全部成功才通过'
      : effectiveGroupRule === 'ANY_SUCCESS' ? '任一成功即通过' : '分别展示'}`
  const result: DiceResult = {
    formula: details.map((detail) => detail.resultData.formula).join(' / '),
    modules: details.flatMap((detail) => detail.resultData.modules),
  }
  return {
    id: previousId + 1,
    result: JSON.parse(JSON.stringify(result)) as DiceResult,
    skin: resolveDiceSkin(skin),
    reason: aggregate.summary.reason,
    toolName: toolName ?? aggregate.summary.toolName,
    presentation: {
      kind: opposed ? 'opposed-check' : 'multiplayer-check',
      resultLabel: opposed
        ? '对抗结果'
        : effectiveGroupRule === 'ALL_SUCCESS'
          ? '全部成功'
          : effectiveGroupRule === 'ANY_SUCCESS' ? '任一成功' : '分别结果',
      resultValue,
      ...opposedResultSummary,
      formulaLabel: opposed ? '对抗双方' : '检定项目',
      formulaValue,
      groups,
      groupRule: opposed ? undefined : effectiveGroupRule,
    },
  }
}

export function createDiceMessagePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  skin: unknown,
): DicePlaybackRequest {
  const latestRound = Math.max(1, ...aggregate.results.map((detail) => detail.roundNo || 1))
  const details = aggregate.results
    .filter((detail): detail is DiceRollDetail & { resultData: DiceResult } => (
      (detail.roundNo || 1) === latestRound && Boolean(detail.resultData)
    ))
    .sort((left, right) => (left.displayOrder || 0) - (right.displayOrder || 0) || left.id - right.id)
  if (!details.length) throw new Error('这条骰子消息没有可显示的骰子')

  const participantCheck = details.every((detail) => PARTICIPANT_CHECK_TYPES.has(
    detail.resolution?.type || detail.displayType || '',
  ))
  const valueRoll = details.every((detail) => VALUE_ROLL_TYPES.has(
    detail.resolution?.type || detail.displayType || '',
  ))
  const request = valueRoll
    ? createDiceValuePlaybackRequest(previousId, aggregate, details, skin)
    : participantCheck || details.length > 1
      ? createDiceAggregatePlaybackRequest(previousId, aggregate, skin)
      : createDicePlaybackRequest(
          previousId,
          details[0]!.resultData,
          skin,
          aggregate.summary.reason || details[0]!.reason,
          aggregate.summary.toolName,
        )
  const resolutionTypes = details.map((detail) => (
    detail.resolution?.type || detail.displayType || ''
  ))
  const windowTone: DicePlayerWindowTone | undefined = resolutionTypes.every((type) => (
    type === 'DAMAGE' || type === 'STUN_DURATION'
  ))
    ? 'damage'
    : resolutionTypes.every((type) => type === 'SAN_CHECK' || type === 'SAN_LOSS')
      ? 'sanity'
      : resolutionTypes.every((type) => type === 'HEALING')
        ? 'healing'
        : request.presentation?.kind === 'opposed-check'
          ? 'opposed'
          : undefined
  return windowTone ? { ...request, windowTone } : request
}

export function createIncomingDiceMessagePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  skin: unknown,
): DicePlaybackRequest {
  const pending = isDiceAggregatePending(aggregate)
  const request: DicePlaybackRequest = {
    ...createDiceMessagePlaybackRequest(previousId, aggregate, skin),
    mode: pending ? 'pending' : 'play',
    autoPlay: !pending,
    autoPlayDelayMs: pending ? undefined : 500,
    offerContinueAfterComplete: true,
  }
  return pending
    ? request
    : { ...request, initialAnimation: createDiceInitialAnimationPlan(request) }
}

export function createDicePlayerSummary(
  result: DiceResult,
  skin: DiceSkin,
  presentation?: DicePlaybackPresentation,
): DicePlayerSummary {
  const skinLabel = DICE_SKIN_OPTIONS.find((option) => option.value === skin)!.label
  const diceCount = result.modules.reduce((total, module) => total + module.dice.length, 0)
  const selectedCount = result.modules.reduce(
    (total, module) => total + module.dice.filter((die) => die.selected).length,
    0,
  )
  const discardedCount = diceCount - selectedCount
  const modifiers = new Set(result.modules.map((module) => module.modifier || 'NORMAL'))
  const modifierLabel = presentation?.kind === 'opposed-check'
    ? '对抗检定'
    : presentation?.kind === 'value-roll'
      ? presentation.groups.length === 1 ? '单人掷骰' : '多人掷骰'
    : presentation?.kind === 'multiplayer-check'
      ? presentation.groups.length === 1 ? '单人检定' : '多人检定'
      : modifiers.size === 1
    ? ({
        NORMAL: '常规判定',
        ADVANTAGE: '奖励骰',
        DOUBLE_ADVANTAGE: '双奖励骰',
        DISADVANTAGE: '惩罚骰',
        DOUBLE_DISADVANTAGE: '双惩罚骰',
      } as Record<string, string>)[modifiers.values().next().value as string] || '特殊判定'
    : '组合判定'
  const settledResult = typeof result.result === 'number' && Number.isFinite(result.result)
    ? result.result
    : '—'
  return {
    skinLabel,
    moduleLabel: `${result.modules.length} 组判定`,
    diceLabel: `${diceCount} 颗骰子`,
    modifierLabel,
    selectionLabel: discardedCount > 0
      ? `${selectedCount} 颗计入 · ${discardedCount} 颗舍弃`
      : `${diceCount} 颗全部计入`,
    groups: presentation
      ? presentation.groups.map((group) => {
          const modules = result.modules.slice(group.moduleStart, group.moduleStart + group.moduleCount)
          return {
            label: group.label,
            expression: group.checkName,
            result: presentation.kind === 'value-roll'
              ? group.outcomeLabel
              : `${Number.isFinite(group.rollResult) ? group.rollResult : '—'} · ${group.outcomeLabel}`,
            diceCount: modules.reduce((total, module) => total + module.dice.length, 0),
          }
        })
      : result.modules.map((module, index) => ({
          label: `第 ${index + 1} 组`,
          expression: module.expression,
          result: Number.isFinite(module.result) ? module.result as number : '—',
          diceCount: module.dice.length,
        })),
    equation: `${result.formula} = ${settledResult}`,
    resultLabel: presentation?.resultLabel || '最终结果',
    resultValue: presentation?.resultValue || settledResult,
    ...(presentation?.resultHeadline ? {
      resultHeadline: presentation.resultHeadline,
      resultDetail: presentation.resultDetail,
      resultTone: presentation.resultTone,
    } : {}),
    formulaLabel: presentation?.formulaLabel || '判定公式',
    formulaValue: presentation?.formulaValue || result.formula,
  }
}

export function createDicePlayerStatus(phase: DicePlayerPhase): DicePlayerStatus {
  return {
    idle: { label: '等待投掷', hint: '准备开始这次判定', revealResult: false, showDieValues: false, actionLabel: '掷骰', actionDisabled: true },
    loading: { label: '正在准备', hint: '加载骰子与判定桌面', revealResult: false, showDieValues: false, actionLabel: '准备骰子', actionDisabled: true },
    ready: { label: '准备就绪', hint: '骰子正在待机，点击掷骰开始判定', revealResult: false, showDieValues: false, actionLabel: '掷骰', actionDisabled: false },
    playing: { label: '正在投掷', hint: '结果将在骰子停稳后揭晓', revealResult: false, showDieValues: false, actionLabel: '正在掷骰', actionDisabled: true },
    complete: { label: '判定完成', hint: '最终点数已锁定', revealResult: true, showDieValues: true, actionLabel: '重放动画', actionDisabled: false },
    error: { label: '播放中断', hint: '已保留结果，可以重新准备', revealResult: true, showDieValues: false, actionLabel: '重新准备', actionDisabled: false },
  }[phase]
}

export function shouldShowDiceRollAction(_phase: DicePlayerPhase, result: DiceResult): boolean {
  return result.modules.some((module) => module.dice.length > 0)
}
