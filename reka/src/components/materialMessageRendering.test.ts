import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('renders a material payload as player-facing content instead of raw JSON', async (context) => {
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
  const content = JSON.stringify({
    schemaVersion: 1,
    materialId: 13,
    title: '梦唤：不眠之夜',
    description: '调查员醒来后像整夜未眠。',
    imageUrl: '',
  })

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
        replyStepId: 3,
        speakerType: 'kp',
        speakerName: 'KP',
        messageKind: 'material',
        content,
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

  assert.match(html, /梦唤：不眠之夜/)
  assert.match(html, /调查员醒来后像整夜未眠。/)
  assert.doesNotMatch(html, /schemaVersion/)
  assert.doesNotMatch(html, /materialId/)
})
