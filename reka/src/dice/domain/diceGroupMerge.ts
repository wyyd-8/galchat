export interface DiceGroupMergePlan {
  delayMs: number
  fadeDelayMs: number
  moveDurationMs: number
  valueOffsets: number[]
}

export interface DiceGroupMergeSequencePlan {
  rowFocuses: Array<{
    rowIndex: number
    delayMs: number
    durationMs: number
  }>
  groups: Array<{
    groupIndex: number
    rowIndex: number
    mergeDelayMs: number
    revealDelayMs: number
  }>
  completionDelayMs: number
}

export interface DiceResultRevealPlan {
  resultGroupIndex: number
  revealDelayMs: number
}

interface RectPosition { left: number; top: number }
interface RectSize extends RectPosition { width: number; height: number }

interface DiceRowScrollOptions {
  reducedMotion: boolean
  durationMs: number
  signal?: AbortSignal
  now?: () => number
  requestFrame?: (callback: (timeMs: number) => void) => number
}

const INITIAL_MERGE_DELAY_MS = 620
const ROW_SCROLL_DURATION_MS = 420
const GROUP_MERGE_STAGGER_MS = 280
const GROUP_MERGE_COMPLETION_MS = 600

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

export function createDiceGroupMergeSequencePlan(
  rowGroupCounts: number[],
  mergeableGroups: boolean[],
): DiceGroupMergeSequencePlan {
  const rowFocuses: DiceGroupMergeSequencePlan['rowFocuses'] = []
  const groups: DiceGroupMergeSequencePlan['groups'] = []
  let groupIndex = 0
  let previousRowCompletionMs = 0

  rowGroupCounts.forEach((rawGroupCount, rowIndex) => {
    const groupCount = Math.max(0, Math.floor(rawGroupCount))
    if (groupCount === 0) return
    const focusDelayMs = previousRowCompletionMs
    const rowMergeStartMs = Math.max(
      INITIAL_MERGE_DELAY_MS,
      focusDelayMs + ROW_SCROLL_DURATION_MS,
    )
    rowFocuses.push({
      rowIndex,
      delayMs: focusDelayMs,
      durationMs: ROW_SCROLL_DURATION_MS,
    })

    let mergeOrder = 0
    let rowCompletionMs = rowMergeStartMs
    for (let rowGroupIndex = 0; rowGroupIndex < groupCount; rowGroupIndex += 1) {
      const mergeable = mergeableGroups[groupIndex] !== false
      const mergeDelayMs = rowMergeStartMs + mergeOrder * GROUP_MERGE_STAGGER_MS
      const revealDelayMs = mergeable
        ? mergeDelayMs + GROUP_MERGE_COMPLETION_MS
        : rowMergeStartMs
      groups.push({ groupIndex, rowIndex, mergeDelayMs, revealDelayMs })
      if (mergeable) mergeOrder += 1
      rowCompletionMs = Math.max(rowCompletionMs, revealDelayMs)
      groupIndex += 1
    }
    previousRowCompletionMs = rowCompletionMs
  })

  return {
    rowFocuses,
    groups,
    completionDelayMs: previousRowCompletionMs,
  }
}

export function createDiceResultRevealPlan(
  resultGroups: Array<{ moduleStart: number, moduleCount: number }>,
  moduleRevealDelays: number[],
): DiceResultRevealPlan[] {
  return resultGroups.map((group, resultGroupIndex) => {
    const delays = moduleRevealDelays.slice(
      Math.max(0, group.moduleStart),
      Math.max(0, group.moduleStart + group.moduleCount),
    )
    return {
      resultGroupIndex,
      revealDelayMs: delays.length ? Math.max(...delays) : 0,
    }
  })
}

export function calculateDiceRowScrollTarget(input: {
  scrollTop: number
  scrollHeight: number
  clientHeight: number
  viewportBottom: number
  rowBottom: number
}): number {
  const maxScrollTop = Math.max(0, input.scrollHeight - input.clientHeight)
  const target = input.scrollTop + input.rowBottom - input.viewportBottom
  return Math.min(maxScrollTop, Math.max(0, target))
}

export function scrollDiceRowIntoView(
  scrollElement: {
    scrollTop: number
    scrollHeight: number
    clientHeight: number
    getBoundingClientRect: () => { bottom: number }
  },
  row: { getBoundingClientRect: () => { bottom: number } },
  options: DiceRowScrollOptions,
): number {
  const target = calculateDiceRowScrollTarget({
    scrollTop: scrollElement.scrollTop,
    scrollHeight: scrollElement.scrollHeight,
    clientHeight: scrollElement.clientHeight,
    viewportBottom: scrollElement.getBoundingClientRect().bottom,
    rowBottom: row.getBoundingClientRect().bottom,
  })
  const start = scrollElement.scrollTop
  if (options.signal?.aborted) return target
  if (options.reducedMotion || options.durationMs <= 0 || target === start) {
    scrollElement.scrollTop = target
    return target
  }

  const requestFrame = options.requestFrame
    || ((callback: (timeMs: number) => void) => window.requestAnimationFrame(callback))
  const startedAt = options.now?.() ?? performance.now()
  const step = (timeMs: number) => {
    if (options.signal?.aborted) return
    const progress = Math.min(1, Math.max(0, (timeMs - startedAt) / options.durationMs))
    const easedProgress = progress < 0.5
      ? 4 * progress ** 3
      : 1 - (-2 * progress + 2) ** 3 / 2
    scrollElement.scrollTop = start + (target - start) * easedProgress
    if (progress < 1) requestFrame(step)
  }
  requestFrame(step)
  return target
}

export function findDiceStageScrollHost<T>(
  element: { closest: (selector: string) => T | null },
): T | undefined {
  return element.closest('.dice-player-stage-scroll')
    || element.closest('.dialog-body')
    || undefined
}
