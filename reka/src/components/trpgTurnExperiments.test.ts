import assert from 'node:assert/strict'
import test from 'node:test'
import type { CurrentTurn } from '../api/types.ts'
import type { DiceRollAggregate } from '../api/types.ts'
import {
  createCountdownController,
  isBetweenTrpgTurns,
  mergeAutoAdvanceDiceSummaryIds,
} from './trpgTurnExperiments.ts'

function turn(status: string): CurrentTurn {
  return {
    turnId: 42,
    status,
    waitingForUser: false,
    sceneOptions: {},
    steps: [],
  }
}

test('shows experimental turn settings only before the first turn or after a completed turn', () => {
  assert.equal(isBetweenTrpgTurns(null), true)
  assert.equal(isBetweenTrpgTurns(turn('completed')), true)
  assert.equal(isBetweenTrpgTurns(turn('paused')), false)
  assert.equal(isBetweenTrpgTurns(turn('waiting_dice')), false)
  assert.equal(isBetweenTrpgTurns(turn('failed')), false)
})

test('counts down from three and completes exactly once', () => {
  const scheduled: Array<() => void> = []
  const ticks: number[] = []
  let completions = 0
  const countdown = createCountdownController({
    seconds: 3,
    schedule(callback) {
      scheduled.push(callback)
      return callback
    },
    cancelScheduled() {},
    onTick: (remaining) => ticks.push(remaining),
    onComplete: () => { completions += 1 },
  })

  countdown.start()
  scheduled.shift()?.()
  scheduled.shift()?.()
  scheduled.shift()?.()

  assert.deepEqual(ticks, [3, 2, 1, 0])
  assert.equal(completions, 1)
  assert.equal(countdown.running(), false)
})

test('cancelling a countdown prevents its scheduled completion', () => {
  const scheduled: Array<() => void> = []
  const cancelled: unknown[] = []
  let completions = 0
  const countdown = createCountdownController({
    seconds: 3,
    schedule(callback) {
      scheduled.push(callback)
      return callback
    },
    cancelScheduled(handle) { cancelled.push(handle) },
    onTick() {},
    onComplete: () => { completions += 1 },
  })

  countdown.start()
  countdown.cancel()
  scheduled.shift()?.()

  assert.equal(cancelled.length, 1)
  assert.equal(completions, 0)
  assert.equal(countdown.running(), false)
})

test('only completed non-user dice pauses are eligible for auto continue', () => {
  const pendingUserRoll = {
    summary: { id: 11, status: 'PENDING' },
    results: [{ id: 101, roundNo: 1 }],
  } as unknown as DiceRollAggregate
  const completedAgentRoll = {
    summary: { id: 12, status: 'COMPLETED' },
    results: [{ id: 102, roundNo: 1, resolvedAt: '2026-09-04T00:00:00' }],
  } as unknown as DiceRollAggregate

  assert.deepEqual(
    [...mergeAutoAdvanceDiceSummaryIds(
      new Set([10]), [pendingUserRoll, completedAgentRoll], true,
    )],
    [10, 12],
  )
  assert.deepEqual(
    [...mergeAutoAdvanceDiceSummaryIds(
      new Set([10]), [completedAgentRoll], false,
    )],
    [10],
  )
})
