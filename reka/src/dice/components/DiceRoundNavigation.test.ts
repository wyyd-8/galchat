import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'

async function renderNavigation(overrides: Record<string, unknown> = {}) {
  const source = await readFile(new URL('./DiceRoundNavigation.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*?)<\/template>/)![1]!
  const { code } = compile(template, { mode: 'function' })
  const render = new Function('Vue', code)(Vue)
  const events: string[] = []
  const node = render({ phase: 'complete', hasPrevious: true, hasNext: true, showContinue: false, roundPosition: 2, roundCount: 3,
    $emit: (event: string) => events.push(event), ...overrides }, []) as Vue.VNode
  return { node, events }
}

function buttons(node: Vue.VNode): Vue.VNode[] {
  return (Array.isArray(node.children) ? node.children : []).filter(child => (
    typeof child === 'object' && child != null && 'type' in child && child.type === 'button'
  )) as Vue.VNode[]
}

test('arrow navigation shows the current round and retains accessible direction labels', async () => {
  for (const phase of ['ready', 'complete']) {
    const { node, events } = await renderNavigation({ phase })
    const actions = buttons(node)
    assert.deepEqual(actions.map(action => action.props?.['aria-label']), ['上一轮', '下一轮'])
    assert.deepEqual(actions.map(action => action.props?.title), ['上一轮', '下一轮'])
    const position = (node.children as Vue.VNode[]).find(child => child.type === 'span')!
    assert.equal(position.children, '2 / 3')
    assert.equal(position.props?.['aria-label'], '第 2 轮，共 3 轮')
    actions[0]!.props!.onClick()
    actions[1]!.props!.onClick()
    assert.deepEqual(events, ['previous', 'next'])
  }
})

test('round boundaries hide only the unavailable direction, and a single round hides the controls', async () => {
  assert.deepEqual(buttons((await renderNavigation({ hasPrevious: false })).node).map(b => b.props?.['aria-label']), ['下一轮'])
  assert.deepEqual(buttons((await renderNavigation({ hasNext: false })).node).map(b => b.props?.['aria-label']), ['上一轮'])
  assert.equal((await renderNavigation({ hasPrevious: false, hasNext: false })).node.type, Vue.Comment)
})

test('Continue and in-flight playback suppress round browsing', async () => {
  assert.equal((await renderNavigation({ showContinue: true })).node.type, Vue.Comment)
  for (const phase of ['idle', 'loading', 'playing', 'error']) {
    assert.equal((await renderNavigation({ phase })).node.type, Vue.Comment)
  }
})
