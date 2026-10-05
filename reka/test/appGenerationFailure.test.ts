import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createRenderer, h, ssrContextKey } from 'vue'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('TRPG auto advance handles generation failures and queued dice rounds', async t => {
  const originals = ['localStorage', 'sessionStorage', 'window', 'document'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  for (const key of ['localStorage', 'sessionStorage']) Object.defineProperty(globalThis, key, { configurable: true, value: {
    getItem: () => null, setItem() {}, removeItem() {},
  } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    addEventListener() {}, removeEventListener() {}, clearTimeout, setTimeout,
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  } })
  let app: any
  t.after(() => { app?.unmount(); for (const [key, descriptor] of originals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else Reflect.deleteProperty(globalThis, key)
  } })
  const vite = await createServer({ configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [{ name: 'omit-browser-dice-player', enforce: 'pre', transform(_code, id) {
      if (id.endsWith('/dice/components/DicePlayerDialog.vue')) return '<template><div /></template>'
    } }, vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  t.after(() => vite.close())
  const { default: App } = await vite.ssrLoadModule('/src/App.vue')
  // Keep App's real setup and handlers; these tests do not need its visual children.
  App.render = () => h('div')
  const renderer = createRenderer<any, any>({ patchProp() {}, insert() {}, remove() {},
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
  let state: any
  app = renderer.createApp(App)
  app.provide(ssrContextKey, { modules: new Set() })
  app.mixin({ created() { if (this.$options.__name === 'App') state = (this.$ as any).setupState } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { addEventListener() {}, removeEventListener() {}, documentElement: { style: { setProperty() {}, removeProperty() {} } } } })
  app.mount({})

  const { api, streamTrpgTurn } = await vite.ssrLoadModule('/src/api/client.ts')
  const { nextTick } = await import('vue')
  const conversation = { id: 7, userWorldId: 3, worldId: 2, mode: 'trpg', title: 'run', status: 'active' }
  state.workspace.conversations.value = [conversation]
  state.workspace.selectedConversationId.value = 7
  await nextTick()
  t.mock.method(api, 'conversation', async () => conversation)
  t.mock.method(api, 'groupMessages', async () => [])
  t.mock.method(api, 'replyPlan', async () => [])
  t.mock.method(api, 'currentTurn', async () => null)
  t.mock.method(api, 'combatOverview', async () => [])
  t.mock.method(api, 'investigatorCards', async () => [])
  for (const accepted of [false, true]) await t.test(`failure cancels auto advance before stream closes (accepted=${accepted})`, async () => {
    let release!: () => void
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: unknown) => void) => {
      if (accepted) receive({ eventType: 'turn.accepted', conversationId: 7, turnId: 42 })
      receive({ eventType: 'generation.failed', conversationId: 7, ...(accepted ? { turnId: 42 } : {}), error: 'cannot start' })
      await new Promise<void>(resolve => { release = resolve })
    })
    state.trpgAutoAdvance = true
    state.autoAdvanceDiceSummaryIds = new Set([501])
    const pending = state.startTrpgTurnWithExperiments()
    try {
      assert.equal(state.trpgAutoAdvance, false)
      assert.deepEqual([...state.autoAdvanceDiceSummaryIds], [])
    } finally { release(); await pending }
    assert.equal(state.trpgAutoAdvance, false)
  })
  await t.test('HTTP rejection cancels auto advance and its pending dice', async () => {
    const { GenerationRequestRejected } = await vite.ssrLoadModule('/src/streaming/generationConnection.ts')
    t.mock.method(streamTrpgTurn, 'continue', async () => { throw new GenerationRequestRejected('rejected') })
    state.trpgAutoAdvance = true
    state.autoAdvanceDiceSummaryIds = new Set([501])
    await state.startTrpgTurnWithExperiments()
    assert.equal(state.trpgAutoAdvance, false)
    assert.deepEqual([...state.autoAdvanceDiceSummaryIds], [])
    const { notice } = await vite.ssrLoadModule('/src/composables/useNotice.ts')
    clearTimeout(notice.timer)
  })
  await t.test('successful turn keeps auto advance enabled', async () => {
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: unknown) => void) => {
      receive({ eventType: 'turn.accepted', conversationId: 7, turnId: 43 })
      receive({ eventType: 'turn.completed', conversationId: 7, turnId: 43 })
    })
    state.trpgAutoAdvance = true
    await state.startTrpgTurnWithExperiments()
    assert.equal(state.trpgAutoAdvance, true)
  })

  await t.test('every settled round in a dice summary retains auto continue until the last round', async () => {
    const { createDiceAggregateFixture } = await vite.ssrLoadModule('/test/fixtures/dice.ts')
    const aggregate = createDiceAggregateFixture('multiplayer-check')
    aggregate.summary = { ...aggregate.summary, id: 501, status: 'COMPLETED', roundCount: 2 }
    const first = aggregate.results[0]
    aggregate.results = [
      { ...first, id: 601, summaryId: 501, roundNo: 1, resolvedAt: '2026-10-05T00:00:00' },
      { ...first, id: 602, summaryId: 501, roundNo: 2, resolvedAt: '2026-10-05T00:00:00' },
    ]
    let continuations = 0
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: unknown) => void) => {
      continuations++
      receive({ eventType: 'turn.completed', conversationId: 7, turnId: 44 })
    })
    state.trpgAutoAdvance = true
    state.openIncomingDiceMessages([aggregate])
    assert.equal(state.diceAutoContinue, true)
    assert.equal(state.queuedDiceAggregates.length, 1)
    await state.continueAfterDice()
    assert.equal(state.diceMessageAggregate.results[0].roundNo, 2)
    assert.equal(state.diceAutoContinue, true, 'second settled round still automatically continues')
    assert.equal(continuations, 0)
    await state.continueAfterDice()
    assert.equal(continuations, 1)
    assert.equal(state.dicePlayerOpen, false)
    assert.deepEqual([...state.autoAdvanceDiceSummaryIds], [])
  })

  await t.test('model save failure cancels automatic turn and preserves direction', async () => {
    state.workspace.conversationReady.value = true
    state.workspace.generationFailed.value = false
    state.workspace.currentTurn.value = null
    state.trpgAutoAdvance = true
    state.trpgDirectionEnabled = true
    state.trpgInvestigatorDirection = 'Inspect the window'
    state.autoAdvanceDiceSummaryIds = new Set([501])
    let release!: () => void
    t.mock.method(api, 'saveActorRuntime', async () => {
      await new Promise<void>(resolve => { release = resolve })
      throw new Error('model save failed')
    })
    let started = 0
    t.mock.method(streamTrpgTurn, 'continue', async (_id: number, _request: string, receive: (event: unknown) => void) => {
      started++
      receive({ eventType: 'turn.completed', conversationId: 7, turnId: 45 })
    })
    const saving = state.workspace.saveActorRuntime({ actorType: 'character', actorId: 101, controlMode: 'MODEL', modelApiId: 2 })
    const starting = state.startTrpgTurnWithExperiments('Inspect the window')
    release()
    await Promise.all([saving, starting])
    assert.equal(started, 0)
    assert.equal(state.trpgAutoAdvance, false)
    assert.deepEqual([...state.autoAdvanceDiceSummaryIds], [])
    assert.equal(state.trpgInvestigatorDirection, 'Inspect the window')
    assert.equal(state.trpgDirectionEnabled, true)
    const { notice } = await vite.ssrLoadModule('/src/composables/useNotice.ts')
    clearTimeout(notice.timer)
  })

})
