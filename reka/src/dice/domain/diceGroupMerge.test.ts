import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createDiceGroupMergePlan,
  createDiceValueMergeTokenLayout,
  shouldMergeDiceModuleValues,
} from './diceGroupMerge.ts'
import * as diceGroupMerge from './diceGroupMerge.ts'

test('keeps placeholder values below their empty dice slots', () => {
  assert.equal(shouldMergeDiceModuleValues(true), false)
  assert.equal(shouldMergeDiceModuleValues(false), true)
})

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

test('finishes scrolling to each dice row before that row starts merging', () => {
  const createSequence = Reflect.get(diceGroupMerge, 'createDiceGroupMergeSequencePlan') as
    | ((rowGroupCounts: number[], mergeableGroups: boolean[]) => unknown)
    | undefined

  assert.equal(typeof createSequence, 'function')
  assert.deepEqual(createSequence?.([2, 2], [true, true, true, true]), {
    rowFocuses: [
      { rowIndex: 0, delayMs: 0, durationMs: 420 },
      { rowIndex: 1, delayMs: 1_500, durationMs: 420 },
    ],
    groups: [
      { groupIndex: 0, rowIndex: 0, mergeDelayMs: 620, revealDelayMs: 1_220 },
      { groupIndex: 1, rowIndex: 0, mergeDelayMs: 900, revealDelayMs: 1_500 },
      { groupIndex: 2, rowIndex: 1, mergeDelayMs: 1_920, revealDelayMs: 2_520 },
      { groupIndex: 3, rowIndex: 1, mergeDelayMs: 2_200, revealDelayMs: 2_800 },
    ],
    completionDelayMs: 2_800,
  })
})

test('reveals a result only after every dice module in that result group has merged', () => {
  const createRevealPlan = Reflect.get(diceGroupMerge, 'createDiceResultRevealPlan') as
    | ((groups: Array<{ moduleStart: number, moduleCount: number }>, moduleRevealDelays: number[]) => unknown)
    | undefined

  assert.equal(typeof createRevealPlan, 'function')
  assert.deepEqual(createRevealPlan?.([
    { moduleStart: 0, moduleCount: 2 },
    { moduleStart: 2, moduleCount: 2 },
  ], [1_220, 1_500, 2_520, 2_800]), [
    { resultGroupIndex: 0, revealDelayMs: 1_500 },
    { resultGroupIndex: 1, revealDelayMs: 2_800 },
  ])
})

test('aligns the active row with the bottom of the visible stage without exceeding scroll bounds', () => {
  const calculateTarget = Reflect.get(diceGroupMerge, 'calculateDiceRowScrollTarget') as
    | ((input: {
        scrollTop: number
        scrollHeight: number
        clientHeight: number
        viewportBottom: number
        rowBottom: number
      }) => number)
    | undefined

  assert.equal(typeof calculateTarget, 'function')
  assert.equal(calculateTarget?.({
    scrollTop: 0,
    scrollHeight: 1_000,
    clientHeight: 400,
    viewportBottom: 500,
    rowBottom: 300,
  }), 0)
  assert.equal(calculateTarget?.({
    scrollTop: 100,
    scrollHeight: 1_000,
    clientHeight: 400,
    viewportBottom: 500,
    rowBottom: 650,
  }), 250)
  assert.equal(calculateTarget?.({
    scrollTop: 550,
    scrollHeight: 1_000,
    clientHeight: 400,
    viewportBottom: 500,
    rowBottom: 700,
  }), 600)
})

test('uses the independently scrolling dice stage as the renderer scroll host', () => {
  const findScrollHost = Reflect.get(diceGroupMerge, 'findDiceStageScrollHost') as
    | (<T>(element: { closest: (selector: string) => T | null }) => T | undefined)
    | undefined
  const stage = { name: 'stage' }
  const dialogBody = { name: 'dialog-body' }
  const selectors: string[] = []

  assert.equal(typeof findScrollHost, 'function')
  assert.equal(findScrollHost?.({
    closest(selector) {
      selectors.push(selector)
      if (selector === '.dice-player-stage-scroll') return stage
      if (selector === '.dialog-body') return dialogBody
      return null
    },
  }), stage)
  assert.deepEqual(selectors, ['.dice-player-stage-scroll'])

  assert.equal(findScrollHost?.({
    closest(selector) {
      return selector === '.dialog-body' ? dialogBody : null
    },
  }), dialogBody)
})

