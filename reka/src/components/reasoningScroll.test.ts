import assert from 'node:assert/strict'
import test from 'node:test'

test('keeps following after keyboard dismissal clamps the scroll position', async () => {
  const module = await import('./reasoningScroll.ts')
  let top = 700
  const viewport = {
    clientHeight: 300, scrollHeight: 1000,
    get scrollTop() { return top },
    set scrollTop(value: number) { top = Math.max(0, Math.min(value, this.scrollHeight - this.clientHeight)) },
    querySelectorAll() { return [] },
  }
  module.resetConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.clientHeight = 600
  viewport.scrollTop = 700 // Browser clamps the position when the keyboard closes.
  module.updateConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.clientHeight = 300
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)
  assert.equal(viewport.scrollTop, 700)
})

test('viewport resize follows latest content but preserves a reader browsing history', async context => {
  const module = await import('./reasoningScroll.ts')
  let resize: (() => void) | undefined
  const original = globalThis.ResizeObserver
  globalThis.ResizeObserver = class {
    constructor(callback: () => void) { resize = callback }
    observe() {}
    disconnect() { resize = undefined }
  } as unknown as typeof ResizeObserver
  context.after(() => { globalThis.ResizeObserver = original })
  let top = 400
  const viewport = {
    clientHeight: 600, scrollHeight: 1000,
    get scrollTop() { return top },
    set scrollTop(value: number) { top = Math.max(0, Math.min(value, this.scrollHeight - this.clientHeight)) },
    querySelectorAll() { return [] },
  }
  const element = viewport as unknown as HTMLElement
  module.resetConversationScrollFollowing(element)
  assert.equal(typeof module.observeConversationResize, 'function', 'chat viewports must react to keyboard and composer resizing')
  const stop = module.observeConversationResize(element, () => module.scrollConversationToLatest(element))
  viewport.clientHeight = 300
  resize?.()
  assert.equal(viewport.scrollTop, 700)
  viewport.scrollTop = 200
  module.updateConversationScrollFollowing(element)
  viewport.clientHeight = 250
  resize?.()
  assert.equal(viewport.scrollTop, 200)
  stop()
  module.resetConversationScrollFollowing(element)
  viewport.clientHeight = 200
  resize?.()
  assert.equal(viewport.scrollTop, 200, 'unmounted conversations stop responding to resizing')
})

test('keeps streaming reasoning and the message viewport at their latest content', async () => {
  const module = await import('./reasoningScroll.ts').catch(() => null)
  assert.ok(module, 'the conversation scrolling behavior should be available')

  const reasoning = { scrollTop: 0, scrollHeight: 360 }
  const viewport = {
    scrollTop: 0,
    scrollHeight: 900,
    querySelectorAll(selector: string) {
      assert.equal(selector, '[data-reasoning-streaming="true"]')
      return [reasoning]
    },
  }

  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(reasoning.scrollTop, 360)
  assert.equal(viewport.scrollTop, 900)
})

test('does not move reasoning that is no longer streaming', async () => {
  const module = await import('./reasoningScroll.ts').catch(() => null)
  assert.ok(module, 'the conversation scrolling behavior should be available')

  const completedReasoning = { scrollTop: 42, scrollHeight: 360 }
  const viewport = {
    scrollTop: 0,
    scrollHeight: 900,
    querySelectorAll(selector: string) {
      assert.equal(selector, '[data-reasoning-streaming="true"]')
      return []
    },
  }

  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(completedReasoning.scrollTop, 42)
})

test('stops following message output after the user scrolls away from the bottom', async () => {
  const module = await import('./reasoningScroll.ts')
  const viewport = {
    scrollTop: 120,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [] },
  }

  module.updateConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.scrollHeight = 980
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(viewport.scrollTop, 120)
})

test('resumes following message output when the user returns to the bottom', async () => {
  const module = await import('./reasoningScroll.ts')
  const viewport = {
    scrollTop: 500,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [] },
  }

  module.updateConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.scrollHeight = 980
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(viewport.scrollTop, 980)
})

test('lets the user browse streaming reasoning without unlocking the message viewport', async () => {
  const module = await import('./reasoningScroll.ts')
  const reasoning = { scrollTop: 40, scrollHeight: 360, clientHeight: 150 }
  const viewport = {
    scrollTop: 500,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [reasoning] },
  }

  module.updateReasoningScrollFollowing(reasoning as unknown as HTMLElement)
  reasoning.scrollHeight = 420
  viewport.scrollHeight = 980
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(reasoning.scrollTop, 40)
  assert.equal(viewport.scrollTop, 980)
})

test('restores following when a different conversation opens in the same viewport', async () => {
  const module = await import('./reasoningScroll.ts')
  const viewport = {
    scrollTop: 120,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [] },
  }

  module.updateConversationScrollFollowing(viewport as unknown as HTMLElement)
  module.resetConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.scrollHeight = 980
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(viewport.scrollTop, 980)
})

test('unlocks message following on the first upward movement inside the bottom tolerance', async () => {
  const module = await import('./reasoningScroll.ts')
  const viewport = {
    scrollTop: 500,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [] },
  }

  module.resetConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.scrollTop = 490
  module.updateConversationScrollFollowing(viewport as unknown as HTMLElement)
  viewport.scrollHeight = 980
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(viewport.scrollTop, 490)
})

test('unlocks reasoning following on the first upward movement inside the bottom tolerance', async () => {
  const module = await import('./reasoningScroll.ts')
  const reasoning = { scrollTop: 210, scrollHeight: 360, clientHeight: 150 }
  const viewport = {
    scrollTop: 500,
    scrollHeight: 900,
    clientHeight: 400,
    querySelectorAll() { return [reasoning] },
  }

  module.scrollConversationToLatest(viewport as unknown as HTMLElement)
  reasoning.scrollTop = 200
  module.updateReasoningScrollFollowing(reasoning as unknown as HTMLElement)
  reasoning.scrollHeight = 420
  module.scrollConversationToLatest(viewport as unknown as HTMLElement)

  assert.equal(reasoning.scrollTop, 200)
})
