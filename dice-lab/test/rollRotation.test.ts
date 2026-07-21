import assert from 'node:assert/strict'
import test from 'node:test'

import { continuousRotationTarget, interpolateRotation } from '../src/dice/rollRotation.ts'

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
