import type { InvestigatorCardSummary } from '../api/types.ts'

export interface ParticipantSelection {
  selectedIds: number[]
  previewId: number | null
}

export interface BindingTarget {
  key: string
  actorType: 'PLAYER' | 'BOT'
  participantId?: number
  boundCardId?: number
}

export function canAutoGenerateCard(target: BindingTarget | undefined): boolean {
  return target?.actorType === 'BOT'
    && target.participantId !== undefined
    && target.boundCardId === undefined
}

export async function loadBindingTargetContent<TCard, TDraft>(
  target: BindingTarget | undefined,
  loadCard: (cardId: number) => Promise<TCard>,
  loadActiveDraft: (participantId: number) => Promise<TDraft | null>,
): Promise<{ card: TCard | null; draft: TDraft | null }> {
  if (target?.boundCardId !== undefined) {
    return { card: await loadCard(target.boundCardId), draft: null }
  }
  if (target && canAutoGenerateCard(target)) {
    return { card: null, draft: await loadActiveDraft(target.participantId!) }
  }
  return { card: null, draft: null }
}

export function toggleParticipantSelection(
  selectedIds: number[],
  _previewId: number | null,
  clickedId: number,
): ParticipantSelection {
  if (selectedIds.includes(clickedId)) {
    return {
      selectedIds: selectedIds.filter((id) => id !== clickedId),
      previewId: null,
    }
  }
  return {
    selectedIds: [...selectedIds, clickedId],
    previewId: clickedId,
  }
}

export function buildBindingTargets(
  participantIds: number[],
  cards: InvestigatorCardSummary[],
): BindingTarget[] {
  const player = cards.find((card) => card.actorType === 'PLAYER')
  return [
    {
      key: 'player',
      actorType: 'PLAYER',
      ...(player ? { boundCardId: player.cardId } : {}),
    },
    ...participantIds.map((participantId) => {
      const bot = cards.find((card) => card.actorType === 'BOT' && card.participantId === participantId)
      return {
        key: `character:${participantId}`,
        actorType: 'BOT' as const,
        participantId,
        ...(bot ? { boundCardId: bot.cardId } : {}),
      }
    }),
  ]
}

export function hasMissingBindings(
  participantIds: number[],
  cards: InvestigatorCardSummary[],
): boolean {
  return buildBindingTargets(participantIds, cards).some((target) => target.boundCardId === undefined)
}

export function encodeParticipantIds(participantIds: number[]): string {
  return JSON.stringify([...new Set(participantIds.filter((id) => Number.isInteger(id) && id > 0))])
}

export function decodeParticipantIds(value: string | null): number[] {
  if (!value) return []
  try {
    const parsed: unknown = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return [...new Set(parsed.filter((id): id is number => Number.isInteger(id) && id > 0))]
  } catch {
    return []
  }
}

export function resolveParticipantIds(
  serverParticipantIds: number[] | undefined,
  rememberedParticipantIds: number[],
  plannedParticipantIds: number[],
): number[] {
  if (serverParticipantIds !== undefined) return [...new Set(serverParticipantIds)]
  return rememberedParticipantIds.length ? rememberedParticipantIds : plannedParticipantIds
}
