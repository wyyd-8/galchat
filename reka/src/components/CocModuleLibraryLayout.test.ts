import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { baseParse, compile, NodeTypes, type ElementNode, type RootNode } from '@vue/compiler-dom'
import * as VueRuntime from 'vue'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

function findElement(root: RootNode, predicate: (element: ElementNode) => boolean): ElementNode | undefined {
  const visit = (node: unknown): ElementNode | undefined => {
    if (!node || typeof node !== 'object') return undefined
    const candidate = node as { type?: number, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT) {
      const element = node as ElementNode
      if (predicate(element)) return element
    }
    for (const child of candidate.children || []) {
      const match = visit(child)
      if (match) return match
    }
    return undefined
  }
  return visit(root)
}

function hasClass(element: ElementNode, className: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE
    && prop.name === 'class'
    && prop.value?.content.split(/\s+/).includes(className))
}

function hasIfExpression(element: ElementNode, expression: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.DIRECTIVE
    && prop.name === 'if'
    && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.exp.content === expression)
}

async function renderCharacters(options: {
  characters?: Array<Record<string, unknown>>
  dialogOpen?: boolean
  editorMode?: 'choose' | 'parse' | 'edit'
  editorTab?: 'basics' | 'skills' | 'weapons' | 'background'
  canFullEdit?: boolean
  hasParsedResult?: boolean
} = {}) {
  const source = await readFile(new URL('./CocModuleLibrary.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'CocModuleLibrary should contain a template')
  const root = baseParse(template)
  const section = findElement(root, (element) => hasClass(element, 'module-character-section'))
  assert.ok(section, 'CocModuleLibrary should contain the preset character section')
  const standaloneSection = section.loc.source
    .replace('<section v-else', '<section v-if="true"')
    .replaceAll(' as const', '')
    .replaceAll(' as HTMLInputElement', '')
    .replaceAll('importedCard!', 'importedCard')
  const render = new Function('Vue', compile(standaloneSection, {
    mode: 'function', expressionPlugins: ['typescript'],
  }).code)(VueRuntime)
  const icon = { render: () => h('span') }
  const BaseDialog = {
    props: ['modelValue', 'title', 'description', 'contentClass'],
    render() {
      if (!(this as { modelValue?: boolean }).modelValue) return null
      const slots = (this as unknown as { $slots: Record<string, () => unknown> }).$slots
      return h('div', { class: ['base-dialog-test', (this as { contentClass?: string }).contentClass] }, [
        h('h2', (this as { title?: string }).title),
        h('p', (this as { description?: string }).description),
        slots.default?.(), slots.footer ? h('footer', { class: 'base-dialog-footer-test' }, slots.footer() as never) : null,
      ] as never)
    },
  }
  const characters = options.characters || []
  const parsedCard = {
    character: {
      actorType: 'BOT', name: '阿利斯泰尔', str: 50, con: 60, siz: 65, dex: 65,
      app: 60, intValue: 65, pow: 70, edu: 70, hpCurrent: 12, hpMax: 12,
      sanCurrent: 70, sanMax: 70, mpCurrent: 14, mpMax: 14,
    },
    skills: [{ displayName: '潜行', value: 40 }], weapons: [], profile: {},
  }
  const importedCard = options.hasParsedResult === false ? null : parsedCard
  const availableCharacterSkills = [
    { skillDefId: 1, name: '潜行', category: '行动', baseValue: 20, allowSpecialization: false },
    { skillDefId: 2, name: '射击', category: '战斗', baseValue: 0, allowSpecialization: true },
  ]
  const app = createSSRApp({
    components: { BaseDialog, Plus: icon, Trash2: icon, ImageUp: icon, Search: icon },
    setup: () => ({
      activeTab: 'characters', canFullEdit: options.canFullEdit ?? true,
      moduleForm: { characters }, characterDialogOpen: options.dialogOpen ?? false,
      isSaving: false,
      characterEditorMode: options.editorMode || 'choose', characterDialogTitle: '新建模组角色卡',
      characterDialogDescription: '手动录入数值，或从现有文本中提取人物资料。',
      characterDialogContentClass: `module-character-editor-dialog character-dialog-${(options.editorMode || 'choose') === 'choose' ? 'choice' : 'workspace'}`,
      importedCard, importingText: '', missingAttributes: [], unresolvedWeaponLines: [],
      characterEditorTab: options.editorTab || 'basics', uploadingCharacterImage: false, skillNames: ['潜行'],
      characterSkillSearch: '', availableCharacterSkills,
      characterSkillBaseValue: (skill: { baseValue?: number }) => skill.baseValue || 0,
      characterSkillValue: (skill: { name: string }) => skill.name === '潜行' ? 40 : '',
      characterSkillSpecialization: () => '', setCharacterSkillValue: () => undefined,
      setCharacterSkillSpecialization: () => undefined,
      weaponBindings: ['斗殴', '射击:手枪'], firearmBinding: false, weaponError: '',
      weaponForm: {}, skillForm: {}, editingCharacterIndex: null,
      openNewCharacterDialog: () => undefined, openCharacterEditor: () => undefined,
      chooseCharacterEntry: () => undefined, closeCharacterDialog: () => undefined,
      parseCharacter: () => undefined, continueParsedCharacter: () => undefined,
      confirmImportedCharacter: () => undefined, uploadCharacterImage: () => undefined,
      removeCharacterImage: () => undefined, removeAt: () => undefined,
      addSkill: () => undefined, addWeapon: () => undefined, clearCharacterImport: () => undefined,
    }),
    render,
  })
  app.config.warnHandler = () => undefined
  return renderToString(app)
}

async function renderCreatingOverview() {
  const source = await readFile(new URL('./CocModuleLibrary.vue', import.meta.url), 'utf8')
  const overview = source.match(/(<section v-if="activeTab === 'overview'"[\s\S]*?<\/section>)/)?.[1]
  assert.ok(overview, 'CocModuleLibrary should contain the overview form')
  const render = new Function('Vue', compile(overview, { mode: 'function' }).code)(VueRuntime)
  const icon = { render: () => h('span') }
  const moduleForm = {
    name: '', author: '', era: '', introduction: '', investigatorCreation: '', coverUrl: '',
    playerCount: '', estimatedDuration: '', visible: true, context: {}, locations: [], clues: [],
    materials: [], characters: [],
  }

  const app = createSSRApp({
    components: {
      ImageUp: icon,
    },
    setup: () => ({
      activeTab: 'overview', canFullEdit: true, creating: true, isDefault: false, moduleForm,
      uploadingCover: false, uploadCoverImage: () => undefined,
    }),
    render,
  })
  app.config.warnHandler = () => undefined
  return renderToString(app)
}

async function renderEditableMaterials(imageUrl = '') {
  const source = await readFile(new URL('./CocModuleLibrary.vue', import.meta.url), 'utf8')
  const materials = source.match(/(<section v-else-if="activeTab === 'materials'"[\s\S]*?<\/section>)/)?.[1]
    ?.replace('v-else-if', 'v-if')
  assert.ok(materials, 'CocModuleLibrary should contain the materials section')
  const render = new Function('Vue', compile(materials, { mode: 'function' }).code)(VueRuntime)
  const icon = { render: () => h('span') }
  const moduleForm = {
    materials: [{ title: '旧报纸', description: '案发当日的报道', imageUrl }],
  }

  const app = createSSRApp({
    components: { ImageUp: icon, Plus: icon, Trash2: icon },
    setup: () => ({
      activeTab: 'materials', canFullEdit: true, moduleForm, uploadingMaterial: null,
      uploadMaterialImage: () => undefined, removeMaterialImage: () => undefined,
      removeAt: () => undefined, addMaterial: () => undefined,
    }),
    render,
  })
  app.config.warnHandler = () => undefined
  return renderToString(app)
}

async function renderModuleTabs() {
  const source = await readFile(new URL('./CocModuleLibrary.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'CocModuleLibrary should contain a template')
  const root = baseParse(template)
  const tabs = findElement(root, (element) => hasClass(element, 'module-tabs'))
  assert.ok(tabs, 'CocModuleLibrary should contain module tabs')
  const render = new Function('Vue', compile(tabs.loc.source.replaceAll(' as const', ''), {
    mode: 'function', expressionPlugins: ['typescript'],
  }).code)(VueRuntime)
  const icon = { render: () => h('span') }
  const app = createSSRApp({
    components: { Download: icon, UnlockKeyhole: icon, Save: icon },
    setup: () => ({
      moduleForm: { name: '雾中来客' }, creating: false, isDefault: false, isLocked: false,
      activeTab: 'overview', selectedId: 7, canFullEdit: true, busy: false, isSaving: false,
      saveStatus: { kind: 'saved', text: '已保存' }, switchTab: () => undefined,
      exportModule: () => undefined, saveModule: () => undefined,
    }),
    render,
  })
  app.config.warnHandler = () => undefined
  return renderToString(app)
}

test('keeps the title and creation actions inside the horizontal module switcher', async () => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: {
      alias: [{ find: '@', replacement: fileURLToPath(new URL('..', import.meta.url)) }],
    },
    server: { middlewareMode: true, hmr: false, ws: false },
  })

  try {
    const { default: CocModuleLibrary } = await vite.ssrLoadModule(
      '/src/components/CocModuleLibrary.vue',
    )
    const html = await renderToString(createSSRApp({
      render: () => h(CocModuleLibrary),
    }))

    assert.match(html, /<nav[^>]*class="module-switcher"[^>]*aria-label="选择模组"/)
    assert.match(html, /<nav[^>]*class="module-switcher"[\s\S]*<h1[^>]*>模组库<\/h1>[\s\S]*导入模组[\s\S]*新建模组[\s\S]*<\/nav>/)
    assert.doesNotMatch(html, /class="module-page-header"/)
    assert.doesNotMatch(html, /<aside[^>]*class="module-list-pane"/)
  } finally {
    await vite.close()
  }
})

test('shows only basic information before a new module has been created', async () => {
  const source = await readFile(new URL('./CocModuleLibrary.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'CocModuleLibrary should contain a template')
  const root = baseParse(template)
  const tabLinks = findElement(root, (element) => hasClass(element, 'module-tab-links'))
  const hero = findElement(root, (element) => hasClass(element, 'module-editor-header'))

  assert.ok(tabLinks, 'non-basic module sections should have their own tab group')
  assert.equal(hasIfExpression(tabLinks, '!creating'), true)
  assert.equal(hero, undefined)
})

test('offers only 1920s and modern as module eras', async () => {
  const html = await renderCreatingOverview()

  assert.match(html, /<select[^>]*><option value="1920s">1920s<\/option><option value="现代">现代<\/option><\/select>/)
  assert.doesNotMatch(html, /placeholder="例如：1920s"/)
})

test('offers cover upload instead of an editable cover address', async () => {
  const html = await renderCreatingOverview()

  assert.match(html, /上传封面/)
  assert.match(html, /type="file"[^>]*accept="image\/\*"/)
  assert.match(html, /<span>模组封面<\/span>[\s\S]*?尚未上传封面/)
})

test('places the save status immediately before the export action', async () => {
  const html = await renderModuleTabs()
  const statusIndex = html.indexOf('module-save-status')
  const exportIndex = html.indexOf('导出')

  assert.ok(statusIndex >= 0)
  assert.ok(exportIndex >= 0)
  assert.ok(statusIndex < exportIndex)
})

test('uses the material image itself as the upload area', async () => {
  const emptyHtml = await renderEditableMaterials()
  assert.match(emptyHtml, /<label[^>]*class="material-image-frame empty"[\s\S]*?上传图片[\s\S]*?<input[^>]*type="file"[^>]*accept="image\/\*"[\s\S]*?<\/label>/)
  assert.doesNotMatch(emptyHtml, /class="[^"]*material-upload/)

  const imageHtml = await renderEditableMaterials('/uploads/handout.png')
  assert.match(imageHtml, /class="material-image-frame has-image"[\s\S]*?<img src="\/uploads\/handout\.png" alt="旧报纸">/)
  assert.match(imageHtml, /class="material-image-actions"[\s\S]*?替换[\s\S]*?删除/)
  assert.doesNotMatch(imageHtml, /<span>图片地址<\/span>/)
})

test('adds a same-size preset character creation card with only the requested label', async () => {
  const html = await renderCharacters()

  assert.match(html, /class="preset-character-create-card"[\s\S]*?>[\s\S]*?新建模组角色卡[\s\S]*?<\/button>/)
  const card = html.match(/<button[^>]*class="preset-character-create-card"[\s\S]*?<\/button>/)?.[0]
  assert.ok(card)
  assert.doesNotMatch(card, /手动录入|文本解析|创建方式/)
  assert.doesNotMatch(html, /character-import-workbench/)
})

test('keeps completed preset character card information beside the creation card', async () => {
  const html = await renderCharacters({
    characters: [{
      character: { name: '阿利斯泰尔' },
      skills: [{ displayName: '潜行', value: 40 }],
      weapons: [{ name: '猎刀', skillName: '斗殴', damage: '1D4+DB' }],
      profile: {},
    }],
  })

  assert.match(html, /阿利斯泰尔/)
  assert.match(html, /1 项技能 · 1 件武器/)
  assert.match(html, /新建模组角色卡/)
})

test('offers manual entry and text parsing without AI generation in the new character dialog', async () => {
  const html = await renderCharacters({ dialogOpen: true, editorMode: 'choose' })

  assert.match(html, /手动录入/)
  assert.match(html, /文本自动解析/)
  assert.doesNotMatch(html, /AI\s*生成|自动生成/)
})

test('uses a compact height for the creation choice instead of the tall editor workspace', async () => {
  const choiceHtml = await renderCharacters({ dialogOpen: true, editorMode: 'choose' })
  const parserHtml = await renderCharacters({ dialogOpen: true, editorMode: 'parse' })

  assert.match(choiceHtml, /class="base-dialog-test module-character-editor-dialog character-dialog-choice"/)
  assert.match(parserHtml, /class="base-dialog-test module-character-editor-dialog character-dialog-workspace"/)
})

test('shows parsed text review before continuing to the unified character editor', async () => {
  const html = await renderCharacters({ dialogOpen: true, editorMode: 'parse' })

  assert.match(html, /粘贴人物数据/)
  assert.match(html, /解析结果/)
  assert.match(html, /填入人物卡并继续完善/)
})

test('keeps one parse action in the dialog footer and changes its label after parsing', async () => {
  const initialHtml = await renderCharacters({ dialogOpen: true, editorMode: 'parse', hasParsedResult: false })
  const parsedHtml = await renderCharacters({ dialogOpen: true, editorMode: 'parse' })

  assert.match(initialHtml, /class="base-dialog-footer-test"[\s\S]*?>开始解析</)
  assert.equal(initialHtml.match(/开始解析/g)?.length, 1)
  assert.doesNotMatch(initialHtml, /重新解析/)
  assert.match(parsedHtml, /class="base-dialog-footer-test"[\s\S]*?>重新解析</)
  assert.equal(parsedHtml.match(/重新解析/g)?.length, 1)
  assert.doesNotMatch(parsedHtml, /开始解析/)
})

test('uses four focused sections in the unified character editor', async () => {
  const html = await renderCharacters({ dialogOpen: true, editorMode: 'edit' })

  assert.match(html, /基础与属性/)
  assert.match(html, /技能/)
  assert.match(html, /武器/)
  assert.match(html, /背景资料/)
  assert.match(html, /保存角色/)
})

test('keeps only the preset character fields that must be authored', async () => {
  const html = await renderCharacters({ dialogOpen: true, editorMode: 'edit', editorTab: 'basics' })

  assert.match(html, />姓名 \*</)
  assert.match(html, />力量 <small>STR<\/small>/)
  assert.match(html, />HP</)
  assert.match(html, />SAN</)
  assert.match(html, />MP</)
  assert.doesNotMatch(html, /上限/)
  assert.doesNotMatch(html, /职业|性别|年龄|时代|出生地|居住地|角色图片|幸运|当前 \/ 最大/)
  assert.doesNotMatch(html, /type="file"/)
})

test('uses the character creation skill-list pattern for final values', async () => {
  const html = await renderCharacters({ dialogOpen: true, editorMode: 'edit', editorTab: 'skills' })

  assert.match(html, /placeholder="搜索技能或类别"/)
  assert.match(html, /潜行[\s\S]*?行动 · 基础 20%[\s\S]*?成功率/)
  assert.match(html, /射击[\s\S]*?填写专攻/)
  assert.doesNotMatch(html, />选择技能</)
  assert.doesNotMatch(html, /添加技能/)
})
