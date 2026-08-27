import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('renders each investigator afterstory in a dedicated epilogue chapter', async (context) => {
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
        status: 'closed',
      },
      username: '调查员',
      messages: [{
        id: 100,
        conversationId: 7,
        speakerType: 'kp',
        speakerName: 'KP',
        messageKind: 'epilogue',
        content: JSON.stringify({
          schemaVersion: 1,
          entries: [
            { characterId: 11, investigatorName: '林恩', content: '林恩重新回到了报社。' },
            { characterId: 12, investigatorName: '威廉', content: '威廉的笔记被妹妹保存了下来。' },
          ],
        }),
        sequenceNo: 42,
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

  assert.match(html, /aria-label="人物后传"/)
  assert.match(html, /<h3>林恩<\/h3>/)
  assert.match(html, /林恩重新回到了报社。/)
  assert.match(html, /<h3>威廉<\/h3>/)
  assert.match(html, /威廉的笔记被妹妹保存了下来。/)
  assert.doesNotMatch(html, /<strong>KP<\/strong>/)
  assert.doesNotMatch(html, /schemaVersion/)
})
