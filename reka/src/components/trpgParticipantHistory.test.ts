import test from 'node:test'
import assert from 'node:assert/strict'
import { createParticipantHistory } from './trpgParticipantHistory.ts'
import type { TrpgParticipantHistory, TrpgParticipantRunPage } from '../api/types.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
const summary = (id: number): TrpgParticipantHistory[] => [{ characterId: id, completedRunCount: 0, latestRun: null }]
const page = (id: number, cursor: string | null = null): TrpgParticipantRunPage => ({
  items: [{ conversationId: id, title: '历史跑团', moduleId: 1, moduleName: '图书馆', status: 'completed', completedAt: '2026-09-01T12:00:00', lastPlayedAt: null }], nextCursor: cursor,
})

test('a slow previous world response never overwrites the current world', async () => {
  const old = deferred<TrpgParticipantHistory[]>()
  const history = createParticipantHistory({
    participantHistory: async (worldId) => worldId === 1 ? old.promise : summary(22),
    participantRuns: async () => page(1),
  })
  const first = history.loadWorld(1)
  await history.loadWorld(2)
  old.resolve(summary(11))
  await first
  assert.deepEqual(history.state.summaries, summary(22))
  assert.equal(history.state.summaryLoading, false)
})

test('changing the viewed character discards stale pages and loading state', async () => {
  const old = deferred<TrpgParticipantRunPage>()
  const history = createParticipantHistory({ participantHistory: async () => summary(11),
    participantRuns: async (_world, character) => character === 11 ? old.promise : page(22) })
  await history.loadWorld(1)
  const first = history.viewCharacter(11)
  await history.viewCharacter(22)
  old.resolve(page(11, 'old'))
  await first
  assert.equal(history.state.runs[0]?.conversationId, 22)
  assert.equal(history.state.nextCursor, null)
  assert.equal(history.state.runsLoading, false)
})

test('pagination failures keep current records and cursor available for retry', async () => {
  let calls = 0
  const history = createParticipantHistory({ participantHistory: async () => summary(11),
    participantRuns: async (_world, _character, cursor) => {
      if (!cursor) return page(1, 'next')
      if (++calls === 1) throw new Error('暂时离线')
      assert.equal(cursor, 'next')
      return page(2)
    } })
  await history.loadWorld(1)
  await history.viewCharacter(11)
  await history.loadMore()
  assert.equal(history.state.runs.length, 1)
  assert.equal(history.state.nextCursor, 'next')
  assert.equal(history.state.runsError, '暂时离线')
  await history.loadMore()
  assert.deepEqual(history.state.runs.map(run => run.conversationId), [1,2])
  assert.equal(history.state.runsError, '')
})

test('failed summaries are distinguishable from zero history and retry updates counts', async () => {
  let fail = true
  const history = createParticipantHistory({ participantHistory: async () => {
    if (fail) throw new Error('网络错误')
    return summary(11)
  }, participantRuns: async () => page(1) })
  await history.loadWorld(1)
  assert.equal(history.state.summaryError, '网络错误')
  assert.equal(history.state.summaryLoading, false)
  fail = false
  await history.reloadSummaries()
  assert.equal(history.state.summaryError, '')
  assert.deepEqual(history.state.summaries, summary(11))
})

test('closing invalidates pending requests', async () => {
  const pending = deferred<TrpgParticipantHistory[]>()
  const history = createParticipantHistory({ participantHistory: async () => pending.promise, participantRuns: async () => page(1) })
  const loading = history.loadWorld(1)
  history.dispose()
  pending.resolve(summary(11))
  await loading
  assert.deepEqual(history.state.summaries, [])
})
