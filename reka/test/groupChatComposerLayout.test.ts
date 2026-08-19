import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('keeps end exploration and send in the same composer action row', async (context) => {
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
      input: '检查房间',
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
        actionType: 'trpg_action',
        inputType: 'message',
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

  const actionRowStart = html.indexOf('class="composer-actions"')
  assert.notEqual(actionRowStart, -1, 'the composer should render a shared action row')

  const actionRowEnd = html.indexOf('</div>', actionRowStart)
  const endExploration = html.indexOf('结束探索', actionRowStart)
  const send = html.indexOf('class="send-button"', actionRowStart)
  assert.ok(endExploration > actionRowStart && endExploration < actionRowEnd, 'end exploration should be inside the action row')
  assert.ok(send > endExploration && send < actionRowEnd, 'send should follow end exploration in the same action row')
})
