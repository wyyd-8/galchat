import { nextTick, onScopeDispose, watch, type Ref } from 'vue'
import { createMobileDialogHistory } from './mobileDialogHistory'

let historyManager: ReturnType<typeof createMobileDialogHistory> | undefined
function manager() {
  return historyManager ||= createMobileDialogHistory({
    state: () => window.history.state,
    url: () => window.location.href,
    push: state => window.history.pushState(state, '', window.location.href),
    back: () => window.history.back(),
    defer: task => queueMicrotask(task),
    listen: listener => {
      window.addEventListener('popstate', listener)
      return () => window.removeEventListener('popstate', listener)
    },
  }, `galchat-dialog-${Date.now()}-${Math.random().toString(36).slice(2)}`)
}

export function useMobileDialogHistory(open: Ref<boolean>, mobile: Ref<boolean>, back?: () => void | Promise<void>) {
  let unregister: (() => void) | undefined
  watch([open, mobile], ([visible, onMobile]) => {
    if (visible && onMobile && typeof window !== 'undefined') {
      unregister ||= manager().register(async () => {
        if (back) await back()
        else open.value = false
        // A parent model proxy may reject closing and open an unsaved-changes dialog.
        await nextTick()
        return !open.value
      })
    } else {
      unregister?.()
      unregister = undefined
    }
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => unregister?.())
}
