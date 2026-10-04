import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createRenderer, h, ssrContextKey } from 'vue'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('mutation completion does not navigate or close dialogs after the originating page is gone', async t => {
  const originals = ['localStorage', 'sessionStorage', 'window', 'document'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  for (const key of ['localStorage', 'sessionStorage']) Object.defineProperty(globalThis, key, { configurable: true, value: {
    getItem: () => null, setItem() {}, removeItem() {},
  } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    addEventListener() {}, removeEventListener() {}, clearTimeout, setTimeout,
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  } })
  let app: any
  t.after(() => { app?.unmount(); for (const [key, descriptor] of originals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else Reflect.deleteProperty(globalThis, key)
  } })
  const vite = await createServer({ configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [{ name: 'omit-browser-dice-player', enforce: 'pre', transform(_code, id) {
      if (id.endsWith('/dice/components/DicePlayerDialog.vue')) return '<template><div /></template>'
    } }, vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  t.after(() => vite.close())
  const { default: App } = await vite.ssrLoadModule('/src/App.vue')
  // Keep App's real setup and handlers; these tests do not need its visual children.
  App.render = () => h('div')
  const renderer = createRenderer<any, any>({ patchProp() {}, insert() {}, remove() {},
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
  let state: any
  app = renderer.createApp(App)
  app.provide(ssrContextKey, { modules: new Set() })
  app.mixin({ created() { if (this.$options.__name === 'App') state = (this.$ as any).setupState } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { addEventListener() {}, removeEventListener() {}, documentElement: { style: { setProperty() {}, removeProperty() {} } } } })
  app.mount({})

  await t.test('an obsolete operation does not close a newly opened dialog', async () => {
    state.dialogs.settings = true
    assert.equal(await state.run(async () => false, 'settings'), false)
    assert.equal(state.dialogs.settings, true)
  })
  for (const method of ['createNormalConversation', 'createTrpgConversation']) await t.test(`${method} skips post-create navigation and binding`, async () => {
    state.view = 'world'
    state.dialogs.conversation = true
    state.dialogs.trpgBinding = false
    t.mock.method(state.workspace, 'createConversation', async () => undefined)
    await state[method]()
    assert.equal(state.view, 'world')
    assert.equal(state.dialogs.conversation, true)
    assert.equal(state.dialogs.trpgBinding, false)
  })
  for (const awayBack of [false, true]) await t.test(`view navigation invalidates pending dialog closure (return=${awayBack})`, async () => {
    state.view = 'world'
    state.dialogs.settings = true
    let finish!: () => void
    const pending = state.run(() => new Promise<void>(resolve => { finish = resolve }), 'settings')
    state.view = 'direct'
    if (awayBack) state.view = 'world'
    finish()
    assert.equal(await pending, false)
    assert.equal(state.dialogs.settings, true)
  })
  for (const [wrapper, mutation] of [['saveTemplate', 'updateTemplate'], ['removeDetail', 'removeDetail'], ['loadWorldSnapshot', 'loadSnapshot']]) await t.test(`${wrapper} propagates the stale result`, async () => {
    state.templateMode = 'edit'
    t.mock.method(state.workspace, mutation, async () => false)
    assert.equal(await state[wrapper](130), false)
  })
  await t.test('deleting the current conversation closes its dialog before navigating', async () => {
    state.view = 'group'
    state.dialogs.deleteConversation = true
    t.mock.method(state.workspace, 'deleteConversation', async () => true)
    await state.permanentlyDeleteConversation()
    assert.equal(state.view, 'world')
    assert.equal(state.dialogs.deleteConversation, false)
  })
  await t.test('current operations retain their normal dialog closure', async () => {
    state.dialogs.settings = true
    assert.equal(await state.run(async () => true, 'settings'), true)
    assert.equal(state.dialogs.settings, false)
  })
})
