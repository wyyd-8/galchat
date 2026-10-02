import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

test('mobile history uses semantic tones instead of the result filter for card coloring', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: History } = await vite.ssrLoadModule('/src/components/MobileDiceHistory.vue')
  const cases = [
    ['critical-success', 'success'], ['fumble', 'failure'], ['sanity', 'numeric'],
    ['sanity', 'other'], ['default', 'other'], ['pending', 'numeric'],
    ['damage', 'numeric'], ['healing', 'numeric'], ['opposed', 'other'],
    ['pushed-check', 'other'], ['success', 'success'], ['failure', 'failure'],
  ]
  const entries = cases.map(([tone, resultKind], index) => ({
    messageId: index + 1, title: `记录${index + 1}`, statusLabel: '结果', tone, resultKind,
    category: '普通检定', aggregate: { summary: { id: index + 1, conversationId: 1 }, results: [] },
  }))
  const html = await renderToString(createSSRApp({ render: () => h(History, {
    query: '', category: '', result: '', allCount: entries.length, matchCopy: '', filtersActive: false,
    loadingOlder: false, hasOlder: false, loadLabel: '', loadHint: '', entries,
  }) }))
  const cards = Array.from(html.matchAll(/<article\b[^>]*class="([^"]*)"/g), match => match[1]!.split(/\s+/))
  assert.equal(cards.length, cases.length)
  cases.forEach(([tone], index) => {
    assert.ok(cards[index]!.includes(`is-${tone}`), `card ${index} should retain ${tone}`)
    assert.ok(cards[index]!.includes('dice-tone'), 'history should consume the shared palette')
  })
})

test('mobile restore follows warning, retained boundary, deleted range and investigator snapshot order', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: Restore } = await vite.ssrLoadModule('/src/components/MobileRestorePreview.vue')
  const message = (id: number, content: string) => ({ id, conversationId: 1, speakerType: 'kp', speakerName: 'KP', messageKind: 'narration', content, sequenceNo: id, status: 'completed' })
  const html = await renderToString(createSSRApp({ render: () => h(Restore, {
    targetTime: '2026-09-10 14:32', loading: false, failed: false, deletesManualSave: true,
    preview: { retained: [message(1, '门厅的前文'), message(2, '大门终于打开')], deleted: [message(3, '进入之后的行动')], deletedMessagesOmitted: true },
    investigators: [{ characterId: 8, name: '周寻', hpCurrent: 0, hpMax: 11, sanCurrent: 55, mpCurrent: 10, dying: true }],
  }) }))
  assert.ok(html.indexOf('将回到2026-09-10 14:32') < html.indexOf('恢复到这里'))
  assert.ok(html.indexOf('大门终于打开') < html.indexOf('以下进度将被删除'))
  assert.ok(html.indexOf('以下进度将被删除') < html.indexOf('调查员快照'))
  assert.match(html, /还将删除该手动存档/)
  assert.ok(html.indexOf('门厅的前文') < html.indexOf('大门终于打开'))
  assert.doesNotMatch(html, /<details/)
  assert.match(html, /进入之后的行动/)
  assert.match(html, /HP 0 \/ 11/)
  assert.match(html, /濒死/)
  assert.doesNotMatch(html, /rollback-confirmation-layout|rollback-preview-viewport|展开完整消息/)

  const { default: History } = await vite.ssrLoadModule('/src/components/MobileDiceHistory.vue')
  const history = await renderToString(createSSRApp({ render: () => h(History, {
    query: '', category: '', result: '', allCount: 1, matchCopy: '已显示 1 条掷骰记录', filtersActive: false,
    loadingOlder: false, hasOlder: false, loadLabel: '已显示全部掷骰记录', loadHint: '',
    entries: [{ messageId: 4, title: '周寻 · 侦查', statusLabel: '困难成功', tone: 'success', category: '普通检定', resultKind: 'success', occurredAt: '2026-09-10T14:32:00', aggregate: { summary: { id: 3, conversationId: 1 }, results: [{ id: 2, summaryId: 3, resultData: { formula: '1D100', modules: [], result: 24 }, resolution: { targetValue: 65 } }] } }],
  }) }))
  assert.match(history, /查看 \/ 回放/)
  assert.match(history, /定位消息/)
  assert.match(history, /24 <small[^>]*>\/ 65/)
  assert.ok(history.indexOf('检定类型') < history.indexOf('检定结果'))
  assert.doesNotMatch(history, /dice-history-scroll|第 4 轮|阅览室/)
})

