import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'

const sourceRoot = new URL('../src/', import.meta.url)
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}${specifier.endsWith('.vue') ? '' : '.ts'}`, sourceRoot).href, shortCircuit: true }
    }
    if (context.parentURL?.startsWith(sourceRoot.href)
      && /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
      return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (url.endsWith('.vue')) {
      if (url.endsWith('/App.vue')) {
        const raw = readFileSync(new URL(url), 'utf8')
        const source = raw.replace('</script>', `defineExpose({ saveTemplate, saveCharacterTemplate, characterTemplateForm, openCreateCharacterTemplate, canDeleteWorld, confirmWorldDeletion, busy, isMobile, run, mobileDeleteWorldOpen, templateForm, templatePublished, openEditTemplate, openCreateTemplate, refreshModelApis, importWorld, dialogs, accountForm, passwordForm, openAccount, templateReturnToSettings, mobileLoreOpen, workspace, direct, view, moduleLibrary, home, logout, selectWorld, selectConversation, openDirectChat, navigateMobile });\n</script>`)
        return { format: 'module-typescript', shortCircuit: true, source: compileScript(parse(source).descriptor, { id: 'app-navigation' }).content }
      }
      if (url.endsWith('/ui/BaseDialog.vue')) {
        const source = readFileSync(new URL(url), 'utf8').replace('</script>', 'defineExpose({ open });</script>')
        return { format: 'module-typescript', shortCircuit: true, source: compileScript(parse(source).descriptor, { id: 'base-dialog' }).content }
      }
      if (url.endsWith('/AuthDialog.vue')) {
        const source = readFileSync(new URL(url), 'utf8').replace('</script>', 'defineExpose({ form, submit });</script>')
        return { format: 'module-typescript', shortCircuit: true, source: compileScript(parse(source).descriptor, { id: 'auth' }).content }
      }
      if (url.endsWith('/ModelApiManagerDialog.vue')) {
        const source = readFileSync(new URL(url), 'utf8').replace('</script>', 'defineExpose({ manager, editorOpen, editorVisible, saveModel, form, openCreate, confirmDelete, deleteTarget, testModel });</script>')
        return { format: 'module-typescript', shortCircuit: true, source: compileScript(parse(source).descriptor, { id: 'model-manager' }).content }
      }
      if (!url.endsWith('/CocModuleLibrary.vue')) return { format: 'module', shortCircuit: true, source: 'export default {}' }
      const raw = readFileSync(new URL(url), 'utf8')
      const source = raw.replace(/defineExpose\(\{ prepareToLeave \}\)/, '').replace('</script>', `defineExpose({ unlockModule, canFullEdit, uploadCoverImage, uploadMaterialImage, uploadingMaterial, loadModules, busy, addLockedClue, saveModule, creationPending, prepareToLeave, uploadingCover, openModule, editing, hasUnsavedChanges, selectedId, exportModule, newModule, message, switchTab, activeTab, mobileBack, mobileView });\n</script>`)
      return { format: 'module-typescript', shortCircuit: true, source: compileScript(parse(source).descriptor, { id: 'system-review' }).content }
    }
    if (!url.endsWith('/api/client.ts')) return nextLoad(url, context)
    const source = readFileSync(new URL(url), 'utf8')
    return {
      format: 'module-typescript',
      shortCircuit: true,
      source: source.replace('import.meta.env.VITE_API_BASE_URL', 'undefined'),
    }
  },
})


async function fixture(t) {
  const {api} = await import('../src/api/client.ts')
  const original = {window:globalThis.window, document:globalThis.document}
  globalThis.window = {addEventListener(){}, removeEventListener(){}, setTimeout(){return 0}, matchMedia:()=>({matches:false,addEventListener(){},removeEventListener(){}})}
  let downloads = 0
  globalThis.document = {body:{append(){}},createElement:()=>({href:'', download:'', click(){downloads++},remove(){}})}
  t.after(()=>Object.assign(globalThis, original))
  const records = new Map([1,2].map(id=>[id,{module:{id,ownerUserId:1,name:`module-${id}`,introduction:'intro',visible:true},context:{},locations:[],clues:[],materials:[],characters:[]}]))
  t.mock.method(api,'myCocModules',async()=>[])
  t.mock.method(api,'cocModules',async()=>[])
  t.mock.method(api,'characterCardCreationRules',async()=>null)
  t.mock.method(api,'manageCocModule',async id=>structuredClone(records.get(id)))
  const updates=[]
  t.mock.method(api,'updateCocModule',async(id,payload)=>{updates.push(id); records.get(id).module.name=payload.name; return records.get(id).module})
  let exportedName
  t.mock.method(api,'exportCocModuleZip',async id=>{exportedName=records.get(id).module.name;return new Blob([JSON.stringify(records.get(id))],{type:'application/zip'})})
  const {createRenderer}=await import('vue')
  const {default:component}=await import('../src/components/CocModuleLibrary.vue')
  component.render=()=>null
  const renderer=createRenderer({patchProp(){},insert(){},remove(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null})
  const app=renderer.createApp(component)
  const vm=app.mount({})
  t.after(()=>app.unmount())
  await new Promise(resolve=>setTimeout(resolve,0))
  await vm.openModule(1)
  return {vm,api,records,updates,get exportedName(){return exportedName},get downloads(){return downloads}}
}

test('switching modules saves the original module before replacing its editor',async t=>{
  const {vm,records,updates}=await fixture(t)
  vm.editing.name='修改后的模组'
  await vm.openModule(2)
  assert.equal(records.get(1).module.name,'修改后的模组')
  assert.deepEqual(updates,[1])
  assert.equal(vm.selectedId,2)
  await vm.openModule(1)
  assert.equal(vm.editing.name,'修改后的模组')
})

test('failed save keeps the module and its draft selected',async t=>{
  const {vm,api}=await fixture(t)
  vm.editing.name='修改后的模组'
  t.mock.method(api,'updateCocModule',async()=>{throw new Error('network failure')})
  await vm.openModule(2)
  assert.equal(vm.selectedId,1)
  assert.equal(vm.editing.name,'修改后的模组')
  assert.equal(vm.hasUnsavedChanges,true)
})

test('invalid module content blocks switching without discarding the draft',async t=>{
  const {vm}=await fixture(t)
  vm.editing.name=''
  await vm.openModule(2)
  assert.equal(vm.selectedId,1)
  assert.equal(vm.editing.name,'')
})

test('switching sections waits for the save before showing the export section',async t=>{
  const {vm,api}=await fixture(t)
  await vm.switchTab('context')
  vm.editing.name='修改后的模组'
  let finish
  t.mock.method(api,'updateCocModule',()=>new Promise(resolve=>{finish=resolve}))
  const pending=vm.switchTab('overview')
  await new Promise(resolve=>setTimeout(resolve,0))
  assert.equal(vm.activeTab,'context')
  finish({})
  await pending
  assert.equal(vm.activeTab,'overview')
  assert.equal(vm.hasUnsavedChanges,false)
})

test('export saves the current draft before downloading',async t=>{
  const f=await fixture(t)
  f.vm.editing.name='最新版本'
  await f.vm.exportModule()
  assert.equal(f.exportedName,'最新版本')
  assert.equal(f.downloads,1)
  assert.equal(f.vm.hasUnsavedChanges,false)
})

test('export does not download old content after a failed save',async t=>{
  const f=await fixture(t)
  f.vm.editing.name='最新版本'
  t.mock.method(f.api,'updateCocModule',async()=>{throw new Error('network failure')})
  await f.vm.exportModule()
  assert.equal(f.downloads,0)
  assert.equal(f.vm.editing.name,'最新版本')
})

test('starting a new module saves existing edits first',async t=>{
  const {vm,records}=await fixture(t)
  vm.editing.name='保留旧模组修改'
  await vm.newModule()
  assert.equal(records.get(1).module.name,'保留旧模组修改')
  assert.equal(vm.selectedId,0)
})

test('edits entered during a save are kept instead of navigating away',async t=>{
  const {vm,api}=await fixture(t)
  vm.editing.name='第一版'
  let finish
  t.mock.method(api,'updateCocModule',()=>new Promise(resolve=>{finish=resolve}))
  const pending=vm.openModule(2)
  await new Promise(resolve=>setTimeout(resolve,0))
  vm.editing.name='第二版'
  finish({})
  await pending
  assert.equal(vm.selectedId,1)
  assert.equal(vm.editing.name,'第二版')
  assert.equal(vm.hasUnsavedChanges,true)
})

test('switching a restricted module saves edited location content with the allowed endpoint',async t=>{
  const {vm,api,records}=await fixture(t)
  records.get(1).module.editLocked=true
  records.get(1).locations=[{id:10,name:'街道',summary:'雨夜',content:'旧正文'}]
  await vm.openModule(1)
  t.mock.method(api,'updateCocModule',async()=>{throw new Error('restricted full update is forbidden')})
  t.mock.method(api,'updateCocModuleLocationContent',async(moduleId,id,content)=>{
    assert.equal(moduleId,1); assert.equal(id,10)
    records.get(1).locations[0].content=content
    return structuredClone(records.get(1).locations[0])
  })
  vm.editing.locations[0].content='新正文'
  await vm.openModule(2)
  assert.equal(vm.selectedId,2)
  assert.equal(records.get(1).locations[0].content,'新正文')
})

test('returning to the mobile directory saves restricted edits before export becomes available',async t=>{
  const {vm,api,records}=await fixture(t)
  records.get(1).module.editLocked=true
  records.get(1).locations=[{id:10,name:'街道',summary:'雨夜',content:'旧正文'}]
  await vm.openModule(1)
  t.mock.method(api,'updateCocModuleLocationContent',async(moduleId,id,content)=>{
    records.get(1).locations[0].content=content
    return structuredClone(records.get(1).locations[0])
  })
  vm.mobileView='section'
  vm.editing.locations[0].content='新正文'
  await vm.mobileBack()
  assert.equal(vm.mobileView,'directory')
  assert.equal(records.get(1).locations[0].content,'新正文')
})

async function appFixture(t) {
  const initialGlobals = { window: globalThis.window, document: globalThis.document, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  const f = await fixture(t)
  const storage = () => ({ getItem: () => null, setItem() {}, removeItem() {}, clear() {} })
  globalThis.localStorage = storage(); globalThis.sessionStorage = storage()
  Object.assign(globalThis.window, { clearTimeout() {} })
  Object.assign(globalThis.document, { addEventListener() {}, removeEventListener() {}, documentElement: { style: { setProperty() {}, removeProperty() {} } } })
  const { createRenderer } = await import('vue')
  const { default: component } = await import('../src/App.vue')
  component.render = () => null
  const renderer = createRenderer({ patchProp() {}, insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp(component)
  const parent = app.mount({})
  const win = globalThis.window, doc = globalThis.document
  t.after(() => { globalThis.window = win; globalThis.document = doc; app.unmount(); Object.assign(globalThis, initialGlobals) })
  parent.view = 'modules'
  parent.moduleLibrary = f.vm
  return { ...f, parent }
}

test('leaving for home saves the module before clearing workspace selection', async t => {
  const { vm, api, parent } = await appFixture(t)
  vm.editing.name = 'leave draft'
  parent.workspace.selectedWorldId.value = 9
  let finish
  t.mock.method(api, 'updateCocModule', () => new Promise(resolve => { finish = resolve }))
  const pending = parent.home()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(parent.view, 'modules')
  assert.equal(parent.workspace.selectedWorldId.value, 9)
  finish({})
  await pending
  assert.equal(parent.view, 'library')
  assert.equal(vm.hasUnsavedChanges, false)
})

test('failed autosave blocks every module-library navigation exit', async t => {
  const { vm, api, parent } = await appFixture(t)
  vm.editing.name = 'keep draft'
  t.mock.method(api, 'updateCocModule', async () => { throw new Error('offline') })
  for (const navigate of [() => parent.home(), () => parent.selectWorld(9), () => parent.selectConversation(7), () => parent.openDirectChat(2), () => parent.navigateMobile('profile'), () => parent.logout()]) {
    await navigate()
    assert.equal(parent.view, 'modules')
    assert.equal(vm.editing.name, 'keep draft')
  }
})

test('mobile profile navigation persists module edits', async t => {
  const { vm, records, parent } = await appFixture(t)
  vm.editing.name = 'mobile draft'
  await parent.navigateMobile('profile')
  assert.equal(records.get(1).module.name, 'mobile draft')
  assert.equal(parent.view, 'profile')
})

test('new-module creation keeps editing locked through the follow-up reload', async t => {
  const { vm, api } = await fixture(t)
  await vm.newModule()
  vm.editing.name = 'new'; vm.editing.introduction = 'intro'
  let finishCreate, finishReload
  t.mock.method(api, 'createCocModule', () => new Promise(resolve => { finishCreate = resolve }))
  const pending = vm.saveModule()
  assert.equal(vm.creationPending, true)
  t.mock.method(api, 'myCocModules', () => new Promise(resolve => { finishReload = resolve }))
  finishCreate({ id: 2 })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(vm.creationPending, true)
  finishReload([])
  await pending
  assert.equal(vm.creationPending, false)
  assert.equal(vm.selectedId, 2)
})

test('failed creation unlocks the editor and retains the draft', async t => {
  const { vm, api } = await fixture(t)
  await vm.newModule()
  vm.editing.name = 'retry me'; vm.editing.introduction = 'intro'
  let reject
  t.mock.method(api, 'createCocModule', () => new Promise((_, fail) => { reject = fail }))
  const pending = vm.saveModule()
  assert.equal(vm.creationPending, true)
  reject(new Error('offline'))
  assert.equal(await pending, false)
  assert.equal(vm.creationPending, false)
  assert.equal(vm.editing.name, 'retry me')
})

test('pending image upload blocks both creation and departure', async t => {
  const { vm, api } = await fixture(t)
  await vm.newModule()
  vm.editing.name = 'with cover'; vm.editing.introduction = 'intro'
  vm.uploadingCover = true
  let creations = 0
  t.mock.method(api, 'createCocModule', async () => { creations++; return { id: 2 } })
  assert.equal(await vm.saveModule(), false)
  assert.equal(await vm.prepareToLeave(), false)
  assert.equal(creations, 0)
})

async function renderEditorBoundary(creationPending, busy = false) {
  const { baseParse, compile, NodeTypes } = await import('@vue/compiler-dom')
  const Vue = await import('vue')
  const { renderToString } = await import('@vue/server-renderer')
  const source = readFileSync(new URL('../src/components/CocModuleLibrary.vue', import.meta.url), 'utf8')
  const root = baseParse(parse(source).descriptor.template!.content)
  function find(node) {
    if (node.type === NodeTypes.ELEMENT && node.props.some(prop => prop.name === 'class' && prop.value?.content === 'module-workspace')) return node
    return node.children?.map(find).find(Boolean)
  }
  const boundary = find(root)
  // Render the real editor container and its bindings; the browser applies a
  // disabled fieldset to all descendant form controls, including upload inputs.
  const tag = boundary.loc.source.slice(0, boundary.loc.source.indexOf('>') + 1)
  const template = `${tag}<input /><textarea /><button>编辑</button></${boundary.tag}>`
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  return renderToString(Vue.createSSRApp({ setup: () => ({ creationPending, busy, detailLoading: false, uploadingCover: false, uploadingMaterial: null }), render }))
}

test('the rendered editor disables inputs and actions only while creation is pending', async t => {
  const { vm, api } = await fixture(t)
  await vm.newModule()
  vm.editing.name = 'new'; vm.editing.introduction = 'intro'
  let reject
  t.mock.method(api, 'createCocModule', () => new Promise((_, fail) => { reject = fail }))
  const pending = vm.saveModule()
  assert.match(await renderEditorBoundary(vm.creationPending), /<fieldset[^>]* disabled[^>]* inert/)
  reject(new Error('offline'))
  await pending
  assert.doesNotMatch(await renderEditorBoundary(vm.creationPending), / disabled| inert/)
})

test('invalid new module prevents leaving without discarding its draft', async t => {
  const { vm, parent } = await appFixture(t)
  await vm.newModule()
  vm.editing.name = 'unfinished'
  await parent.home()
  assert.equal(parent.view, 'modules')
  assert.equal(vm.editing.name, 'unfinished')
})

test('logout saves a dirty module before clearing authentication', async t => {
  const { vm, parent, records } = await appFixture(t)
  Object.assign(parent.workspace.session, { id: 1, token: 'session' })
  await (await import('vue')).nextTick()
  vm.editing.name = 'save before logout'
  await parent.logout()
  assert.equal(records.get(1).module.name, 'save before logout')
  assert.equal(parent.workspace.isLoggedIn.value, false)
})

for (const transition of ['logout', 'switch account']) {
  test(`account dialogs and sensitive drafts reset on ${transition}`, async t => {
    const { parent, api } = await appFixture(t)
    Object.assign(parent.workspace.session, { id: 1, token: 'A' })
    t.mock.method(api, 'userInfo', async () => ({ username: 'Alice', email: 'alice@example.test', birthday: '2000-01-01', diceSkin: 'classic' }))
    await parent.openAccount()
    parent.dialogs.password = true
    parent.dialogs.modelApis = true
    parent.dialogs.template = true
    parent.templateReturnToSettings = true
    parent.mobileLoreOpen = true
    Object.assign(parent.passwordForm, { email: 'alice@example.test', newPassword: 'secret', confirmPassword: 'secret', code: '123456' })
    if (transition === 'logout') parent.workspace.logout()
    else Object.assign(parent.workspace.session, { id: 2, token: 'B' })
    await (await import('vue')).nextTick()
    assert.ok(Object.values(parent.dialogs).every(value => value === false))
    assert.equal(parent.mobileLoreOpen, false)
    assert.equal(parent.accountForm.username, '')
    assert.equal(parent.accountForm.email, '')
    assert.equal(parent.passwordForm.newPassword, '')
    assert.equal(parent.passwordForm.code, '')
    Object.assign(parent.workspace.session, { id: 2, token: 'B' })
    t.mock.method(api, 'userInfo', async () => ({ username: 'Bob', email: 'bob@example.test', birthday: '', diceSkin: 'classic' }))
    await parent.openAccount()
    assert.equal(parent.accountForm.username, 'Bob')
    assert.equal(parent.accountForm.email, 'bob@example.test')
  })
}

async function lockedClueFixture(t) {
  const f = await fixture(t)
  f.records.get(1).module.editLocked = true
  f.records.get(1).clues = [{ id: 10, title: 'old clue', content: 'old content', important: false }]
  await f.vm.openModule(1)
  f.vm.editing.clues.push({ title: 'new clue', content: 'new content', important: false })
  t.mock.method(f.api, 'addCocModuleClue', async (id, clue) => {
    const saved = { ...clue, id: 11 }
    f.records.get(id).clues.push(saved)
    return saved
  })
  return f
}

test('adding a locked clue preserves other dirty clues until the next save', async t => {
  const { vm, api, records } = await lockedClueFixture(t)
  vm.editing.clues[0].content = 'unsaved edit'
  await vm.addLockedClue()
  assert.equal(vm.editing.clues[0].content, 'unsaved edit')
  assert.equal(vm.editing.clues[1].id, 11)
  assert.equal(vm.hasUnsavedChanges, true)
  t.mock.method(api, 'updateCocModuleClueContent', async (moduleId, id, content) => {
    const clue = records.get(moduleId).clues.find(item => item.id === id)
    clue.content = content
    return { ...clue }
  })
  assert.equal(await vm.prepareToLeave(), true)
  assert.equal(records.get(1).clues[0].content, 'unsaved edit')
  assert.equal(records.get(1).clues.length, 2)
})

test('adding a locked clue preserves content typed while its request is pending', async t => {
  const { vm, api, records } = await lockedClueFixture(t)
  let finish
  t.mock.method(api, 'addCocModuleClue', (moduleId, clue) => {
    const snapshot = { ...clue, id: 11 }
    return new Promise(resolve => { finish = () => { records.get(moduleId).clues.push(snapshot); resolve(snapshot) } })
  })
  const pending = vm.addLockedClue()
  vm.editing.clues[1].content = 'typed while adding'
  finish()
  await pending
  assert.equal(vm.editing.clues[1].content, 'typed while adding')
  assert.equal(vm.editing.clues[1].id, 11)
  assert.equal(vm.hasUnsavedChanges, true)
})

test('adding a locked clue updates the saved baseline without making a clean editor dirty', async t => {
  const { vm } = await lockedClueFixture(t)
  await vm.addLockedClue()
  assert.equal(vm.hasUnsavedChanges, false)
  assert.equal(vm.editing.clues[1].id, 11)
})

test('failed locked-clue creation retains both old and new drafts for retry', async t => {
  const { vm, api } = await lockedClueFixture(t)
  vm.editing.clues[0].content = 'unsaved edit'
  t.mock.method(api, 'addCocModuleClue', async () => { throw new Error('offline') })
  await vm.addLockedClue()
  assert.equal(vm.editing.clues[0].content, 'unsaved edit')
  assert.equal(vm.editing.clues[1].id, undefined)
  assert.equal(vm.editing.clues[1].content, 'new content')
  assert.equal(vm.hasUnsavedChanges, true)
})


test('late initial lists preserve a newly entered module draft', async t => {
  const {vm,api,records}=await fixture(t)
  let finish
  t.mock.method(api,'myCocModules',()=>new Promise(resolve=>{finish=resolve}))
  const pending=vm.loadModules()
  await vm.newModule()
  vm.editing.name='draft'
  finish([records.get(1).module]);await pending
  assert.equal(vm.selectedId,0)
  assert.equal(vm.editing.name,'draft')
})

test('successful module creation followed by failed detail refresh never repeats POST', async t => {
  const {vm,api}=await fixture(t)
  await vm.newModule();vm.editing.name='new';vm.editing.introduction='intro'
  let writes=0
  t.mock.method(api,'createCocModule',async()=>{writes++;return {id:55,ownerUserId:1,name:'new',introduction:'intro',visible:true}})
  t.mock.method(api,'manageCocModule',async()=>{throw new Error('read failed')})
  assert.equal(await vm.saveModule(),true)
  assert.equal(vm.selectedId,55)
  assert.equal(await vm.saveModule(),true)
  assert.equal(writes,1)
})

test('switching module details disables the editor for the whole request', async t => {
  const {vm,api,records}=await fixture(t)
  let finish
  t.mock.method(api,'manageCocModule',id=>new Promise(resolve=>{finish=()=>resolve(structuredClone(records.get(id)))}))
  const pending=vm.openModule(2)
  await new Promise(resolve=>setTimeout(resolve,0))
  assert.match(await renderEditorBoundary(false,vm.busy), /<fieldset[^>]* disabled[^>]* inert/)
  finish();await pending
  assert.equal(vm.selectedId,2)
})

test('world import reports committed success separately from refresh failure', async t => {
  const {parent,api}=await appFixture(t)
  let writes=0
  t.mock.method(api,'importWorldFile',async()=>{writes++;return {worldId:55,name:'imported',characterCount:0}})
  t.mock.method(api,'worldTemplates',async()=>{throw new Error('read failed')})
  await parent.importWorld(new File(['{}'],'world.json',{type:'application/json'}))
  const {notice}=await import('../src/composables/useNotice.ts')
  assert.equal(writes,1)
  assert.equal(notice.title,'模板导入完成')
  assert.match(notice.message,/刷新/)
})


test('model-manager mutations refresh current group and direct model choices', async t => {
  const {parent,api}=await appFixture(t)
  const {createRenderer}=await import('vue')
  const {default:component}=await import('../src/components/ModelApiManagerDialog.vue')
  component.render=()=>null
  const records=[]
  t.mock.method(api,'modelApis',async()=>structuredClone(records))
  t.mock.method(api,'createModelApi',async payload=>{const saved={...payload,id:99,status:'UNTESTED'};records.push(saved);return saved})
  t.mock.method(api,'updateModelApi',async(id,payload)=>{Object.assign(records[0],payload);return {...records[0]}})
  t.mock.method(api,'testModelApi',async()=>{records[0].status='SUCCESS';return {...records[0]}})
  t.mock.method(api,'deleteModelApi',async()=>{records.length=0})
  const refreshes=[]
  const renderer=createRenderer({patchProp(){},insert(){},remove(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null})
  const app=renderer.createApp(component,{modelValue:true,onChanged:()=>refreshes.push(parent.refreshModelApis())})
  const manager=app.mount({});t.after(()=>app.unmount())
  manager.openCreate()
  Object.assign(manager.form,{name:'new model',baseUrl:'https://example.test',modelName:'test',apiKey:'key'})
  await manager.saveModel();await Promise.all(refreshes)
  assert.equal(parent.workspace.modelApis.value[0].id,99)
  assert.equal(parent.direct.modelApis.value[0].id,99)
  await manager.testModel(records[0]);await Promise.all(refreshes)
  assert.equal(parent.workspace.modelApis.value[0].status,'SUCCESS')
  manager.deleteTarget=records[0]
  await manager.confirmDelete();await Promise.all(refreshes)
  assert.deepEqual(parent.workspace.modelApis.value,[])
  assert.deepEqual(parent.direct.modelApis.value,[])
})


for (const kind of ['cover', 'material']) {
  test(`${kind} upload blocks module navigation until the uploaded image can be saved`, async t => {
    const {vm,api,records}=await appFixture(t)
    if (kind==='material') {
      records.get(1).materials=[{title:'image',description:'material description',imageUrl:''}]
      await vm.openModule(1)
    }
    const original=globalThis.fetch
    t.after(()=>{globalThis.fetch=original})
    let finish
    globalThis.fetch=()=>new Promise(resolve=>{finish=()=>resolve(new Response(JSON.stringify({code:1,data:'https://example.test/image.png'})))})
    const event={target:{files:[new File(['image'],'image.png',{type:'image/png'})],value:'image.png'}}
    const pending=kind==='cover'?vm.uploadCoverImage(event):vm.uploadMaterialImage(event,0)
    await vm.openModule(2)
    assert.equal(vm.selectedId,1)
    await vm.newModule()
    assert.equal(vm.selectedId,1)
    vm.mobileView='section'
    await vm.mobileBack()
    assert.equal(vm.mobileView,'section')
    assert.equal(await vm.switchTab('materials'),false)
    finish();await pending
    t.mock.method(api,'updateCocModule',async(id,payload)=>{
      records.get(id).module.coverUrl=payload.coverUrl
      records.get(id).materials=structuredClone(payload.materials)
      return records.get(id).module
    })
    await vm.openModule(2)
    assert.equal(vm.selectedId,2)
    await vm.openModule(1)
    assert.equal(kind==='cover'?vm.editing.coverUrl:vm.editing.materials[0].imageUrl,'https://example.test/image.png')
  })
}

async function renderModelFields(manager) {
  const {baseParse,compile,NodeTypes}=await import('@vue/compiler-dom')
  const Vue=await import('vue')
  const {renderToString}=await import('@vue/server-renderer')
  const source=readFileSync(new URL('../src/components/ModelApiManagerDialog.vue',import.meta.url),'utf8')
  const root=baseParse(parse(source).descriptor.template.content)
  function find(node) {
    if (node.type===NodeTypes.ELEMENT && node.props.some(prop=>prop.name==='class' && prop.value?.content.split(' ').includes('form-stack'))) return node
    return node.children?.map(find).find(Boolean)
  }
  const boundary=find(root)
  const tag=boundary.loc.source.slice(0,boundary.loc.source.indexOf('>')+1)
  const render=new Function('Vue',compile(`${tag}<input/><textarea/><button>解析</button></${boundary.tag}>`,{mode:'function',prefixIdentifiers:true}).code)(Vue)
  return renderToString(Vue.createSSRApp({setup:()=>({manager}),render}))
}

for (const fails of [false,true]) {
  test(`model saving locks the rendered fields and editor until ${fails?'failure':'success'}`, async t => {
    const {api}=await appFixture(t)
    const {createRenderer}=await import('vue')
    const {default:component}=await import('../src/components/ModelApiManagerDialog.vue')
    component.render=()=>null
    let finish
    t.mock.method(api,'createModelApi',payload=>new Promise((resolve,reject)=>{finish=()=>fails?reject(new Error('offline')):resolve({...payload,id:99})}))
    const renderer=createRenderer({patchProp(){},insert(){},remove(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null})
    const app=renderer.createApp(component,{modelValue:true})
    const vm=app.mount({});t.after(()=>app.unmount())
    vm.openCreate()
    Object.assign(vm.form,{name:'new',baseUrl:'https://example.test',modelName:'test',apiKey:'key'})
    const pending=vm.saveModel()
    assert.match(await renderModelFields(vm.manager),/<fieldset[^>]* disabled[^>]* inert/)
    vm.editorOpen=false
    assert.equal(vm.editorVisible,true)
    vm.openCreate()
    assert.equal(vm.form.name,'new')
    finish();await pending
    assert.doesNotMatch(await renderModelFields(vm.manager),/ disabled| inert/)
    assert.equal(vm.editorVisible,fails)
    if (fails) assert.equal(vm.form.apiKey,'key')
  })
}

test('authentication clears sensitive fields when closing and cannot reuse them after logout', async t => {
  await appFixture(t)
  const {createRenderer,ref,h,nextTick}=await import('vue')
  const {default:component}=await import('../src/components/AuthDialog.vue')
  component.render=()=>null
  const visible=ref(true),child=ref(null),submissions=[]
  const renderer=createRenderer({patchProp(){},insert(){},remove(){},createElement:()=>({}),createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null})
  const app=renderer.createApp({setup(){return ()=>h(component,{ref:child,modelValue:visible.value,onSubmit:payload=>{submissions.push(payload);visible.value=false}})}})
  app.mount({});t.after(()=>app.unmount())
  child.value.form.email='12345678@bjtu.edu.cn';child.value.form.password='previous-login-secret'
  child.value.form.confirmPassword='secret';child.value.form.code='123456'
  await child.value.submit();await nextTick()
  assert.equal(visible.value,false)
  // App keeps AuthDialog mounted and only toggles authOpen on logout.
  visible.value=true;await nextTick()
  assert.equal(child.value.form.password,'')
  assert.equal(child.value.form.confirmPassword,'')
  assert.equal(child.value.form.code,'')
  await child.value.submit()
  assert.equal(submissions.length,1)
})


test('failed image upload releases navigation without losing the current cover', async t => {
  const {vm,records}=await appFixture(t)
  records.get(1).module.coverUrl='https://example.test/old.png'
  await vm.openModule(1)
  const original=globalThis.fetch
  t.after(()=>{globalThis.fetch=original})
  globalThis.fetch=async()=>{throw new Error('offline')}
  await vm.uploadCoverImage({target:{files:[new File(['image'],'image.png',{type:'image/png'})],value:'image.png'}})
  assert.equal(vm.uploadingCover,false)
  assert.equal(vm.editing.coverUrl,'https://example.test/old.png')
  await vm.openModule(2)
  assert.equal(vm.selectedId,2)
})


async function appTemplateElement(predicate) {
  const {baseParse, NodeTypes}=await import('@vue/compiler-dom')
  const source=readFileSync(new URL('../src/App.vue',import.meta.url),'utf8')
  const root=baseParse(parse(source).descriptor.template!.content)
  function find(node) {
    if (node.type===NodeTypes.ELEMENT && predicate(node)) return node
    return node.children?.map(find).find(Boolean)
  }
  return find(root)
}

function clickAppElement(node, parent) {
  const click=node.props.find(prop=>prop.name==='on' && prop.arg?.content==='click')
  const result=new Function('ctx',`with(ctx) { return ${click.exp.content}; }`)(parent)
  return typeof result==='function' ? result() : result
}

test('desktop world deletion requires confirmation and cancellation does not delete',async t=>{
  const {parent,api}=await appFixture(t)
  parent.view='world'; parent.workspace.session.id=1
  parent.workspace.worlds.value=[{id:3,worldId:13,name:'with solo TRPG',myWorld:true}]
  parent.workspace.selectedWorldId.value=3
  parent.dialogs.settings=true
  let deleted=0
  t.mock.method(api,'deleteWorld',async()=>{deleted++})
  t.mock.method(api,'userWorlds',async()=>[])
  t.mock.method(api,'cocModules',async()=>[])
  const button=await appTemplateElement(node=>node.tag==='button' && node.children.some(child=>child.content==='删除世界'))
  await clickAppElement(button,parent)
  assert.equal(deleted,0)
  assert.equal(parent.mobileDeleteWorldOpen,true)
  const boundary=await appTemplateElement(node=>node.tag==='BaseDialog' && node.props.some(prop=>prop.name==='model' && prop.exp?.content==='mobileDeleteWorldOpen'))
  const Vue=await import('vue')
  const {compile}=await import('@vue/compiler-dom')
  const {renderToString}=await import('@vue/server-renderer')
  const render=new Function('Vue',compile(boundary.loc.source,{mode:'function',prefixIdentifiers:true}).code)(Vue)
  const dialogApp=Vue.createSSRApp({setup:()=>parent,render})
  dialogApp.component('BaseDialog',{props:['modelValue'],setup:(props,{slots})=>()=>props.modelValue ? Vue.h('section',[slots.default?.(),slots.footer?.()]) : null})
  const html=await renderToString(dialogApp)
  assert.match(html,/with solo TRPG/)
  assert.match(html,/确认删除/)
  const cancel=await appTemplateElement(node=>node.tag==='button' && node.children.some(child=>child.content==='保留世界'))
  await clickAppElement(cancel,parent)
  assert.equal(parent.mobileDeleteWorldOpen,false)
  assert.equal(deleted,0)
  await clickAppElement(button,parent)
  const confirm=await appTemplateElement(node=>node.tag==='button' && node.children.some(child=>child.content==='确认删除') && node.props.some(prop=>/workspace.removeWorld|confirmWorldDeletion/.test(prop.exp?.content||'')))
  t.mock.method(api,'deleteWorld',async()=>{throw new Error('world busy')})
  await clickAppElement(confirm,parent)
  assert.equal(parent.mobileDeleteWorldOpen,true)
  assert.equal(parent.view,'world')
  t.mock.method(api,'deleteWorld',async()=>{deleted++})
  await clickAppElement(confirm,parent)
  assert.equal(deleted,1)
  assert.equal(parent.mobileDeleteWorldOpen,false)
  assert.equal(parent.view,'library')
})

for (const visible of [true, null, false]) test(`world-template visibility control respects published state ${visible}`,async t=>{
  const {parent,api}=await appFixture(t)
  parent.view='world';parent.workspace.session.id=1
  parent.workspace.worlds.value=[{id:3,worldId:13,myWorld:true,name:'world'}]
  parent.workspace.selectedWorldId.value=3
  parent.dialogs.settings=true
  t.mock.method(api,'myWorldTemplate',async()=>({id:13,name:'template',background:'background',visible}))
  await parent.openEditTemplate()
  const node=await appTemplateElement(node=>node.tag==='input' && node.props.some(prop=>prop.name==='model' && prop.exp?.content==='templateForm.visible'))
  const {compile}=await import('@vue/compiler-dom')
  const Vue=await import('vue')
  const {renderToString}=await import('@vue/server-renderer')
  const render=new Function('Vue',compile(node.loc.source,{mode:'function',prefixIdentifiers:true}).code)(Vue)
  const html=await renderToString(Vue.createSSRApp({setup:()=>parent,render}))
  if (visible!==false) {
    assert.match(html,/ disabled/)
    assert.equal(parent.templateForm.visible,true)
  } else assert.doesNotMatch(html,/ disabled/)
  parent.openCreateTemplate()
  const createHtml=await renderToString(Vue.createSSRApp({setup:()=>parent,render}))
  assert.doesNotMatch(createHtml,/ disabled/)
})


for (const scenario of [{characters:1,loading:false},{characters:0,loading:true}]) test(`world deletion is blocked with ${scenario.characters} characters and loading=${scenario.loading}`,async t=>{
  const {parent,api}=await appFixture(t)
  parent.view='world';parent.workspace.session.id=1
  parent.workspace.worlds.value=[{id:3,worldId:13,name:'都市',myWorld:true}]
  parent.workspace.selectedWorldId.value=3
  parent.workspace.characters.value=scenario.characters?[{characterId:101,userWorldId:3,characterName:'角色'}]:[]
  parent.workspace.loading.workspace=scenario.loading
  parent.mobileDeleteWorldOpen=true
  let deleted=0
  t.mock.method(api,'deleteWorld',async()=>{deleted++})
  t.mock.method(api,'userWorlds',async()=>[])
  t.mock.method(api,'cocModules',async()=>[])
  const boundary=await appTemplateElement(node=>node.tag==='BaseDialog' && node.props.some(prop=>prop.name==='model' && prop.exp?.content==='mobileDeleteWorldOpen'))
  const Vue=await import('vue')
  const {compile}=await import('@vue/compiler-dom')
  const {renderToString}=await import('@vue/server-renderer')
  const render=new Function('Vue',compile(boundary.loc.source,{mode:'function',prefixIdentifiers:true}).code)(Vue)
  const app=Vue.createSSRApp({setup:()=>parent,render})
  app.component('BaseDialog',{props:['modelValue'],setup:(props,{slots})=>()=>props.modelValue?Vue.h('section',[slots.default?.(),slots.footer?.()]):null})
  const html=await renderToString(app)
  assert.match(html,/<button[^>]* disabled[^>]*>确认删除<\/button>/)
  if(scenario.characters) assert.match(html,/1.*角色/)
  const confirm=await appTemplateElement(node=>node.tag==='button' && node.children.some(child=>child.content==='确认删除') && node.props.some(prop=>/workspace.removeWorld|confirmWorldDeletion/.test(prop.exp?.content||'')))
  await clickAppElement(confirm,parent)
  assert.equal(deleted,0)
  assert.equal(parent.mobileDeleteWorldOpen,true)
})

for (const refreshFails of [false, true]) {
  test(`unlocking preserves unsaved content without saving it, list refresh failure=${refreshFails}`, async t => {
    const { vm, api, records } = await fixture(t)
    records.get(1).module.editLocked = true
    records.get(1).locations = [{ id: 10, name: '街道', summary: '雨夜', content: '旧正文' }]
    records.get(1).clues = [{ id: 11, title: '脚印', content: '旧线索', important: false }]
    await vm.openModule(1)
    await vm.switchTab('locations')
    vm.editing.locations[0].content = '保留地点修改'
    vm.editing.clues[0].content = '保留线索修改'
    vm.editing.clues.push({ title: '新线索', content: '尚未保存', important: true })
    const draft = structuredClone(JSON.parse(JSON.stringify(vm.editing)))
    let saves = 0
    for (const endpoint of ['updateCocModule', 'updateCocModuleLocationContent', 'updateCocModuleClueContent', 'addCocModuleClue']) {
      t.mock.method(api, endpoint, async () => { saves++ })
    }
    t.mock.method(api, 'unlockCocModule', async () => { records.get(1).module.editLocked = false })
    if (refreshFails) t.mock.method(api, 'myCocModules', async () => { throw new Error('list unavailable') })
    await vm.unlockModule()
    assert.deepEqual(JSON.parse(JSON.stringify(vm.editing)), draft)
    assert.equal(vm.hasUnsavedChanges, true)
    assert.equal(vm.canFullEdit, true)
    assert.equal(vm.activeTab, 'locations')
    assert.equal(saves, 0)
    // The retained draft must still be saved normally by a later explicit save.
    t.mock.method(api, 'updateCocModule', async (id, payload) => {
      records.get(id).locations = structuredClone(payload.locations)
      records.get(id).clues = structuredClone(payload.clues)
    })
    assert.equal(await vm.saveModule(), true)
    assert.equal(records.get(1).locations[0].content, '保留地点修改')
    assert.equal(records.get(1).clues.length, 2)
    assert.equal(vm.hasUnsavedChanges, false)
  })
}

test('failed unlock keeps the locked editor and its unsaved draft', async t => {
  const { vm, api, records } = await fixture(t)
  records.get(1).module.editLocked = true
  records.get(1).locations = [{ id: 10, name: '街道', summary: '雨夜', content: '旧正文' }]
  await vm.openModule(1)
  vm.editing.locations[0].content = '保留修改'
  t.mock.method(api, 'unlockCocModule', async () => { throw new Error('unlock failed') })
  await vm.unlockModule()
  assert.equal(vm.editing.locations[0].content, '保留修改')
  assert.equal(vm.canFullEdit, false)
  assert.equal(vm.hasUnsavedChanges, true)
})

async function renderTemplateBoundary(dialog, busy, isMobile) {
  const { baseParse, compile, NodeTypes } = await import('@vue/compiler-dom')
  const Vue = await import('vue')
  const { renderToString } = await import('@vue/server-renderer')
  const root = baseParse(parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')).descriptor.template!.content)
  const boundary = root.children.find(node => node.type === NodeTypes.ELEMENT && node.tag === 'BaseDialog'
    && node.props.some(prop => prop.name === 'model' && prop.exp?.content === `dialogs.${dialog}`))
  const editor = boundary.children.find(node => node.type === NodeTypes.ELEMENT && ['div', 'fieldset'].includes(node.tag))
  const header = boundary.loc.source.slice(0, boundary.loc.source.indexOf('>') + 1)
  const editorHeader = editor.loc.source.slice(0, editor.loc.source.indexOf('>') + 1)
  const template = `${header}${editorHeader}<input /><textarea /><button>编辑</button></${editor.tag}></BaseDialog>`
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  return renderToString(Vue.createSSRApp({
    components: { BaseDialog: { props: ['closeDisabled'], setup: (props, { slots }) => () => Vue.h('section', { 'data-close-disabled': String(Boolean(props.closeDisabled)) }, slots.default?.()) } },
    setup: () => ({ busy, isMobile, dialogs: { [dialog]: true }, templateDialogTitle: '', templateDialogDescription: '', characterTemplateDialogTitle: '' }), render,
  }))
}

test('world and character template forms block editing and dismissal throughout save', async () => {
  for (const dialog of ['template', 'characterTemplate']) {
    for (const isMobile of [false, true]) {
      const locked = await renderTemplateBoundary(dialog, true, isMobile)
      assert.match(locked, /data-close-disabled="true"/)
      assert.match(locked, /<fieldset[^>]* disabled[^>]* inert/)
      const unlocked = await renderTemplateBoundary(dialog, false, isMobile)
      assert.match(unlocked, /data-close-disabled="false"/)
      assert.doesNotMatch(unlocked, / disabled| inert/)
    }
  }
})

test('a locked dialog rejects close requests until saving finishes', async t => {
  await appFixture(t)
  const { createRenderer, reactive, h, nextTick } = await import('vue')
  const { default: component } = await import('../src/components/ui/BaseDialog.vue')
  component.render = () => null
  const state = reactive({ open: true, locked: true })
  let dialog
  const renderer = createRenderer({ patchProp() {}, insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
  const app = renderer.createApp({ render: () => h(component, { ref: value => { dialog = value }, modelValue: state.open, closeDisabled: state.locked, title: '保存', 'onUpdate:modelValue': value => { state.open = value } }) })
  app.mount({}); t.after(() => app.unmount())
  dialog.open = false
  assert.equal(state.open, true)
  state.locked = false; await nextTick()
  dialog.open = false
  assert.equal(state.open, false)
  state.open = true; state.locked = true; await nextTick()
  state.open = false; await nextTick()
  assert.equal(dialog.open, false, 'parent session cleanup can still close a locked dialog')
})

for (const kind of ['world', 'character']) {
  test(`${kind} template save unlocks after failure and preserves draft for retry`, async t => {
    const {parent,api}=await appFixture(t)
    parent.view='library'
    parent.workspace.session.id=1
    parent.workspace.worlds.value=[{id:3,worldId:13,name:'world',myWorld:true}]
    parent.workspace.selectedWorldId.value=3
    const isWorld=kind==='world'
    if(isWorld) parent.openCreateTemplate(); else parent.openCreateCharacterTemplate()
    const form=isWorld?parent.templateForm:parent.characterTemplateForm
    form.name='draft'; form.background='background'
    let reject
    t.mock.method(api,isWorld?'createWorldTemplate':'createCharacterTemplate',()=>new Promise((_,fail)=>{reject=fail}))
    const pending=parent.run(isWorld?parent.saveTemplate:parent.saveCharacterTemplate,isWorld?'template':'characterTemplate')
    assert.equal(parent.busy,true)
    reject(new Error('offline')); await pending
    assert.equal(parent.busy,false)
    assert.equal(parent.dialogs[isWorld?'template':'characterTemplate'],true)
    assert.equal(form.name,'draft')
    t.mock.method(api,isWorld?'createWorldTemplate':'createCharacterTemplate',async()=>({}))
    t.mock.method(api,isWorld?'worldTemplates':'characterTemplates',async()=>[])
    await parent.run(isWorld?parent.saveTemplate:parent.saveCharacterTemplate,isWorld?'template':'characterTemplate')
    assert.equal(parent.busy,false)
    assert.equal(parent.dialogs[isWorld?'template':'characterTemplate'],false)
  })
}
