import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('binds and clears the selected model for a direct-chat character', async (context) => {
  const originalFetch = globalThis.fetch
  const localStorageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const calls: Array<{ url: string, method: string, body?: unknown }> = []
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
    const call: { url: string, method: string, body?: unknown } = {
      url: String(input),
      method: init.method || 'GET',
    }
    if (typeof init.body === 'string') call.body = JSON.parse(init.body)
    calls.push(call)
    return new Response(JSON.stringify({
      code: 1,
      msg: 'success',
      data: { modelApiId: call.body && (call.body as { modelApiId?: number }).modelApiId },
    }), {
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

  await api.updateCharacterModel(5, 12, 44)
  await api.updateCharacterModel(5, 12, undefined)

  assert.deepEqual(calls, [
    { url: '/api/character/5/12/model', method: 'PUT', body: { modelApiId: 44 } },
    { url: '/api/character/5/12/model', method: 'PUT', body: {} },
  ])
})
