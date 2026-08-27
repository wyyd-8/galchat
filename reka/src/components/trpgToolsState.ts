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
const UNRECOGNIZED_WEAPON_TAG = '未识别武器'
const WEAPON_RISK_MESSAGES: Record<string, string> = {
  '显眼': '公开携带容易引起注意、警觉或盘问；在需要低调行动、进入公共场所或受控区域时尤其明显。',
  '高噪声': '使用会产生明显声响，可能暴露位置、惊动附近人员，并使秘密行动迅速升级。',
  '笨重': '尺寸或重量会妨碍隐藏、攀爬和狭窄空间移动；长时间携带或快速转移时可能成为负担。',
  '严格管制': '持有、携带或使用可能受到当地法律和场所规则限制；被发现时可能引发查验、扣留或执法介入。',
  '破坏现场': '使用容易造成大范围破坏、污染痕迹或损坏线索；在需要保护的调查现场中应谨慎。',
  [UNRECOGNIZED_WEAPON_TAG]: '系统未能匹配该武器，当前仅按大型棍棒的非名称属性处理；战斗时只能使用近战攻击。',
}

export interface WeaponRiskGuidance {
  tag: string
  message: string
}

export function shouldShowWeaponRisk(
  weapon: Pick<CocWeapon, 'abnormal' | 'riskTags'>,
): boolean {
  return weapon.abnormal === true || weapon.riskTags?.includes(UNRECOGNIZED_WEAPON_TAG) === true
}

export function buildWeaponRiskGuidance(riskTags: string[] = []): WeaponRiskGuidance[] {
  return [...new Set(riskTags.map((tag) => tag.trim()).filter(Boolean))].map((tag) => ({
    tag,
    message: WEAPON_RISK_MESSAGES[tag]
      || '该风险没有固定惩罚，需由 KP 结合模组背景、当前地点与具体行动判断。',
  }))
}

export function formatCheckRate(value?: number): string {
  if (value == null) return '—'
  return `${value}% / ${Math.floor(value / 2)}% / ${Math.floor(value / 5)}%`
}

export function formatKpPromptUpdatedAt(
  value?: string,
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
): string {
  if (!value) return '暂无记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '暂无记录'

  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date).filter((part) => part.type !== 'literal')
    .map((part) => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
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

export function preferredToolCharacterTargetKey(
  targets: ToolCharacterTarget[],
  cardId: number | null,
  fallbackKey: string,
): string {
  const requestedTarget = cardId == null
    ? undefined
    : targets.find((target) => target.cardId === cardId)
  if (requestedTarget) return requestedTarget.key
  return targets.some((target) => target.key === fallbackKey)
    ? fallbackKey
    : targets[0]?.key ?? fallbackKey
}
