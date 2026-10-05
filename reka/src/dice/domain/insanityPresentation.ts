import type { DiceRollDetail } from '../../api/types'

export function insanityDetailPresentation(detail: DiceRollDetail) {
  const type = detail.resolution?.type || detail.displayType
  if (type !== 'TEMPORARY_INSANITY_TYPE' && type !== 'TEMPORARY_INSANITY_DURATION') return undefined
  const symptom = type === 'TEMPORARY_INSANITY_TYPE'
  const outcome = detail.resolution?.outcome
  const name = detail.resolution?.characterName || (typeof outcome?.characterName === 'string' ? outcome.characterName : '')
  const description = detail.resolvedAt && typeof outcome?.display === 'string' ? outcome.display.trim() : ''
  // Keep the backend's symptom name, with its full explanation available separately.
  const colon = description.indexOf('：')
  const heading = colon < 0 ? description : description.slice(0, colon)
  const shortDisplay = colon >= 0 && heading.includes('（') && !heading.includes('）') ? `${heading}）` : heading
  const hours = outcome?.durationHours
  const value = !detail.resolvedAt ? '待确定' : symptom
    ? shortDisplay || '症状未记录'
    : typeof hours === 'number' && Number.isFinite(hours) && hours > 0 ? `${hours} 小时` : '时长未记录'
  return { name, label: symptom ? '症状' : '持续时间', value, description, symptom }
}

export function insanityRoundPresentation(details: DiceRollDetail[]) {
  if (!details.length) return undefined
  const items = details.map(detail => ({ detail, display: insanityDetailPresentation(detail) }))
  if (items.some(item => !item.display)) return undefined
  // A missing source must never make unrelated records a synthetic pair.
  const pairs = new Map<string, typeof items>()
  for (const item of items) {
    const source = item.detail.resolution?.sourceResultId
    const key = source == null ? `detail:${item.detail.id}` : `source:${source}`
    const pair = pairs.get(key) || []
    pair.push(item)
    pairs.set(key, pair)
  }
  const names = [...new Set(items.map(item => item.display!.name).filter(Boolean))]
  const title = names.length ? `${names.join('、')} · 临时疯狂` : '临时疯狂'
  const summary = [...pairs.values()].map(pair => {
    const name = pair.find(item => item.display!.name)?.display!.name
    const result = [...pair].sort((a, b) => Number(b.display!.symptom) - Number(a.display!.symptom))
      .map(item => item.display!.value === '待确定' ? `${item.display!.label}待确定` : item.display!.value).join(' · ')
    return pairs.size > 1 && name ? `${name}：${result}` : result
  }).join('；')
  return { title, summary }
}
