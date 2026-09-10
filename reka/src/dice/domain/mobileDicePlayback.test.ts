import test from 'node:test'
import assert from 'node:assert/strict'
import { createMobileDiceRevealSteps } from './mobileDicePlayback.ts'

test('mobile reveal visits only final covered modules, skipping intermediate dice rows and groups', () => {
  assert.deepEqual(createMobileDiceRevealSteps(5, [
    { moduleStart: 0, moduleCount: 2 },
    { moduleStart: 2, moduleCount: 1 },
    { moduleStart: 3, moduleCount: 2 },
  ]), [
    { moduleIndex: 1, resultIndexes: [0] },
    { moduleIndex: 2, resultIndexes: [1] },
    { moduleIndex: 4, resultIndexes: [2] },
  ])
})

test('raw numeric rolls show their complete footer once after the final module, including zero-dice placeholders', () => {
  assert.deepEqual(createMobileDiceRevealSteps(3), [{ moduleIndex: 2, resultIndexes: [0] }])
  assert.deepEqual(createMobileDiceRevealSteps(1, [{ moduleStart: 0, moduleCount: 1 }]), [{ moduleIndex: 0, resultIndexes: [0] }])
  assert.deepEqual(createMobileDiceRevealSteps(0), [])
})

test('overlapping result ranges share one scroll stop and retain both results', () => {
  assert.deepEqual(createMobileDiceRevealSteps(2, [
    { moduleStart: 0, moduleCount: 2 }, { moduleStart: 1, moduleCount: 1 },
  ]), [{ moduleIndex: 1, resultIndexes: [0, 1] }])
})
