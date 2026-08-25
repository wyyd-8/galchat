import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DiceOutcomeAudioController,
  DiceRollAudioController,
  createDiceEndCuePlan,
  createSingleCheckOutcomeCuePlan,
} from './diceAudio.ts'
import type { DicePlaybackPresentation } from '../domain/dicePlayback.ts'

function singleCheckPresentation(
  outcomeTone: 'critical-success' | 'success' | 'failure' | 'fumble' | 'none',
): DicePlaybackPresentation {
  return {
    kind: 'multiplayer-check',
    resultLabel: '检定结果',
    resultValue: '已结算',
    formulaLabel: '参与者',
    formulaValue: '1 人',
    groupRule: 'SEPARATE',
    groups: [{
      label: '林恩',
      checkName: '侦查',
      outcomeLabel: '已结算',
      outcomeTone,
      success: outcomeTone === 'critical-success' || outcomeTone === 'success',
      moduleStart: 0,
      moduleCount: 1,
    }],
  }
}

test('maps every settled single-check outcome to a cue when its dice values start merging', () => {
  const expected = [
    ['critical-success', { tone: 'critical-success', delayMs: 620 }],
    ['success', { tone: 'success', delayMs: 620 }],
    ['failure', { tone: 'failure', delayMs: 620 }],
    ['fumble', { tone: 'fumble', delayMs: 620 }],
  ] as const
  const mergePlan = [{
    resultGroupIndex: 0,
    revealDelayMs: 1_220,
    groupIndex: 0,
    mergeDelayMs: 620,
  }]

  for (const [tone, plan] of expected) {
    assert.deepEqual(createSingleCheckOutcomeCuePlan(
      singleCheckPresentation(tone),
      mergePlan,
    ), plan)
  }
})

test('does not create outcome cues for multiplayer, opposed, value, or unresolved results', () => {
  const multiplayer = singleCheckPresentation('success')
  multiplayer.groups.push({ ...multiplayer.groups[0]!, label: '陈默', moduleStart: 1 })
  const opposed = { ...singleCheckPresentation('success'), kind: 'opposed-check' as const }
  const valueRoll = { ...singleCheckPresentation('success'), kind: 'value-roll' as const }

  assert.equal(createSingleCheckOutcomeCuePlan(multiplayer, []), undefined)
  assert.equal(createSingleCheckOutcomeCuePlan(opposed, []), undefined)
  assert.equal(createSingleCheckOutcomeCuePlan(valueRoll, []), undefined)
  assert.equal(createSingleCheckOutcomeCuePlan(singleCheckPresentation('none'), []), undefined)
})

test('starts the ending cue late enough for it to finish when the dice settle', () => {
  assert.deepEqual(createDiceEndCuePlan(4_050, 1_384), {
    startDelayMs: 2_666,
    startOffsetSeconds: 0,
  })
})

test('uses only the ending tail when the roll is shorter than the cue', () => {
  assert.deepEqual(createDiceEndCuePlan(360, 1_384), {
    startDelayMs: 0,
    startOffsetSeconds: 1.024,
  })
})

test('plays the start cue immediately and schedules the ending cue', () => {
  const audioElements: Array<{
    url: string
    currentTime: number
    preload: string
    playCount: number
    pauseCount: number
    play: () => Promise<void>
    pause: () => void
  }> = []
  const scheduled: Array<{ callback: () => void, delayMs: number }> = []
  const runtime = {
    createAudio(url: string) {
      const audio = {
        url,
        currentTime: 0,
        preload: '',
        playCount: 0,
        pauseCount: 0,
        play() {
          this.playCount += 1
          return Promise.resolve()
        },
        pause() {
          this.pauseCount += 1
        },
      }
      audioElements.push(audio)
      return audio
    },
    setTimeout(callback: () => void, delayMs: number) {
      scheduled.push({ callback, delayMs })
      return scheduled.length
    },
    clearTimeout() {},
  }
  const controller = new DiceRollAudioController({
    startUrl: '/start.mp3',
    endUrl: '/end.mp3',
    endCueDurationMs: 1_384,
    runtime,
  })

  controller.play(4_050)

  assert.deepEqual(audioElements.map((audio) => audio.url), ['/start.mp3', '/end.mp3'])
  assert.equal(audioElements[0]?.playCount, 1)
  assert.equal(audioElements[1]?.playCount, 0)
  assert.equal(scheduled[0]?.delayMs, 2_666)

  scheduled[0]?.callback()
  assert.equal(audioElements[1]?.currentTime, 0)
  assert.equal(audioElements[1]?.playCount, 1)
})

test('cancels a pending ending cue when playback is stopped', () => {
  let nextTimer = 0
  const pending = new Map<number, () => void>()
  const cleared: number[] = []
  const audioElements: Array<{
    currentTime: number
    preload: string
    playCount: number
    pauseCount: number
    play: () => Promise<void>
    pause: () => void
  }> = []
  const runtime = {
    createAudio() {
      const audio = {
        currentTime: 0,
        preload: '',
        playCount: 0,
        pauseCount: 0,
        play() {
          this.playCount += 1
          return Promise.resolve()
        },
        pause() {
          this.pauseCount += 1
        },
      }
      audioElements.push(audio)
      return audio
    },
    setTimeout(callback: () => void) {
      const id = ++nextTimer
      pending.set(id, callback)
      return id
    },
    clearTimeout(id: number) {
      cleared.push(id)
      pending.delete(id)
    },
  }
  const controller = new DiceRollAudioController({
    startUrl: '/start.mp3',
    endUrl: '/end.mp3',
    endCueDurationMs: 1_384,
    runtime,
  })

  controller.play(4_050)
  controller.stop()

  assert.deepEqual(cleared, [1])
  assert.equal(pending.size, 0)
  assert.equal(audioElements[1]?.playCount, 0)
  assert.equal(audioElements.every((audio) => audio.pauseCount > 0), true)
})

test('plays only the requested single-check outcome cue from its beginning', () => {
  const audioElements: Array<{
    url: string
    currentTime: number
    preload: string
    playCount: number
    pauseCount: number
    play: () => Promise<void>
    pause: () => void
  }> = []
  const runtime = {
    createAudio(url: string) {
      const audio = {
        url,
        currentTime: 7,
        preload: '',
        playCount: 0,
        pauseCount: 0,
        play() {
          this.playCount += 1
          return Promise.resolve()
        },
        pause() {
          this.pauseCount += 1
        },
      }
      audioElements.push(audio)
      return audio
    },
    setTimeout() {
      return 0
    },
    clearTimeout() {},
  }
  const controller = new DiceOutcomeAudioController({
    urls: {
      'critical-success': '/superwin.mp3',
      success: '/win.mp3',
      failure: '/lose.mp3',
      fumble: '/superlose.mp3',
    },
    runtime,
  })

  controller.play('fumble')

  assert.deepEqual(audioElements.map((audio) => audio.url), [
    '/superwin.mp3',
    '/win.mp3',
    '/lose.mp3',
    '/superlose.mp3',
  ])
  assert.equal(audioElements.every((audio) => audio.preload === 'auto'), true)
  assert.deepEqual(audioElements.map((audio) => audio.playCount), [0, 0, 0, 1])
  assert.equal(audioElements[3]?.currentTime, 0)
})
