import type { Character, InvestigatorCardSummary } from '../api/types.ts'

export interface ToolCharacterTarget {
  key: string
  actorType: 'PLAYER' | 'BOT'
  participantId?: number
  name: string
  image?: string
  cardId?: number
}

export function buildToolCharacterTargets(
  characters: Character[],
  cards: InvestigatorCardSummary[],
): ToolCharacterTarget[] {
  const playerCard = cards.find((card) => card.actorType === 'PLAYER')

  return [
    {
      key: 'player',
      actorType: 'PLAYER',
      name: '玩家调查员',
      ...(playerCard ? { cardId: playerCard.cardId } : {}),
    },
    ...characters.map((character) => {
      const boundCard = cards.find((card) => card.actorType === 'BOT'
        && card.participantId === character.characterId)
      return {
        key: `character:${character.characterId}`,
        actorType: 'BOT' as const,
        participantId: character.characterId,
        name: character.characterName,
        ...(character.characterImage ? { image: character.characterImage } : {}),
        ...(boundCard ? { cardId: boundCard.cardId } : {}),
      }
    }),
  ]
}
