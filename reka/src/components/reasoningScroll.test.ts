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
