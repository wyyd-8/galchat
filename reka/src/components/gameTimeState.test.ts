import assert from 'node:assert/strict'
import test from 'node:test'
import type { Conversation, GroupChatEvent } from '../api/types.ts'
import { applyGameTimeEvent } from './gameTimeState.ts'

test('applies a game time SSE event without creating a chat message', () => {
  const conversation: Conversation = {
    id: 7, userWorldId: 2, worldId: 1, mode: 'trpg',
    title: '雾港', status: 'active',
  }
  const event: GroupChatEvent = {
    eventType: 'game_time.changed',
    gameTime: {
      dayNo: 2, period: 'EVENING', periodLabel: '晚上',
      displayText: '第二天 - 晚上', revision: 3,
    },
  }

  const updated = applyGameTimeEvent(conversation, event)

  assert.equal(updated.gameTime?.displayText, '第二天 - 晚上')
  assert.equal(updated.gameTime?.revision, 3)
})
