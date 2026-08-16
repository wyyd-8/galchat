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

export interface ToolSkillDisplayItem {
  key: string
  kind: 'category' | 'skill'
  displayName: string
  category?: string
  value?: number
}

const COLLAPSIBLE_SKILL_GROUPS = [
  '艺术和手艺', '技艺', '语言', '科学', '生存', '操纵', '驾驶', '学识',
  '格斗', '射击', '炮术',
]
const COMBAT_SKILL_GROUPS = new Set(['格斗', '射击', '炮术'])

export function formatCheckRate(value?: number): string {
  if (value == null) return '—'
  return `${value}% / ${Math.floor(value / 2)}% / ${Math.floor(value / 5)}%`
}

function normalizedSkillGroupName(value?: string): string {
  return (value || '').trim().replaceAll('：', ':')
}

function collapsibleSkillGroup(skill: CocSkill): string | undefined {
  const name = normalizedSkillGroupName(skill.displayName)
  const category = normalizedSkillGroupName(skill.category)
  return COLLAPSIBLE_SKILL_GROUPS.find((group) => name === group
    || name.startsWith(`${group}:`)
    || (category === group && (COMBAT_SKILL_GROUPS.has(group) || Boolean(skill.specialization?.trim()))))
}

function skillDisplayItem(skill: CocSkill): ToolSkillDisplayItem {
  return {
    key: `skill:${skill.id ?? skill.displayName}`,
    kind: 'skill',
    displayName: skill.displayName,
    ...(skill.category ? { category: skill.category } : {}),
    value: skill.value,
  }
}

export function buildSkillDisplayItems(
  skills: CocSkill[],
  activeGroup?: string | null,
): ToolSkillDisplayItem[] {
  const normalizedActiveGroup = normalizedSkillGroupName(activeGroup || undefined)
  if (normalizedActiveGroup) {
    return [
      { key: `category:${normalizedActiveGroup}`, kind: 'category', displayName: normalizedActiveGroup },
      ...skills
        .filter((skill) => collapsibleSkillGroup(skill) === normalizedActiveGroup
          && normalizedSkillGroupName(skill.displayName) !== normalizedActiveGroup)
        .map(skillDisplayItem),
    ]
  }

  const displayedGroups = new Set<string>()
  const result: ToolSkillDisplayItem[] = []

  for (const skill of skills) {
    const group = collapsibleSkillGroup(skill)
    if (group) {
      if (!displayedGroups.has(group)) {
        displayedGroups.add(group)
        result.push({ key: `category:${group}`, kind: 'category', displayName: group })
      }
      if (normalizedSkillGroupName(skill.displayName) === group) continue
      if (skill.baseValue != null && skill.value === skill.baseValue) continue
    }
    result.push(skillDisplayItem(skill))
  }
  return result
}

export function nextSkillGroup(current: string | null, requested: string): string | null {
  return current === requested ? null : requested
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
