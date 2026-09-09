import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('completion review lists every combat separately with results collapsed and distinguishes legacy reports', async (context) => {
  const vite = await createServer({
    appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: Stage } = await vite.ssrLoadModule('/src/components/TrpgCompletionStage.vue')
  const render = (combats: unknown) => renderToString(createSSRApp({ render: () => h(Stage, {
    report: { status: 'ready', title: '灯塔', ending: '调查结束', completedAt: null, archivedAt: null,
      coverUrl: null, turnCount: 3, journey: [], investigators: [], rolls: [], combats },
    loading: false, busy: false, error: '',
  }) }))
  const html = await render([
    { combatId: 11, sceneName: '林间营地', summary: '战斗结果：\n第一场结果' },
    { combatId: 12, sceneName: '林间营地', summary: '战斗结果：\n第二场结果' },
  ])
  assert.match(html, /04 \/ 战斗回顾/)
  assert.match(html, /1 \/ 5/)
  assert.match(html, /第 1 场战斗/)
  assert.match(html, /第 2 场战斗/)
  assert.equal((html.match(/林间营地/g) || []).length, 2)
  for (const text of ['第一场结果', '第二场结果']) {
    const detail = [...html.matchAll(/<details\b([^>]*)>([\s\S]*?)<\/details>/g)].find(match => match[2].includes(text))
    assert.ok(detail, 'combat result is inside expandable details')
    assert.doesNotMatch(detail[1], /\bopen(?:\s|=|$)/)
  }
  const empty = await render([])
  assert.match(empty, /本次跑团没有已完成的战斗记录/)
  const legacy = await render(undefined)
  assert.match(legacy, /这份完成记录尚未收录战斗回顾/)
  assert.doesNotMatch(legacy, /本次跑团没有已完成的战斗记录/)
})
