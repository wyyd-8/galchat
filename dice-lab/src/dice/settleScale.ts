const FINAL_SCALE = 1.12

export function settleScaleFactor(progress: number): number {
  const clamped = Math.min(Math.max(progress, 0), 1)
  return 1 + (FINAL_SCALE - 1) * clamped
}
