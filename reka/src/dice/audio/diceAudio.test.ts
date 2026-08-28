import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DiceOutcomeAudioController,
  DiceRollAudioController,
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

test('plays one combined roll cue immediately without scheduling another cue', () => {
  const audioElements: Array<{
    url: string
    currentTime: number
    preload: string
    playCount: number
    pauseCount: number
    play: () => Promise<void>
    pause: () => void
  }> = []
  let scheduledCount = 0
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
    setTimeout() {
      scheduledCount += 1
      return scheduledCount
    },
    clearTimeout() {},
  }
  const controller = new DiceRollAudioController({
    url: '/combined-roll.mp3',
    runtime,
  })

  controller.play()

  assert.deepEqual(audioElements.map((audio) => audio.url), ['/combined-roll.mp3'])
  assert.equal(audioElements[0]?.playCount, 1)
  assert.equal(audioElements[0]?.currentTime, 0)
  assert.equal(scheduledCount, 0)
})

test('stops the combined roll cue', () => {
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
    setTimeout() { return 0 },
    clearTimeout() {},
  }
  const controller = new DiceRollAudioController({
    url: '/combined-roll.mp3',
    runtime,
  })

  controller.play()
  controller.stop()

  assert.equal(audioElements.length, 1)
  assert.equal(audioElements[0]?.pauseCount, 2)
  assert.equal(audioElements[0]?.currentTime, 0)
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
