import type { ReplyPlanItem } from '../api/types'

export function replyPlanSignature(items: ReplyPlanItem[]): string {
  return JSON.stringify(items.map((item) => [item.actorType, item.actorId ?? null, item.subjectCharacterId ?? null]))
}

export function shouldShowSavePlan(canEdit: boolean, loadedSignature: string, items: ReplyPlanItem[]): boolean {
  return canEdit && loadedSignature !== replyPlanSignature(items)
}
