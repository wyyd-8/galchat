import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

test('participant picker keeps selection available while history loads and renders solo state', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: Picker } = await vite.ssrLoadModule('/src/components/TrpgParticipantPicker.vue')
  const render = (characters: unknown[], selected: number[]) => renderToString(createSSRApp({ render: () => h(Picker, {
    worldId: 1, characters, modelValue: selected, busy: false,
  }) }))
  const html = await render([{ characterId: 11, characterName: '格兰特利' }], [11])
  assert.match(html, /type="checkbox"[^>]*checked/)
  assert.match(html, /查看格兰特利的同行档案/)
  assert.match(html, /选择格兰特利加入本次跑团/)
  assert.match(html, /加载同行记录/)
  assert.doesNotMatch(html, /尚未一起跑团/)
  assert.doesNotMatch(html, /type="checkbox"[^>]*disabled/)
  const solo = await render([], [])
  assert.match(solo, /单人团/)
  assert.doesNotMatch(solo, /type="checkbox"/)
})
