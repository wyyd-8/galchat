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

export function createDicePlayerLayout(groupCount: number): DicePlayerLayout {
  const count = Math.max(0, Math.floor(groupCount))
  if (count === 0) {
    return { columns: 1, rows: 1, rowGroupCounts: [], stageMinHeightPx: 360 }
  }

  const columns = count <= 3 ? count : count === 4 ? 2 : 3
  const rows = Math.ceil(count / columns)
  const minimumPerRow = Math.floor(count / rows)
  const fullerRows = count % rows
  const rowGroupCounts = Array.from(
    { length: rows },
    (_, rowIndex) => minimumPerRow + (rowIndex < fullerRows ? 1 : 0),
  )

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
