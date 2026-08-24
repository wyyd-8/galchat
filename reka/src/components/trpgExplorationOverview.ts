import type { InvestigatorCardSummary } from '../api/types'
import type { TrpgExecutionActor } from './trpgExecutionState'

export interface TrpgExplorationMetric {
  label: string
  value: string
}

export interface TrpgExplorationHoverCard {
  name: string
  roleLabel: '调查员'
  statuses: string[]
  resources: TrpgExplorationMetric[]
  commonChecks: TrpgExplorationMetric[]
  specialtyChecks: TrpgExplorationMetric[]
}

const COMMON_CHECKS = [
  { name: '侦查', label: '侦查' },
  { name: '聆听', label: '聆听' },
  { name: '图书馆使用', label: '图书馆' },
] as const

const EXPLORATION_SKILLS = [
  '心理学', '潜行', '追踪', '说服', '话术', '取悦', '恐吓',
  '乔装', '妙手', '急救', '医学', '精神分析', '锁匠', '导航',
  '神秘学', '克苏鲁神话', '历史', '博物学', '考古学', '人类学',
  '会计', '估价', '法律', '计算机使用', '电子学', '电气维修',
  '机械维修', '信用评级', '攀爬', '跳跃', '游泳', '汽车驾驶',
  '骑术', '操作重型机械',
] as const

const EXPLORATION_SKILL_GROUPS = [
  '科学', '艺术和手艺', '外语', '生存', '驾驶', '学识',
] as const

function shown(value?: number): string {
  return value == null ? '—' : String(value)
}

function shownPair(current?: number, maximum?: number): string {
  return `${shown(current)} / ${shown(maximum)}`
}

function normalizedName(value: string): string {
  return value.trim().replaceAll('：', ':')
}

function checkValue(card: InvestigatorCardSummary, ...names: string[]): number | undefined {
  const requested = new Set(names.map(normalizedName))
  return Object.entries(card.checkValues).find(([name]) => requested.has(normalizedName(name)))?.[1]
}

function specialtyChecks(card: InvestigatorCardSummary): TrpgExplorationMetric[] {
  const normalizedValues = new Map(
    Object.entries(card.checkValues).map(([name, value]) => [normalizedName(name), value]),
  )
  const candidates: Array<{ name: string; value: number; priority: number }> = []

  EXPLORATION_SKILLS.forEach((name, priority) => {
    const value = normalizedValues.get(name)
    if (value != null) candidates.push({ name, value, priority })
  })
  const specializedNames = [...normalizedValues.keys()]
    .filter((name) => EXPLORATION_SKILL_GROUPS.some((group) => name.startsWith(`${group}:`)))
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
  specializedNames.forEach((name, index) => {
    candidates.push({
      name,
      value: normalizedValues.get(name) as number,
      priority: EXPLORATION_SKILLS.length + index,
    })
  })

  return candidates
    .sort((left, right) => right.value - left.value || left.priority - right.priority)
    .slice(0, 3)
    .map(({ name, value }) => ({ label: name, value: shown(value) }))
}

function statuses(card: InvestigatorCardSummary): string[] {
  const result: string[] = []
  if (card.dead) result.push('死亡')
  else if (card.dying) result.push('濒死')
  else if (card.unconscious) result.push('昏迷')
  if (card.majorWound) result.push('重伤')
  if (card.temporaryInsanity) result.push('临时疯狂')
  return result
}

export function buildExplorationHoverCard(
  actor: TrpgExecutionActor,
  cards: InvestigatorCardSummary[],
): TrpgExplorationHoverCard | null {
  const characterId = actor.item.subjectCharacterId
  if (characterId == null) return null
  const card = cards.find((item) => item.cardId === characterId)
  if (!card) return null

  return {
    name: card.name || actor.name,
    roleLabel: '调查员',
    statuses: statuses(card),
    resources: [
      { label: 'SAN', value: shownPair(card.sanCurrent, card.sanMax) },
      { label: '幸运', value: shown(checkValue(card, 'LUCK', '幸运')) },
      { label: 'HP', value: shownPair(card.hpCurrent, card.hpMax) },
    ],
    commonChecks: COMMON_CHECKS.map(({ name, label }) => ({
      label,
      value: shown(checkValue(card, name)),
    })),
    specialtyChecks: specialtyChecks(card),
  }
}
