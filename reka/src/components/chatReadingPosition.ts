import { pauseConversationScrollFollowing, resetConversationScrollFollowing, scrollConversationToLatest } from './reasoningScroll.ts'

interface ReadingPosition { anchorId?: string; offset: number; top: number; following: boolean }
const positions = new Map<string, ReadingPosition>()

export function clearChatReadingPositions() { positions.clear() }

export function rememberChatReadingPosition(key: string, viewport: HTMLElement) {
  const viewportTop = viewport.getBoundingClientRect().top
  const anchor = [...viewport.querySelectorAll<HTMLElement>('[data-message-id]')]
    .find(element => element.getBoundingClientRect().bottom > viewportTop)
  positions.set(key, {
    anchorId: anchor?.dataset.messageId,
    offset: anchor ? anchor.getBoundingClientRect().top - viewportTop : 0,
    top: viewport.scrollTop,
    following: viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 32,
  })
}

/** Missing anchors request another history page before moving the reader. */
export function restoreChatReadingPosition(key: string, viewport: HTMLElement, hasOlder: boolean): boolean {
  const position = positions.get(key)
  if (!position || position.following) {
    resetConversationScrollFollowing(viewport)
    scrollConversationToLatest(viewport)
    return true
  }
  const anchor = [...viewport.querySelectorAll<HTMLElement>('[data-message-id]')]
    .find(element => element.dataset.messageId === position.anchorId)
  if (!anchor && position.anchorId && hasOlder) return false
  viewport.scrollTop = anchor
    ? viewport.scrollTop + anchor.getBoundingClientRect().top - viewport.getBoundingClientRect().top - position.offset
    : position.top
  pauseConversationScrollFollowing(viewport)
  return true
}
