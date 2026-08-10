import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createDiceGroupMergePlan,
  createDiceValueMergeTokenLayout,
} from './diceGroupMerge.ts'

test('moves only value labels to the measured center of their group', () => {
  assert.deepEqual(createDiceGroupMergePlan([100, 260, 420], 260, 0), {
    delayMs: 620,
    fadeDelayMs: 210,
    moveDurationMs: 520,
    valueOffsets: [160, 0, -160],
  })
})

test('stages later value-label merges after the preceding group', () => {
  assert.deepEqual(createDiceGroupMergePlan([180, 360], 270, 2), {
    delayMs: 1180,
    fadeDelayMs: 210,
    moveDurationMs: 520,
    valueOffsets: [90, -90],
  })
})

test('places a moving token over the original value without moving its die', () => {
  assert.deepEqual(createDiceValueMergeTokenLayout(
    { left: 100, top: 300, width: 40, height: 26 },
    { left: 50, top: 200 },
    260,
  ), {
    left: 70,
    top: 113,
    offsetX: 140,
  })
})
