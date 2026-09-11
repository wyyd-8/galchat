import assert from 'node:assert/strict'
import test from 'node:test'

test('disabled auto expansion stays collapsed across resumed thinking and allows manual expansion', async () => {
  const { syncReasoningDisclosure } = await import('./reasoningDisclosure.ts')
  const open: Record<number, boolean> = {}
  const phases = new Map<number, 'thinking' | 'main' | 'idle'>()

  syncReasoningDisclosure(open, phases, 9, 'thinking', false)
  assert.equal(open[9], false)
  open[9] = true
  syncReasoningDisclosure(open, phases, 9, 'thinking', false)
  assert.equal(open[9], true)
  syncReasoningDisclosure(open, phases, 9, 'main', false)
  syncReasoningDisclosure(open, phases, 9, 'thinking', false)
  assert.equal(open[9], false)
})

test('reopens reasoning when the same reply step starts thinking again after a dice roll', async () => {
  const module = await import('./reasoningDisclosure.ts').catch(() => null)
  assert.ok(module, 'the reasoning disclosure behavior should be available')

  const open: Record<number, boolean> = {}
  const phases = new Map<number, 'thinking' | 'main' | 'idle'>()

  module.syncReasoningDisclosure(open, phases, 9, 'thinking')
  module.syncReasoningDisclosure(open, phases, 9, 'main')
  module.syncReasoningDisclosure(open, phases, 9, 'idle')
  module.syncReasoningDisclosure(open, phases, 9, 'thinking')

  assert.equal(open[9], true)
})
