import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createRenderer, h, ssrContextKey } from 'vue'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('page callbacks ignore obsolete navigation and retain template ownership', async t => {
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

  state.workspace.selectedWorldId.value = 3
  state.workspace.conversations.value = [conversation, { ...conversation, id:8, title:'second run' }]
  t.mock.method(api, 'conversation', async (id:number) => ({ ...conversation, id, characterIds:[] }))
  t.mock.method(api, 'actorRuntimes', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'investigatorCards', async () => [{actorType:'PLAYER',cardId:100}])
  const waitFor = async (check:()=>boolean) => {
    const deadline = Date.now()+1000
    while (!check()) { if (Date.now()>deadline) throw new Error('timeout'); await new Promise(resolve=>setTimeout(resolve,0)) }
  }
  for (const outcome of ['success','abort']) for (const returnToOrigin of [false, true]) await t.test(`obsolete TRPG start ${outcome} cannot change settings (return=${returnToOrigin})`, async () => {
    let release!:()=>void
    t.mock.method(api, 'groupMessages', async (id:number) => {
      if (id===7 && outcome==='success') await new Promise<void>(resolve=>{release=resolve})
      return []
    })
    t.mock.method(streamTrpgTurn, 'continue', async (_id:number,_request:string,receive:(e:unknown)=>void) => {
      if (outcome==='abort') await new Promise<void>(resolve=>{release=resolve})
      receive({eventType:'turn.completed',conversationId:7,turnId:42})
    })
    state.workspace.selectedConversationId.value=7
    await nextTick()
    const old = state.startTrpgTurnWithExperiments()
    await waitFor(()=>!!release)
    await state.selectConversation(8)
    if (returnToOrigin) {
      t.mock.method(api, 'groupMessages', async () => [])
      await state.selectConversation(7)
    }
    state.trpgAutoAdvance = true
    state.trpgDirectionEnabled = true
    state.trpgInvestigatorDirection = 'B的新行动方向'
    release()
    await old
    assert.deepEqual({ auto:state.trpgAutoAdvance, enabled:state.trpgDirectionEnabled, direction:state.trpgInvestigatorDirection },
      {auto:true,enabled:true,direction:'B的新行动方向'})
  })
  await t.test('obsolete binding check does not open a modal in an already bound run', async () => {
    t.mock.method(api, 'groupMessages', async () => [])
    let release!:(cards:unknown[])=>void
    let reads7=0
    t.mock.method(api, 'investigatorCards', async (id:number) => {
      if(id===7 && ++reads7===2) return await new Promise(resolve=>{release=resolve})
      return [{actorType:'PLAYER',cardId:100}]
    })
    const old = state.selectConversation(7)
    await waitFor(()=>!!release)
    await state.selectConversation(8)
    assert.equal(state.dialogs.trpgBinding,false)
    release([])
    await old
    assert.equal(state.workspace.selectedConversationId.value,8)
    assert.equal(state.dialogs.trpgBinding,false,'old run binding result must not open a modal in B')
  })
  await t.test('stale world template load must not edit another world', async () => {
    state.workspace.worlds.value = [
      {id:3,worldId:30,name:'A',myWorld:true},
      {id:4,worldId:40,name:'B',myWorld:true},
    ]
    state.workspace.selectedWorldId.value = 3
    state.view='world'
    state.dialogs.settings=true
    let release!:(value:unknown)=>void
    t.mock.method(api,'myWorldTemplate',async (id:number)=> {
      assert.equal(id,3)
      return await new Promise(resolve=>{release=resolve})
    })
    const old=state.openEditTemplate()
    await waitFor(()=>!!release)
    state.dialogs.settings=false
    state.workspace.selectedWorldId.value=4
    release({id:30,name:'A template',background:'A background'})
    await old
    const dialogOpened=state.dialogs.template
    const writes:unknown[]=[]
    t.mock.method(api,'updateWorldTemplate',async (id:number,payload:unknown)=>{
      writes.push({id,payload})
      throw new Error('probe stops at write boundary')
    })
    if(dialogOpened) await state.saveTemplate().catch(()=>{})
    assert.deepEqual({dialogOpened,writes}, {dialogOpened:false,writes:[]})
  })
  await t.test('stale template preview must not reopen after navigation', async () => {
    state.view='library'
    state.dialogs.templatePreview=false
    let release!:(value:unknown)=>void
    t.mock.method(api,'worldTemplate',async ()=>await new Promise(resolve=>{release=resolve}))
    const old=state.openTemplatePreview(30)
    await waitFor(()=>!!release)
    state.view='group'
    release({id:30,name:'old preview',background:'old'})
    await old
    assert.equal(state.dialogs.templatePreview,false)
  })
  await t.test('closing and reopening settings invalidates a pending template editor', async () => {
    state.workspace.selectedWorldId.value = 3
    state.view = 'world'
    state.dialogs.template = false
    state.dialogs.settings = true
    let release!: (value: unknown) => void
    t.mock.method(api, 'myWorldTemplate', async () => new Promise(resolve => { release = resolve }))
    const pending = state.openEditTemplate()
    await waitFor(() => !!release)
    state.dialogs.settings = false
    state.dialogs.settings = true
    release({ id: 30, name: 'obsolete', background: 'old' })
    await pending
    assert.equal(state.dialogs.template, false)
    assert.equal(state.dialogs.settings, true)
  })
  await t.test('an opened template editor cannot save into another world', async () => {
    state.workspace.selectedWorldId.value = 3
    state.view = 'world'
    state.dialogs.settings = true
    t.mock.method(api, 'myWorldTemplate', async () => ({ id: 30, name: 'A template', background: 'A background' }))
    await state.openEditTemplate()
    assert.equal(state.dialogs.template, true, 'current editor still opens normally')
    const writes: number[] = []
    t.mock.method(api, 'updateWorldTemplate', async (id: number) => { writes.push(id); throw new Error('write boundary') })
    await assert.rejects(state.saveTemplate(), /write boundary/)
    assert.deepEqual(writes, [3], 'normal saving targets the originating world')
    state.workspace.selectedWorldId.value = 4
    await state.saveTemplate().catch(() => {})
    assert.deepEqual(writes, [3], 'stale editor must not issue a second write to B')
  })
  await t.test('current template preview still opens', async () => {
    state.view = 'library'
    state.dialogs.templatePreview = false
    t.mock.method(api, 'worldTemplate', async () => ({ id: 30, name: 'current preview', background: 'current' }))
    await state.openTemplatePreview(30)
    assert.equal(state.dialogs.templatePreview, true)
    assert.equal(state.selectedTemplatePreview.name, 'current preview')
  })
  await t.test('failed run loading never checks bindings against the previous party', async () => {
    state.workspace.selectedWorldId.value = 3
    state.workspace.conversations.value = [{ ...conversation, id: 7 }, { ...conversation, id: 8 }]
    t.mock.method(api, 'conversation', async (id: number) => ({ ...conversation, id, characterIds: id === 7 ? [101] : [102] }))
    t.mock.method(api, 'replyPlan', async () => [])
    let failed = true
    t.mock.method(api, 'groupMessages', async (id: number) => {
      if (id === 8 && failed) throw new Error('history unavailable')
      return []
    })
    t.mock.method(api, 'investigatorCards', async (id: number) => [
      { actorType: 'PLAYER', cardId: 100 },
      { actorType: 'BOT', participantId: id === 7 ? 101 : 102, cardId: id === 7 ? 201 : 202 },
    ])
    await state.selectConversation(7)
    assert.deepEqual(state.workspace.participantIds.value, [101])
    await state.selectConversation(8)
    assert.equal(state.dialogs.trpgBinding, false)
    assert.deepEqual(state.workspace.participantIds.value, [])
    failed = false
    await state.selectConversation(8)
    assert.equal(state.dialogs.trpgBinding, false)
    assert.deepEqual(state.workspace.participantIds.value, [102])
    t.mock.method(api, 'investigatorCards', async () => [{ actorType: 'PLAYER', cardId: 100 }])
    await state.selectConversation(8)
    assert.equal(state.dialogs.trpgBinding, true, 'a successfully loaded party still prompts for missing cards')
  })
  await t.test('new runs only open card binding after their initial load succeeds', async () => {
    state.workspace.selectedWorldId.value = 3
    state.conversationForm.title = 'new run'
    state.conversationForm.moduleId = '1'
    state.conversationForm.characterIds = [102]
    const created = { ...conversation, id: 9, characterIds: [102] }
    t.mock.method(api, 'createConversation', async () => created)
    t.mock.method(api, 'conversations', async () => [created])
    t.mock.method(api, 'conversation', async () => created)
    for (const failed of [true, false]) {
      state.view = 'world'
      state.dialogs.trpgBinding = false
      t.mock.method(api, 'groupMessages', async () => {
        if (failed) throw new Error('history unavailable')
        return []
      })
      await state.createTrpgConversation()
      assert.equal(state.dialogs.trpgBinding, !failed)
      if (failed) assert.equal(state.view, 'group')
      else assert.deepEqual(state.workspace.participantIds.value, [102])
    }
  })
})
