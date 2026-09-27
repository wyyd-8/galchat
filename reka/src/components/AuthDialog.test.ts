import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

for (const mobile of [false, true]) {
  test(`password recovery works on ${mobile ? 'mobile' : 'desktop'} and returns to login`, async context => {
    const requests: Array<{ url: string, method: string, body: unknown }> = []
    const originalFetch = globalThis.fetch
    const windowDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'window')
    const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { clearTimeout() {}, setTimeout() { return 1 } } })
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem() { return null } } })
    let failReset = false
    globalThis.fetch = async (input, init = {}) => {
      requests.push({ url: String(input), method: init.method || 'GET', body: JSON.parse(String(init.body)) })
      return new Response(JSON.stringify(failReset ? { code: 0, msg: '邮箱验证码错误或已过期' } : { code: 1 }))
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
    // Capture the real component state while rendering, without exporting test-only APIs.
    let state: any
    const app = createSSRApp({ render: () => h(AuthDialog, { modelValue: true }) })
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
    assert.deepEqual(requests[0], { url: '/api/user/password/reset/email-code', method: 'POST', body: { email: '12345678@bjtu.edu.cn' } })
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
    failReset = false
    await state.submit()
    assert.deepEqual(requests.at(-1), { url: '/api/user/password/reset', method: 'PUT', body: { email: '12345678@bjtu.edu.cn', newPassword: 'new-secret', verificationCode: '123456' } })
    assert.equal(state.mode, 'login')
    assert.equal(state.form.email, '12345678@bjtu.edu.cn')
    assert.equal(state.form.password, '')
    assert.equal(state.form.code, '')
    assert.equal(state.busy, false)
  })
}
