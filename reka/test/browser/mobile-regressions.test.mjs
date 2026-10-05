import test from 'node:test'
import assert from 'node:assert/strict'
import { mobilePage, appUrl, enterWorld } from './mobile-fixtures.mjs'

async function setup(t, options) { const fixture = await mobilePage(options); t.after(()=>fixture.browser.close()); return fixture }
async function back(page) { await page.evaluate(()=>history.back()) }

test('system Back closes the top dialog, returns through chat and world, and preserves the draft', async t=>{
  const {page}=await setup(t); await enterWorld(page)
  await page.getByRole('button',{name:/林遥.*单聊/}).click()
  await page.getByRole('textbox',{name:'给角色的消息'}).fill('未发送的手机草稿')
  await page.getByRole('button',{name:'角色详情',exact:true}).click()
  await back(page); await page.getByRole('textbox',{name:'给角色的消息'}).waitFor()
  await back(page); await page.getByRole('button',{name:'世界操作'}).waitFor()
  await page.getByRole('button',{name:/林遥.*单聊/}).click()
  assert.equal(await page.getByRole('textbox',{name:'给角色的消息'}).inputValue(),'未发送的手机草稿')
  await back(page); await page.getByRole('button',{name:'世界操作'}).waitFor()
  await back(page); await page.getByRole('button',{name:'雾港调查局 进入世界'}).waitFor()
})

test('system Back traverses module entries and respects unsaved new module confirmation', async t=>{
  const {page}=await setup(t); await page.goto(appUrl)
  await page.getByRole('button',{name:'模组',exact:true}).click()
  await page.getByRole('button',{name:'查看与编辑'}).click()
  await page.getByRole('button',{name:/地点.*1/}).click()
  await page.getByRole('button',{name:/钟楼/}).click()
  await back(page); await page.getByRole('heading',{name:'地点',exact:true}).waitFor()
  await back(page); await page.getByRole('heading',{name:'模组详情',exact:true}).waitFor()
  await back(page); await page.getByRole('button',{name:'查看与编辑'}).waitFor()
  await page.getByRole('button',{name:'创建模组',exact:true}).first().click()
  await page.getByLabel('模组名称',{exact:true}).fill('还未保存的模组')
  await back(page); await page.getByRole('dialog').waitFor()
  assert.match(await page.getByRole('dialog').innerText(),/未保存|尚未保存/)
  await back(page); assert.equal(await page.getByLabel('模组名称',{exact:true}).inputValue(),'还未保存的模组')
})

test('closed mobile group conversations still expose conversation operations', async t=>{
  const {page}=await setup(t,{closed:true});await enterWorld(page)
  await page.getByRole('button',{name:/雨夜调查/}).click()
  await page.getByRole('button',{name:'会话操作',exact:true}).click()
  await page.getByRole('dialog',{name:'会话操作',exact:true}).getByRole('button',{name:'会话操作',exact:true}).click()
  await page.getByRole('button',{name:'永久删除',exact:true}).waitFor()
})

test('opening mobile login does not focus an input and summon the soft keyboard', async t=>{
  const {page}=await setup(t,{loggedIn:false});await page.goto(appUrl)
  await page.getByRole('dialog').waitFor()
  assert.equal(await page.evaluate(()=>['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName)),false)
})

test('mobile login stays disabled for the complete request, including slow responses', async t=>{
  const {page,requests}=await setup(t,{loggedIn:false});await page.goto(appUrl)
  await page.getByLabel('邮箱',{exact:true}).fill('test@example.test')
  await page.getByLabel('密码',{exact:true}).fill('password')
  await page.getByRole('button',{name:'进入 GalChat',exact:true}).click()
  await page.waitForTimeout(700)
  const submit=page.locator('#auth-form button[type=submit]')
  assert.equal(await submit.isDisabled(),true,'do not unlock after an arbitrary 500ms')
  assert.equal(requests.filter(r=>r.path==='/user/login').length,1)
  await page.getByRole('button',{name:'进入 GalChat',exact:true}).waitFor()
})

test('chat composer and navigation stay within the panned visual viewport', async t=>{
  const {page}=await setup(t);await enterWorld(page)
  await page.getByRole('button',{name:/林遥.*单聊/}).click()
  await page.getByRole('textbox',{name:'给角色的消息'}).waitFor()
  await page.evaluate(()=>{
    Object.defineProperty(visualViewport,'height',{configurable:true,value:400})
    Object.defineProperty(visualViewport,'offsetTop',{configurable:true,value:60})
    visualViewport.dispatchEvent(new Event('resize'))
  })
  const header=await page.locator('.chat-header').boundingBox()
  const composer=await page.locator('.composer').boundingBox()
  assert.equal(header.y,60)
  assert.ok(composer.y+composer.height<=460)
  await page.getByRole('button',{name:'返回世界',exact:true}).click()
  const navigation=await page.locator('.mobile-navigation').boundingBox()
  assert.ok(navigation.y+navigation.height<=460,'bottom navigation must follow the visible viewport too')
})

test('mobile favor-stage editor rejects blank, fractional and out-of-range thresholds', async t=>{
  const {page}=await setup(t);await enterWorld(page)
  await page.getByRole('button',{name:'添加角色',exact:true}).click()
  await page.getByRole('button',{name:'新建角色模板',exact:true}).click()
  await page.getByRole('button',{name:/好感阶段提示词/}).click()
  await page.getByRole('button',{name:'添加阶段',exact:true}).click()
  await page.getByLabel('阶段提示词',{exact:true}).fill('更加信任旅人')
  const threshold=page.getByLabel(/^触发阈值/)
  const save=page.getByRole('button',{name:'保存这一阶段',exact:true})
  await threshold.fill('10');await threshold.fill('')
  assert.equal(await save.isDisabled(),true,'clearing a numeric input produces an empty string')
  for(const invalid of ['1.5','-1','101']) { await threshold.fill(invalid);assert.equal(await save.isDisabled(),true) }
  await threshold.fill('0');assert.equal(await save.isDisabled(),false)
  await save.click();await page.getByRole('button',{name:/好感达到 0/}).waitFor()
})

test('pending dice shortcut opens dice history directly, then normal tools reopen at the directory', async t=>{
  const {page}=await setup(t,{waitingDice:true});await enterWorld(page)
  await page.getByRole('button',{name:/钟楼跑团/}).click()
  await page.getByRole('button',{name:'前往掷骰',exact:true}).click()
  await page.getByRole('dialog',{name:'掷骰记录',exact:true}).waitFor()
  await back(page); await page.getByRole('heading',{name:'调查档案',exact:true}).waitFor()
  await back(page); await page.getByRole('button',{name:'跑团工具',exact:true}).click()
  await page.getByRole('heading',{name:'调查档案',exact:true}).waitFor()
})
