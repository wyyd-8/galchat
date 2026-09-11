import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

for (const source of ['SCENE', 'COMBAT']) {
  test(`mobile scene page shows ${source} actors without a mode switch, including a routed response`, async context => {
    const render = await mobileRenderer(context)
    const plan = { id: 42, source, displayName: '钟楼前厅', items: [
      { order: 1, actorType: 'character', actorId: 7, subjectCharacterId: 70, subjectCharacterName: '阿尔法' },
      { order: 2, actorType: 'user', subjectCharacterId: 80, subjectCharacterName: '本' },
    ] }
    const html = await render('GroupChatStage', {
      input: '', scroller: null,
      conversation: { id: 1, userWorldId: 3, title: '调查', mode: 'trpg', status: 'active' },
      username: '你', messages: [], reasoning: {}, characters: [], replyPlan: plan, replyPlans: [plan],
      availableCharacters: [], investigatorCards: [{ cardId: 80, actorType: 'PLAYER', checkValues: {} }],
      currentTurn: { turnId: 10, planId: 42, status: 'waiting_input', inputType: 'message', waitingForUser: true,
        sceneName: '钟楼前厅', actionType: 'combat_defense', sceneOptions: {}, steps: [],
        routeContext: { ownerCharacterId: 70, targetCharacterId: 80 } },
      replyTurnState: null, sending: false, loading: true, hasOlderMessages: false,
    })
    const panel = html.split('data-dialog="场景与队伍"')[1]?.split('</footer></section>')[0] || ''
    assert.doesNotMatch(panel, />探索<\/button>|>战斗<\/button>/)
    assert.match(panel, /阿尔法/)
    assert.match(panel, /由你控制/)
    assert.match(panel, /响应阿尔法/)
    assert.match(panel, /等待防守/)
    assert.match(panel, /人物卡与角色控制/)
  })
}

