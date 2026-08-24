const STREAMING_REASONING_SELECTOR = '[data-reasoning-streaming="true"]'
const LATEST_THRESHOLD = 32
const conversationFollowing = new WeakMap<HTMLElement, boolean>()
const reasoningFollowing = new WeakMap<HTMLElement, boolean>()

function isNearLatest(element: HTMLElement) {
  return element.scrollHeight - element.clientHeight - element.scrollTop <= LATEST_THRESHOLD
}

export function updateConversationScrollFollowing(viewport: HTMLElement) {
  conversationFollowing.set(viewport, isNearLatest(viewport))
}

export function updateReasoningScrollFollowing(reasoning: HTMLElement) {
  reasoningFollowing.set(reasoning, isNearLatest(reasoning))
}

export function resetConversationScrollFollowing(viewport: HTMLElement) {
  conversationFollowing.set(viewport, true)
}

export function scrollConversationToLatest(viewport: HTMLElement) {
  viewport.querySelectorAll<HTMLElement>(STREAMING_REASONING_SELECTOR).forEach((reasoning) => {
    if (reasoningFollowing.get(reasoning) !== false) reasoning.scrollTop = reasoning.scrollHeight
  })
  if (conversationFollowing.get(viewport) !== false) viewport.scrollTop = viewport.scrollHeight
}
