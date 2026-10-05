import { computed, onScopeDispose, shallowReactive, watch, type Ref } from 'vue'

const openDialogs = shallowReactive(new Set<symbol>())

export const hasOpenDialog = computed(() => openDialogs.size > 0)

/** Track each modal separately so closing a nested dialog cannot expose the background. */
export function useDialogPresence(open: Readonly<Ref<boolean>>) {
  const id = Symbol('dialog')
  watch(open, visible => {
    if (visible) openDialogs.add(id)
    else openDialogs.delete(id)
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => openDialogs.delete(id))
}
