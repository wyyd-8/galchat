import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'

const rowSource = readFileSync(new URL('./TrpgActorRow.vue', import.meta.url), 'utf8')
const renderRow = new Function('Vue', compile(`${rowSource.match(/<component :is="isMobile \? CollapsibleRoot[\s\S]*?>/)![0]}</component>`, {
  mode: 'function',
}).code)(Vue)

function row(mobile: boolean, modal: boolean, cardId: number | null = 1) {
  const component = { render() {} }
  return renderRow({
    isMobile: mobile, hasOpenDialog: modal,
    CollapsibleRoot: component, TooltipRoot: component, CollapsibleTrigger: component,
    TooltipTrigger: component, TooltipPortal: component, CollapsibleContent: component, TooltipContent: component,
    hasOverview: () => true, investigatorCardId: () => cardId,
    overviewOpen: false, updateOverview() {},
  }, []) as Vue.VNode
}

test('desktop overview preserves its focus target and is controlled and disabled while a dialog is open', () => {
  const before = row(false, false)
  const during = row(false, true)
  assert.equal(before.key, during.key, 'modal changes must preserve the actor DOM node for focus restoration')
  assert.equal(during.props?.open, false)
  assert.equal(typeof during.props?.['onUpdate:open'], 'function')
  assert.equal(during.props?.disabled, true)
  const after = row(false, false)
  assert.equal(during.key, after.key)
  assert.equal(after.props?.disabled, false)
  assert.equal(after.props?.ignoreNonKeyboardFocus, true)
})

test('changing a desktop actor between overview and card link replaces its tooltip anchor owner', () => {
  assert.notEqual(row(false, false, null).key, row(false, false, 1).key)
})

test('mobile disclosures are not reset or disabled by their own dialog or a nested dialog', () => {
  assert.equal(row(true, false).key, row(true, true).key)
  assert.notEqual(row(true, true).props?.disabled, true)
})

test('actor overview hides when detached and collapsing the roster disposes its portals', () => {
  assert.match(rowSource, /:hide-when-detached="!isMobile"/)
  const stage = readFileSync(new URL('./GroupChatStage.vue', import.meta.url), 'utf8')
  assert.match(stage, /<div v-if="planOpen" class="reply-plan-list">/)
})

test('dialog presence tracks nested dialogs, embedded changes and unmount cleanup', async () => {
  const url = new URL('../composables/useDialogPresence.ts', import.meta.url)
  assert.ok(existsSync(url), 'shared dialog visibility must be available to background overviews')
  const { hasOpenDialog, useDialogPresence } = await import(url.href)
  const firstScope = Vue.effectScope()
  const secondScope = Vue.effectScope()
  const first = Vue.ref(false)
  const second = Vue.ref(true)
  const embedded = Vue.ref(true)
  try {
    firstScope.run(() => useDialogPresence(first))
    secondScope.run(() => useDialogPresence(Vue.computed(() => second.value && !embedded.value)))
    assert.equal(hasOpenDialog.value, false, 'embedded content is not a modal')
    first.value = true
    assert.equal(hasOpenDialog.value, true, 'presence changes synchronously with open')
    embedded.value = false
    first.value = false
    assert.equal(hasOpenDialog.value, true, 'closing one dialog must not expose the background under another')
    secondScope.stop()
    assert.equal(hasOpenDialog.value, false, 'unmounting an open dialog removes its presence')
    first.value = true
    assert.equal(hasOpenDialog.value, true)
    firstScope.stop()
    assert.equal(hasOpenDialog.value, false)
  } finally {
    firstScope.stop()
    secondScope.stop()
  }
})

test('BaseDialog registers only non-embedded open instances', () => {
  const source = readFileSync(new URL('./ui/BaseDialog.vue', import.meta.url), 'utf8')
  assert.match(source, /useDialogPresence\(computed\(\(\) => open.value && !props.embedded\)\)/)
})


test('modal transitions close actor overviews and reject stale delayed opens until a fresh interaction', async () => {
  const url = new URL('../composables/useActorOverview.ts', import.meta.url)
  assert.ok(existsSync(url), 'actor overview must guard delayed callbacks')
  const { useActorOverview } = await import(url.href)
  const scope = Vue.effectScope()
  const blocked = Vue.ref(false)
  try {
    const state = scope.run(() => useActorOverview(blocked))!
    state.allowOverview()
    state.updateOverview(true)
    assert.equal(state.overviewOpen.value, true)
    blocked.value = true
    assert.equal(state.overviewOpen.value, false)
    state.allowOverview()
    state.updateOverview(true)
    assert.equal(state.overviewOpen.value, false, 'background interaction cannot open over a modal')
    blocked.value = false
    state.updateOverview(true)
    assert.equal(state.overviewOpen.value, false, 'a timer created before the modal cannot reopen the tooltip')
    state.allowOverview()
    state.updateOverview(true)
    assert.equal(state.overviewOpen.value, true, 'fresh hover or keyboard focus works after closing')
    state.updateOverview(false)
    assert.equal(state.overviewOpen.value, false)
  } finally { scope.stop() }
})
