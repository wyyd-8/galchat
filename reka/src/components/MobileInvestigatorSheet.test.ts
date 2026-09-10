import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

test('mobile sheet renders real resource values, all attributes and each skill difficulty', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: Sheet } = await vite.ssrLoadModule('/src/components/MobileInvestigatorSheet.vue')
  const card = { character: { name: '周寻', actorType: 'PLAYER', occupation: '记者', age: 28,
    str: 50, con: 55, siz: 60, dex: 65, app: 60, intValue: 75, pow: 50, edu: 70,
    hpCurrent: 0, hpMax: 11, sanCurrent: 52, sanMax: 99, mpCurrent: 10, mpMax: 10, luckCurrent: 60, dying: true },
    skills: [{ displayName: '侦查', value: 65 }, { displayName: '图书馆使用', value: 70 }], weapons: [] }
  const html = await renderToString(createSSRApp({ render: () => h(Sheet, { card, actorName: '你', canSwitch: true }) }))
  assert.match(html, /切换人物卡/)
  assert.match(html, /濒死/)
  assert.match(html, /生命 HP<\/span><strong[^>]*>0.*?11.*?<\/strong>/)
  for (const label of ['力量', '体质', '体型', '敏捷', '外貌', '智力', '意志', '教育']) assert.ok(html.includes(label))
  assert.match(html, /困难 32 \/ 极难 13/)
  assert.match(html, /困难 35 \/ 极难 14/)
  assert.match(html, /背景与资产/)
  assert.doesNotMatch(html, /NaN|undefined/)
})
