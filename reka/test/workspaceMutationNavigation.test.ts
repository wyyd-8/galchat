import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { Conversation, GroupMessage } from '../src/api/types.ts'

const sourceRoot = new URL('../src/', import.meta.url)
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}.ts`, sourceRoot).href, shortCircuit: true }
    }
    if (context.parentURL?.startsWith(sourceRoot.href)
      && /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
      return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (!url.endsWith('/api/client.ts')) return nextLoad(url, context)
    const source = readFileSync(new URL(url), 'utf8')
    return {
      format: 'module-typescript',
      shortCircuit: true,
      source: source.replace('import.meta.env.VITE_API_BASE_URL', 'undefined'),
    }
  },
})

async function groupMutationFixture(t: import('node:test').TestContext) {
  const { api } = await import('../src/api/client.ts')
  const { useWorkspace } = await import('../src/composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const globals = { window: globalThis.window, localStorage: globalThis.localStorage,
    sessionStorage: globalThis.sessionStorage, fetch: globalThis.fetch }
  Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(), sessionStorage: storage() })
  t.after(() => Object.assign(globalThis, globals))
  let workspace!: ReturnType<typeof useWorkspace>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
    createElement: () => ({}), createText: text => ({ text }), createComment: text => ({ text }),
    setText(node, text) { node.text = text }, setElementText(node, text) { node.text = text },
    parentNode: node => node.parent as Record<string, unknown> | null, nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
  app.mount({})
  t.after(() => app.unmount())
  const conversations: Conversation[] = [7, 8].map(id => ({ id, userWorldId: 3, worldId: 2,
    mode: 'chat', title: String(id), status: 'active', characterIds: [101, 102] }))
  t.mock.method(api, 'conversation', async (id: number) => conversations.find(item => item.id === id)!)
  t.mock.method(api, 'conversations', async () => conversations.filter(item => item.id !== 7))
  t.mock.method(api, 'groupMessages', async (id: number): Promise<GroupMessage[]> => [{ id: id * 10,
    conversationId: id, turnId: 42, speakerType: 'user', messageKind: 'dialogue', content: `history-${id}`, sequenceNo: 1, status: 'completed' }])
  t.mock.method(api, 'replyPlan', async () => [{ source: 'USER', displayName: '群聊',
    items: [{ order: 1, actorType: 'character', actorId: 101 }] }])
  t.mock.method(api, 'currentTurn', async () => null)
  t.mock.method(api, 'actorRuntimes', async () => [])
  t.mock.method(api, 'modelApis', async () => [])
  t.mock.method(api, 'characters', async () => [])
  workspace.selectedWorldId.value = 3
  workspace.conversations.value = conversations
  await workspace.selectConversation(7)
  return { workspace, api }
}


function storage(entries: Array<[string, string]> = []): Storage {
  const values = new Map(entries)
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
    clear: () => values.clear(),
    key: (index) => [...values.keys()][index] ?? null,
    get length() { return values.size },
  }
}

async function waitFor(predicate: () => boolean) {
  const deadline = Date.now() + 1000
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('timed out waiting for condition')
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}
async function worldMutationFixture(t) {
  const {workspace, api} = await groupMutationFixture(t)
  const {notice} = await import('../src/composables/useNotice.ts')
  t.after(() => clearTimeout(notice.timer))
  workspace.session.id = 1
  const worlds = [3,4].map(id=>({id,worldId:id+10,name:`world-${id}`,myWorld:true}))
  const conversations = [
    {id:7,userWorldId:3,worldId:13,mode:'chat',title:'A',status:'active'},
    {id:8,userWorldId:4,worldId:14,mode:'chat',title:'B1',status:'active'},
    {id:9,userWorldId:4,worldId:14,mode:'chat',title:'B2',status:'active'},
  ]
  workspace.worlds.value = worlds
  t.mock.method(api,'userWorld',async id=>worlds.find(w=>w.id===id))
  t.mock.method(api,'userWorlds',async ()=>worlds)
  t.mock.method(api,'cocModules',async ()=>[])
  t.mock.method(api,'conversation',async id=>conversations.find(c=>c.id===id))
  t.mock.method(api,'conversations',async worldId=>conversations.filter(c=>c.userWorldId===worldId))
  t.mock.method(api,'worldSave',async id=>({userWorldId:id,remark:`save-${id}`}))
  t.mock.method(api,'worldDetails',async id=>[{id:id*10,worldId:id,about:`detail-${id}`}])
  t.mock.method(api,'characterTemplates',async ()=>[])
  await workspace.selectWorld(3)
  return {workspace,api,worlds,conversations}
}

test('workspace mutation: late closing refresh cannot overwrite new world conversation list', async t=>{
  const {workspace,api,conversations}=await worldMutationFixture(t)
  t.mock.method(api,'closeConversation',async ()=>undefined)
  let finish
  t.mock.method(api,'conversations',async id=>id===3 ? new Promise(resolve=>{finish=()=>resolve(conversations.filter(c=>c.userWorldId===3))}) : conversations.filter(c=>c.userWorldId===id))
  const pending=workspace.closeConversation()
  await waitFor(()=>!!finish)
  await workspace.selectWorld(4)
  finish();await pending
  assert.deepEqual(workspace.conversations.value.map(c=>c.id),[8,9])
})

test('workspace mutation: late snapshot save cannot overwrite new world snapshot', async t=>{
  const {workspace,api}=await worldMutationFixture(t)
  let finish
  t.mock.method(api,'saveWorld',async id=>new Promise(resolve=>{finish=()=>resolve({userWorldId:id,remark:'A saved'})}))
  const pending=workspace.saveSnapshot('A saved')
  await workspace.selectWorld(4)
  finish();await pending
  assert.equal(workspace.worldSave.value.userWorldId,4)
})

for (const operation of ['add','remove']) test(`workspace mutation: late detail ${operation} refresh cannot overwrite new world lore`, async t=>{
  const {workspace,api}=await worldMutationFixture(t)
  t.mock.method(api,'addWorldDetail',async ()=>undefined)
  t.mock.method(api,'deleteWorldDetail',async ()=>undefined)
  let finish
  t.mock.method(api,'worldDetails',async id=>id===13 ? new Promise(resolve=>{finish=()=>resolve([{id:130,worldId:13,about:'A lore'}])}) : [{id:140,worldId:14,about:'B lore'}])
  const pending=operation==='add'?workspace.addDetail({about:'A lore',details:'x'}):workspace.removeDetail(130)
  await waitFor(()=>!!finish)
  await workspace.selectWorld(4)
  finish();await pending
  assert.equal(workspace.details.value[0].worldId,14)
})

test('workspace mutation: delete old world must not clear current world and drafts', async t=>{
  const {workspace,api}=await worldMutationFixture(t)
  let finish
  t.mock.method(api,'deleteWorld',async ()=>new Promise(resolve=>{finish=resolve}))
  const pending=workspace.removeWorld()
  await workspace.selectWorld(4)
  workspace.messageInput.value='B draft'
  finish();await pending
  assert.equal(workspace.selectedWorldId.value,4)
  assert.equal(workspace.messageInput.value,'B draft')
})

for (const operation of ['load','template']) test(`workspace mutation: ${operation} completion preserves the new world's selected chat`, async t=>{
  const {workspace,api}=await worldMutationFixture(t)
  let finish
  t.mock.method(api,operation==='load'?'loadWorld':'updateWorldTemplate',async ()=>new Promise(resolve=>{finish=resolve}))
  t.mock.method(api,'worldTemplates',async ()=>[])
  const pending=operation==='load'?workspace.loadSnapshot():workspace.updateTemplate({name:'A edited'})
  await workspace.selectWorld(4)
  await workspace.selectConversation(9)
  finish();await pending
  assert.equal(workspace.selectedConversationId.value,9)
})


