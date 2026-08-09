import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createDicePlayerLayout,
  createDicePlayerWindowWidth,
} from './dicePlayerLayout.ts'

test('keeps three dice groups on one widened row', () => {
  assert.deepEqual(createDicePlayerLayout(3), {
    columns: 3,
    rows: 1,
    rowGroupCounts: [3],
    stageMinHeightPx: 360,
  })
})

test('balances four to six groups across at most three columns', () => {
  assert.deepEqual(createDicePlayerLayout(4).rowGroupCounts, [2, 2])
  assert.deepEqual(createDicePlayerLayout(5).rowGroupCounts, [3, 2])
  assert.deepEqual(createDicePlayerLayout(6).rowGroupCounts, [3, 3])
})

test('adds vertically scrollable rows without exceeding three columns', () => {
  assert.deepEqual(createDicePlayerLayout(7), {
    columns: 3,
    rows: 3,
    rowGroupCounts: [3, 2, 2],
    stageMinHeightPx: 832,
  })
  assert.deepEqual(createDicePlayerLayout(10), {
    columns: 3,
    rows: 4,
    rowGroupCounts: [3, 3, 2, 2],
    stageMinHeightPx: 1068,
  })
})

test('keeps an empty player in a valid single-cell layout', () => {
  assert.deepEqual(createDicePlayerLayout(0), {
    columns: 1,
    rows: 1,
    rowGroupCounts: [],
    stageMinHeightPx: 360,
  })
})

test('widens the player to contain its widest balanced row', () => {
  assert.equal(createDicePlayerWindowWidth([1180]), 1226)
  assert.equal(createDicePlayerWindowWidth([680, 930]), 980)
})
