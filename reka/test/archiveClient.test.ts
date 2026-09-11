import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

test('ZIP routes preserve files, confirmation, authentication and legacy JSON; failed exports never download JSON', async context => {
  const originalFetch = globalThis.fetch
  const storage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => 'test-token' } })
  const calls: { url: string; init: RequestInit }[] = []
  let downloadError = false
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), init })
    if (String(url).endsWith('export-zip') && !downloadError) return new Response(new Uint8Array([80, 75, 3, 4]), { headers: { 'Content-Type': 'application/zip' } })
    return new Response(JSON.stringify(downloadError ? { code: 0, msg: '归档图片不存在' } : { code: 1, data: { worldId: 1 } }), { headers: { 'Content-Type': 'application/json' } })
  }
  const vite = await createServer({ configFile: false, root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true, hmr: false, ws: false } })
  context.after(async () => {
    globalThis.fetch = originalFetch
    if (storage) Object.defineProperty(globalThis, 'localStorage', storage)
    else delete (globalThis as { localStorage?: Storage }).localStorage
    await vite.close()
  })
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')
  const zip = new File([new Uint8Array([80, 75, 3, 4])], '世界.ZIP', { type: 'application/octet-stream' })
  await api.importWorldFile(zip)
  await api.importCocModuleFile(zip)
  await api.replaceWorldTemplateFile(9, zip)
  await api.replaceWorldTemplateFile(9, zip, true)
  assert.deepEqual(calls.map(c => [c.url, c.init.method]), [
    ['/api/world/import-zip', 'POST'], ['/api/coc-modules/import-zip', 'POST'],
    ['/api/world/templates/9/replace-zip?confirmLowMatch=false', 'PUT'],
    ['/api/world/templates/9/replace-zip?confirmLowMatch=true', 'PUT'],
  ])
  for (const call of calls) {
    assert.equal(new Headers(call.init.headers).get('token'), 'test-token')
    assert.equal(new Headers(call.init.headers).get('Content-Type'), null)
    const file = (call.init.body as FormData).get('file') as File
    assert.equal(file.name, '世界.ZIP')
    assert.deepEqual(new Uint8Array(await file.arrayBuffer()), new Uint8Array([80, 75, 3, 4]))
  }
  const legacy = new File(['{"formatVersion":1,"world":{"name":"旧世界"}}'], 'world.json')
  await api.importWorldFile(legacy)
  assert.equal(calls.at(-1)?.url, '/api/world/import')
  assert.equal(JSON.parse(calls.at(-1)?.init.body as string).world.name, '旧世界')
  await api.replaceWorldTemplateFile(9, legacy, true)
  assert.equal(calls.at(-1)?.url, '/api/world/templates/9/replace?confirmLowMatch=true')
  await api.importCocModuleFile(new File(['{"formatVersion":1,"module":{}}'], 'module.json'))
  assert.equal(calls.at(-1)?.url, '/api/coc-modules/import')
  const count = calls.length
  await assert.rejects(api.importWorldFile(new File(['oops'], 'a.json')), /JSON/)
  await assert.rejects(api.importWorldFile(new File(['oops'], 'a.txt')), /ZIP.*JSON/)
  await assert.rejects(api.importWorldFile(new File([new Uint8Array(64 * 1024 * 1024 + 1)], 'a.zip')), /64/)
  assert.equal(calls.length, count)
  assert.equal((await api.exportWorldZip(1)).type, 'application/zip')
  assert.equal((await api.exportCocModuleZip(1)).type, 'application/zip')
  downloadError = true
  await assert.rejects(api.exportWorldZip(1), /归档图片不存在/)
})
