import type { DiceHistoryEntry } from '@/dice/domain/dicePlayback'

const outcomeLabels: Record<string, string> = {
  CRITICAL_SUCCESS: '大成功', EXTREME_SUCCESS: '极难成功', HARD_SUCCESS: '困难成功',
  SUCCESS: '成功', FAILURE: '失败', FUMBLE: '大失败',
}
const rankLabels: Record<string, string> = { CRITICAL: '大成功', EXTREME: '极难成功', HARD: '困难成功', REGULAR: '常规成功' }
function participantOutcome(outcome?: Record<string, unknown>) {
  const category = typeof outcome?.category === 'string' ? outcome.category : ''
  if (!outcomeLabels[category]) return null
  return {
    label: category === 'SUCCESS' && typeof outcome?.rank === 'string' ? rankLabels[outcome.rank] || outcomeLabels[category] : outcomeLabels[category],
    failure: category === 'FAILURE' || category === 'FUMBLE',
  }
}

export function mobileHistoryValues(entry: DiceHistoryEntry) {
  const latestRound = Math.max(1, ...entry.aggregate.results.map(detail => detail.roundNo || 1))
  return entry.aggregate.results.filter(detail => (detail.roundNo || 1) === latestRound).map(detail => ({
    key: detail.id,
    name: [detail.resolution?.characterName, detail.resolution?.checkName].filter(Boolean).join(' · '),
    value: typeof detail.resultData?.result === 'number' && Number.isFinite(detail.resultData.result) ? detail.resultData.result : null,
    outcome: participantOutcome(detail.resolution?.outcome),
    target: typeof detail.resolution?.targetValue === 'number' && Number.isFinite(detail.resolution.targetValue) ? detail.resolution.targetValue : null,
  }))
}

export function matchesMobileHistoryQuery(entry: DiceHistoryEntry, query: string) {
  const normalize = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase('zh-CN')
  const text = [entry.title, ...entry.aggregate.results.flatMap(detail => [detail.resolution?.characterName, detail.resolution?.checkName])].filter(Boolean).join(' ')
  return normalize(text).includes(normalize(query))
}

export function mobileHistoryDay(value?: string, now = new Date()) {
  const date = value ? new Date(value) : null
  if (!date || Number.isNaN(date.getTime())) return '时间未记录'
  const key = (item: Date) => `${item.getFullYear()}-${String(item.getMonth() + 1).padStart(2, '0')}-${String(item.getDate()).padStart(2, '0')}`
  return key(date) === key(now) ? '今天' : key(date)
}

export function mobileHistoryTitle(entry: DiceHistoryEntry) {
  const values = mobileHistoryValues(entry)
  return values.length === 1 && values[0]?.name ? values[0].name : entry.title
}

export function mobileHistoryReasons(entry: DiceHistoryEntry) {
  const title = mobileHistoryTitle(entry)
  return [...new Set([entry.title, entry.aggregate.summary.reason, ...entry.aggregate.results.map(detail => detail.reason)]
    .filter((reason): reason is string => Boolean(reason?.trim()) && reason !== title))]
}
