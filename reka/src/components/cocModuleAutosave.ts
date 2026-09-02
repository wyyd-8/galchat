import type { CocModuleSavePayload } from '../api/types'
import { cloneCocModuleData } from './cocModuleData.ts'

function hasText(value: string | null | undefined): value is string {
  return Boolean(value?.trim())
}

export function validateCocModulePayload(payload: CocModuleSavePayload): string | null {
  if (!hasText(payload.name)) return '模组名称不能为空'
  if (!hasText(payload.introduction)) return '模组简介不能为空'

  const locationNames = new Set<string>()
  for (const location of payload.locations) {
    if (!hasText(location.name) || !hasText(location.summary) || !hasText(location.content)) {
      return '地点名称、摘要和正文不能为空'
    }
    const name = location.name.trim()
    if (locationNames.has(name)) return '同一模组内地点名称不能重复'
    locationNames.add(name)
  }

  const clueTitles = new Set<string>()
  for (const clue of payload.clues) {
    if (!hasText(clue.title) || !hasText(clue.content)) return '线索标题和正文不能为空'
    const title = clue.title.trim()
    if (clueTitles.has(title)) return '同一模组内线索标题不能重复'
    clueTitles.add(title)
  }

  const materialTitles = new Set<string>()
  for (const material of payload.materials) {
    if (!hasText(material.title) || !hasText(material.description)) {
      return '素材标题和介绍不能为空'
    }
    const title = material.title.trim()
    if (materialTitles.has(title)) return '同一模组内素材标题不能重复'
    materialTitles.add(title)
  }

  for (const card of payload.characters) {
    const damageBonus = card.character.damageBonus
    if (!hasText(damageBonus)) continue
    if (!isValidDamageBonus(damageBonus)) {
      const name = card.character.name.trim() || '未命名角色'
      return `预设角色“${name}”的伤害加值（DB）格式不合法`
    }
  }

  return null
}

function isValidDamageBonus(value: string): boolean {
  const normalized = value.trim().toUpperCase()
  return /^(?:-[12]|0|\+?(?:1D4|[1-9]\d*D6))$/.test(normalized)
}

export function cocModulePayloadFingerprint(payload: CocModuleSavePayload): string {
  return JSON.stringify(cloneCocModuleData(payload))
}

export function createCocModuleSaveQueue(
  persist: (moduleId: number, payload: CocModuleSavePayload) => Promise<void>,
) {
  let tail = Promise.resolve()
  const pending = new Map<string, Promise<void>>()

  return {
    enqueue(moduleId: number, payload: CocModuleSavePayload) {
      const snapshot = cloneCocModuleData(payload)
      const key = `${moduleId}:${cocModulePayloadFingerprint(snapshot)}`
      const existing = pending.get(key)
      if (existing) return existing

      const saving = tail.then(() => persist(moduleId, snapshot))
      tail = saving.catch(() => undefined)
      pending.set(key, saving)
      const clear = () => {
        if (pending.get(key) === saving) pending.delete(key)
      }
      saving.then(clear, clear)
      return saving
    },
  }
}

export async function saveCocModuleIfNeeded(
  queue: ReturnType<typeof createCocModuleSaveQueue>,
  moduleId: number,
  payload: CocModuleSavePayload,
  savedFingerprint: string,
): Promise<
  | { status: 'unchanged' }
  | { status: 'invalid', message: string }
  | { status: 'saved', fingerprint: string }
  | { status: 'error', message: string }
> {
  const fingerprint = cocModulePayloadFingerprint(payload)
  if (fingerprint === savedFingerprint) return { status: 'unchanged' }

  const validationMessage = validateCocModulePayload(payload)
  if (validationMessage) return { status: 'invalid', message: validationMessage }

  try {
    await queue.enqueue(moduleId, payload)
    return { status: 'saved', fingerprint }
  } catch (error) {
    return { status: 'error', message: error instanceof Error ? error.message : '保存失败' }
  }
}