test('mobile history promotes a concise single-person title and renders individual group outcomes', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: History } = await vite.ssrLoadModule('/src/components/MobileDiceHistory.vue')
  const reason = '周寻逐一检查房间中的遗留物，希望找到失踪者的踪迹。'
  const detail = (id: number, name: string, category: string, result: number) => ({ id, summaryId: 3, resultData: { formula: '1D100', modules: [], result }, resolution: { characterName: name, checkName: '侦查', targetValue: 65, outcome: { category } } })
  const entries = [
    { messageId: 4, title: reason, statusLabel: '成功', tone: 'success', category: '普通检定', resultKind: 'success', aggregate: { summary: { id: 3, conversationId: 1 }, results: [detail(1, '周寻', 'SUCCESS', 24)] } },
    { messageId: 5, title: '群体侦查', statusLabel: '分别结算', tone: 'default', category: '群体检定', resultKind: 'other', aggregate: { summary: { id: 4, conversationId: 1 }, results: [detail(2, '林遥', 'SUCCESS', 31), detail(3, '陈默', 'FAILURE', 80)] } },
  ]
  const html = await renderToString(createSSRApp({ render: () => h(History, {
    query: '', category: '', result: '', allCount: 2, matchCopy: '', filtersActive: false,
    loadingOlder: false, hasOlder: false, loadLabel: '', loadHint: '', entries,
  }) }))
  assert.match(html, /<header[^>]*><strong[^>]*>周寻 · 侦查<\/strong>/)
  assert.match(html, new RegExp('<details[^>]*class="history-reason"[^>]*>.*查看行动说明.*' + reason + '.*<\/details>'))
  assert.match(html, /林遥 · 侦查[^]*?31[^]*?history-tag[^>]*>成功/)
  assert.match(html, /陈默 · 侦查[^]*?80[^]*?<span(?=[^>]*\bhistory-tag\b)(?=[^>]*\bis-failure\b)[^>]*>失败/)
})

test('long restore boundary is collapsed to three lines with a full-text toggle and no nested scroll viewport', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: Restore } = await vite.ssrLoadModule('/src/components/MobileRestorePreview.vue')
  const content = '调查员走进了旧宅，走廊里传来细微的脚步声。'.repeat(30)
  const html = await renderToString(createSSRApp({ render: () => h(Restore, {
    targetTime: '14:32', loading: false, failed: false, deletesManualSave: false, investigators: [],
    preview: { retained: [{ id: 1, content, speakerType: 'kp' }], deleted: [], deletedMessagesOmitted: false },
  }) }))
  assert.ok(html.includes(content))
  assert.match(html, /class="restore-boundary-collapsed"/)
  assert.match(html, /aria-expanded="false"[^>]*>展开完整消息/)
  assert.ok(html.indexOf(content) < html.indexOf('调查员快照'))
  assert.doesNotMatch(html, /rollback-preview-viewport/)
})

test('mobile dice history outlines loaded same-group rounds without joining records across day headings', async context => {
  const vite = await createServer({ appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: History } = await vite.ssrLoadModule('/src/components/MobileDiceHistory.vue')
  const entries = [
    [3, 12, '2026-09-10T14:32:00'], [1, 12, '2026-09-10T14:30:00'],
    [1, 13, '2026-09-10T14:20:00'], [1, 13, '2026-09-09T14:20:00'],
  ].map(([roundNo, summaryId, occurredAt], index) => ({
    messageId: index + 1, title: `记录${index + 1}`, statusLabel: '成功', tone: 'success',
    category: '普通检定', resultKind: 'success', occurredAt,
    aggregate: { summary: { id: summaryId, conversationId: 1 }, results: [
      { id: index + 1, summaryId, roundNo, resultData: { formula: '1D100', modules: [], result: 24 } },
    ] },
  }))
  const html = await renderToString(createSSRApp({ render: () => h(History, {
    query: '', category: '', result: '', allCount: 4, matchCopy: '', filtersActive: false,
    loadingOlder: false, hasOlder: true, loadLabel: '', loadHint: '', entries,
  }) }))
  const classLists = Array.from(html.matchAll(/class="([^"]*)"/g), match => match[1]!.split(/\s+/))
  assert.equal(classLists.filter(classes => classes.includes('history-roll-group') && classes.includes('is-grouped')).length, 1)
  assert.equal((html.match(/aria-label="同组掷骰"/g) || []).length, 1)
  assert.equal(classLists.filter(classes => classes.includes('history-card')).length, 4)
  assert.ok(html.indexOf('记录1') < html.indexOf('记录2'))
  assert.ok(html.indexOf('记录2') < html.indexOf('记录3'))
  assert.ok(html.indexOf('记录3') < html.indexOf('记录4'))
  assert.match(html, /第 3 次掷骰/)
  assert.doesNotMatch(html, /第 2 次掷骰/)
})
