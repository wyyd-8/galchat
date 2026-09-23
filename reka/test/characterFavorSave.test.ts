import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('favor changes only update favor and reject invalid or unauthorized changes', async context => {
  const originals = ['localStorage', 'window'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { addEventListener() {}, clearTimeout() {}, setTimeout: () => 0 } })
  context.after(() => {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else Reflect.deleteProperty(globalThis, key)
    }
  })
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { useWorkspace } = await vite.ssrLoadModule('/src/composables/useWorkspace.ts')
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')
  let workspace: ReturnType<typeof useWorkspace>
  await renderToString(createSSRApp({ setup() { workspace = useWorkspace(); return () => h('div') } }))
  workspace.worlds.value = [{ id: 3, worldId: 2, name: '学院', myWorld: true }]
  workspace.selectedWorldId.value = 3
  workspace.characters.value = [{ characterId: 7, userWorldId: 3, characterName: '艾琳', favorValue: 68, userInfoPrompt: '保留这段备注' }]
  const calls: unknown[] = []
  api.updateFavor = async (...args: unknown[]) => { calls.push(['favor', ...args]) }
  api.updatePrompt = async (...args: unknown[]) => { calls.push(['prompt', ...args]) }
  api.characters = async (worldId: number) => {
    calls.push(['reload', worldId])
    return [{ ...workspace.characters.value[0], favorValue: 80 }]
  }
  assert.equal(typeof workspace.updateCharacterFavor, 'function', 'dedicated favor save is available')
  await workspace.updateCharacterFavor(7, 80)
  assert.deepEqual(calls, [['favor', 3, 7, 80], ['reload', 3]])
  assert.equal(workspace.characters.value[0].userInfoPrompt, '保留这段备注')
  assert.equal(workspace.characters.value[0].favorValue, 80)
  calls.length = 0
  for (const value of [-1, 101, 1.5, NaN]) await assert.rejects(() => workspace.updateCharacterFavor(7, value), /0.*100/)
  workspace.worlds.value[0].myWorld = false
  await assert.rejects(() => workspace.updateCharacterFavor(7, 70), /作者/)
  assert.deepEqual(calls, [])
})
