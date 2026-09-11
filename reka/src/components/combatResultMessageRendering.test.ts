import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('renders a combat result as a dedicated card instead of a KP message bubble', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: GroupChatStage } = await vite.ssrLoadModule('/src/components/GroupChatStage.vue')

  const html = await renderToString(createSSRApp({
    render: () => h(GroupChatStage, {
      input: '',
      scroller: null,
      conversation: {
        id: 7,
        userWorldId: 1,
        worldId: 2,
        moduleId: 15,
        mode: 'trpg',
        title: '古树之中',
        status: 'active',
      },
      username: '调查员',
      messages: [{
        id: 99,
        conversationId: 7,
        speakerType: 'kp',
        speakerName: 'KP',
        messageKind: 'combat_result',
        content: '战斗结果：\n1. 食尸鬼倒地。\n2. 查理失去3点生命值。',
        sequenceNo: 5,
        status: 'completed',
      }],
      reasoning: {},
      characters: [],
      replyPlan: { source: 'SCENE', displayName: '林间营地', items: [] },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: null,
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))

  assert.match(html, /aria-label="战斗结算"/)
  assert.match(html, /食尸鬼倒地。/)
  assert.match(html, /查理失去3点生命值。/)
  assert.doesNotMatch(html, /<strong>KP<\/strong>/)
  assert.doesNotMatch(html, /战斗结果：/)
})
