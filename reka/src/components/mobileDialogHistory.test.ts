import assert from 'node:assert/strict'
import test from 'node:test'
import { createMobileDialogHistory, type DialogHistoryPort } from '../composables/mobileDialogHistory.ts'

function fakeHistory() {
  const pages = [{ state: { route: 'previous' } as unknown, url: '/previous' }, { state: { route: 'chat', position: 42 } as unknown, url: '/chat' }]
  let index = 1
  const listeners = new Set<() => void>()
  const queue: number[] = []
  const deferred: Array<() => void> = []
  const move = (delta: number) => {
    const next = Math.max(0, Math.min(pages.length - 1, index + delta))
    if (next === index) return
    index = next
    for (const listener of [...listeners]) listener()
  }
  const port: DialogHistoryPort = {
    state: () => pages[index]!.state,
    url: () => pages[index]!.url,
    push: state => { pages.splice(index + 1); pages.push({ state, url: pages[index]!.url }); index++ },
    back: () => { queue.push(-1) },
    defer: task => { deferred.push(task) },
    listen: listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
  }
  return {
    port, pages, listeners, queue, deferred,
    get index() { return index },
    systemBack: () => move(-1),
    forward: () => move(1),
    flush: () => { while (deferred.length) deferred.shift()!(); while (queue.length) move(queue.shift()!) },
    foreignPush: (state: unknown, url: string) => { pages.splice(index + 1); pages.push({ state, url }); index++ },
  }
}

test('nested mobile dialogs use one sentinel and Back dismisses only the top layer', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  manager.register(() => { closed.push('parent') })
  manager.register(() => { closed.push('child') })
  assert.equal(history.index, 2)
  assert.equal(history.listeners.size, 1)
  history.systemBack()
  assert.deepEqual(closed, ['child'])
  assert.equal(history.index, 2, 'remaining parent gets a fresh sentinel')
  history.systemBack()
  assert.deepEqual(closed, ['child', 'parent'])
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
  assert.deepEqual(history.port.state(), { route: 'chat', position: 42 })
  history.systemBack()
  assert.equal(history.port.url(), '/previous', 'next Back navigates normally')
})

test('UI closes remove the sentinel asynchronously without closing any other dialog', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  const parent = manager.register(() => { closed.push('parent') })
  const child = manager.register(() => { closed.push('child') })
  child()
  assert.equal(history.queue.length, 0)
  parent()
  parent()
  assert.equal(history.deferred.length, 1, 'cleanup is idempotent')
  history.flush()
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
  assert.deepEqual(closed, [])
})

test('rapid UI close and reopen waits for the old sentinel pop before arming again', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  manager.register(() => { closed.push('first') })()
  manager.register(() => { closed.push('second') })
  history.flush()
  assert.equal(history.index, 2)
  assert.deepEqual(closed, [])
  history.systemBack()
  assert.deepEqual(closed, ['second'])
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('repeated open and close cycles leave no extra Back steps or active listeners', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  for (let n = 0; n < 5; n++) {
    manager.register(() => assert.fail('UI close must not call Back dismissal'))()
    history.flush()
    assert.equal(history.index, 1)
    assert.equal(history.listeners.size, 0)
  }
  history.systemBack()
  assert.equal(history.port.url(), '/previous')
})

test('unmounting a parent first retains the active child, including synchronous close cleanup', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  const parent = manager.register(() => { closed.push('parent') })
  let child: () => void
  child = manager.register(() => { closed.push('child'); child() })
  parent()
  history.systemBack()
  assert.deepEqual(closed, ['child'])
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('foreign history entries and state are never popped by UI cleanup', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const unregister = manager.register(() => assert.fail('foreign navigation must not close dialogs'))
  const foreignState = { route: 'editor', draft: 'keep this' }
  history.foreignPush(foreignState, '/editor')
  unregister()
  history.flush()
  assert.equal(history.port.url(), '/editor')
  assert.equal(history.port.state(), foreignState)
  assert.equal(history.listeners.size, 0)
})

