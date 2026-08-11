import assert from 'node:assert/strict'
import test from 'node:test'

import { formatDiceGroupLabel } from './diceGroupLabel.ts'

test('labels a single dice module with its one-based sorted group number', () => {
  assert.equal(formatDiceGroupLabel(0, 1), '第 1 组')
  assert.equal(formatDiceGroupLabel(2, 1), '第 3 组')
})

test('labels a participant spanning multiple dice modules with the full group range', () => {
  assert.equal(formatDiceGroupLabel(1, 2), '第 2–3 组')
})
