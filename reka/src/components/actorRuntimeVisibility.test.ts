import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import type { TrpgExecutionScene } from './trpgExecutionState.ts'

async function componentLoader(context: test.TestContext) {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  return vite
}

test('normal group chat exposes each reply actor model inside the reply order', async (context) => {
  const vite = await componentLoader(context)
  const { default: GroupChatStage } = await vite.ssrLoadModule(
    '/src/components/GroupChatStage.vue',
  )
  const html = await renderToString(createSSRApp({
    render: () => h(GroupChatStage, {
      input: '',
      scroller: null,
      conversation: {
        id: 1,
        userWorldId: 2,
        worldId: 3,
        mode: 'chat',
        title: '测试群聊',
        status: 'active',
      },
      username: '用户',
      messages: [],
      reasoning: {},
      characters: [{
        characterId: 12,
        characterName: '艾琳',
        prompt: '',
        favor: 0,
      }],
      replyPlan: {
        source: 'USER',
        displayName: '默认顺序',
        items: [{ order: 1, actorType: 'character', actorId: 12 }],
      },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: {
        turnId: 8,
        stepId: 81,
        status: 'waiting_input',
        inputType: 'message',
        waitingForUser: true,
        sceneOptions: {},
        steps: [{
          stepId: 81,
          itemOrder: 1,
          actorType: 'character',
          actorId: 12,
          status: 'waiting_input',
        }],
      },
      actorRuntimes: [{
        actorType: 'character',
        actorId: 12,
        controlMode: 'MODEL',
        modelApiId: 7,
        modelApiName: '月影',
        modelApiAvailable: true,
      }],
      modelApis: [{
        id: 7,
        name: '月影',
        baseUrl: 'https://example.com/v1',
        modelName: 'moon-chat',
        requestOverrides: {},
        apiKeyHint: '...test',
        status: 'SUCCESS',
        chatCapability: 'SUPPORTED',
        streamingCapability: 'SUPPORTED',
        toolCallingCapability: 'SUPPORTED',
        reasoningOutputStatus: 'NOT_DETECTED',
      }],
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))

  assert.match(html, /placeholder="输入群聊消息…"/)
  assert.doesNotMatch(html, /以\s*艾琳\s*的身份输入/)
  assert.doesNotMatch(html, /人工接管/)
  assert.match(html, /aria-label="选择艾琳的回复模型"/)
  // Vue serializes an empty value as either value="" or a bare value attribute.
  const modelSelect = html.match(/<select\b[^>]*aria-label="选择艾琳的回复模型"[^>]*>[\s\S]*?<\/select>/)?.[0]
  assert.ok(modelSelect, 'the actor must have its own model selector')
  assert.match(modelSelect, /<option value(?:="")?>默认模型<\/option>/)
  assert.match(html, /<select value="7" aria-label="选择艾琳的回复模型">/)
  assert.match(html, /<option value="7">月影<\/option>/)
})

test('trpg roster does not expose actor runtime configuration', async (context) => {
  const vite = await componentLoader(context)
  const { default: TrpgActorRoster } = await vite.ssrLoadModule(
    '/src/components/TrpgActorRoster.vue',
  )
  const scene: TrpgExecutionScene = {
    plan: { id: 20, source: 'SCENE', displayName: '书房', items: [] },
    kind: 'main',
    status: 'current',
    statusLabel: '当前场景',
    activeActors: [{
      item: { order: 1, actorType: 'character', actorId: 12 },
      name: '艾琳',
      status: 'running',
      statusLabel: '行动中',
      genericKp: false,
    }],
    waitingActors: [],
    readyActors: [],
    childScenes: [],
  }
  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, {
      scene,
      combatOverview: [],
    }),
  }))

  assert.match(html, /艾琳/)
  assert.doesNotMatch(html, /设置\s*艾琳\s*的发言方式/)
})