for (const mode of ['chat', 'trpg']) for (const navigation of ['world', 'away-back', 'logout', 'stay']) {
  test(`workspace mutation: ${mode} creation respects ${navigation}`, async t => {
    const { workspace, api, conversations } = await worldMutationFixture(t)
    let finish
    const created = { id: 10, userWorldId: 3, worldId: 13, mode, title: 'created', status: 'active', characterIds: [101] }
    t.mock.method(api, 'createConversation', async payload => {
      assert.equal(payload.userWorldId, 3)
      await new Promise(resolve => { finish = resolve })
      conversations.push(created)
      return created
    })
    t.mock.method(api, 'combatOverview', async () => [])
    t.mock.method(api, 'investigatorCards', async () => [])
    const pending = workspace.createConversation({mode, title:'created', characterIds:[101]})
    if (navigation === 'world' || navigation === 'away-back') await workspace.selectWorld(4)
    if (navigation === 'away-back') await workspace.selectWorld(3)
    if (navigation === 'logout') workspace.logout()
    const previousId = workspace.selectedConversationId.value
    finish()
    const result = await pending
    assert.equal(workspace.selectedConversationId.value, navigation === 'stay' ? 10 : previousId)
    assert.equal(Boolean(result), navigation === 'stay', 'only a current creation may navigate or open binding')
  })
}

