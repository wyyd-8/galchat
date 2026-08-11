export interface DiceGroupMergePlan {
  delayMs: number
  fadeDelayMs: number
  moveDurationMs: number
  valueOffsets: number[]
}

interface RectPosition { left: number; top: number }
interface RectSize extends RectPosition { width: number; height: number }

export function shouldMergeDiceModuleValues(placeholder: boolean): boolean {
  return !placeholder
}

export function createDiceValueMergeTokenLayout(
  valueRect: RectSize,
  moduleRect: RectPosition,
  groupCenter: number,
) {
  const valueCenter = valueRect.left + valueRect.width / 2
  return {
    left: valueCenter - moduleRect.left,
    top: valueRect.top + valueRect.height / 2 - moduleRect.top,
    offsetX: groupCenter - valueCenter,
  }
}

export function createDiceGroupMergePlan(
  valueCenters: number[],
  groupCenter: number,
  groupIndex: number,
): DiceGroupMergePlan {
  return {
    delayMs: 620 + Math.max(0, groupIndex) * 280,
    fadeDelayMs: 210,
    moveDurationMs: 520,
    valueOffsets: valueCenters.map((center) => groupCenter - center),
  }
}
