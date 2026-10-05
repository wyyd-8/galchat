import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { mobilePage, appUrl } from './mobile-fixtures.mjs'

for (const width of [320, 390, 430]) {
  test(`mobile pages and dialogs fit a ${width}px screen`, async t => {
    const { browser, page, errors } = await mobilePage({ width })
    t.after(() => browser.close())
    const screens = []
    async function check(name) {
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      const layout = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        dialogs: [...document.querySelectorAll('[role=dialog][data-state=open]')].map(el => {
          const r = el.getBoundingClientRect()
          return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, overflow: el.scrollWidth > el.clientWidth }
        }),
        smallFields: [...document.querySelectorAll('input:not([type=checkbox]):not([type=radio]):not([type=file]):not([type=range]), textarea, select')]
          .filter(el => el.getBoundingClientRect().width && !el.disabled && parseFloat(getComputedStyle(el).fontSize) < 16)
          .map(el => el.outerHTML.slice(0, 100)),
      }))
      assert.equal(layout.overflow, false, `${name}: document overflows horizontally`)
      for (const r of layout.dialogs) {
        assert.ok(r.left >= -1 && r.right <= width + 1 && r.top >= -1 && r.bottom <= 845, `${name}: dialog outside viewport`)
        assert.equal(r.overflow, false, `${name}: dialog content overflows`)
      }
      assert.deepEqual(layout.smallFields, [], `${name}: text input can cause iOS focus zoom`)
      assert.deepEqual(errors, [], `${name}: runtime errors`)
      if (process.env.MOBILE_AUDIT_SCREENSHOTS) {
        await fs.mkdir(process.env.MOBILE_AUDIT_SCREENSHOTS, { recursive: true })
        await page.screenshot({ path: path.join(process.env.MOBILE_AUDIT_SCREENSHOTS, `${name}-${width}.png`) })
      }
      screens.push(name)
    }
    const click = name => page.getByRole('button', { name, exact: typeof name === 'string' }).click()
    const back = () => click('返回')
    await page.goto(appUrl)
    await page.getByRole('button', { name: '雾港调查局 进入世界' }).waitFor()
    await check('library')
    await page.getByRole('tab', { name: /世界模板/ }).click()
    await check('templates')
    await click(/雾港调查局.*查看背景/)
    await check('template-preview')
    await click('林 林遥'); await check('template-character'); await back()
    await click('使用此模板'); await check('new-world'); await back()
    await page.getByRole('tab', { name: /已有世界/ }).click()
    await click('雾港调查局 进入世界')
    await page.getByRole('button', { name: '世界操作' }).waitFor()
    await check('world')
    await click('世界操作'); await check('world-menu')
    await click(/世界设置.*常规/); await check('world-settings')
    await click(/世界设定.*主题与内容/); await check('world-lore')
    await click(/城市传闻/); await check('world-lore-detail'); await back()
    await click('添加 ＋'); await check('world-lore-create'); await back(); await back()
    await click(/数据管理.*导入/); await check('world-data')
    await click(/删除当前世界/); await check('world-delete-confirm'); await back(); await back(); await back()
    await click(/世界存档/); await check('world-save')
    await click('创建存档'); await check('world-save-edit'); await back(); await back()
    await click('新建群聊/跑团'); await check('new-conversation'); await back()
    await click('添加角色'); await check('characters')
    await click('新建角色模板'); await check('character-template')
    await click(/好感阶段提示词/); await check('favor-stages')
    await click('添加阶段'); await check('favor-editor'); await back(); await back(); await back()
    await click(/林遥.*单聊/)
    await page.getByRole('textbox', { name: '给角色的消息' }).waitFor()
    await check('direct-chat')
    await click('角色详情'); await check('direct-settings'); await back()
    await click('聊天操作'); await check('direct-menu'); await click('关闭')
    await click('返回世界'); await click(/雨夜调查/)
    await page.getByText('钟声已经响过，街角的灯还亮着。', { exact: true }).waitFor()
    await check('group-chat')
    await page.getByRole('button', { name: '回复顺序', exact: true }).click()
    await check('group-order'); await back()
    await click('返回当前世界'); await click(/钟楼跑团/)
    await page.getByText('钟声已经响过，街角的灯还亮着。', { exact: true }).waitFor()
    await check('trpg-chat'); await click('跑团工具')
    await page.getByRole('heading', { name: '调查档案' }).waitFor(); await check('trpg-tools')
    await click(/场景与执行状态/); await check('trpg-scene'); await back(); await click('跑团工具')
    for (const label of [/人物卡.*查看调查员/, /跑团存档.*手动存档/, /掷骰记录.*筛选历史/]) {
      await click(label); await check(`trpg-tool-${screens.length}`); await back()
    }
    await click(/行动轮设置.*自动推进/); await check('trpg-turn-settings'); await back()
    await click('返回当前世界'); await click('我的'); await check('profile')
    await click(/账号资料/); await check('account'); await back()
    await click(/修改密码/); await check('password'); await back()
    await click(/模型管理/); await check('models')
    await click('添加模型'); await check('model-editor'); await back(); await back()
    await click('模组'); await page.getByRole('button', { name: '查看与编辑' }).waitFor(); await check('modules')
    await click('查看与编辑'); await check('module-directory')
    for (const label of [/基本资料/, /主持人设定/, /地点.*1/, /线索.*0/, /素材.*0/, /模组角色卡/]) {
      await click(label); await check(`module-section-${screens.length}`)
      await click('返回上一层')
    }
    console.log(`${width}px: verified ${screens.length} screens`)
  })
}
