import assert from 'node:assert/strict'
import test from 'node:test'

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
