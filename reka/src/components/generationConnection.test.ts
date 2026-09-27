import assert from 'node:assert/strict'
import test from 'node:test'
import { followGeneration } from '../streaming/generationConnection.ts'

type Event = { sequence?: number; content?: string; done?: boolean }

test('retries only recovery, keeps the cursor and ignores duplicated transport events', async () => {
  const controller = new AbortController()
  const messages: string[] = []
  const cursors: number[] = []
  let sends = 0
  await followGeneration<Event>({
    signal: controller.signal,
    initial: async receive => { sends++; receive({ sequence: 1, content: 'A' }); throw new Error('lost transport') },
    resume: async (receive, after) => {
      cursors.push(after)
      receive({ sequence: 1, content: 'A' })
      receive({ sequence: 2, content: 'B' })
      receive({ sequence: 3, done: true })
    },
    sequence: event => event.sequence,
    terminal: event => !!event.done,
    receive: event => { if (event.content) messages.push(event.content) },
  })
  assert.equal(sends, 1)
  assert.deepEqual(cursors, [1])
  assert.deepEqual(messages, ['A', 'B'])
})

test('cancelling during retry backoff prevents reconnect and late event delivery', async () => {
  const controller = new AbortController()
  let lateReceive!: (event: Event) => void
  let reconnects = 0
  const messages: string[] = []
  const task = followGeneration<Event>({
    signal: controller.signal,
    initial: async receive => { lateReceive = receive; throw new Error('disconnect') },
    resume: async () => { reconnects++ },
    sequence: event => event.sequence,
    terminal: event => !!event.done,
    receive: event => { if (event.content) messages.push(event.content) },
  })
  await new Promise(resolve => setImmediate(resolve))
  controller.abort()
  lateReceive({ sequence: 1, content: 'stale' })
  await assert.rejects(task, { name: 'AbortError' })
  assert.equal(reconnects, 0)
  assert.deepEqual(messages, [])
})

test('an EOF without a terminal event is retried, with a bounded number of attempts', async () => {
  let attempts = 0
  await assert.rejects(followGeneration<Event>({
    signal: new AbortController().signal,
    resume: async () => { attempts++ },
    sequence: event => event.sequence,
    terminal: event => !!event.done,
    receive() {},
  }), /回复连接中断/)
  assert.equal(attempts, 3)
})
