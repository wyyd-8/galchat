import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('renders a clarification as a compact, accessible KP prompt beside the composer', async (context) => {
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

  const html = await renderToString(createSSRApp({
    render: () => h(GroupChatStage, {
      input: '',
      scroller: null,
      conversation: {
        id: 1,
        userWorldId: 2,
        worldId: 3,
        mode: 'trpg',
        title: '测试跑团',
        status: 'active',
      },
      username: '调查员',
      messages: [],
      reasoning: {},
      characters: [],
      replyPlan: { source: 'SCENE', displayName: '书房', items: [] },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: {
        turnId: 10,
        status: 'waiting_input',
        inputType: 'clarification',
        waitingForUser: true,
        sceneOptions: {},
        steps: [],
      },
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))

  assert.match(html, /class="clarification-prompt"/)
  assert.match(html, /role="status"/)
  assert.match(html, /KP 追问/)
  assert.match(html, /等待回复/)
  assert.match(html, /补充细节、调整行动，或放弃原行动/)
})

test('renders combat defense in the same compact prompt beside the composer', async (context) => {
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

  const html = await renderToString(createSSRApp({
    render: () => h(GroupChatStage, {
      input: '',
      scroller: null,
      conversation: {
        id: 1,
        userWorldId: 2,
        worldId: 3,
        mode: 'trpg',
        title: '测试跑团',
        status: 'active',
      },
      username: '调查员',
      messages: [],
      reasoning: {},
      characters: [],
      replyPlan: { source: 'SCENE', displayName: '战斗', items: [] },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: {
        turnId: 10,
        status: 'waiting_input',
        actionType: 'combat_defense',
        inputType: 'message',
        waitingForUser: true,
        sceneName: '战斗防守：闪避 / 反击',
        sceneOptions: {},
        steps: [],
      },
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))

  assert.match(html, /class="clarification-prompt"/)
  assert.match(html, /role="status"/)
  assert.match(html, /战斗防守/)
  assert.match(html, /轮到你防守/)
  assert.match(html, /战斗防守：闪避 \/ 反击/)
  assert.match(html, /等待行动/)
})
