import type { Conversation, GroupChatEvent } from '../api/types'

export function applyGameTimeEvent(conversation: Conversation, event: GroupChatEvent): Conversation {
  if (event.eventType !== 'game_time.changed' || !event.gameTime) return conversation
  return { ...conversation, gameTime: event.gameTime }
}
