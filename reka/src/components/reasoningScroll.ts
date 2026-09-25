const STREAMING_REASONING_SELECTOR = '[data-reasoning-streaming="true"]'
const LATEST_THRESHOLD = 32
interface ScrollFollowingState { following: boolean; lastScrollTop: number }
const conversationFollowing = new WeakMap<HTMLElement, ScrollFollowingState>()
const reasoningFollowing = new WeakMap<HTMLElement, ScrollFollowingState>()

/** Keyboard and composer resizing can hide the latest message without adding content. */
export function observeConversationResize(viewport: HTMLElement, onResize: () => void): () => void {
  if (typeof ResizeObserver === 'undefined') return () => {}
  const observer = new ResizeObserver(onResize)
  observer.observe(viewport)
  return () => observer.disconnect()
}

function isNearLatest(element: HTMLElement) {
  return element.scrollHeight - element.clientHeight - element.scrollTop <= LATEST_THRESHOLD
}

export function updateConversationScrollFollowing(viewport: HTMLElement) {
  updateScrollFollowing(conversationFollowing, viewport)
}

export function updateReasoningScrollFollowing(reasoning: HTMLElement) {
  updateScrollFollowing(reasoningFollowing, reasoning)
}

export function pauseConversationScrollFollowing(viewport: HTMLElement) {
  conversationFollowing.set(viewport, { following: false, lastScrollTop: viewport.scrollTop })
}

export function resetConversationScrollFollowing(viewport: HTMLElement) {
  conversationFollowing.set(viewport, { following: true, lastScrollTop: viewport.scrollTop })
}

export function scrollConversationToLatest(viewport: HTMLElement) {
  viewport.querySelectorAll<HTMLElement>(STREAMING_REASONING_SELECTOR).forEach((reasoning) => {
    scrollElementToLatest(reasoningFollowing, reasoning)
  })
  scrollElementToLatest(conversationFollowing, viewport)
}

function updateScrollFollowing(states: WeakMap<HTMLElement, ScrollFollowingState>, element: HTMLElement) {
  const previous = states.get(element)
  // A taller viewport clamps scrollTop downward; that is not a request to read history.
  const maxTop = Math.max(0, element.scrollHeight - element.clientHeight)
  const movingUp = previous !== undefined && element.scrollTop < Math.min(previous.lastScrollTop, maxTop)
  let following = previous?.following ?? isNearLatest(element)
  if (movingUp) following = false
  else if (!following && isNearLatest(element)) following = true
  states.set(element, { following, lastScrollTop: element.scrollTop })
}

function scrollElementToLatest(states: WeakMap<HTMLElement, ScrollFollowingState>, element: HTMLElement) {
  if (states.get(element)?.following === false) return
  element.scrollTop = element.scrollHeight
  states.set(element, { following: true, lastScrollTop: element.scrollTop })
}
