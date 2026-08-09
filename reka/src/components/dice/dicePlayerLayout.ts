export interface DicePlayerLayout {
  columns: number
  rows: number
  rowGroupCounts: number[]
  stageMinHeightPx: number
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
