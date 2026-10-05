import { onScopeDispose, watch, type Ref } from 'vue'
import { api } from '@/api/client'
import type { Character, CharacterTemplate } from '@/api/types'

/** One ownership/version boundary for whole-list reads and character mutations. */
export function createCharacterData(options: {
  worldId: Ref<number | null>
  sessionKey: () => string
  characters: Ref<Character[]>
  templates: Ref<CharacterTemplate[]>
}) {
  let epoch = 0
  let sessionEpoch = 0
  let listRevision = 0
  let templateRevision = 0
  const mutations = new Map<number, number>()

  function reset() {
    epoch++; listRevision++; templateRevision++; mutations.clear()
    options.characters.value = []; options.templates.value = []
  }
  function clearSession() { sessionEpoch++; reset() }
  watch(options.worldId, reset, { flush: 'sync' })
  watch(options.sessionKey, clearSession, { flush: 'sync' })
  onScopeDispose(clearSession)

  function capture(worldId = options.worldId.value) {
    if (worldId == null || worldId !== options.worldId.value) return null
    const ownEpoch = epoch; const ownSession = sessionEpoch; const key = options.sessionKey()
    const sameSession = () => ownSession === sessionEpoch && key === options.sessionKey()
    return { worldId, sameSession, current: () => sameSession() && ownEpoch === epoch && options.worldId.value === worldId }
  }
  type Scope = NonNullable<ReturnType<typeof capture>>

  async function refresh(scope = capture()) {
    if (!scope?.current()) return
    const revision = ++listRevision
    const rows = await api.characters(scope.worldId)
    if (scope.current() && revision === listRevision) options.characters.value = rows
  }
  async function refreshTemplates(scope: Scope, templateWorldId: number) {
    if (!scope.current()) return
    const revision = ++templateRevision
    const rows = await api.characterTemplates(templateWorldId)
    if (scope.current() && revision === templateRevision) options.templates.value = rows
  }
  async function change<T>(scope: Scope, characterId: number, operation: () => Promise<T>,
    patch?: (result: T) => Partial<Character>, valid = () => true) {
    const revision = (mutations.get(characterId) ?? 0) + 1
    mutations.set(characterId, revision)
    const current = () => scope.current() && valid() && mutations.get(characterId) === revision
    let result: T
    try { result = await operation() }
    catch (error) {
      // A multi-step save or a lost response can fail after a write committed.
      if (current()) await refresh(scope).catch(() => undefined)
      throw error
    }
    if (current()) {
      // A successful write makes every earlier list snapshot obsolete, including
      // reads started while the write was in flight.
      listRevision++
      if (patch) {
        const character = options.characters.value.find(item => item.userWorldId === scope.worldId && item.characterId === characterId)
        if (character) Object.assign(character, patch(result))
      } else await refresh(scope)
    }
    return result
  }
  async function saveModel(worldId: number, characterId: number, modelApiId: number | undefined, valid: () => boolean) {
    const scope = capture(worldId)
    if (!scope) return
    return change(scope, characterId, () => api.updateCharacterModel(worldId, characterId, modelApiId),
      runtime => ({ modelApiId: runtime.modelApiId }), valid)
  }
  return { capture, reset, clearSession, refresh, refreshTemplates, change, saveModel }
}