test('honors the planned row-scroll duration instead of delegating timing to native smooth scrolling', () => {
  const scrollRow = Reflect.get(diceGroupMerge, 'scrollDiceRowIntoView') as
    | ((scrollElement: {
        scrollTop: number
        scrollHeight: number
        clientHeight: number
        getBoundingClientRect: () => { bottom: number }
      }, row: {
        getBoundingClientRect: () => { bottom: number }
      }, options: {
        reducedMotion: boolean
        durationMs: number
        now: () => number
        requestFrame: (callback: (timeMs: number) => void) => number
      }) => number)
    | undefined
  const frameCallbacks: Array<(timeMs: number) => void> = []
  const scrollElement = {
    scrollTop: 100,
    scrollHeight: 1_000,
    clientHeight: 400,
    getBoundingClientRect: () => ({ bottom: 500 }),
  }
  const runNextFrame = (timeMs: number) => {
    const callback = frameCallbacks.shift()
    assert.ok(callback, `expected a scheduled animation frame at ${timeMs}ms`)
    callback(timeMs)
  }

  assert.equal(typeof scrollRow, 'function')
  const target = scrollRow?.(scrollElement, {
    getBoundingClientRect: () => ({ bottom: 650 }),
  }, {
    reducedMotion: false,
    durationMs: 420,
    now: () => 0,
    requestFrame: (callback) => {
      frameCallbacks.push(callback)
      return frameCallbacks.length
    },
  })

  assert.equal(target, 250)
  assert.equal(scrollElement.scrollTop, 100)

  runNextFrame(16)
  assert.ok(scrollElement.scrollTop > 100 && scrollElement.scrollTop < 105)
  runNextFrame(210)
  assert.equal(scrollElement.scrollTop, 175)
  runNextFrame(420)
  assert.equal(scrollElement.scrollTop, 250)
  assert.equal(frameCallbacks.length, 0)
})

test('stops a pending row scroll when its playback is cancelled', () => {
  const scrollRow = Reflect.get(diceGroupMerge, 'scrollDiceRowIntoView') as
    | ((scrollElement: {
        scrollTop: number
        scrollHeight: number
        clientHeight: number
        getBoundingClientRect: () => { bottom: number }
      }, row: {
        getBoundingClientRect: () => { bottom: number }
      }, options: {
        reducedMotion: boolean
        durationMs: number
        signal: AbortSignal
        requestFrame: (callback: (timeMs: number) => void) => number
      }) => number)
    | undefined
  const frameCallbacks: Array<(timeMs: number) => void> = []
  const scrollElement = {
    scrollTop: 100,
    scrollHeight: 1_000,
    clientHeight: 400,
    getBoundingClientRect: () => ({ bottom: 500 }),
  }
  const playback = new AbortController()

  assert.equal(typeof scrollRow, 'function')
  scrollRow?.(scrollElement, {
    getBoundingClientRect: () => ({ bottom: 650 }),
  }, {
    reducedMotion: false,
    durationMs: 420,
    signal: playback.signal,
    requestFrame: (callback) => {
      frameCallbacks.push(callback)
      return frameCallbacks.length
    },
  })

  const firstFrame = frameCallbacks.shift()
  assert.ok(firstFrame)
  firstFrame(0)
  playback.abort()

  const pendingFrame = frameCallbacks.shift()
  assert.ok(pendingFrame)
  pendingFrame(210)
  assert.equal(scrollElement.scrollTop, 100)
  assert.equal(frameCallbacks.length, 0)
})
