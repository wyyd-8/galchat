import assert from 'node:assert/strict'
import test from 'node:test'
import { createCarePolling, carePollingWindow } from '../composables/carePolling.ts'
import type { CareMessagePage, ChatHistory } from '../api/types.ts'

const time = (hhmm: string) => Date.parse(`2026-09-28T${hhmm}:00+08:00`)
const empty = (cursor = 0): CareMessagePage => ({ messages: [], nextCursor: cursor, hasMore: false })
async function settle() { for (let i = 0; i < 8; i++) await Promise.resolve() }
function fixture(hhmm = '07:59') {
  let now = time(hhmm); let visible = true; let id = 0
  const timers = new Map<number, { at: number; callback: () => void }>()
  const cursors = new Map<string, number>()
  const calls: Array<{ worldId: number; after?: number; signal: AbortSignal }> = []
  const received: ChatHistory[] = []
  let fetch: (after?: number) => Promise<CareMessagePage> = async after => empty(after)
  const poller = createCarePolling({
    now: () => now, visible: () => visible,
    schedule(callback, delay) { const key = ++id; timers.set(key, { at: now + delay, callback }); return () => { timers.delete(key) } },
    readCursor: key => cursors.get(key), writeCursor: (key, cursor) => { cursors.set(key, cursor) },
    fetchPage: (worldId, after, signal) => { calls.push({ worldId, after, signal }); return fetch(after) },
    receive: messages => received.push(...messages),
  })
  async function advance(ms: number) {
    const end = now + ms
    while (true) {
      const entry = [...timers.entries()].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!entry) break
      now = entry[1].at; timers.delete(entry[0]); entry[1].callback(); await settle()
    }
    now = end; await settle()
  }
  return { poller, calls, cursors, received, timers, advance,
    fetch(value: typeof fetch) { fetch = value },
    visibility(value: boolean) { visible = value; poller.visibilityChanged() },
  }
}

test('nested pauses block foreground refresh until every history mutation finishes', async () => {
  const f = fixture('08:00')
  f.cursors.set('a', 10)
  let finish!: (page: CareMessagePage) => void
  f.fetch(() => new Promise(resolve => { finish = resolve }))
  f.poller.start('a', 3)
  f.poller.pause(); f.poller.pause()
  assert.equal(f.calls[0]!.signal.aborted, true)
  f.visibility(true); await f.poller.refresh(); await f.advance(60_000)
  f.poller.resume(); await settle()
  assert.equal(f.calls.length, 1)
  f.fetch(async () => ({ messages: [{ id: 12 }], nextCursor: 12, hasMore: false }))
  f.poller.resume(); await settle()
  assert.equal(f.calls.length, 2)
  assert.equal(f.calls[1]!.after, 10)
  finish({ messages: [{ id: 11 }], nextCursor: 11, hasMore: false }); await settle()
  assert.deepEqual(f.received.map(item => item.id), [12])
  assert.equal(f.cursors.get('a'), 12)
  f.poller.stop()
})

test('uses four Beijing windows of exactly forty minutes including timezone and day rollover', () => {
  for (const hour of [8, 13, 19, 21]) {
    assert.equal(carePollingWindow(time(`${hour.toString().padStart(2, '0')}:00`)).active, true)
    assert.equal(carePollingWindow(time(`${hour.toString().padStart(2, '0')}:39`) + 59_999).active, true)
    assert.equal(carePollingWindow(time(`${hour.toString().padStart(2, '0')}:40`)).active, false)
  }
  assert.deepEqual(carePollingWindow(Date.parse('2026-09-28T00:00:00Z')), { active: true, delay: 60_000 })
  assert.equal(carePollingWindow(time('23:00')).delay, 9 * 60 * 60_000)
})

test('checks on world entry then polls once per minute only inside the window', async () => {
  const f = fixture(); f.poller.start('account:world', 3); await settle()
  assert.equal(f.calls.length, 1)
  await f.advance(60_000); assert.equal(f.calls.length, 2)
  await f.advance(39 * 60_000); assert.equal(f.calls.length, 41)
  await f.advance(60_000); assert.equal(f.calls.length, 41)
  await f.advance(4 * 60 * 60_000); assert.equal(f.calls.length, 41)
  await f.advance(20 * 60_000); assert.equal(f.calls.length, 42)
  f.poller.stop(); assert.equal(f.timers.size, 0)
})

test('pauses while hidden and catches up immediately on foreground even outside a window', async () => {
  const f = fixture('08:00'); f.poller.start('a', 3); await settle()
  f.visibility(false); assert.equal(f.timers.size, 0)
  await f.advance(2 * 60 * 60_000); assert.equal(f.calls.length, 1)
  f.visibility(true); await settle(); assert.equal(f.calls.length, 2)
  await f.advance(60_000); assert.equal(f.calls.length, 2)
  f.poller.stop()
})

test('keeps cursor after failure and drains pages without losing messages across restart', async () => {
  const f = fixture('13:00'); f.cursors.set('a', 10)
  f.fetch(async () => { throw new Error('offline') })
  f.poller.start('a', 3); await settle(); assert.equal(f.cursors.get('a'), 10)
  f.fetch(async after => after === 10
    ? { messages: [{ id: 11 }], nextCursor: 11, hasMore: true }
    : { messages: [{ id: 12 }], nextCursor: 12, hasMore: false })
  await f.advance(60_000)
  assert.deepEqual(f.calls.map(call => call.after), [10, 10, 11])
  assert.deepEqual(f.received.map(item => item.id), [11, 12])
  assert.equal(f.cursors.get('a'), 12)
  f.fetch(async after => empty(after)); f.poller.start('a', 3); await settle()
  assert.equal(f.calls.at(-1)!.after, 12)
  f.poller.stop()
})

test('aborts old world requests and ignores late results after switching worlds or logging out', async () => {
  const f = fixture('19:00'); let resolve!: (page: CareMessagePage) => void
  f.fetch(() => new Promise(r => { resolve = r }))
  f.poller.start('old-account:3', 3)
  f.fetch(async () => empty(70)); f.poller.start('new-account:4', 4); await settle()
  assert.equal(f.calls[0]!.signal.aborted, true)
  resolve({ messages: [{ id: 99 }], nextCursor: 99, hasMore: false }); await settle()
  assert.equal(f.cursors.has('old-account:3'), false)
  assert.deepEqual(f.received, [])
  f.fetch(() => new Promise(r => { resolve = r })); void f.poller.refresh()
  f.poller.stop(); resolve({ messages: [{ id: 100 }], nextCursor: 100, hasMore: false }); await settle()
  assert.equal(f.cursors.get('new-account:4'), 70)
  assert.deepEqual(f.received, [])
  assert.equal(f.timers.size, 0)
})

test('does not overlap requests and aborts an overdue query', async () => {
  const f = fixture('21:00'); let resolve!: (page: CareMessagePage) => void
  f.fetch(() => new Promise(r => { resolve = r })); f.poller.start('a', 3)
  void f.poller.refresh(); assert.equal(f.calls.length, 1)
  await f.advance(45_000); assert.equal(f.calls[0]!.signal.aborted, true)
  resolve({ messages: [{ id: 10 }], nextCursor: 10, hasMore: false }); await settle()
  assert.deepEqual(f.received, []); assert.equal(f.cursors.has('a'), false)
  f.fetch(async () => empty(20)); await f.advance(60_000)
  assert.equal(f.calls.length, 2); assert.equal(f.cursors.get('a'), 20)
  f.poller.stop()
})
