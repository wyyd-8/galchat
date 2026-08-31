import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('permanently deletes a conversation through the authenticated DELETE endpoint', async (context) => {
  const originalFetch = globalThis.fetch
  const localStorageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const calls: Array<{ url: string, method: string, token?: string | null }> = []
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => key === 'galchat.token' ? 'test-token' : null,
      setItem: () => undefined,
      removeItem: () => undefined,
      key: () => null,
      clear: () => undefined,
      length: 0,
    },
  })
  globalThis.fetch = async (input, init = {}) => {
    const requestHeaders = new Headers(init.headers)
    calls.push({
      url: String(input),
      method: init.method || 'GET',
      token: requestHeaders.get('token'),
    })
    return new Response(JSON.stringify({ code: 1, msg: 'success' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  context.after(() => {
    globalThis.fetch = originalFetch
    if (localStorageDescriptor) Object.defineProperty(globalThis, 'localStorage', localStorageDescriptor)
    else delete (globalThis as { localStorage?: Storage }).localStorage
  })

  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')

  await api.deleteConversation(7)

  assert.deepEqual(calls, [{
    url: '/api/group-chat/conversations/7',
    method: 'DELETE',
    token: 'test-token',
  }])
})
