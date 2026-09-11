import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeGenerationResponseEvents } from './generationErrorFormatting.ts'

test('merges only adjacent streaming deltas that belong to the same message', () => {
  const response = {
    eventCount: 8,
    events: [
      { eventType: 'reply.started', turnId: 41, replyStepId: 42, messageId: 43 },
      { eventType: 'reasoning.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '先' },
      { eventType: 'reasoning.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '判断' },
      { eventType: 'message.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '你' },
      { eventType: 'message.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '好。' },
      { eventType: 'message.delta', turnId: 41, replyStepId: 44, messageId: 45, delta: '另一条' },
      { eventType: 'message.completed', turnId: 41, replyStepId: 42, messageId: 43, content: '你好。' },
      { eventType: 'reasoning.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '补充' },
    ],
  }

  const displayed = mergeGenerationResponseEvents(response)

  assert.deepEqual(displayed, {
    eventCount: 8,
    events: [
      { eventType: 'reply.started', turnId: 41, replyStepId: 42, messageId: 43 },
      { eventType: 'reasoning.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '先判断' },
      { eventType: 'message.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '你好。' },
      { eventType: 'message.delta', turnId: 41, replyStepId: 44, messageId: 45, delta: '另一条' },
      { eventType: 'message.completed', turnId: 41, replyStepId: 42, messageId: 43, content: '你好。' },
      { eventType: 'reasoning.delta', turnId: 41, replyStepId: 42, messageId: 43, delta: '补充' },
    ],
  })
  assert.equal(response.events.length, 8)
  assert.equal(response.events[1]?.delta, '先')
})
