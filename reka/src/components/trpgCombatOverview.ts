import type { TrpgCombatParticipantOverview } from '../api/types'
import type { TrpgExecutionActor } from './trpgExecutionState'

export interface TrpgCombatHoverMetric {
  label: string
  value: string
}

export interface TrpgCombatHoverCard {
  name: string
  roleLabel: '调查员' | 'NPC'
  statuses: string[]
  metrics: TrpgCombatHoverMetric[]
}

export function combatInvestigatorCardId(
  actor: TrpgExecutionActor,
  overviews: TrpgCombatParticipantOverview[],
): number | null {
  const characterId = actor.item.subjectCharacterId
  if (characterId == null) return null
  return overviews.some((item) => item.characterId === characterId && item.investigator)
    ? characterId
    : null
}

function shown(value: number | string | undefined): string {
  return value == null || value === '' ? '—' : String(value)
}

export function buildCombatHoverCard(
  actor: TrpgExecutionActor,
  overviews: TrpgCombatParticipantOverview[],
): TrpgCombatHoverCard | null {
  const characterId = actor.item.subjectCharacterId
  if (characterId == null) return null
  const overview = overviews.find((item) => item.characterId === characterId)
  if (!overview) return null
  const metrics: TrpgCombatHoverMetric[] = overview.investigator
    ? [
        { label: 'HP', value: `${shown(overview.hpCurrent)} / ${shown(overview.hpMax)}` },
        { label: '护甲', value: shown(overview.armor) },
        { label: 'DEX', value: shown(overview.dex) },
        { label: '体格', value: shown(overview.build) },
        { label: 'MOV', value: shown(overview.mov) },
        { label: '伤害加值', value: shown(overview.damageBonus) },
      ]
    : []
  return {
    name: overview.name || actor.name,
    roleLabel: overview.investigator ? '调查员' : 'NPC',
    statuses: overview.statuses ?? [],
    metrics,
  }
}
