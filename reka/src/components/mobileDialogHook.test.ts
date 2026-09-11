import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { effectScope, nextTick, ref } from 'vue'
import { createServer } from 'vite'

// Exercise the real composable with browser history, rather than only its state machine.
test('mobile Back returns from an internal page before closing the dialog', async () => {
  const vite = await createServer({
    appType: 'custom', configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  const listeners = new Set<() => void>()
  const pages: unknown[] = [{ route: 'workspace' }]
  let position = 0
  const back = () => {
    if (!position) return
    position--
    for (const listener of [...listeners]) listener()
  }
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    location: { href: 'http://localhost/workspace' },
    history: {
      get state() { return pages[position] },
      pushState(state: unknown) { pages.splice(position + 1); pages.push(state); position++ },
      back,
    },
    addEventListener(type: string, listener: () => void) { if (type === 'popstate') listeners.add(listener) },
    removeEventListener(type: string, listener: () => void) { if (type === 'popstate') listeners.delete(listener) },
  } })
  const scope = effectScope()
  try {
    const { useMobileDialogHistory } = await vite.ssrLoadModule('/src/composables/useMobileDialogHistory.ts')
    const open = ref(true)
    const mobile = ref(true)
    const page = ref('detail')
    scope.run(() => useMobileDialogHistory(open, mobile, () => {
      if (page.value === 'detail') page.value = 'list'
      else open.value = false
    }))
    assert.equal(position, 1)
    back()
    await nextTick()
    await new Promise<void>(resolve => setImmediate(resolve))
    assert.equal(page.value, 'list')
    assert.equal(open.value, true)
    assert.equal(position, 1, 'the still-open dialog retains its Back boundary')
    back()
    await nextTick()
    await new Promise<void>(resolve => setImmediate(resolve))
    assert.equal(open.value, false)
    assert.equal(position, 0)
    assert.equal(listeners.size, 0)
    assert.deepEqual(pages[position], { route: 'workspace' })
  } finally {
    scope.stop()
    await nextTick()
    if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow)
    else Reflect.deleteProperty(globalThis, 'window')
    await vite.close()
  }
})
