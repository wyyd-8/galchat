const FINAL_SCALE = 1.12
const CAMERA_HEADROOM = 1.04

export function settleScaleFactor(progress: number): number {
  const clamped = Math.min(Math.max(progress, 0), 1)
  return 1 + (FINAL_SCALE - 1) * clamped
}

export function settleCameraDistance(baseDistance: number): number {
  return baseDistance * FINAL_SCALE * CAMERA_HEADROOM
}