test('Back landing on the sentinel from foreign history does not consume that navigation', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  let closed = 0
  manager.register(() => { closed++ })
  history.foreignPush({ route: 'editor' }, '/editor')
  history.systemBack()
  assert.equal(closed, 0)
  assert.equal(history.port.url(), '/chat')
  history.systemBack()
  assert.equal(closed, 1)
  assert.deepEqual(history.port.state(), { route: 'chat', position: 42 })
})

test('Forward to an old sentinel never resurrects a disposed dialog', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  let closed = 0
  manager.register(() => { closed++ })()
  history.flush()
  history.forward()
  assert.equal(closed, 0)
  assert.equal(history.listeners.size, 0)
})


test('a new dialog opened after Forward reuses the orphan sentinel without reopening the old one', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  manager.register(() => { closed.push('old') })()
  history.flush()
  history.forward()
  manager.register(() => { closed.push('new') })
  history.systemBack()
  assert.deepEqual(closed, ['new'])
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('same-turn foreign navigation cancels pending cleanup without traversing its entry', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  manager.register(() => assert.fail('already disposed'))()
  history.foreignPush({ route: 'next' }, '/next')
  history.flush()
  assert.equal(history.port.url(), '/next')
  assert.deepEqual(history.port.state(), { route: 'next' })
  assert.equal(history.listeners.size, 0)
})


test('a rejected close retains the editor beneath the confirmation it opens', () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  let dirty = true
  manager.register(() => {
    if (!dirty) { closed.push('editor'); return true }
    manager.register(() => { closed.push('confirmation'); return true })
    return false
  })
  history.systemBack()
  assert.deepEqual(closed, [])
  assert.equal(history.index, 2)
  history.systemBack()
  assert.deepEqual(closed, ['confirmation'])
  dirty = false
  history.systemBack()
  assert.deepEqual(closed, ['confirmation', 'editor'])
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('asynchronous close rejection keeps the new confirmation above the original registration', async () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  const closed: string[] = []
  let decide!: (accepted: boolean) => void
  const editor = manager.register(() => new Promise<boolean>(resolve => { decide = resolve }))
  history.systemBack()
  manager.register(() => { closed.push('confirmation') })
  decide(false)
  await Promise.resolve()
  history.systemBack()
  assert.deepEqual(closed, ['confirmation'])
  assert.equal(history.index, 2, 'editor remains registered and armed below the confirmation')
  editor()
  history.flush()
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('async acceptance removes only its own entry, preserving a newly registered dialog', async () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  let decide!: (accepted: boolean) => void
  let nextClosed = 0
  manager.register(() => new Promise<boolean>(resolve => { decide = resolve }))
  history.systemBack()
  manager.register(() => { nextClosed++ })
  decide(true)
  await Promise.resolve()
  history.systemBack()
  assert.equal(nextClosed, 1)
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})

test('disposing a pending registration prevents late rejection from resurrecting it', async () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  let decide!: (accepted: boolean) => void
  const dispose = manager.register(() => new Promise<boolean>(resolve => { decide = resolve }))
  history.systemBack()
  dispose()
  history.flush()
  decide(false)
  await Promise.resolve()
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
  history.systemBack()
  assert.equal(history.port.url(), '/previous')
})

test('repeated Back while an async decision is pending does not request closing twice', async () => {
  const history = fakeHistory()
  const manager = createMobileDialogHistory(history.port, 'test')
  let decide!: (accepted: boolean) => void
  let requests = 0
  manager.register(() => { requests++; return new Promise<boolean>(resolve => { decide = resolve }) })
  history.systemBack()
  history.systemBack()
  assert.equal(requests, 1)
  decide(true)
  await Promise.resolve()
  history.flush()
  assert.equal(history.index, 1)
  assert.equal(history.listeners.size, 0)
})
