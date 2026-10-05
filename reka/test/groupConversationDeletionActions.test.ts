import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

async function renderStage(context: TestContext, status: 'active' | 'closed', failedTurn = false) {
  const previousRequestFrame = globalThis.requestAnimationFrame
  const previousCancelFrame = globalThis.cancelAnimationFrame
  globalThis.requestAnimationFrame = () => 0
  globalThis.cancelAnimationFrame = () => undefined
  context.after(() => {
    globalThis.requestAnimationFrame = previousRequestFrame
    globalThis.cancelAnimationFrame = previousCancelFrame
  })
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: GroupChatStage } = await vite.ssrLoadModule('/src/components/GroupChatStage.vue')
  return renderToString(createSSRApp({
    render: () => h(GroupChatStage, {
      input: '',
      scroller: null,
      conversation: {
        id: 7,
        userWorldId: 2,
        worldId: 3,
        mode: 'chat',
        title: '测试群聊',
        status,
      },
      username: '用户',
      messages: [],
      reasoning: {},
      characters: [],
      replyPlan: { source: 'USER', displayName: '群聊', items: [] },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: failedTurn ? { turnId: 42, planSource: 'USER', status: 'failed', waitingForUser: false, sceneOptions: {}, steps: [] } : null,
      replyTurnState: failedTurn ? { turnId: 42, phase: 'failed', error: '401: API key expired.' } : null,
      sending: false,
      loading: !failedTurn,
      hasOlderMessages: false,
    }),
  }))
}

test('active conversations expose deletion through the close-conversation entry', async (context) => {
  const html = await renderStage(context, 'active')

  assert.match(html, />结束群聊</)
  assert.doesNotMatch(html, />永久删除</)
})

test('closed conversations retain one conversation-actions entry', async (context) => {
  const html = await renderStage(context, 'closed')

  assert.doesNotMatch(html, />结束群聊</)
  assert.match(html, />会话操作</)
  assert.doesNotMatch(html, />永久删除</)
})


test('ordinary group retry appears below the error in the reply panel', async context => {
  const html = await renderStage(context, 'active', true)
  const overview = html.match(/<section class="[^"]*chat-reply-overview[^"]*"[\s\S]*?<\/section>/)?.[0]
  assert.ok(overview)
  assert.match(overview, /401: API key expired\.[\s\S]*>重试本轮回复</)
  assert.equal(html.match(/>重试本轮回复</g)?.length, 1)
})
