const STREAMING_REASONING_SELECTOR = '[data-reasoning-streaming="true"]'

export function scrollConversationToLatest(viewport: HTMLElement) {
  viewport.querySelectorAll<HTMLElement>(STREAMING_REASONING_SELECTOR).forEach((reasoning) => {
    reasoning.scrollTop = reasoning.scrollHeight
  })
  viewport.scrollTop = viewport.scrollHeight
}
