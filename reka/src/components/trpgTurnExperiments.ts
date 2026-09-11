import type { CurrentTurn, DiceRollAggregate } from '../api/types'
import { isDiceAggregatePending } from '../dice/domain/dicePlayback.ts'

export function isBetweenTrpgTurns(currentTurn: CurrentTurn | null): boolean {
  return currentTurn == null || currentTurn.status === 'completed'
}

export function mergeAutoAdvanceDiceSummaryIds(
  current: ReadonlySet<number>,
  incoming: readonly DiceRollAggregate[],
  enabled: boolean,
): Set<number> {
  if (!enabled) return new Set(current)
  return new Set([
    ...current,
    ...incoming
      .filter((aggregate) => !isDiceAggregatePending(aggregate))
      .map((aggregate) => aggregate.summary.id),
  ])
}

export interface CountdownController {
  start(): void
  cancel(): void
  running(): boolean
}

interface CountdownOptions {
  seconds: number
  schedule?: (callback: () => void, delayMs: number) => unknown
  cancelScheduled?: (handle: unknown) => void
  onTick: (remaining: number) => void
  onComplete: () => void
}

export function createCountdownController(options: CountdownOptions): CountdownController {
  const schedule = options.schedule
    ?? ((callback, delayMs) => window.setTimeout(callback, delayMs))
  const cancelScheduled = options.cancelScheduled
    ?? ((handle) => window.clearTimeout(handle as number))
  let active = false
  let remaining = 0
  let scheduled: unknown
  let generation = 0

  function cancel() {
    generation += 1
    active = false
    if (scheduled !== undefined) cancelScheduled(scheduled)
    scheduled = undefined
  }

  function queueNext(activeGeneration: number) {
    scheduled = schedule(() => {
      if (!active || generation !== activeGeneration) return
      scheduled = undefined
      remaining -= 1
      options.onTick(remaining)
      if (remaining <= 0) {
        active = false
        options.onComplete()
        return
      }
      queueNext(activeGeneration)
    }, 1_000)
  }

  function start() {
    cancel()
    if (options.seconds <= 0) {
      options.onTick(0)
      options.onComplete()
      return
    }
    active = true
    remaining = options.seconds
    const activeGeneration = generation
    options.onTick(remaining)
    queueNext(activeGeneration)
  }

  return {
    start,
    cancel,
    running: () => active,
  }
}
