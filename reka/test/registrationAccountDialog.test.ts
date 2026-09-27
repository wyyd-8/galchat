import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('registration opens the new account editor only after successful authentication', async context => {
  const originals = ['localStorage', 'window', 'fetch'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  const stored = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => stored.get(key) ?? null,
    setItem: (key: string, value: string) => stored.set(key, value),
  } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    addEventListener() {}, clearTimeout() {}, setTimeout: () => 0,
    matchMedia: () => ({ matches: false }),
  } })
  let failAuth = false
  let profileReads = 0
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input)
    let data: unknown
    if (url === '/api/user/register' || url === '/api/user/login') {
      if (failAuth) return new Response(JSON.stringify({ code: 0, msg: '验证失败' }))
      data = { id: 23, username: '新旅人', token: 'new-account-token' }
    } else {
      assert.equal(new Headers(init.headers).get('token'), 'new-account-token')
      if (url === '/api/user/info') {
        profileReads++
        data = { id: 23, username: '新旅人', email: '87654321@bjtu.edu.cn', birthday: '2000-01-02', diceSkin: 'classic' }
      } else if (['/api/world/user/23', '/api/world/templates', '/api/coc-modules'].includes(url)) data = []
      else throw new Error(`Unexpected request: ${url}`)
    }
    return new Response(JSON.stringify({ code: 1, data }))
  }
  context.after(() => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else Reflect.deleteProperty(globalThis, key)
    }
  })
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [{ name: 'omit-browser-dice-player', enforce: 'pre',
      transform(_code, id) {
        if (id.endsWith('/dice/components/DicePlayerDialog.vue')) return '<template><div /></template>'
      },
    }, vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: App } = await vite.ssrLoadModule('/src/App.vue')
  let state: any
  const app = createSSRApp(App)
  app.mixin({ created() { if (this.$options.__name === 'App') state = (this.$ as any).setupState } })
  await renderToString(app)
  const credentials = { email: '87654321@bjtu.edu.cn', password: 'new-password', code: '123456' }

  failAuth = true
  await state.authenticate({ ...credentials, mode: 'register' })
  assert.equal(state.dialogs.account, false, 'failed registration must not open the editor')
  assert.equal(state.authOpen, true)
  assert.equal(profileReads, 0)

  failAuth = false
  await state.authenticate({ ...credentials, mode: 'login' })
  assert.equal(state.dialogs.account, false, 'ordinary login must not open the editor')
  assert.equal(state.authOpen, false)

  await state.authenticate({ ...credentials, mode: 'register' })
  assert.equal(state.authOpen, false)
  assert.equal(state.workspace.isLoggedIn.value, true)
  assert.equal(state.dialogs.account, true, 'successful registration should open the editor')
  assert.deepEqual(state.accountForm, { username: '新旅人', email: '87654321@bjtu.edu.cn', birthday: '2000-01-02', diceSkin: 'classic' })
})
