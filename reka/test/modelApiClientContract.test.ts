import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('uses the authenticated model API CRUD and test endpoints', async (context) => {
  const originalFetch = globalThis.fetch
  const localStorageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const calls: Array<{ url: string, method: string, body?: unknown, token?: string | null }> = []
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
    const call: { url: string, method: string, body?: unknown, token?: string | null } = {
      url: String(input),
      method: init.method || 'GET',
      token: requestHeaders.get('token'),
    }
    if (typeof init.body === 'string') call.body = JSON.parse(init.body)
    calls.push(call)
    return new Response(JSON.stringify({ code: 1, msg: 'success', data: { id: 7 } }), {
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
  const payload = {
    name: 'DeepSeek 主模型',
    baseUrl: 'https://api.deepseek.com/v1',
    modelName: 'deepseek-chat',
    apiKey: 'sk-secret',
    requestOverrides: { thinking: { type: 'enabled' }, reasoning_effort: 'high' },
  }

  await api.modelApis()
  await api.createModelApi(payload)
  await api.updateModelApi(7, { ...payload, apiKey: undefined })
  await api.testModelApi(7)
  await api.deleteModelApi(7)

  assert.deepEqual(calls, [
    { url: '/api/model-apis', method: 'GET', token: 'test-token' },
    { url: '/api/model-apis', method: 'POST', body: payload, token: 'test-token' },
    { url: '/api/model-apis/7', method: 'PUT', body: { name: payload.name, baseUrl: payload.baseUrl, modelName: payload.modelName, requestOverrides: payload.requestOverrides }, token: 'test-token' },
    { url: '/api/model-apis/7/test', method: 'POST', token: 'test-token' },
    { url: '/api/model-apis/7', method: 'DELETE', token: 'test-token' },
  ])
})
