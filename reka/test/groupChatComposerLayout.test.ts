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
      inquiryInput: '门还开着吗？',
      composerIntent: 'inquiry',
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

test('renders the tabletop action and inquiry choices when KP can be asked', async (context) => {
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
      input: '我走到窗边查看街道。',
      inquiryInput: '街上有正在经过的空载出租车吗？',
      composerIntent: 'inquiry',
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
      replyPlan: { source: 'SCENE', displayName: '深夜街道', items: [] },
      replyPlans: [],
      availableCharacters: [],
      currentTurn: {
        turnId: 10,
        status: 'waiting_input',
        actionType: 'trpg_scene',
        inputType: 'message',
        waitingForUser: true,
        canAskKp: true,
        sceneOptions: {},
        steps: [],
      },
      replyTurnState: null,
      sending: false,
      loading: true,
      hasOlderMessages: false,
    }),
  }))

  assert.match(html, /class="composer-intent-toggle"/)
  assert.match(html, /data-intent="action"[\s\S]*?<span>行动<\/span>/)
  assert.match(html, /data-intent="inquiry"[\s\S]*?<span>询问<\/span>/)
  assert.match(html, /aria-pressed="true"[^>]*data-intent="inquiry"|data-intent="inquiry"[^>]*aria-pressed="true"/)
  assert.match(html, /maxlength="200"/)
  assert.match(html, /placeholder="向 KP 询问公开事实或当前可见信息……"/)
  assert.ok(html.includes('告诉 KP，你的调查员现在要做什么。比如走近查看、打开抽屉、与人交谈或发动攻击。'))
  assert.ok(html.includes('请 KP 补充你此刻本就能知道的事。比如眼前有什么、距离多远，或确认刚才提到的细节。得到回答后，再决定怎么做。'))
})
