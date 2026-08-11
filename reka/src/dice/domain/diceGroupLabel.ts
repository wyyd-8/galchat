export function formatDiceGroupLabel(moduleStart: number, moduleCount: number): string {
  const first = Math.max(0, moduleStart) + 1
  const last = first + Math.max(1, moduleCount) - 1
  return first === last ? `第 ${first} 组` : `第 ${first}–${last} 组`
}
