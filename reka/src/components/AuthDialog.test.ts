import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

for (const mobile of [false, true]) {
  test(`password recovery and registration automatically sign in the submitted account on ${mobile ? 'mobile' : 'desktop'}`, async context => {
    const requests: Array<{ url: string, method: string, body: unknown, token: string | null }> = []
    const originalFetch = globalThis.fetch
    const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window')
    const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { clearTimeout() {}, setTimeout() { return 1 }, addEventListener() {} } })
    const stored = new Map<string, string>()
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
      getItem(key: string) { return stored.get(key) ?? null },
      setItem(key: string, value: string) { stored.set(key, value) },
    } })
    let failReset = false
    globalThis.fetch = async (input, init = {}) => {
      const url = String(input)
      requests.push({ url, method: init.method || 'GET', body: init.body ? JSON.parse(String(init.body)) : undefined, token: new Headers(init.headers).get('token') })
      if (url === '/api/user/password/reset') return new Response(JSON.stringify(failReset ? { code: 0, msg: '邮箱验证码错误或已过期' } : { code: 1 }))
      const data = url === '/api/user/login' ? { id: 17, username: '找回的账号', token: 'recovered-token' }
        : url === '/api/user/register' ? { id: 23, username: '新注册账号', token: 'registered-token' }
        : url === '/api/user/info' ? { id: Number(stored.get('galchat.userId')), username: stored.get('galchat.username') }
        : []
      return new Response(JSON.stringify({ code: 1, data }))
    }
    context.after(() => {
      globalThis.fetch = originalFetch
      if (windowDescriptor) Object.defineProperty(globalThis, 'window', windowDescriptor)
      else Reflect.deleteProperty(globalThis, 'window')
      if (storageDescriptor) Object.defineProperty(globalThis, 'localStorage', storageDescriptor)
      else Reflect.deleteProperty(globalThis, 'localStorage')
    })
    const vite = await createServer({
      appType: 'custom', configFile: false,
      root: fileURLToPath(new URL('../..', import.meta.url)),
      plugins: [{ name: 'auth-dialog-fixtures', enforce: 'pre',
        load(id) {
          if (id.endsWith('/composables/useMobileViewport.ts')) return `import { ref } from 'vue'; export function useMobileViewport() { return { isMobile: ref(${mobile}) } }`
        },
        transform(_code, id) {
          if (id.endsWith('/ui/BaseDialog.vue')) return '<script setup>defineProps(["title"])</script><template><section :aria-label="title"><slot /><slot name="footer" /></section></template>'
        },
      }, vue()],
      resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
      server: { middlewareMode: true, hmr: false, ws: false },
    })
    context.after(() => vite.close())
    const { default: AuthDialog } = await vite.ssrLoadModule('/src/components/AuthDialog.vue')
    const { notice } = await vite.ssrLoadModule('/src/composables/useNotice.ts')
    const { useWorkspace } = await vite.ssrLoadModule('/src/composables/useWorkspace.ts')
    // Capture the real component state while rendering, without exporting test-only APIs.
    let state: any
    let workspace: any
    let authentication: Promise<void> | undefined
    const app = createSSRApp({ setup() {
      workspace = useWorkspace()
      return () => h(AuthDialog, { modelValue: true, onSubmit(payload: unknown) { authentication = workspace.authenticate(payload) } })
    } })
    app.mixin({ created() { if (this.$options.__name === 'AuthDialog') state = (this.$ as any).setupState } })
    const html = await renderToString(app)
    assert.match(html, /找回密码<\/button>/)
    state.form.email = '12345678@bjtu.edu.cn'
    state.form.password = 'old-secret'
    state.switchMode('reset')
    assert.equal(state.form.email, '12345678@bjtu.edu.cn')
    assert.equal(state.form.password, '')
    assert.equal(state.title, '找回密码')
    await state.sendCode()
    assert.deepEqual(requests[0], { url: '/api/user/password/reset/email-code', method: 'POST', body: { email: '12345678@bjtu.edu.cn' }, token: null })
    state.form.password = 'new-secret'
    state.form.confirmPassword = 'different'
    state.form.code = '123456'
    await state.submit()
    assert.equal(requests.length, 1, 'password mismatch must not reach the server')
    state.form.confirmPassword = 'new-secret'
    state.form.code = ''
    await state.submit()
    assert.equal(requests.length, 1, 'missing verification code must not reach the server')
    state.form.code = '123456'
    failReset = true
    await state.submit()
    assert.equal(state.mode, 'reset', 'failure keeps the form available for correction')
    assert.equal(state.busy, false)
    assert.equal(notice.message, '邮箱验证码错误或已过期')
    assert.equal(authentication, undefined, 'failed reset must not sign in')
    failReset = false
    const resetting = state.submit()
    Object.assign(state.form, { email: '99999999@bjtu.edu.cn', password: 'edited-while-waiting' })
    await resetting
    await authentication
    assert.deepEqual(requests.filter(request => request.url === '/api/user/password/reset').at(-1), { url: '/api/user/password/reset', method: 'PUT', body: { email: '12345678@bjtu.edu.cn', newPassword: 'new-secret', verificationCode: '123456' }, token: null })
    assert.deepEqual(requests.find(request => request.url === '/api/user/login'), { url: '/api/user/login', method: 'POST', body: { email: '12345678@bjtu.edu.cn', password: 'new-secret' }, token: null })
    assert.deepEqual(workspace.session, { id: 17, username: '找回的账号', token: 'recovered-token' })
    assert.equal(workspace.isLoggedIn.value, true)
    assert.equal(stored.get('galchat.token'), 'recovered-token')
    assert.equal(workspace.userInfo.value.id, 17)
    assert.ok(requests.some(request => request.url === '/api/world/user/17' && request.token === 'recovered-token'))
    assert.equal(state.mode, 'login')
    assert.equal(state.form.email, '12345678@bjtu.edu.cn')
    assert.equal(state.form.password, '')
    assert.equal(state.form.code, '')
    assert.equal(state.busy, false)

    state.switchMode('register')
    Object.assign(state.form, { email: '87654321@bjtu.edu.cn', password: 'registered-secret', confirmPassword: 'registered-secret', code: '654321' })
    await state.submit()
    await authentication
    assert.deepEqual(requests.find(request => request.url === '/api/user/register'), { url: '/api/user/register', method: 'POST', body: { email: '87654321@bjtu.edu.cn', password: 'registered-secret', verificationCode: '654321' }, token: 'recovered-token' })
    assert.deepEqual(workspace.session, { id: 23, username: '新注册账号', token: 'registered-token' })
    assert.equal(workspace.isLoggedIn.value, true)
    assert.equal(stored.get('galchat.userId'), '23')
    assert.equal(stored.get('galchat.token'), 'registered-token')
    assert.equal(workspace.userInfo.value.id, 23)
    assert.ok(requests.some(request => request.url === '/api/world/user/23' && request.token === 'registered-token'))
    assert.equal(requests.filter(request => request.url === '/api/user/login').length, 1, 'registration uses its returned token directly')
  })
}
