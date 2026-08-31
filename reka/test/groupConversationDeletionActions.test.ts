import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

async function renderStage(context: TestContext, status: 'active' | 'closed') {
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
      currentTurn: null,
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))
}

test('active conversations expose deletion through the close-conversation entry', async (context) => {
  const html = await renderStage(context, 'active')

  assert.match(html, />关闭会话</)
  assert.doesNotMatch(html, />永久删除</)
})

test('closed conversations retain one conversation-actions entry', async (context) => {
  const html = await renderStage(context, 'closed')

  assert.doesNotMatch(html, />关闭会话</)
  assert.match(html, />会话操作</)
  assert.doesNotMatch(html, />永久删除</)
})
