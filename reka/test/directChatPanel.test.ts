import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('desktop profile reports save failures and prevents model changes during a settings save', async context => {
  // SSR has no scroll viewport; prevent the component's scheduled scroll work.
  const previousRequestFrame = globalThis.requestAnimationFrame
  const previousCancelFrame = globalThis.cancelAnimationFrame
  globalThis.requestAnimationFrame = () => 0
  globalThis.cancelAnimationFrame = () => undefined
  context.after(() => {
    if (previousRequestFrame) globalThis.requestAnimationFrame = previousRequestFrame
    else delete (globalThis as { requestAnimationFrame?: unknown }).requestAnimationFrame
    if (previousCancelFrame) globalThis.cancelAnimationFrame = previousCancelFrame
    else delete (globalThis as { cancelAnimationFrame?: unknown }).cancelAnimationFrame
  })
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: DirectChatStage } = await vite.ssrLoadModule('/src/components/DirectChatStage.vue')
  const render = (extra: Record<string, unknown>) => renderToString(createSSRApp({
    render: () => h(DirectChatStage, {
      input: '', scroller: null,
      world: { id: 3, worldId: 2, name: '雾港' },
      character: { characterId: 7, userWorldId: 3, characterName: '林间', modelApiId: 4, userInfoPrompt: '叫我旅人' },
      messages: [], modelApis: [{ id: 4, name: '叙事模型' }],
      loading: { history: false, sending: false, withdrawing: false, model: false },
      canWithdraw: false, hasOlderMessages: false,
      ...extra,
    }),
  }))

  await context.test('a failed note save exposes the server error to the desktop user', async () => {
    const html = await render({ settingsError: '保存失败，请重试' })
    assert.match(html, /role="alert"[^>]*>保存失败，请重试</)
    assert.match(html, /叫我旅人/)
  })
  await context.test('the immediate model picker cannot race with a settings save', async () => {
    const html = await render({ settingsSaving: true })
    const picker = html.match(/<select[^>]*aria-label="选择单聊回复模型"[^>]*>/)?.[0]
    assert.ok(picker, 'desktop model picker is present')
    assert.match(picker, /disabled/)
    const idle = await render({ settingsSaving: false })
    const idlePicker = idle.match(/<select[^>]*aria-label="选择单聊回复模型"[^>]*>/)?.[0]
    assert.ok(idlePicker)
    assert.doesNotMatch(idlePicker, /disabled/)
  })
})
