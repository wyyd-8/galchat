import type { ConversationMode, ReplyPlanItem } from '../api/types'

export function visibleReplyPlanItems(mode: ConversationMode, items: ReplyPlanItem[]): ReplyPlanItem[] {
  return mode === 'trpg'
    ? items.filter((item) => item.actorType !== 'kp' || item.subjectCharacterId != null)
    : items
}

export function replyPlanActorName(item: ReplyPlanItem, username: string, characterName?: string): string {
  if (item.actorType === 'user') return username.trim() || '用户'
  if (characterName) return characterName
  if (item.actorType === 'kp') return `NPC #${item.subjectCharacterId}`
  return `角色 #${item.actorId}`
}

export function replyPlanSignature(items: ReplyPlanItem[]): string {
  return JSON.stringify(items.map((item) => [item.actorType, item.actorId ?? null, item.subjectCharacterId ?? null]))
}

export function shouldShowSavePlan(canEdit: boolean, loadedSignature: string, items: ReplyPlanItem[]): boolean {
  return canEdit && loadedSignature !== replyPlanSignature(items)
}
