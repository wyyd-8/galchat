import { ref, watch, type Ref } from 'vue'
import type { Character, CocSkill, CocWeapon, InvestigatorCardSummary } from '../api/types.ts'

export interface ToolCharacterTarget {
  key: string
  actorType: 'PLAYER' | 'BOT'
  participantId?: number
  name: string
  image?: string
  cardId?: number
}

export function formatCheckRate(value?: number): string {
  if (value == null) return '—'
  return `${value}% / ${Math.floor(value / 2)}% / ${Math.floor(value / 5)}%`
}

function normalizedCheckName(value?: string): string {
  return (value || '').trim().replaceAll('：', ':').replaceAll(/\s+/g, '')
}

export function resolveWeaponCheckValue(
  weapon: Pick<CocWeapon, 'name' | 'skillName'>,
  skills: Array<Pick<CocSkill, 'displayName' | 'value'>>,
): number | undefined {
  const requestedNames = new Set([
    normalizedCheckName(weapon.skillName),
    normalizedCheckName(weapon.name),
  ].filter(Boolean))
  if (['徒手格斗', '斗殴', '徒手战斗'].some((name) => requestedNames.has(name))) {
    requestedNames.add('格斗:斗殴')
  }
  return skills.find((skill) => requestedNames.has(normalizedCheckName(skill.displayName)))?.value
}

export function toolDialogContentClass(tab: string): string {
  return tab === 'card' ? 'trpg-binding-dialog trpg-tools-character-dialog' : ''
}

export function useToolConfirmations(open: Ref<boolean>, selectedTab: Ref<string>) {
  const confirmLoad = ref(false)
  const confirmRollback = ref(false)

  watch([open, selectedTab], () => {
    confirmLoad.value = false
    confirmRollback.value = false
  })

  return { confirmLoad, confirmRollback }
}

export function buildToolCharacterTargets(
  characters: Character[],
  cards: InvestigatorCardSummary[],
  participantIds: number[],
  username = '',
): ToolCharacterTarget[] {
  const playerCard = cards.find((card) => card.actorType === 'PLAYER')
  const participantIdSet = new Set(participantIds)

  return [
    {
      key: 'player',
      actorType: 'PLAYER',
      name: username.trim() || '当前玩家',
      ...(playerCard ? { cardId: playerCard.cardId } : {}),
    },
    ...characters.filter((character) => participantIdSet.has(character.characterId)).map((character) => {
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
