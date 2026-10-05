import { watch, type Ref } from 'vue'

/** Desktop Enter submits; mobile keyboards and IME confirmation always keep editing. */
export function shouldSubmitChatKey(event: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'isComposing' | 'keyCode'>, mobile: boolean, composing = false) {
  return !mobile && !composing && !event.isComposing && event.keyCode !== 229 && event.key === 'Enter' && !event.shiftKey
}

/** Drafts live only in this workspace instance, never in a shared browser account store. */
export function useScopedChatDraft(scope: Ref<string | null>, fields: Record<string, Ref<string>>) {
  const drafts = new Map<string, Record<string, string>>()
  const defaults = Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, field.value]))
  const snapshot = () => Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, field.value]))
  watch(scope, (key, previous) => {
    if (previous) drafts.set(previous, snapshot())
    const draft = key ? drafts.get(key) : undefined
    Object.entries(fields).forEach(([name, field]) => { field.value = draft?.[name] ?? defaults[name] ?? '' })
  }, { flush: 'sync' })
  function restoreIfEmpty(key: string, values: Record<string, string>) {
    const draft = scope.value === key ? snapshot() : { ...defaults, ...drafts.get(key) }
    for (const [name, value] of Object.entries(values)) {
      if (draft[name]) continue
      draft[name] = value
      if (scope.value === key && fields[name]) fields[name].value = value
    }
    drafts.set(key, draft)
  }
  return { restoreIfEmpty, forget: (key: string) => drafts.delete(key), clear: () => drafts.clear() }
}
