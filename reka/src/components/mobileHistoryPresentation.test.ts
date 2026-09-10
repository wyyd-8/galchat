import test from 'node:test'
import assert from 'node:assert/strict'
import type { DiceHistoryEntry } from '@/dice/domain/dicePlayback'
import { matchesMobileHistoryQuery, mobileHistoryDay, mobileHistoryValues, mobileHistoryTitle, mobileHistoryReasons } from './mobileHistoryPresentation.ts'

const entry: DiceHistoryEntry = {
  messageId: 4, title: '群体侦查', statusLabel: '分别结果', tone: 'default', category: '群体检定', resultKind: 'other',
  aggregate: { summary: { id: 3, conversationId: 1, status: 'COMPLETED' }, results: [
    { id: 1, summaryId: 3, roundNo: 1, resultData: { formula: '1D100', modules: [], result: 99 } },
    { id: 2, summaryId: 3, roundNo: 2, resultData: { formula: '1D100', modules: [], result: 0 }, resolution: { characterName: '林遥', checkName: '侦查', targetValue: 65 } },
    { id: 3, summaryId: 3, roundNo: 2, resultData: { formula: '1D100', modules: [] }, resolution: { characterName: '陈默', checkName: '侦查' } },
  ] },
}
test('history cards keep separate participants, zero values and missing values in the displayed round', () => {
  assert.deepEqual(mobileHistoryValues(entry), [
    { key: 2, name: '林遥 · 侦查', value: 0, target: 65, outcome: null },
    { key: 3, name: '陈默 · 侦查', value: null, target: null, outcome: null },
  ])
})
test('mobile history searches participant and check names as well as the original aggregate title', () => {
  assert.equal(matchesMobileHistoryQuery(entry, '  林遥  '), true)
  assert.equal(matchesMobileHistoryQuery(entry, '侦查'), true)
  assert.equal(matchesMobileHistoryQuery(entry, '群体'), true)
  assert.equal(matchesMobileHistoryQuery(entry, '不存在的角色'), false)
})
test('date headings use the record date and do not turn missing dates into today', () => {
  const now = new Date(2026, 8, 12, 10)
  assert.equal(mobileHistoryDay(new Date(2026, 8, 12, 9).toISOString(), now), '今天')
  assert.equal(mobileHistoryDay(new Date(2026, 8, 11, 9).toISOString(), now), '2026-09-11')
  assert.equal(mobileHistoryDay(undefined, now), '时间未记录')
  assert.equal(mobileHistoryDay('invalid', now), '时间未记录')
})

test('single participant title uses the character and check while retaining the full action reason', () => {
  const single = { ...entry, title: '林遥仔细检查房间中所有遗留物', aggregate: { ...entry.aggregate, summary: { ...entry.aggregate.summary, reason: '请求找出案件线索' }, results: [entry.aggregate.results[1]!] } }
  assert.equal(mobileHistoryTitle(single), '林遥 · 侦查')
  assert.deepEqual(mobileHistoryReasons(single), ['林遥仔细检查房间中所有遗留物', '请求找出案件线索'])
  assert.equal(mobileHistoryTitle(entry), '群体侦查')
  assert.equal(mobileHistoryTitle({ ...single, aggregate: { ...single.aggregate, results: [entry.aggregate.results[0]!] } }), single.title)
})
test('each participant receives only their actual resolved outcome, including ranks and fumbles', () => {
  const group = { ...entry, aggregate: { ...entry.aggregate, results: ['SUCCESS', 'FAILURE', 'FUMBLE', 'CRITICAL_SUCCESS', 'UNKNOWN'].map((category, index) => ({ id: index, summaryId: 3, resolution: { outcome: { category, rank: 'HARD' } } })) } }
  assert.deepEqual(mobileHistoryValues(group).map(value => value.outcome), [
    { label: '困难成功', failure: false }, { label: '失败', failure: true },
    { label: '大失败', failure: true }, { label: '大成功', failure: false }, null,
  ])
})
