import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('loads and saves actor runtimes and submits manual character output', async (context) => {
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
    if (String(input).endsWith('/manual-message')) {
      return new Response('data: {"eventType":"message.completed","content":"由我来回答"}\n\n', {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    }
    return new Response(JSON.stringify({
      code: 1,
      msg: 'success',
      data: String(input).endsWith('/actor-runtimes') && (init.method || 'GET') === 'GET'
        ? []
        : { actorType: 'character', actorId: 9, controlMode: 'MANUAL' },
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
  const { api, streamManualGroupMessage } = await vite.ssrLoadModule('/src/api/client.ts')
  const payload = { actorType: 'character', actorId: 9, controlMode: 'MANUAL' }
  const events: Array<{ eventType: string, content?: string }> = []

  await api.actorRuntimes(7)
  await api.saveActorRuntime(7, payload)
  await streamManualGroupMessage(
    7, 42, 99,
    { clientRequestId: 'manual-1', content: '由我来回答' },
    (event: { eventType: string, content?: string }) => events.push(event),
  )

  assert.deepEqual(calls, [
    { url: '/api/group-chat/conversations/7/actor-runtimes', method: 'GET' },
    { url: '/api/group-chat/conversations/7/actor-runtimes', method: 'PUT', body: payload },
    {
      url: '/api/group-chat/conversations/7/turns/42/steps/99/manual-message',
      method: 'POST',
      body: { clientRequestId: 'manual-1', content: '由我来回答' },
    },
  ])
  assert.deepEqual(events, [{ eventType: 'message.completed', content: '由我来回答' }])
})
