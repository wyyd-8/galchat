import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createIdleSpinLoop,
  IDLE_TURN_DURATION_MS,
  idleSpinAngle,
  randomIdleQuaternion,
  type IdleSpinScheduler,
} from '../src/dice/idleSpin.ts'

test('creates a normalized deterministic quaternion from supplied randomness', () => {
  const values = [0.25, 0.5, 0.75]
  const quaternion = randomIdleQuaternion(() => values.shift() ?? 0)
  const length = Math.hypot(quaternion.x, quaternion.y, quaternion.z, quaternion.w)

  assert.ok(Math.abs(length - 1) < 1e-12)
  assert.ok(Math.abs(quaternion.y + Math.sqrt(0.75)) < 1e-12)
  assert.ok(Math.abs(quaternion.z + 0.5) < 1e-12)
})

test('rotates only in the positive direction at one turn per nine seconds', () => {
  assert.equal(IDLE_TURN_DURATION_MS, 9_000)
  assert.ok(Math.abs(idleSpinAngle(4_500) - Math.PI) < 1e-12)
  assert.equal(idleSpinAngle(-20), 0)
})

test('uses one stoppable frame loop and advances every target by the same angle', () => {
  let nextHandle = 0
  const callbacks = new Map<number, (now: number) => void>()
  const cancelled: number[] = []
  const scheduler: IdleSpinScheduler = {
    request(callback) {
      nextHandle += 1
      callbacks.set(nextHandle, callback)
      return nextHandle
    },
    cancel(handle) {
      cancelled.push(handle)
      callbacks.delete(handle)
    },
  }
  const angles: number[][] = [[], []]
  const renders = [0, 0]
  const loop = createIdleSpinLoop(scheduler)
  loop.start(angles.map((targetAngles, index) => ({
    rotateBy(angleRadians) {
      targetAngles.push(angleRadians)
    },
    render() {
      renders[index] += 1
    },
  })))

  callbacks.get(1)?.(1_000)
  callbacks.get(2)?.(5_500)
  loop.stop()

  assert.deepEqual(renders, [2, 2])
  assert.ok(Math.abs(angles[0][0] - Math.PI) < 1e-12)
  assert.equal(angles[0][0], angles[1][0])
  assert.deepEqual(cancelled, [3])
})

test('cancels the previous frame before restarting', () => {
  let handle = 0
  const cancelled: number[] = []
  const loop = createIdleSpinLoop({
    request() {
      handle += 1
      return handle
    },
    cancel(frameHandle) {
      cancelled.push(frameHandle)
    },
  })

  loop.start([])
  loop.start([])

  assert.deepEqual(cancelled, [1])
})
