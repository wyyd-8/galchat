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
