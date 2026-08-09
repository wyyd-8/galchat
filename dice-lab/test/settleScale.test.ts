import assert from 'node:assert/strict'
import test from 'node:test'

import { settleCameraDistance, settleScaleFactor } from '../src/dice/settleScale.ts'

test('scales from the original size to 1.12 during reset', () => {
  assert.equal(settleScaleFactor(0), 1)
  assert.equal(settleScaleFactor(0.5), 1.06)
  assert.equal(settleScaleFactor(1), 1.12)
})

test('stays monotonic without a rebound', () => {
  const samples = Array.from({ length: 11 }, (_, index) => settleScaleFactor(index / 10))

  for (let index = 1; index < samples.length; index += 1) {
    assert.ok(samples[index] >= samples[index - 1])
  }
})

test('reserves camera headroom for the enlarged settled die', () => {
  const baseDistance = 4
  const cameraDistance = settleCameraDistance(baseDistance)
  const baselineProjectedScale = 1 / baseDistance
  const settledProjectedScale = settleScaleFactor(1) / cameraDistance

  assert.ok(cameraDistance > baseDistance * settleScaleFactor(1))
  assert.ok(settledProjectedScale < baselineProjectedScale)
})
