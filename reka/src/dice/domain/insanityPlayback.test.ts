import assert from 'node:assert/strict'
import test from 'node:test'
import type { DiceRollAggregate, DiceRollDetail } from '../../api/types.ts'
import { createDiceMessagePlaybackRequest, createDiceMessagePresentation, createDicePlayerSummary, listDiceHistoryEntriesNewestFirst } from './dicePlayback.ts'
import { mobileHistoryValues, mobileHistoryTitle } from '../../components/mobileHistoryPresentation.ts'

function detail(id: number, source: number | undefined, kind: 'TYPE' | 'DURATION', name = '巴里'): DiceRollDetail {
  const value = kind === 'TYPE' ? 8 : 4
  return {
    id, summaryId: 1, roundNo: 3, displayOrder: id, characterId: source,
    displayType: `TEMPORARY_INSANITY_${kind}`, reason: `${name}临时疯狂${kind === 'TYPE' ? '类型' : '持续时间'}`,
    resolvedAt: '2026-10-03T12:00:00',
    resultData: { formula: '1D10', result: value, modules: [{ expression: '1D10', diceCount: 1, diceSides: 10, modifier: 'NORMAL', result: value, dice: [{ sides: 10, value, selected: true, role: 'NORMAL' }] }] },
    resolution: { type: `TEMPORARY_INSANITY_${kind}`, sourceResultId: source, characterName: name,
      outcome: kind === 'TYPE' ? { typeRoll: 8, display: '惊慌逃窜：调查员已身处远方。' } : { durationHours: 4 } },
  }
}
function aggregate(results = [detail(1, 20, 'TYPE'), detail(2, 20, 'DURATION')]): DiceRollAggregate {
  return { summary: { id: 1, conversationId: 1, status: 'COMPLETED', toolName: 'requestSanCheck', reason: '原始理智检定', totalResult: '过时的总结果' }, results }
}
function entry(a: DiceRollAggregate) {
  return listDiceHistoryEntriesNewestFirst([{ id: 1, conversationId: 1, speakerType: 'kp', messageKind: 'dice_roll', content: '', sequenceNo: 1, status: 'completed', diceRoll: a }])[0]!
}

test('insanity uses symptom and hours consistently in playback, chat and history without modifying saved dice', () => {
  const a = aggregate()
  const before = structuredClone(a)
  const request = createDiceMessagePlaybackRequest(0, a, 'classic')
  assert.equal(request.reason, '巴里 · 临时疯狂')
  assert.equal(request.windowTone, 'sanity')
  assert.equal(request.presentation?.kind, 'value-roll')
  assert.equal(request.presentation?.participantCount, 1)
  assert.deepEqual(request.result.modules.map(m => m.expression), ['1D10（症状）', '1D10（持续时间）'])
  assert.deepEqual(request.presentation?.groups.map(g => [g.checkName, g.outcomeLabel, g.outcomeTone]), [
    ['1D10（症状）', '惊慌逃窜', 'none'], ['1D10（持续时间）', '4 小时', 'none'],
  ])
  assert.equal(createDicePlayerSummary(request.result, 'classic', request.presentation).modifierLabel, '临时疯狂')
  assert.deepEqual(createDiceMessagePresentation(a), { title: '巴里 · 临时疯狂', statusLabel: '惊慌逃窜 · 4 小时', tone: 'sanity' })
  const history = entry(a)
  assert.equal(history.category, '临时疯狂')
  assert.equal(mobileHistoryTitle(history), '巴里 · 临时疯狂')
  assert.deepEqual(mobileHistoryValues(history).map(v => [v.name, v.value]), [['巴里 · 症状', '惊慌逃窜'], ['巴里 · 持续时间', '4 小时']])
  assert.deepEqual(a, before)
})

test('insanity pairs by source rather than ordering or names and ignores earlier rounds', () => {
  const a = aggregate([detail(1, 20, 'TYPE'), detail(2, 30, 'DURATION'), detail(3, 30, 'TYPE'), detail(4, 20, 'DURATION')])
  a.results[1]!.resolution!.outcome = { durationHours: 9 }
  a.results[2]!.resolution!.outcome = { display: '恐惧症（恐高症：害怕高处）' }
  a.results.unshift({ ...detail(9, 10, 'TYPE'), roundNo: 2 })
  assert.equal(createDiceMessagePresentation(a).statusLabel, '巴里：惊慌逃窜 · 4 小时；巴里：恐惧症（恐高症） · 9 小时')
  assert.equal(createDiceMessagePlaybackRequest(0, a, 'classic').presentation?.participantCount, 1)
})

test('pending insanity preserves labels without exposing placeholder dice as outcomes', () => {
  const a = aggregate(aggregate().results.map(d => ({ ...d, resolvedAt: undefined, resolution: { ...d.resolution, outcome: undefined } })))
  assert.equal(createDiceMessagePresentation(a).statusLabel, '未投掷')
  assert.equal(createDiceMessagePresentation(a).title, '巴里 · 临时疯狂')
  const request = createDiceMessagePlaybackRequest(0, a, 'classic')
  assert.deepEqual(request.presentation?.groups.map(g => g.outcomeLabel), ['待确定', '待确定'])
  assert.deepEqual(mobileHistoryValues(entry(a)).map(v => v.value), ['待确定', '待确定'])
})

test('partial and legacy insanity records show only available semantic information', () => {
  const duration = aggregate([detail(2, 20, 'DURATION')])
  assert.equal(createDiceMessagePresentation(duration).statusLabel, '4 小时')
  assert.equal(mobileHistoryTitle(entry(duration)), '巴里 · 临时疯狂')
  const missing = detail(1, undefined, 'TYPE')
  missing.resolution = undefined
  assert.equal(createDiceMessagePresentation(aggregate([missing])).statusLabel, '症状未记录')
  const incomplete = aggregate([detail(1, undefined, 'TYPE'), detail(2, undefined, 'DURATION')])
  assert.equal(createDiceMessagePresentation(incomplete).statusLabel, '巴里：惊慌逃窜；巴里：4 小时')
})

test('rendered chat and mobile history expose Chinese insanity results and the full symptom explanation', async context => {
  const { createServer } = await import('vite')
  const { default: vue } = await import('@vitejs/plugin-vue')
  const { fileURLToPath } = await import('node:url')
  const { createSSRApp, h } = await import('vue')
  const { renderToString } = await import('vue/server-renderer')
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: History } = await vite.ssrLoadModule('/src/components/MobileDiceHistory.vue')
  const { default: Chat } = await vite.ssrLoadModule('/src/dice/components/DiceRollMessage.vue')
  const a = aggregate()
  const html = await renderToString(createSSRApp({ render: () => h(History, {
    query: '', category: '', result: '', allCount: 1, matchCopy: '', filtersActive: false,
    loadingOlder: false, hasOlder: false, loadLabel: '', loadHint: '', entries: [entry(a)],
  }) }))
  assert.match(html, /巴里 · 临时疯狂/)
  assert.match(html, /惊慌逃窜 · 4 小时/)
  assert.match(html, /title="惊慌逃窜：调查员已身处远方。"/)
  assert.doesNotMatch(html, /TEMPORARY_INSANITY|已结算/)
  const chat = await renderToString(createSSRApp({ render: () => h(Chat, { aggregate: a }) }))
  assert.match(chat, /is-insanity/)
  assert.match(chat, /惊慌逃窜 · 4 小时/)
})
