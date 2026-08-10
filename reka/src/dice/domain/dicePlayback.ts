import type { DiceResult, DiceRollAggregate, DiceRollDetail, GroupMessage } from '../../api/types'

export type DiceSkin = 'classic' | 'galaxy' | 'moonwhite'
export type DicePlayerPhase = 'idle' | 'loading' | 'ready' | 'playing' | 'complete' | 'error'
export type DicePlaybackMode = 'pending' | 'play' | 'settled'
export type DiceGroupRule = 'ANY_SUCCESS' | 'ALL_SUCCESS' | 'SEPARATE'
export type DiceGroupOutcomePhase = 'concealed' | 'individual' | 'highlighted' | 'merging' | 'merged'
export type DiceMessageTone = 'pending' | 'damage' | 'sanity' | 'healing' | 'pushed-check'
  | 'opposed' | 'success' | 'failure' | 'default'
export interface DiceMessagePresentation {
  title: string
  statusLabel: string
  tone: DiceMessageTone
}
export interface DicePlaybackGroupPresentation {
  label: string
  checkName: string
  outcomeLabel: string
  success: boolean
  winner?: boolean
}
export interface DicePlaybackPresentation {
  kind: 'multiplayer-check' | 'opposed-check'
  resultLabel: string
  resultValue: string
  formulaLabel: string
  formulaValue: string
  groups: DicePlaybackGroupPresentation[]
  groupRule?: DiceGroupRule
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
  offerContinueAfterComplete?: boolean
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

export function createDicePlayerWindowClass(toolName?: string): string {
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
  skin: DiceSkin,
  reason?: string,
  toolName?: string,
): DicePlaybackRequest {
  const snapshot = JSON.parse(JSON.stringify(result)) as DiceResult
  return { id: previousId + 1, result: snapshot, skin, reason, toolName }
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

const PARTICIPANT_CHECK_TYPES = new Set(['CHECK', 'SAN_CHECK', 'OPPOSED_CHECK'])

function checkOutcomeLabel(outcome: Record<string, unknown>): string {
  const category = typeof outcome.category === 'string' ? outcome.category : undefined
  if (!category) return '已结算'
  if (category === 'SUCCESS' && typeof outcome.rank === 'string') {
    return CHECK_RANK_LABELS[outcome.rank] || CHECK_OUTCOME_LABELS[category]
  }
  return CHECK_OUTCOME_LABELS[category] || category
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
      tone = SUCCESSFUL_CHECK_OUTCOMES.has(categories[0]!) ? 'success' : 'failure'
    } else if (details.length > 1 && groupRule && groupRule !== 'SEPARATE'
        && categories.length === details.length) {
      const succeeded = groupRule === 'ALL_SUCCESS'
        ? categories.every((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
        : categories.some((category) => SUCCESSFUL_CHECK_OUTCOMES.has(category))
      tone = succeeded ? 'success' : 'failure'
    }
  }
  let statusLabel = '已投掷'
  if (opposed) {
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
): boolean {
  return request?.offerContinueAfterComplete === true
    && summaryStatus === 'COMPLETED'
    && !hasPendingResults
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
  return request.mode === 'pending'
    ? createStandbyDiceResult(request.result)
    : request.result
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

export function findDiceMessageElement<T extends { dataset: { messageId?: string } }>(
  elements: Iterable<T>,
  messageId: number,
): T | undefined {
  return Array.from(elements).find((element) => element.dataset.messageId === String(messageId))
}

function aggregateGroup(detail: DiceRollDetail, index: number): DicePlaybackGroupPresentation {
  const outcome = detail.resolution?.outcome || {}
  return {
    label: typeof outcome.characterName === 'string'
      ? outcome.characterName
      : detail.reason || `参与者 ${index + 1}`,
    checkName: typeof outcome.checkName === 'string' ? outcome.checkName : detail.displayType || '检定',
    outcomeLabel: checkOutcomeLabel(outcome),
    success: typeof outcome.category === 'string'
      && SUCCESSFUL_CHECK_OUTCOMES.has(outcome.category),
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

export function createDiceAggregatePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  skin: DiceSkin,
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
  const effectiveGroupRule = groupRule
    ?? details.map((detail) => detail.resolution?.groupRule).find((rule): rule is DiceGroupRule =>
      rule === 'ANY_SUCCESS' || rule === 'ALL_SUCCESS' || rule === 'SEPARATE')
    ?? 'SEPARATE'
  const aggregateResult = aggregate.semanticResult || aggregate.summary.totalResult || '已完成'
  const winnerName = opposed ? aggregateResult.match(/^(.+?)获胜(?:；|$)/)?.[1] : undefined
  const groups = details.map((detail, index) => {
    const group = aggregateGroup(detail, index)
    return opposed ? { ...group, winner: group.label === winnerName } : group
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
    skin,
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
  skin: DiceSkin,
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
  if (participantCheck || details.length > 1) {
    return createDiceAggregatePlaybackRequest(previousId, aggregate, skin)
  }
  return createDicePlaybackRequest(
    previousId,
    details[0]!.resultData,
    skin,
    aggregate.summary.reason || details[0]!.reason,
    aggregate.summary.toolName,
  )
}

export function createIncomingDiceMessagePlaybackRequest(
  previousId: number,
  aggregate: DiceRollAggregate,
  skin: DiceSkin,
): DicePlaybackRequest {
  const pending = isDiceAggregatePending(aggregate)
  return {
    ...createDiceMessagePlaybackRequest(previousId, aggregate, skin),
    mode: pending ? 'pending' : 'play',
    autoPlay: !pending,
    autoPlayDelayMs: pending ? undefined : 1_000,
    offerContinueAfterComplete: true,
  }
}

export function createDicePlayerSummary(
  result: DiceResult,
  skin: DiceSkin,
  presentation?: DicePlaybackPresentation,
): DicePlayerSummary {
  const skinLabel = { classic: '经典', galaxy: '星穹', moonwhite: '月白冰晶' }[skin]
  const diceCount = result.modules.reduce((total, module) => total + module.dice.length, 0)
  const selectedCount = result.modules.reduce(
    (total, module) => total + module.dice.filter((die) => die.selected).length,
    0,
  )
  const discardedCount = diceCount - selectedCount
  const modifiers = new Set(result.modules.map((module) => module.modifier || 'NORMAL'))
  const modifierLabel = presentation?.kind === 'opposed-check'
    ? '对抗检定'
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
    groups: result.modules.map((module, index) => ({
      label: presentation?.groups[index]?.label || `第 ${index + 1} 组`,
      expression: presentation?.groups[index]?.checkName || module.expression,
      result: presentation?.groups[index]
        ? `${Number.isFinite(module.result) ? module.result : '—'} · ${presentation.groups[index].outcomeLabel}`
        : Number.isFinite(module.result) ? module.result as number : '—',
      diceCount: module.dice.length,
    })),
    equation: `${result.formula} = ${settledResult}`,
    resultLabel: presentation?.resultLabel || '最终结果',
    resultValue: presentation?.resultValue || settledResult,
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
