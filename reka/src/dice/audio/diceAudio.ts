import type { DiceOutcomeTone, DicePlaybackPresentation } from '../domain/dicePlayback'

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

interface DiceAudioElement {
  currentTime: number
  preload: string
  play(): Promise<void>
  pause(): void
}

interface DiceAudioRuntime {
  createAudio(url: string): DiceAudioElement
}

interface DiceRollAudioControllerOptions {
  url: string
  runtime?: DiceAudioRuntime
}

const browserAudioRuntime: DiceAudioRuntime = {
  createAudio: (url) => new Audio(url),
}

function playSafely(audio: DiceAudioElement): void {
  void audio.play().catch(() => {
    // Browser autoplay policy may reject background or automatic dice playback.
  })
}

export class DiceRollAudioController {
  private readonly cue: DiceAudioElement

  constructor(options: DiceRollAudioControllerOptions) {
    const runtime = options.runtime || browserAudioRuntime
    this.cue = runtime.createAudio(options.url)
    this.cue.preload = 'auto'
  }

  play(): void {
    this.stop()
    this.cue.currentTime = 0
    playSafely(this.cue)
  }

  stop(): void {
    this.cue.pause()
    this.cue.currentTime = 0
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
