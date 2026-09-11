import type { TrpgCompletionRoll, TrpgRollOutcome } from '../api/types.ts'

export const completionOutcomes: Array<{ value: TrpgRollOutcome; label: string; color: string }> = [
  { value: 'CRITICAL_SUCCESS', label: '大成功', color: '#a58347' },
  { value: 'SUCCESS', label: '成功', color: '#41665b' },
  { value: 'FAILURE', label: '失败', color: '#929589' },
  { value: 'FUMBLE', label: '大失败', color: '#934b54' },
]

export function completionDice(rolls: TrpgCompletionRoll[], characterId: number | null) {
  const selected = characterId == null ? rolls : rolls.filter((roll) => roll.characterId === characterId)
  return {
    total: selected.length,
    counts: completionOutcomes.map((outcome) => ({ ...outcome, count: selected.filter((roll) => roll.outcome === outcome.value).length })),
    highlights: selected.filter((roll) => roll.outcome === 'CRITICAL_SUCCESS' || roll.outcome === 'FUMBLE').slice(0, 6),
  }
}
