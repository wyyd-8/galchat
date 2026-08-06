import type { GroupChatEvent, GroupMessage, ReplyPlanItem } from '../api/types'

export type ReplyTurnPhase = 'starting' | 'running' | 'completed' | 'failed'
export type ReplyActorPhase = 'waiting' | 'replying' | 'completed' | 'failed'

export interface ReplyTurnState {
  turnId?: number
  phase: ReplyTurnPhase
  activeActorId?: number
  error?: string
}

export function beginReplyTurn(): ReplyTurnState {
  return { phase: 'starting' }
}

export function updateReplyTurn(current: ReplyTurnState, event: GroupChatEvent): ReplyTurnState
export function updateReplyTurn(current: null, event: GroupChatEvent): ReplyTurnState | null
export function updateReplyTurn(current: ReplyTurnState | null, event: GroupChatEvent): ReplyTurnState | null
export function updateReplyTurn(current: ReplyTurnState | null, event: GroupChatEvent): ReplyTurnState | null {
  if (event.eventType === 'turn.accepted' && event.turnId) {
    return { turnId: event.turnId, phase: 'running' }
  }
  if (current?.turnId && event.turnId && current.turnId !== event.turnId) return current
  if (event.eventType === 'reply.started' && event.turnId) {
    return { turnId: event.turnId, phase: 'running', activeActorId: event.speaker?.id }
  }
  if (event.eventType === 'turn.completed' && (event.turnId || current)) {
    return { turnId: event.turnId ?? current?.turnId, phase: 'completed' }
  }
  if (event.eventType === 'reply.failed' && (event.turnId || current)) {
    return {
      turnId: event.turnId ?? current?.turnId,
      phase: 'failed',
      activeActorId: event.speaker?.id ?? current?.activeActorId,
      error: event.error,
    }
  }
  return current
}

export function replyActorPhase(state: ReplyTurnState, item: ReplyPlanItem, messages: GroupMessage[]): ReplyActorPhase {
  if (state.phase === 'failed' && state.activeActorId === item.actorId) return 'failed'
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message.turnId !== state.turnId || message.speakerId !== item.actorId || message.speakerType !== item.actorType) continue
    if (message.status === 'failed') return 'failed'
    if (message.status === 'completed') return 'completed'
    if (message.status === 'streaming') return 'replying'
  }
  return 'waiting'
}
