const FULL_TURN = Math.PI * 2

export interface EulerRotation {
  x: number
  y: number
  z: number
}

export interface DiceAnimationGroupTiming {
  moduleStart: number
  moduleCount: number
  startDelayMs: number
}

const DIE_STAGGER_MS = 90

function unwrapFrom(start: number, target: number, turns: number): number {
  const positiveDelta = ((target - start) % FULL_TURN + FULL_TURN) % FULL_TURN
  return start + positiveDelta + turns * FULL_TURN
}

export function continuousRotationTarget(
  start: EulerRotation,
  target: EulerRotation,
  turns: EulerRotation,
): EulerRotation {
  return {
    x: unwrapFrom(start.x, target.x, turns.x),
    y: unwrapFrom(start.y, target.y, turns.y),
    z: unwrapFrom(start.z, target.z, turns.z),
  }
}

export function interpolateRotation(
  start: EulerRotation,
  end: EulerRotation,
  progress: number,
): EulerRotation {
  return {
    x: start.x + (end.x - start.x) * progress,
    y: start.y + (end.y - start.y) * progress,
    z: start.z + (end.z - start.z) * progress,
  }
}

export function createDiceStartDelays(
  moduleDiceCounts: number[],
  groups?: DiceAnimationGroupTiming[],
): number[] {
  const totalDice = moduleDiceCounts.reduce((total, count) => total + Math.max(0, count), 0)
  const delays = Array.from({ length: totalDice }, (_, index) => index * DIE_STAGGER_MS)
  if (!groups?.length) return delays

  const moduleOffsets: number[] = []
  moduleDiceCounts.reduce((offset, count, moduleIndex) => {
    moduleOffsets[moduleIndex] = offset
    return offset + Math.max(0, count)
  }, 0)
  for (const group of groups) {
    let groupDieIndex = 0
    const groupEnd = Math.min(moduleDiceCounts.length, group.moduleStart + group.moduleCount)
    for (let moduleIndex = Math.max(0, group.moduleStart); moduleIndex < groupEnd; moduleIndex += 1) {
      const count = Math.max(0, moduleDiceCounts[moduleIndex] || 0)
      const offset = moduleOffsets[moduleIndex] || 0
      for (let dieIndex = 0; dieIndex < count; dieIndex += 1) {
        delays[offset + dieIndex] = Math.max(0, group.startDelayMs) + groupDieIndex * DIE_STAGGER_MS
        groupDieIndex += 1
      }
    }
  }
  return delays
}
