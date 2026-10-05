import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createRenderer, defineComponent, reactive, nextTick, h, ssrContextKey } from 'vue'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

function deferred() {
  let resolve!: (value?: any) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<any>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const makeDraft = (id = 701) => ({ draftId: id, version: 1, creationMode: 'AUTO_QUICK_START', state: { buildRolls: {} } })
const settle = async () => { for (let i = 0; i < 12; i++) await nextTick() }

test('character binding keeps asynchronous results in their originating dialog and investigator', async t => {
  const originals = ['window', 'localStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  Object.defineProperty(globalThis, 'window', { configurable: true, value: {
    addEventListener() {}, removeEventListener() {}, clearTimeout, setTimeout,
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
  } })
  const vite = await createServer({ configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)),
    plugins: [{ name: 'omit-browser-dice-player', enforce: 'pre', transform(_code, id) {
      if (id.endsWith('/dice/components/DicePlayerDialog.vue')) return '<template><div /></template>'
    } }, vue()], resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  const { api } = await vite.ssrLoadModule('/src/api/client.ts')
  const { notice } = await vite.ssrLoadModule('/src/composables/useNotice.ts')
  t.after(async () => {
    clearTimeout(notice.timer)
    await vite.close()
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else Reflect.deleteProperty(globalThis, key)
    }
  })
  async function mount(t: any, wizard = false) {
    t.mock.method(api, 'investigatorCards', async () => [])
    t.mock.method(api, 'activeCharacterCardDraft', async () => null)
    t.mock.method(api, 'characterCardCreationRules', async () => ({ skills: [], weapons: [] }))
    notice.open = false
    const name = wizard ? 'StepwiseCharacterCardWizard' : 'TrpgCharacterBindingDialog'
    const { default: Component } = await vite.ssrLoadModule(`/src/components/${name}.vue`)
    Component.render = () => h('div') // Exercise real setup; omit visual children.
    const props: any = reactive({ modelValue: true, conversation: { id: 7 }, characters: [], participantIds: [101, 102],
      runId: 7, participantId: 101, active: true, draft: null,
      'onUpdate:draft': (value: any) => { props.draft = value },
      onComplete: () => { events.push('complete') }, onAbandoned: () => { events.push('abandoned') },
    })
    const events: string[] = []
    const renderer = createRenderer<any, any>({ patchProp() {}, insert() {}, remove() {},
      createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
      setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
    let state: any
    const app = renderer.createApp(defineComponent({ render: () => h(Component, props) }))
    app.provide(ssrContextKey, { modules: new Set() })
    app.mixin({ created() { if (this.$options.__name === name) state = (this.$ as any).setupState } })
    app.mount({})
    let mounted = true
    const unmount = () => { if (mounted) { mounted = false; app.unmount() } }
    t.after(unmount)
    await settle()
    if (!wizard) await state.selectTarget('character:101')
    return { state, props, events, unmount }
  }
  const draftMethods = [
    ['generateCard', 'createAutoCharacterCardDraft'],
    ['regenerateCard', 'regenerateCharacterCardDraft'],
    ['rewriteBackground', 'rewriteCharacterCardBackground'],
    ['loadSelectedCard', 'activeCharacterCardDraft'],
  ]
  for (const [method, endpoint] of draftMethods) await t.test(`${method} ignores a response after switching runs`, async t => {
    const { state, props } = await mount(t)
    state.draft = makeDraft()
    const response = deferred()
    t.mock.method(api, endpoint, (runId: number) => endpoint === 'activeCharacterCardDraft' && runId !== 7 ? Promise.resolve(null) : response.promise)
    const pending = state[method]()
    props.conversation = { id: 8 }
    await settle()
    state.draft = makeDraft(802)
    response.resolve(makeDraft())
    await pending
    await settle()
    assert.equal(state.draft?.draftId, 802)
    assert.equal(state.diceOpen, false)
  })
  for (const boundary of ['target', 'close/reopen', 'away/back', 'unmount']) await t.test(`generation is invalidated by ${boundary}`, async t => {
    const { state, props, unmount } = await mount(t)
    const response = deferred()
    t.mock.method(api, 'createAutoCharacterCardDraft', () => response.promise)
    const pending = state.generateCard()
    if (boundary === 'target') await state.selectTarget('character:102')
    if (boundary === 'close/reopen') { props.modelValue = false; await settle(); props.modelValue = true; await settle() }
    if (boundary === 'away/back') { props.conversation = { id: 8 }; await settle(); props.conversation = { id: 7 }; await settle() }
    if (boundary === 'unmount') unmount()
    response.resolve(makeDraft())
    await pending
    assert.equal(state.draft, null)
    let submitted = false
    t.mock.method(api, 'completeCharacterCardDraft', async () => { submitted = true })
    await state.confirmGeneratedCard()
    assert.equal(submitted, false)
  })
  for (const [method, endpoint] of [['confirmGeneratedCard', 'completeCharacterCardDraft'], ['abandonAutoDraft', 'abandonCharacterCardDraft'], ['removeCard', 'deleteCharacterCard']]) await t.test(`${method} does not clear the next editor or refresh its cards`, async t => {
    const { state, props } = await mount(t)
    state.draft = makeDraft()
    state.card = { character: { id: 71 } }
    state.confirmDelete = true
    const response = deferred()
    t.mock.method(api, endpoint, () => response.promise)
    const pending = state[method]()
    props.conversation = { id: 8 }
    await settle()
    state.draft = makeDraft(802)
    let refreshes = 0
    t.mock.method(api, 'investigatorCards', async () => { refreshes++; return [] })
    response.resolve()
    await pending
    assert.equal(state.draft?.draftId, 802)
    assert.equal(refreshes, 0)
    assert.equal(notice.open, false)
  })
  await t.test('import finishes luck for its original card without refreshing the new run', async t => {
    const { state, props } = await mount(t)
    state.cardText = 'investigator text'
    const response = deferred()
    t.mock.method(api, 'createCharacterCard', () => response.promise)
    let luckCard: number | undefined
    t.mock.method(api, 'rollCharacterLuck', async (id: number) => { luckCard = id; return { result: 55, modules: [], formula: '3d6*5' } })
    const pending = state.bindCard()
    props.conversation = { id: 8 }
    await settle()
    state.draft = makeDraft(802)
    response.resolve({ character: { id: 71, name: 'original investigator' } })
    await pending
    assert.equal(luckCard, 71)
    assert.equal(state.draft?.draftId, 802)
    assert.equal(state.diceOpen, false)
    assert.equal(notice.open, false)
  })
  await t.test('obsolete failures do not release a newer load or show an error', async t => {
    const { state, props } = await mount(t)
    const old = deferred()
    const pending = state.execute(() => old.promise)
    props.modelValue = false
    await settle()
    const fresh = deferred()
    t.mock.method(api, 'investigatorCards', () => fresh.promise)
    props.modelValue = true
    await settle()
    old.reject(new Error('old run failed'))
    await pending
    assert.equal(state.busy, true)
    assert.equal(notice.open, false)
    fresh.resolve([])
    await settle()
    assert.equal(state.busy, false)
  })
  await t.test('current generation and confirmation still work', async t => {
    const { state } = await mount(t)
    t.mock.method(api, 'createAutoCharacterCardDraft', async () => makeDraft())
    let confirmed: number | undefined
    t.mock.method(api, 'completeCharacterCardDraft', async (id: number) => { confirmed = id })
    await state.execute(state.generateCard)
    assert.equal(state.draft.draftId, 701)
    await state.execute(state.confirmGeneratedCard)
    assert.equal(confirmed, 701)
    assert.equal(state.draft, null)
    assert.equal(notice.tone, 'success')
    assert.equal(state.busy, false)
  })
  await t.test('a delayed card list cannot replace the current run or start its card load', async t => {
    const { state, props } = await mount(t)
    const response = deferred()
    t.mock.method(api, 'investigatorCards', (runId: number) => runId === 7 ? response.promise : Promise.resolve([]))
    let cardReads = 0
    t.mock.method(api, 'characterCardById', async () => { cardReads++; return { character: { id: 71 } } })
    const pending = state.refreshCards(true)
    props.conversation = { id: 8 }
    await settle()
    response.resolve([{ cardId: 71, actorType: 'PLAYER', name: 'old player' }])
    await pending
    assert.deepEqual(state.cards, [])
    assert.equal(cardReads, 0)
  })
  await t.test('a delayed bound card cannot replace another investigator', async t => {
    const { state } = await mount(t)
    state.cards = [{ cardId: 71, actorType: 'BOT', participantId: 101, name: 'old investigator' }]
    const response = deferred()
    t.mock.method(api, 'characterCardById', () => response.promise)
    const pending = state.loadSelectedCard()
    await state.selectTarget('character:102')
    response.resolve({ character: { id: 71 } })
    await pending
    assert.equal(state.card, null)
    assert.equal(state.selectedKey, 'character:102')
  })
  await t.test('opening with a requested investigator loads its draft normally', async t => {
    const { state, props } = await mount(t)
    props.modelValue = false
    await settle()
    props.requestedTargetKey = 'character:102'
    t.mock.method(api, 'activeCharacterCardDraft', async (runId: number, participantId: number) => {
      assert.equal(runId, 7)
      assert.equal(participantId, 102)
      return makeDraft(702)
    })
    props.modelValue = true
    await settle()
    assert.equal(state.selectedKey, 'character:102')
    assert.equal(state.draft?.draftId, 702)
    assert.equal(state.selectedCreationMethod, 'AUTO')
    assert.equal(state.busy, false)
  })
  await t.test('current import still rolls luck and notifies', async t => {
    const { state } = await mount(t)
    state.cardText = 'investigator text'
    t.mock.method(api, 'createCharacterCard', async () => ({ character: { id: 71, name: 'investigator' } }))
    t.mock.method(api, 'rollCharacterLuck', async () => ({ result: 55, modules: [], formula: '3d6*5' }))
    await state.execute(state.bindCard)
    assert.equal(state.diceOpen, true)
    assert.equal(state.diceRequest.presentation.resultValue, '55')
    assert.equal(notice.tone, 'success')
  })
  await t.test('current stepwise equipment submission completes and emits its card', async t => {
    const { state, props, events } = await mount(t, true)
    props.draft = makeDraft()
    await settle()
    t.mock.method(api, 'saveCharacterCardDraftEquipment', async () => ({ ...makeDraft(), version: 2 }))
    t.mock.method(api, 'completeCharacterCardDraft', async (id: number, input: any) => {
      assert.equal(id, 701)
      assert.equal(input.expectedVersion, 2)
      return { character: { id: 71 } }
    })
    await state.execute(state.confirmEquipment)
    assert.equal(props.draft, null)
    assert.deepEqual(events, ['complete'])
    assert.equal(state.busy, false)
    assert.equal(state.failure, '')
  })
  for (const [method, endpoint] of [['createDraft', 'createStepCharacterCardDraft'], ['rollAttributes', 'rollCharacterCardDraftAttributes'], ['submitAgeAdjustment', 'saveCharacterCardDraftAgeAdjustment'], ['confirmOccupation', 'saveCharacterCardDraftOccupation'], ['confirmSkills', 'saveCharacterCardDraftSkills'], ['rollBackground', 'rollCharacterCardDraftBackground'], ['confirmBackground', 'saveCharacterCardDraftBackground'], ['confirmEquipment', 'saveCharacterCardDraftEquipment'], ['completeDraft', 'completeCharacterCardDraft'], ['abandonDraft', 'abandonCharacterCardDraft']]) await t.test(`stepwise ${method} cannot write back after deactivation`, async t => {
    const { state, props, events } = await mount(t, true)
    props.draft = makeDraft()
    state.occupationText = '记者'
    state.confirmAbandon = true
    await settle()
    const response = deferred()
    const request = t.mock.method(api, endpoint, () => response.promise)
    let completions = 0
    if (method !== 'completeDraft') t.mock.method(api, 'completeCharacterCardDraft', async () => { completions++ })
    const pending = state[method]()
    assert.equal(request.mock.callCount(), 1)
    props.active = false
    await settle()
    props.draft = makeDraft(802)
    await settle()
    response.resolve(makeDraft())
    await pending
    assert.equal(props.draft?.draftId, 802)
    assert.deepEqual(events, [])
    assert.equal(completions, 0)
  })
})
