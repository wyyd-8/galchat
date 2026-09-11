import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref, shallowRef } from 'vue'
import { watchMobileDicePreparation } from './mobileDiceLifecycle.ts'

test('preloaded request waits for opening and both delayed portal nodes, then prepares once', async () => {
  const scope = effectScope()
  const open = ref(false)
  const id = ref<number | undefined>(1)
  const tray = shallowRef<HTMLElement | null>(null)
  const layer = shallowRef<HTMLElement | null>(null)
  let prepared = 0
  scope.run(() => watchMobileDicePreparation(() => open.value, () => id.value, tray, layer, () => { prepared++ }))
  await nextTick()
  assert.equal(prepared, 0)
  open.value = true
  await nextTick()
  assert.equal(prepared, 0, 'open alone is not a mounted portal')
  tray.value = {} as HTMLElement
  await nextTick()
  assert.equal(prepared, 0, 'renderer needs both DOM nodes')
  layer.value = {} as HTMLElement
  await nextTick()
  assert.equal(prepared, 1)
  await nextTick()
  assert.equal(prepared, 1, 'unchanged ready state does not restart preparation')
  scope.stop()
})

test('same request prepares again after closing and reopening, and request updates use existing nodes', async () => {
  const scope = effectScope()
  const open = ref(true)
  const id = ref<number | undefined>(1)
  const tray = shallowRef<HTMLElement | null>({} as HTMLElement)
  const layer = shallowRef<HTMLElement | null>({} as HTMLElement)
  const prepared: number[] = []
  scope.run(() => watchMobileDicePreparation(() => open.value, () => id.value, tray, layer, () => { prepared.push(id.value!) }))
  await nextTick()
  assert.deepEqual(prepared, [1])
  open.value = false
  tray.value = null
  layer.value = null
  await nextTick()
  assert.deepEqual(prepared, [1])
  open.value = true
  await nextTick()
  assert.deepEqual(prepared, [1])
  tray.value = {} as HTMLElement
  layer.value = {} as HTMLElement
  await nextTick()
  assert.deepEqual(prepared, [1, 1])
  id.value = 2
  await nextTick()
  assert.deepEqual(prepared, [1, 1, 2])
  scope.stop()
  id.value = 3
  await nextTick()
  assert.deepEqual(prepared, [1, 1, 2], 'unmount stops preparation')
})