for (const operation of ['save', 'close', 'detail']) test(`workspace mutation: ${operation} still updates the original page`, async t => {
  const { workspace, api, conversations } = await worldMutationFixture(t)
  if (operation === 'save') {
    t.mock.method(api,'saveWorld',async id=>({userWorldId:id,remark:'new save'}))
    await workspace.saveSnapshot('new save')
    assert.equal(workspace.worldSave.value.remark,'new save')
  } else if (operation === 'close') {
    t.mock.method(api,'closeConversation',async ()=>{conversations[0].status='closed'})
    await workspace.closeConversation()
    assert.equal(workspace.selectedConversation.value.status,'closed')
  } else {
    t.mock.method(api,'addWorldDetail',async ()=>undefined)
    t.mock.method(api,'worldDetails',async ()=>[{id:131,worldId:13,about:'new lore'}])
    await workspace.addDetail({about:'new lore',details:'text'})
    assert.equal(workspace.details.value[0].about,'new lore')
  }
})

test('workspace mutation: world settings save never refreshes another world', async t => {
  const { workspace, api, worlds } = await worldMutationFixture(t)
  let finish
  t.mock.method(api, 'updateWorld', async id => {
    assert.equal(id, 3)
    await new Promise(resolve => { finish = resolve })
  })
  const reads: number[] = []
  t.mock.method(api, 'userWorld', async id => { reads.push(id); return worlds.find(world => world.id === id) })
  const pending = workspace.updateWorld({ name: 'A edited' })
  await workspace.selectWorld(4)
  finish()
  const current = await pending
  assert.deepEqual(reads, [4], 'only navigation to B may load B; completing A must not read B again')
  assert.equal(current, false)
})

test('workspace mutation: deleting the selected world refreshes the remaining world list', async t => {
  const { workspace, api, worlds } = await worldMutationFixture(t)
  t.mock.method(api, 'deleteWorld', async id => { worlds.splice(worlds.findIndex(world => world.id === id), 1) })
  assert.equal(await workspace.removeWorld(), true)
  assert.equal(workspace.selectedWorldId.value, null)
  assert.deepEqual(workspace.worlds.value.map(world => world.id), [4])
})

test('a world using another author’s template opens without requesting private lore', async t => {
  const {workspace, api, worlds} = await worldMutationFixture(t)
  worlds[1].myWorld = false
  t.mock.method(api, 'worldDetails', async () => { throw new Error('无权访问该世界详情') })
  assert.equal(await workspace.selectWorld(4), true)
  assert.deepEqual(workspace.details.value, [])
  assert.equal(workspace.selectedConversationId.value, 8)
})

test('profile save updates both visible and persisted username', async t => {
  const {workspace, api} = await worldMutationFixture(t)
  workspace.session.username = '旧名字'
  localStorage.setItem('galchat.username', '旧名字')
  t.mock.method(api, 'updateUserInfo', async () => {})
  t.mock.method(api, 'userInfo', async () => ({id:1, username:'新名字', email:'user@example.test'}))
  await workspace.saveUserInfo({username:'新名字'})
  assert.equal(workspace.session.username, '新名字')
  assert.equal(localStorage.getItem('galchat.username'), '新名字')
})

for (const [method, field, oldValue, newValue] of [
  ['loadUserInfo', 'userInfo', {id:1,username:'old',email:'old@example.test'}, {id:2,username:'new',email:'new@example.test'}],
  ['loadWorlds', 'worlds', [{id:3,name:'old world'}], [{id:5,name:'new world'}]],
  ['loadTemplates', 'templates', [{id:13,name:'private old template'}], [{id:15,name:'new template'}]],
  ['loadModules', 'modules', [{id:21,name:'old module'}], [{id:22,name:'new module'}]],
] as const) {
  test(`${method} ignores an old response after switching accounts`, async t => {
    const {workspace, api} = await worldMutationFixture(t)
    let finish
    const endpoint = {loadUserInfo:'userInfo', loadWorlds:'userWorlds', loadTemplates:'worldTemplates', loadModules:'cocModules'}[method]
    t.mock.method(api, endpoint, () => new Promise(resolve => {finish=resolve}))
    const pending = workspace[method]()
    workspace.logout()
    Object.assign(workspace.session, {id:2,token:'next-token',username:'new'})
    workspace[field].value = structuredClone(newValue)
    finish(oldValue)
    await pending
    assert.deepEqual(workspace[field].value, newValue)
    assert.equal(workspace.session.username, 'new')
  })
}

test('logout clears private template, lore and save data', async t => {
  const {workspace} = await worldMutationFixture(t)
  workspace.templates.value = [{id:13,name:'private template'}]
  workspace.logout()
  assert.deepEqual(workspace.templates.value, [])
  assert.deepEqual(workspace.details.value, [])
  assert.equal(workspace.worldSave.value, null)
})