async function mobileRenderer(context: { after: (fn: () => Promise<void>) => void }) {
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [{
      name: 'mobile-viewport-and-dialog-test', enforce: 'pre',
      load(id) {
        if (id.endsWith('/composables/useMobileViewport.ts')) return `import { ref } from 'vue'; export function useMobileViewport() { return { isMobile: ref(true) } }`
      },
      transform(code, id) {
        // Keep production dialog contents visible in SSR to inspect forms and permissions.
        if (id.endsWith('/ui/BaseDialog.vue')) return `<script setup>defineProps(['title'])</script><template><section :data-dialog="title"><slot /><footer><slot name="footer" /></footer></section></template>`
      },
    }, vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  return async (file: string, props: Record<string, unknown>) => {
    const { default: component } = await vite.ssrLoadModule(`/src/components/${file}.vue`)
    return renderToString(createSSRApp({ render: () => h(component, props) }))
  }
}

test('mobile direct profile has a saveable personal note without exposing author controls', async context => {
  const render = await mobileRenderer(context)
  const html = await render('DirectChatStage', {
    input: '未发送的草稿', scroller: null,
    world: { id: 3, worldId: 2, name: '雾港' },
    character: { characterId: 7, userWorldId: 3, characterName: '林间', favorValue: 68 },
    messages: [], modelApis: [], loading: { history: true, sending: false, withdrawing: false, model: false },
    canWithdraw: false, hasOlderMessages: false,
  })
  assert.match(html, /data-dialog="角色详情"/)
  assert.match(html, /希望角色记住的事<\/span><textarea/)
  assert.match(html, /保存设置/)
  assert.doesNotMatch(html, /编辑角色模板|调整好感度|最近对话/)
  assert.match(html, /aria-label="聊天操作"/)
  assert.match(html, /未发送的草稿/)
})

test('mobile tabletop exposes full-page turn settings and textual intent while preserving inquiry limit', async context => {
  const render = await mobileRenderer(context)
  const html = await render('GroupChatStage', {
    input: '行动草稿', inquiryInput: '询问草稿', composerIntent: 'inquiry', scroller: null,
    conversation: { id: 1, userWorldId: 3, worldId: 2, title: '调查', mode: 'trpg', status: 'active' },
    username: '你', messages: [], reasoning: {}, characters: [], replyPlan: { source: 'SCENE', items: [] },
    replyPlans: [], availableCharacters: [], currentTurn: { turnId: 10, status: 'waiting_input', inputType: 'message', waitingForUser: true, canAskKp: true, sceneName: '图书馆', sceneOptions: {}, steps: [] },
    replyTurnState: null, sending: false, loading: true, hasOlderMessages: false,
  })
  assert.match(html, /data-dialog="行动轮设置"/)
  assert.match(html, /data-dialog="场景与队伍"/)
  assert.match(html, /询问 KP<\/button>/)
  assert.match(html, /maxlength="200"/)
  assert.match(html, /询问草稿/)
  assert.doesNotMatch(html, /class="turn-experiment-popover"/)
})

test('generating a mobile group turn locks order changes and keeps current actor progress visible', async context => {
  const render = await mobileRenderer(context)
  const html = await render('GroupChatStage', {
    input: '', scroller: null,
    conversation: { id: 1, userWorldId: 3, worldId: 2, title: '群聊', mode: 'chat', status: 'active' },
    username: '你', messages: [], reasoning: {}, characters: [{ characterId: 7, characterName: '林间' }],
    replyPlan: { source: 'USER', items: [{ actorType: 'character', actorId: 7, order: 1 }] },
    replyPlans: [], availableCharacters: [], currentTurn: null,
    replyTurnState: { turnId: 10, phase: 'running' }, sending: true, loading: true, hasOlderMessages: false,
  })
  assert.match(html, /当前轮正在生成，顺序暂不可编辑/)
  assert.match(html, /当前回复状态/)
  assert.match(html, /下一轮回复顺序/)
  assert.match(html, /disabled[^>]*aria-label="上移林间"|aria-label="上移林间"[^>]*disabled/)
})

test('scene model sheet shows the current connection, shared KP scope and no credential fields', async context => {
  const render = await mobileRenderer(context)
  const html = await render('MobileActorModelDialog', {
    modelValue: true, actor: { name: '守卫', genericKp: false, item: { actorType: 'kp', subjectCharacterId: 99, order: 1 } },
    actorRuntimes: [{ actorType: 'kp', controlMode: 'MODEL', modelApiAvailable: true, modelApiId: 4 }],
    modelApis: [{ id: 4, name: '叙事模型', modelName: 'story-model', status: 'SUCCESS', apiKeyHint: 'secret-key-hint', baseUrl: 'https://secret-endpoint.test' }],
    locked: false, saveRuntime: async () => undefined,
  })
  assert.match(html, /切换回复模型/)
  assert.match(html, /当前使用 · 叙事模型/)
  assert.match(html, /切换将同时影响 KP 与其他 NPC/)
  assert.match(html, /value="4"[^>]*checked|checked[^>]*value="4"/)
  assert.match(html, /默认模型/)
  assert.match(html, /确认切换/)
  assert.doesNotMatch(html, /secret-key-hint|secret-endpoint/)
})

test('scene model sheet falls back from removed models and explains manual control', async context => {
  const render = await mobileRenderer(context)
  const html = await render('MobileActorModelDialog', {
    modelValue: true, actor: { name: '巴里', item: { actorType: 'character', actorId: 7, order: 1 } },
    actorRuntimes: [{ actorType: 'character', actorId: 7, controlMode: 'MANUAL', modelApiAvailable: false, modelApiId: 4 }],
    modelApis: [], locked: false, saveRuntime: async () => undefined,
  })
  assert.match(html, /原模型已不可用/)
  assert.match(html, /切回 AI 控制后使用/)
  assert.match(html, /还没有自定义模型/)
  assert.match(html, /value=""[^>]*checked|checked[^>]*value=""/)
})
