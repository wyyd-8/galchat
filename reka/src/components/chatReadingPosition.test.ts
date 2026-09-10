import assert from 'node:assert/strict'
import test from 'node:test'
import { scrollConversationToLatest } from './reasoningScroll.ts'
import { rememberChatReadingPosition, restoreChatReadingPosition } from './chatReadingPosition.ts'

function viewport(top: number, ids: string[], height = 1600) {
  const view = {
    scrollTop: top, scrollHeight: height, clientHeight: 400,
    getBoundingClientRect: () => ({ top: 60 }),
    querySelectorAll(selector: string) {
      if (selector !== '[data-message-id]') return []
      return ids.map((id, index) => ({ dataset: { messageId: id },
        getBoundingClientRect: () => ({ top: 60 + index * 200 - view.scrollTop, bottom: 260 + index * 200 - view.scrollTop }),
      }))
    },
  }
  return view as unknown as HTMLElement
}
test('restores the visible message offset after older messages are inserted', () => {
  const before = viewport(450, ['1', '2', '3', '4', '5', '6', '7', '8'])
  rememberChatReadingPosition('test:read:1', before)
  const after = viewport(0, ['old-1', 'old-2', '1', '2', '3', '4', '5', '6', '7', '8'], 2000)
  assert.equal(restoreChatReadingPosition('test:read:1', after, true), true)
  assert.equal(after.scrollTop, 850)
  scrollConversationToLatest(after)
  assert.equal(after.scrollTop, 850)
})
test('requests another history page when the saved anchor has not loaded and handles deleted anchors', () => {
  rememberChatReadingPosition('test:read:2', viewport(250, ['a', 'b', 'c', 'd']))
  const after = viewport(0, ['later-1', 'later-2'])
  assert.equal(restoreChatReadingPosition('test:read:2', after, true), false)
  assert.equal(after.scrollTop, 0)
  assert.equal(restoreChatReadingPosition('test:read:2', after, false), true)
  assert.equal(after.scrollTop, 250)
})
test('a conversation left at the bottom returns to latest and does not inherit another world position', () => {
  rememberChatReadingPosition('test:world:1:latest', viewport(1200, ['1', '2']))
  const after = viewport(0, ['1', '2'], 1900)
  assert.equal(restoreChatReadingPosition('test:world:1:latest', after, true), true)
  assert.equal(after.scrollTop, 1900)
  const differentWorld = viewport(0, ['1'], 900)
  assert.equal(restoreChatReadingPosition('test:world:2:latest', differentWorld, true), true)
  assert.equal(differentWorld.scrollTop, 900)
})
