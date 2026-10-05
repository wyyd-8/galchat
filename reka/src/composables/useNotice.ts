import { reactive } from 'vue'

export type NoticeTone = 'neutral' | 'success' | 'danger'
export const notice = reactive({ open: false, title: '', message: '', tone: 'neutral' as NoticeTone, timer: 0 })

export function notify(title: string, message = '', tone: NoticeTone = 'neutral') {
  window.clearTimeout(notice.timer)
  Object.assign(notice, { open: true, title, message, tone })
  notice.timer = window.setTimeout(() => { notice.open = false }, 3600)
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '发生未知错误'
}


// A failed read after a committed write must not ask the user to repeat the write.
export async function refreshAfterSave(title: string, refresh: () => Promise<unknown>, current: () => boolean = () => true, detail = '') {
  try {
    await refresh()
    if (current()) notify(title, detail, 'success')
  } catch (error) {
    if (current()) notify(title, `数据已保存，列表刷新失败，请重新打开页面刷新。${errorMessage(error)}`)
  }
}
