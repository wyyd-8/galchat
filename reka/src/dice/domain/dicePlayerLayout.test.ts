import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createDicePlayerLayout,
  createDicePlayerWindowWidth,
} from './dicePlayerLayout.ts'
import * as dicePlayerLayout from './dicePlayerLayout.ts'

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

test('centers a single special outcome on the stage and multiplayer outcomes on their group', () => {
  const createEffectLayout = Reflect.get(dicePlayerLayout, 'createDiceOutcomeVfxLayout') as
    | ((
      scope: 'stage' | 'local',
      group: { left: number; top: number; width: number; height: number },
      surface: { left: number; top: number; width: number; height: number },
    ) => { leftPx: number; topPx: number; sizePx: number })
    | undefined
  const surface = { left: 100, top: 50, width: 900, height: 400 }
  const group = { left: 400, top: 150, width: 200, height: 180 }

  assert.equal(typeof createEffectLayout, 'function')
  assert.deepEqual(createEffectLayout?.('stage', group, surface), {
    leftPx: 450,
    topPx: 200,
    sizePx: 600,
  })
  assert.deepEqual(createEffectLayout?.('local', group, surface), {
    leftPx: 400,
    topPx: 190,
    sizePx: 290,
  })
})

test('merges every module rectangle belonging to one participant effect', () => {
  const mergeRects = Reflect.get(dicePlayerLayout, 'mergeDiceOutcomeVfxRects') as
    | ((rects: Array<{ left: number; top: number; width: number; height: number }>) => {
      left: number
      top: number
      width: number
      height: number
    } | undefined)
    | undefined

  assert.equal(typeof mergeRects, 'function')
  assert.deepEqual(mergeRects?.([
    { left: 180, top: 90, width: 120, height: 160 },
    { left: 320, top: 110, width: 180, height: 130 },
  ]), {
    left: 180,
    top: 90,
    width: 320,
    height: 160,
  })
  assert.equal(mergeRects?.([]), undefined)
})

test('limits the shared dice canvas to the visible dialog area', () => {
  const intersectRects = Reflect.get(dicePlayerLayout, 'intersectDiceViewportRects') as
    | ((rects: Array<{ left: number; top: number; width: number; height: number }>) => {
      left: number
      top: number
      width: number
      height: number
    } | undefined)
    | undefined

  assert.equal(typeof intersectRects, 'function')
  assert.deepEqual(intersectRects?.([
    { left: 100, top: 40, width: 800, height: 1_100 },
    { left: 70, top: 80, width: 900, height: 520 },
    { left: 0, top: 0, width: 1_280, height: 720 },
  ]), {
    left: 100,
    top: 80,
    width: 800,
    height: 520,
  })
  assert.equal(intersectRects?.([
    { left: 0, top: 0, width: 20, height: 20 },
    { left: 30, top: 30, width: 20, height: 20 },
  ]), undefined)
})

test('keeps a partially clipped die aligned to its full square viewport', () => {
  const createViewport = Reflect.get(dicePlayerLayout, 'createDiceRenderViewport') as
    | ((
      slot: { left: number; top: number; width: number; height: number },
      canvas: { left: number; top: number; width: number; height: number },
    ) => {
      viewport: { x: number; y: number; width: number; height: number }
      scissor: { x: number; y: number; width: number; height: number }
    } | undefined)
    | undefined

  assert.equal(typeof createViewport, 'function')
  assert.deepEqual(createViewport?.(
    { left: 70, top: 120, width: 160, height: 160 },
    { left: 100, top: 80, width: 800, height: 520 },
  ), {
    viewport: { x: -30, y: 320, width: 160, height: 160 },
    scissor: { x: 0, y: 320, width: 130, height: 160 },
  })
})
