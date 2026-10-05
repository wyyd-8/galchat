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


for (const scenario of [
  {name:'world',write:'createWorld',read:'userWorlds',call:w=>w.createWorld({worldId:13,name:'new'})},
  {name:'world template',write:'createWorldTemplate',read:'worldTemplates',call:w=>w.createTemplate({name:'new',background:'background'})},
  {name:'character template',write:'createCharacterTemplate',read:'characterTemplates',call:w=>w.createCharacterTemplate({name:'new'})},
  {name:'world detail',write:'addWorldDetail',read:'worldDetails',call:w=>w.addDetail({about:'new',details:'details'})},
  ...['chat','trpg'].map(mode=>({name:mode,write:'createConversation',read:'conversations',call:w=>w.createConversation({title:'new',mode,moduleId:1,characterIds:[]})})),
]) {
  test(`${scenario.name} reports a committed creation despite list-read failure`,async t=>{
    const {workspace,api}=await worldMutationFixture(t)
    const created=[]
    t.mock.method(api,scenario.write,async()=>{const record={id:100+created.length,title:'new'};created.push(record);return record})
    t.mock.method(api,scenario.read,async()=>{throw new Error('read failed after write')})
    await assert.doesNotReject(()=>scenario.call(workspace))
    assert.equal(created.length,1)
    const {notice} = await import('../src/composables/useNotice.ts')
    assert.match(notice.title, /已/)
    assert.match(notice.message, /刷新/)
  })
  test(`${scenario.name} still propagates a failed creation without refreshing`, async t => {
    const {workspace,api}=await worldMutationFixture(t)
    t.mock.method(api,scenario.write,async()=>{throw new Error('write failed')})
    let reads=0
    t.mock.method(api,scenario.read,async()=>{reads++;return []})
    await assert.rejects(()=>scenario.call(workspace),/write failed/)
    assert.equal(reads,0)
  })
}

for (const [read,load,state,oldValue,newValue] of [
  ['userWorlds','loadWorlds','worlds',[],[{id:77,name:'new'}]],
  ['worldTemplates','loadTemplates','templates',[],[{id:77,name:'new'}]],
  ['cocModules','loadModules','modules',[],[{id:77,name:'new'}]],
  ['modelApis','loadModelApis','modelApis',[],[{id:77,name:'new'}]],
  ['userInfo','loadUserInfo','userInfo',{username:'old'},{username:'new'}],
]) {
  test(`old ${read} response cannot overwrite a newer snapshot in the same session`,async t=>{
    const {workspace,api}=await worldMutationFixture(t)
    let finish
    t.mock.method(api,read,()=>new Promise(resolve=>{finish=resolve}))
    const oldLoad=workspace[load]()
    t.mock.method(api,read,async()=>newValue)
    await workspace[load]()
    assert.deepEqual(workspace[state].value,newValue)
    finish(oldValue); await oldLoad
    assert.deepEqual(workspace[state].value,newValue)
  })
}

test('model-manager late list read preserves newly created configuration',async()=>{
  const {createModelApiManagerState}=await import('../src/components/modelApiManagerState.ts')
  let finish
  const saved={id:77,name:'new',baseUrl:'https://example.test/v1',modelName:'test',apiKeyHint:'1234',requestOverrides:{},status:'UNTESTED',chatCapability:'UNKNOWN',streamingCapability:'UNKNOWN',toolCallingCapability:'UNKNOWN',reasoningOutputStatus:'UNKNOWN'}
  const manager=createModelApiManagerState({list:()=>new Promise(resolve=>{finish=resolve}),create:async()=>saved,update:async()=>saved,test:async()=>saved,delete:async()=>{}})
  const loading=manager.load()
  await manager.create({name:'new',baseUrl:saved.baseUrl,modelName:'test',apiKey:'secret',requestOverrides:{}})
  assert.equal(manager.models.value.length,1)
  finish([]);await loading
  assert.equal(manager.models.value.length,1)
})

for (const phase of ['pending', 'failed']) {
  test(`world switch ${phase}: old save and conversations are cleared and snapshots cannot mutate the new world`, async t => {
    const { workspace, api, worlds } = await worldMutationFixture(t)
    let finish
    t.mock.method(api, 'userWorld', () => new Promise((resolve, reject) => {
      finish = () => phase === 'failed' ? reject(new Error('world read failed')) : resolve(worlds[1])
    }))
    const switching = workspace.selectWorld(4)
    if (phase === 'failed') { finish(); await switching }
    assert.equal(workspace.selectedWorldId.value, 4)
    assert.equal(workspace.worldSave.value, null)
    assert.deepEqual(workspace.conversations.value, [])
    assert.deepEqual(workspace.details.value, [])
    let writes = 0
    t.mock.method(api, 'loadWorld', async () => { writes++ })
    t.mock.method(api, 'saveWorld', async () => { writes++; return {} })
    assert.equal(await workspace.loadSnapshot(), false)
    assert.equal(await workspace.saveSnapshot('must not overwrite unknown save'), false)
    assert.equal(writes, 0)
    if (phase === 'pending') { finish(); await switching }
    // A successful retry must restore normal snapshot operation for B.
    t.mock.method(api, 'userWorld', async () => worlds[1])
    assert.equal(await workspace.selectWorld(4), true)
    assert.equal(workspace.worldSave.value.userWorldId, 4)
    assert.equal(await workspace.loadSnapshot(), true)
    assert.equal(writes, 1)
  })
}

test('a late failure from an old world does not clear the successfully selected world', async t => {
  const { workspace, api, worlds } = await worldMutationFixture(t)
  let rejectOld
  t.mock.method(api, 'userWorld', id => id === 3
    ? new Promise((_, reject) => { rejectOld = reject }) : Promise.resolve(worlds[1]))
  const old = workspace.selectWorld(3)
  await workspace.selectWorld(4)
  rejectOld(new Error('late old read failure'))
  await old
  assert.equal(workspace.selectedWorldId.value, 4)
  assert.equal(workspace.worldSave.value.userWorldId, 4)
  assert.equal(workspace.conversations.value[0].userWorldId, 4)
})
