import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('reply settings stay locked until conversation loading succeeds on desktop and mobile', async t => {
  // SSR has no scroll viewport; suppress scheduled animation work.
  const frames = { requestAnimationFrame: globalThis.requestAnimationFrame, cancelAnimationFrame: globalThis.cancelAnimationFrame }
  globalThis.requestAnimationFrame = () => 0
  globalThis.cancelAnimationFrame = () => undefined
  t.after(() => Object.assign(globalThis, frames))
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)), plugins: [{ name: 'test-mobile-panel', enforce: 'pre', transform(_code, id) {
      if (id.endsWith('/components/ui/BaseDialog.vue')) return '<script setup>defineProps(["modelValue"])</script><template><section v-if="modelValue"><slot/><slot name="footer"/></section></template>'
    } }, vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  t.after(() => vite.close())
  const { default: GroupChatStage } = await vite.ssrLoadModule('/src/components/GroupChatStage.vue')
  const previousWindow = globalThis.window
  t.after(() => { globalThis.window = previousWindow })
  for (const mobile of [false, true]) for (const phase of ['loading', 'failed', 'ready', 'saving-plan', 'saving-model']) {
    globalThis.window = { matchMedia: () => ({ matches: mobile }) } as unknown as Window & typeof globalThis
    const loading = phase === 'loading'
    const conversationReady = !['loading', 'failed'].includes(phase)
    const savingReplyPlan = phase === 'saving-plan'
    const savingActorKeys = phase === 'saving-model' ? ['character:101'] : []
    const locked = !conversationReady || savingReplyPlan
    const html = await renderToString(createSSRApp({ render: () => h(GroupChatStage, {
      input: '新草稿', scroller: null, username: '用户',
      conversation: { id: 7, userWorldId: 3, worldId: 2, mode: 'chat', title: '群聊', status: 'active' },
      messages: [], reasoning: {}, characters: [], availableCharacters: [],
      replyPlan: { source: 'USER', displayName: '群聊', items: [{ actorType: 'character', actorId: 101, order: 1 }, { actorType: 'character', actorId: 102, order: 2 }] }, replyPlans: [],
      currentTurn: null, replyTurnState: null, sending: false, withdrawing: false, loading, conversationReady, savingReplyPlan, savingActorKeys, hasOlderMessages: false,
    }) }).mixin({ created() {
      if (this.$options.__name !== 'GroupChatStage') return
      // Open the real panel, then make its reply order dirty to exercise Save.
      ;(this.$ as any).setupState.openScene()
      ;(this.$props as any).replyPlan.items.push({ actorType: 'character', actorId: 103, order: 3 })
    } }))
    const button = html.match(mobile ? /<button[^>]*aria-label="下移角色 #101"[^>]*>/ : /<button[^>]*class="chat-reply-edit"[^>]*>/)?.[0]
    assert.ok(button)
    assert.equal(button.includes('disabled'), locked, `${mobile ? 'mobile' : 'desktop'}: ${phase}`)
    const model = html.match(mobile ? /<select[^>]*><option value="">默认模型/ : /<select[^>]*aria-label="选择角色 #101的回复模型"[^>]*>/)?.[0]
    assert.ok(model)
    assert.equal(model.includes('disabled'), locked || phase === 'saving-model', `model: ${mobile}: ${phase}`)
    if (mobile) {
      const save = html.match(/<button[^>]*>(?:保存顺序|正在保存…)<\/button>/)?.[0]
      assert.ok(save)
      assert.equal(save.includes('disabled'), locked, `mobile save: ${phase}`)
    }
  }
})
