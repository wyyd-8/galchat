import assert from 'node:assert/strict'
import test from 'node:test'
import type { GroupChatEvent, GroupMessage, ReplyPlanItem } from '../api/types.ts'
import { beginReplyTurn, replyActorPhase, updateReplyTurn } from './replyTurnStatus.ts'

const event = (eventType: GroupChatEvent['eventType'], values: Partial<GroupChatEvent> = {}): GroupChatEvent => ({ eventType, ...values })
const item = (actorId: number): ReplyPlanItem => ({ actorType: 'character', actorId, order: 1 })
const message = (actorId: number, status: string): GroupMessage => ({
  id: actorId,
  conversationId: 7,
  turnId: 42,
  replyStepId: actorId,
  speakerType: 'character',
  speakerId: actorId,
  messageKind: 'dialogue',
  content: '',
  sequenceNo: actorId,
  status,
})

test('tracks a normal group turn from sending through completion', () => {
  let state = beginReplyTurn()
  assert.equal(state.phase, 'starting')

  state = updateReplyTurn(state, event('turn.accepted', { turnId: 42 }))
  assert.deepEqual(state, { turnId: 42, phase: 'running' })

  state = updateReplyTurn(state, event('reply.started', { turnId: 42, speaker: { type: 'character', id: 22 } }))
  assert.deepEqual(state, { turnId: 42, phase: 'running', activeActorId: 22 })

  state = updateReplyTurn(state, event('turn.completed', { turnId: 42 }))
  assert.deepEqual(state, { turnId: 42, phase: 'completed' })
})

test('reports each actor progress from messages in the current turn', () => {
  const state = { turnId: 42, phase: 'running' as const, activeActorId: 22 }
  const messages = [message(11, 'completed'), message(22, 'streaming')]

  assert.equal(replyActorPhase(state, item(11), messages), 'completed')
  assert.equal(replyActorPhase(state, item(22), messages), 'replying')
  assert.equal(replyActorPhase(state, item(33), messages), 'waiting')
})

test('marks the active actor failed when the turn fails', () => {
  const state = updateReplyTurn(
    { turnId: 42, phase: 'running' as const, activeActorId: 22 },
    event('reply.failed', { turnId: 42, error: '模型调用失败' }),
  )

  assert.equal(state.phase, 'failed')
  assert.equal(state.error, '模型调用失败')
  assert.equal(replyActorPhase(state, item(22), [message(22, 'streaming')]), 'failed')
})
