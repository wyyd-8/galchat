const FULL_TURN = Math.PI * 2

export interface EulerRotation {
  x: number
  y: number
  z: number
}

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
