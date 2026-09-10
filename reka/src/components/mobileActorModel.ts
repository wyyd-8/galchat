import type { GroupActorRuntime, GroupActorRuntimeSavePayload, ModelApi, ReplyPlanItem } from '../api/types'

export type SceneModelTarget = Pick<GroupActorRuntimeSavePayload, 'actorType' | 'actorId'>
export function sceneModelTarget(item: ReplyPlanItem): SceneModelTarget | null {
  if (item.actorType === 'kp') return { actorType: 'kp' }
  if (item.actorType === 'character' && item.actorId != null) return { actorType: 'character', actorId: item.actorId }
  return null
}
export function sceneModelPayload(
  target: SceneModelTarget,
  runtime: GroupActorRuntime | undefined,
  selection: string,
  models: Pick<ModelApi, 'id'>[],
): GroupActorRuntimeSavePayload | null {
  if (selection && !models.some(model => String(model.id) === selection)) return null
  return {
    ...target,
    controlMode: target.actorType === 'kp' ? 'MODEL' : runtime?.controlMode || 'MODEL',
    modelApiId: selection ? Number(selection) : undefined,
  }
}
