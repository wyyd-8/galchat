/** Visual rows never become separate results: each result is presented at its range's last module. */
export function createMobileDiceRevealSteps(
  moduleCount: number,
  groups?: Array<{ moduleStart: number; moduleCount: number }>,
): Array<{ moduleIndex: number; resultIndexes: number[] }> {
  if (moduleCount <= 0) return []
  const ranges = groups?.length ? groups : [{ moduleStart: 0, moduleCount }]
  const steps = new Map<number, number[]>()
  ranges.forEach((group, resultIndex) => {
    const end = Math.min(moduleCount - 1, Math.max(0, group.moduleStart + group.moduleCount - 1))
    steps.set(end, [...(steps.get(end) || []), resultIndex])
  })
  return [...steps].sort(([a], [b]) => a - b).map(([moduleIndex, resultIndexes]) => ({ moduleIndex, resultIndexes }))
}
