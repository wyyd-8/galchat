export interface DicePlayerLayout {
  columns: number
  rows: number
  rowGroupCounts: number[]
  stageMinHeightPx: number
}

export interface DiceOutcomeVfxRect {
  left: number
  top: number
  width: number
  height: number
}

export interface DiceOutcomeVfxLayout {
  leftPx: number
  topPx: number
  sizePx: number
}

export interface DiceViewportRect {
  left: number
  top: number
  width: number
  height: number
}

export interface DiceRenderViewport {
  viewport: { x: number; y: number; width: number; height: number }
  scissor: { x: number; y: number; width: number; height: number }
}

export function createDicePlayerLayout(groupDiceCounts: number[]): DicePlayerLayout {
  const diceCounts = groupDiceCounts.map((count) => Math.max(0, Math.floor(count)))
  if (diceCounts.length === 0) {
    return { columns: 1, rows: 1, rowGroupCounts: [], stageMinHeightPx: 360 }
  }

  const rowGroupCounts: number[] = []
  let currentRowGroupCount = 0
  let currentRowDiceCount = 0
  diceCounts.forEach((diceCount) => {
    const needsNewRow = currentRowGroupCount > 0
      && (currentRowGroupCount >= 4 || currentRowDiceCount + diceCount > 6)
    if (needsNewRow) {
      rowGroupCounts.push(currentRowGroupCount)
      currentRowGroupCount = 0
      currentRowDiceCount = 0
    }
    currentRowGroupCount += 1
    currentRowDiceCount += diceCount
  })
  rowGroupCounts.push(currentRowGroupCount)

  const columns = Math.max(...rowGroupCounts)
  const rows = rowGroupCounts.length

  return {
    columns,
    rows,
    rowGroupCounts,
    stageMinHeightPx: 360 + (rows - 1) * 236,
  }
}

export function createDicePlayerWindowWidth(rowWidths: number[]): number {
  const widestRow = rowWidths.reduce(
    (widest, width) => Number.isFinite(width) ? Math.max(widest, width) : widest,
    0,
  )
  return Math.max(980, Math.ceil(widestRow) + 46)
}

export function createDiceOutcomeVfxLayout(
  scope: 'stage' | 'local',
  group: DiceOutcomeVfxRect,
  surface: DiceOutcomeVfxRect,
): DiceOutcomeVfxLayout {
  if (scope === 'stage') {
    return {
      leftPx: surface.width / 2,
      topPx: surface.height / 2,
      sizePx: Math.max(360, Math.min(680, surface.width * .72, surface.height * 1.5)),
    }
  }
  return {
    leftPx: group.left - surface.left + group.width / 2,
    topPx: group.top - surface.top + group.height / 2,
    sizePx: Math.min(420, Math.max(240, group.width * 1.45, group.height * 1.2)),
  }
}

export function mergeDiceOutcomeVfxRects(
  rects: DiceOutcomeVfxRect[],
): DiceOutcomeVfxRect | undefined {
  if (!rects.length) return undefined
  const left = Math.min(...rects.map((rect) => rect.left))
  const top = Math.min(...rects.map((rect) => rect.top))
  const right = Math.max(...rects.map((rect) => rect.left + rect.width))
  const bottom = Math.max(...rects.map((rect) => rect.top + rect.height))
  return { left, top, width: right - left, height: bottom - top }
}

export function intersectDiceViewportRects(
  rects: DiceViewportRect[],
): DiceViewportRect | undefined {
  if (!rects.length) return undefined
  const left = Math.max(...rects.map((rect) => rect.left))
  const top = Math.max(...rects.map((rect) => rect.top))
  const right = Math.min(...rects.map((rect) => rect.left + rect.width))
  const bottom = Math.min(...rects.map((rect) => rect.top + rect.height))
  if (right <= left || bottom <= top) return undefined
  return { left, top, width: right - left, height: bottom - top }
}

export function createDiceRenderViewport(
  slot: DiceViewportRect,
  canvas: DiceViewportRect,
): DiceRenderViewport | undefined {
  const visible = intersectDiceViewportRects([slot, canvas])
  if (!visible) return undefined
  return {
    viewport: {
      x: slot.left - canvas.left,
      y: canvas.top + canvas.height - (slot.top + slot.height),
      width: slot.width,
      height: slot.height,
    },
    scissor: {
      x: visible.left - canvas.left,
      y: canvas.top + canvas.height - (visible.top + visible.height),
      width: visible.width,
      height: visible.height,
    },
  }
}
