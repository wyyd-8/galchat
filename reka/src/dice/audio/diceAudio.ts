import type { DiceOutcomeTone, DicePlaybackPresentation } from '../domain/dicePlayback'

export interface DiceEndCuePlan {
  startDelayMs: number
  startOffsetSeconds: number
}

export type AudibleDiceOutcomeTone = Exclude<DiceOutcomeTone, 'none'>

export interface DiceOutcomeCuePlan {
  tone: AudibleDiceOutcomeTone
  delayMs: number
}

export function createSingleCheckOutcomeCuePlan(
  presentation: DicePlaybackPresentation | undefined,
  mergePlan: Array<{ groupIndex: number, mergeDelayMs: number }>,
): DiceOutcomeCuePlan | undefined {
  if (presentation?.kind !== 'multiplayer-check' || presentation.groups.length !== 1) {
    return undefined
  }
  const group = presentation.groups[0]
  const tone = group?.outcomeTone
  if (!tone || tone === 'none') return undefined
  const groupMergeDelays = mergePlan
    .filter((result) => (
      result.groupIndex >= group.moduleStart
      && result.groupIndex < group.moduleStart + group.moduleCount
    ))
    .map((result) => result.mergeDelayMs)
  return {
    tone,
    delayMs: Math.max(0, groupMergeDelays.length ? Math.min(...groupMergeDelays) : 0),
  }
}

export function createDiceEndCuePlan(
  settleAfterMs: number,
  endCueDurationMs: number,
): DiceEndCuePlan {
  const settleDelay = Math.max(0, settleAfterMs)
  const cueDuration = Math.max(0, endCueDurationMs)
  return {
    startDelayMs: Math.max(0, settleDelay - cueDuration),
    startOffsetSeconds: Math.max(0, cueDuration - settleDelay) / 1_000,
  }
}

interface DiceAudioElement {
  currentTime: number
  preload: string
  play(): Promise<void>
  pause(): void
}

interface DiceAudioRuntime {
  createAudio(url: string): DiceAudioElement
  setTimeout(callback: () => void, delayMs: number): number
  clearTimeout(handle: number): void
}

interface DiceRollAudioControllerOptions {
  startUrl: string
  endUrl: string
  endCueDurationMs: number
  runtime?: DiceAudioRuntime
}

const browserAudioRuntime: DiceAudioRuntime = {
  createAudio: (url) => new Audio(url),
  setTimeout: (callback, delayMs) => window.setTimeout(callback, delayMs),
  clearTimeout: (handle) => window.clearTimeout(handle),
}

function playSafely(audio: DiceAudioElement): void {
  void audio.play().catch(() => {
    // Browser autoplay policy may reject background or automatic dice playback.
  })
}

export class DiceRollAudioController {
  private readonly startCue: DiceAudioElement
  private readonly endCue: DiceAudioElement
  private readonly endCueDurationMs: number
  private readonly runtime: DiceAudioRuntime
  private endTimer?: number

  constructor(options: DiceRollAudioControllerOptions) {
    this.runtime = options.runtime || browserAudioRuntime
    this.endCueDurationMs = Math.max(0, options.endCueDurationMs)
    this.startCue = this.runtime.createAudio(options.startUrl)
    this.endCue = this.runtime.createAudio(options.endUrl)
    this.startCue.preload = 'auto'
    this.endCue.preload = 'auto'
  }

  play(settleAfterMs: number): void {
    this.stop()
    this.startCue.currentTime = 0
    playSafely(this.startCue)

    const plan = createDiceEndCuePlan(settleAfterMs, this.endCueDurationMs)
    this.endTimer = this.runtime.setTimeout(() => {
      this.endTimer = undefined
      this.endCue.currentTime = plan.startOffsetSeconds
      playSafely(this.endCue)
    }, plan.startDelayMs)
  }

  stop(): void {
    if (this.endTimer !== undefined) {
      this.runtime.clearTimeout(this.endTimer)
      this.endTimer = undefined
    }
    for (const cue of [this.startCue, this.endCue]) {
      cue.pause()
      cue.currentTime = 0
    }
  }
}

interface DiceOutcomeAudioControllerOptions {
  urls: Record<AudibleDiceOutcomeTone, string>
  runtime?: DiceAudioRuntime
}

const AUDIBLE_DICE_OUTCOME_TONES: AudibleDiceOutcomeTone[] = [
  'critical-success',
  'success',
  'failure',
  'fumble',
]

export class DiceOutcomeAudioController {
  private readonly cues: Record<AudibleDiceOutcomeTone, DiceAudioElement>

  constructor(options: DiceOutcomeAudioControllerOptions) {
    const runtime = options.runtime || browserAudioRuntime
    this.cues = Object.fromEntries(AUDIBLE_DICE_OUTCOME_TONES.map((tone) => {
      const cue = runtime.createAudio(options.urls[tone])
      cue.preload = 'auto'
      return [tone, cue]
    })) as Record<AudibleDiceOutcomeTone, DiceAudioElement>
  }

  play(tone: AudibleDiceOutcomeTone): void {
    this.stop()
    const cue = this.cues[tone]
    cue.currentTime = 0
    playSafely(cue)
  }

  stop(): void {
    for (const cue of Object.values(this.cues)) {
      cue.pause()
      cue.currentTime = 0
    }
  }
}
