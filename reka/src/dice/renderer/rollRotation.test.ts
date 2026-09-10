import assert from 'node:assert/strict'
import test from 'node:test'

import {
  continuousRotationTarget,
  createDicePhysicalSettleDelay,
  interpolateRotation,
} from './rollRotation.ts'
import * as rollRotation from './rollRotation.ts'

const FULL_TURN = Math.PI * 2
const moduloTurn = (value: number): number => ((value % FULL_TURN) + FULL_TURN) % FULL_TURN

test('keeps the current visible rotation at zero progress', () => {
  const start = { x: 0.8, y: -1.1, z: 2.4 }
  const target = continuousRotationTarget(
    start,
    { x: -0.3, y: 0.4, z: -2 },
    { x: 3, y: 4, z: 2 },
  )

  assert.deepEqual(interpolateRotation(start, target, 0), start)
})

test('ends at an equivalent target after positive full turns', () => {
  const start = { x: 0.8, y: -1.1, z: 2.4 }
  const desired = { x: -0.3, y: 0.4, z: -2 }
  const end = continuousRotationTarget(start, desired, { x: 3, y: 4, z: 2 })

  assert.ok(end.x > start.x && end.y > start.y && end.z > start.z)
  assert.ok(Math.abs(moduloTurn(end.x) - moduloTurn(desired.x)) < 1e-12)
  assert.ok(Math.abs(moduloTurn(end.y) - moduloTurn(desired.y)) < 1e-12)
  assert.ok(Math.abs(moduloTurn(end.z) - moduloTurn(desired.z)) < 1e-12)
})

test('maps character group starts onto every die while preserving the group-internal stagger', () => {
  const createStartDelays = Reflect.get(rollRotation, 'createDiceStartDelays') as
    | ((
      moduleDiceCounts: number[],
      groups?: Array<{ moduleStart: number; moduleCount: number; startDelayMs: number }>,
    ) => number[])
    | undefined

  assert.equal(typeof createStartDelays, 'function')
  assert.deepEqual(createStartDelays?.([2, 1, 3], [
    { moduleStart: 0, moduleCount: 2, startDelayMs: 0 },
    { moduleStart: 2, moduleCount: 1, startDelayMs: 220 },
  ]), [0, 90, 180, 220, 310, 400])
})

test('keeps the original global stagger when no first-play timing is supplied', () => {
  const createStartDelays = Reflect.get(rollRotation, 'createDiceStartDelays') as
    | ((moduleDiceCounts: number[]) => number[])
    | undefined

  assert.equal(typeof createStartDelays, 'function')
  assert.deepEqual(createStartDelays?.([2, 1, 3]), [0, 90, 180, 270, 360, 450])
})

test('reports when the last staggered physical die will settle', () => {
  assert.equal(createDicePhysicalSettleDelay([2, 1, 3]), 4_050)
  assert.equal(createDicePhysicalSettleDelay([2, 1, 3], [
    { moduleStart: 0, moduleCount: 2, startDelayMs: 0 },
    { moduleStart: 2, moduleCount: 1, startDelayMs: 220 },
  ]), 4_000)
  assert.equal(createDicePhysicalSettleDelay([2, 1], undefined, true), 360)
})

test('mobile simultaneous mode removes every per-die and per-participant delay', () => {
  const delays = rollRotation.createDiceStartDelays as (...args: unknown[]) => number[]
  assert.deepEqual(delays([4, 0, 2, 1], [
    { moduleStart: 0, moduleCount: 1, startDelayMs: 900 },
    { moduleStart: 2, moduleCount: 2, startDelayMs: 1500 },
  ], true), [0, 0, 0, 0, 0, 0, 0])
})
