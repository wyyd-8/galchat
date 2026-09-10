export type ReasoningPhase = 'thinking' | 'main' | 'idle'

export function syncReasoningDisclosure(
  open: Record<number, boolean>,
  phases: Map<number, ReasoningPhase>,
  step: number,
  phase: ReasoningPhase,
  autoExpand = true,
) {
  const previous = phases.get(step)
  if (phase === 'thinking' && previous !== 'thinking') open[step] = autoExpand
  else if (previous === 'thinking' && phase !== 'thinking') open[step] = false
  phases.set(step, phase)
}
