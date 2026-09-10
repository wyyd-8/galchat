import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, ref } from 'vue'
import { shouldSubmitChatKey, useScopedChatDraft } from './chatInputState.ts'

const enter = { key: 'Enter', shiftKey: false, isComposing: false, keyCode: 13 }
test('mobile return inserts a line; desktop return sends except Shift or IME confirmation', () => {
  assert.equal(shouldSubmitChatKey(enter, true), false)
  assert.equal(shouldSubmitChatKey(enter, false), true)
  assert.equal(shouldSubmitChatKey({ ...enter, shiftKey: true }, false), false)
  assert.equal(shouldSubmitChatKey({ ...enter, isComposing: true }, false), false)
  assert.equal(shouldSubmitChatKey({ ...enter, keyCode: 229 }, false), false)
  assert.equal(shouldSubmitChatKey(enter, false, true), false)
})
test('keeps action, inquiry and intent together while isolating worlds and conversations', () => {
  const effect = effectScope()
  effect.run(() => {
    const scope = ref<string | null>(null)
    const action = ref(''), inquiry = ref(''), intent = ref('action')
    const drafts = useScopedChatDraft(scope, { action, inquiry, intent })
    scope.value = 'world:1:group:2'
    action.value = '打开门'; inquiry.value = '门锁是什么样？'; intent.value = 'inquiry'
    scope.value = 'world:2:group:2'
    assert.deepEqual([action.value, inquiry.value, intent.value], ['', '', 'action'])
    action.value = '另一世界的行动'
    scope.value = 'world:1:group:2'
    assert.deepEqual([action.value, inquiry.value, intent.value], ['打开门', '门锁是什么样？', 'inquiry'])
    scope.value = null
    assert.equal(action.value, '')
    scope.value = 'world:1:group:2'
    assert.equal(action.value, '打开门')
    action.value = '' // A successfully sent draft must remain cleared on return.
    scope.value = null
    scope.value = 'world:1:group:2'
    assert.equal(action.value, '')
    scope.value = null
    drafts.clear()
    scope.value = 'world:2:group:2'
    assert.equal(action.value, '')
  })
  effect.stop()
})
