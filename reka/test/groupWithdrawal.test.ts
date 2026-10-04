import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('group withdrawal disables sending and repeated withdrawal while leaving drafts editable', async t => {
  // SSR has no scroll viewport; suppress scheduled animation work.
  const frames = { requestAnimationFrame: globalThis.requestAnimationFrame, cancelAnimationFrame: globalThis.cancelAnimationFrame }
  globalThis.requestAnimationFrame = () => 0
  globalThis.cancelAnimationFrame = () => undefined
  t.after(() => Object.assign(globalThis, frames))
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  t.after(() => vite.close())
  const { default: GroupChatStage } = await vite.ssrLoadModule('/src/components/GroupChatStage.vue')
  for (const withdrawing of [false, true]) {
    const html = await renderToString(createSSRApp({ render: () => h(GroupChatStage, {
      input: '新草稿', scroller: null, username: '用户',
      conversation: { id: 7, userWorldId: 3, worldId: 2, mode: 'chat', title: '群聊', status: 'active' },
      messages: [], reasoning: {}, characters: [], availableCharacters: [],
      replyPlan: { source: 'USER', displayName: '群聊', items: [] }, replyPlans: [],
      currentTurn: null, replyTurnState: null, sending: false, withdrawing, loading: false, hasOlderMessages: false,
    }) }))
    for (const label of ['发送消息', '撤回上一轮']) {
      const button = html.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))?.[0]
      assert.ok(button)
      assert.equal(button.includes('disabled'), withdrawing)
    }
    const input = html.match(/<textarea[^>]*aria-label="会话消息"[^>]*>/)?.[0]
    assert.ok(input)
    assert.doesNotMatch(input, /disabled/)
    assert.match(html, /新草稿/)
  }
})
