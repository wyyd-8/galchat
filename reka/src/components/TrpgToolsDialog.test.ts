import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode, type RootNode } from '@vue/compiler-dom'

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

function hasAttribute(element: ElementNode, name: string, value: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE
    && prop.name === name
    && prop.value?.content === value)
}

function hasIfExpression(element: ElementNode, expression: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.DIRECTIVE
    && prop.name === 'if'
    && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.exp.content === expression)
}

function hasDirectiveExpression(element: ElementNode, name: string, expression: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.DIRECTIVE
    && prop.name === name
    && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.exp.content === expression)
}

function textContent(node: unknown): string {
  if (!node || typeof node !== 'object') return ''
  const candidate = node as { type?: number, content?: unknown, children?: unknown[] }
  if (candidate.type === NodeTypes.TEXT || candidate.type === NodeTypes.SIMPLE_EXPRESSION) {
    return typeof candidate.content === 'string' ? candidate.content : ''
  }
  if (candidate.type === NodeTypes.INTERPOLATION) return textContent(candidate.content)
  return (candidate.children || []).map(textContent).join('')
}

test('keeps an established character card read-only in the TRPG tools dialog', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')

  const sheet = findElement(baseParse(template), (element) => hasClass(element, 'binding-sheet'))

  assert.ok(sheet, 'the tools dialog should render the selected character card')
  assert.equal(findElement(sheet as unknown as RootNode, (element) => ['input', 'textarea', 'select'].includes(element.tag)
    || (element.tag === 'button' && !hasClass(element, 'sheet-skill-category-button'))), undefined)
})

test('organizes the read-only character sheet into practical data panels', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const sheet = findElement(baseParse(template), (element) => hasClass(element, 'binding-sheet'))
  assert.ok(sheet, 'the tools dialog should render the selected character card')

  const root = sheet as unknown as RootNode
  for (const panel of ['skills', 'combat', 'profile']) {
    assert.ok(findElement(root, (element) => element.tag === 'TabsContent'
      && hasAttribute(element, 'value', panel)), `the sheet should contain the ${panel} panel`)
  }
  for (const panel of ['background', 'connections', 'trauma', 'assets']) {
    assert.ok(findElement(root, (element) => element.tag === 'TabsContent'
      && hasAttribute(element, 'value', panel)), `the profile should contain the ${panel} panel`)
  }

  const sheetText = textContent(sheet)
  assert.match(sheetText, /成功率/)
  assert.match(sheetText, /射程/)
  assert.match(sheetText, /次数/)
  assert.match(sheetText, /弹药/)
  assert.match(sheetText, /故障值/)
  assert.doesNotMatch(sheetText, /成长|贯穿/)
})

test('shows skills in a dense list using only their final three-level check rates', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const skillsPanel = findElement(baseParse(template), (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'skills'))
  assert.ok(skillsPanel, 'the character sheet should contain the skills panel')

  const skillGrid = findElement(skillsPanel as unknown as RootNode, (element) => hasClass(element, 'sheet-skill-grid'))
  assert.ok(skillGrid, 'all skills should be arranged in a compact multi-column list')
  assert.match(textContent(skillGrid), /item\.displayName/)
  assert.match(textContent(skillGrid), /formatCheckRate\(item\.value\)/)
  const checkRate = findElement(skillGrid as unknown as RootNode, (element) => element.tag === 'code'
    && hasClass(element, 'check-rate'))
  assert.ok(checkRate, 'skill rows should show their three-level check rate')
  assert.equal(hasIfExpression(checkRate, "item.kind === 'skill'"), true,
    'category rows should not render a check rate')
  assert.doesNotMatch(textContent(skillsPanel), /基础值|baseValue/)
})

test('uses a category card to enter and leave a focused skill group', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const skillsPanel = findElement(baseParse(template), (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'skills'))
  assert.ok(skillsPanel, 'the character sheet should contain the skills panel')

  const categoryButton = findElement(skillsPanel as unknown as RootNode, (element) => element.tag === 'button'
    && hasClass(element, 'sheet-skill-category-button'))
  assert.ok(categoryButton, 'collapsed categories should be navigable cards')
  assert.equal(hasIfExpression(categoryButton, "item.kind === 'category'"), true)
  assert.equal(hasDirectiveExpression(categoryButton, 'on', 'toggleSkillGroup(item.displayName)'), true)
  assert.match(textContent(categoryButton), /selectedSkillGroup.*返回全部技能.*查看大类技能/)
})

test('shows contextual risk details only for abnormal or unrecognized weapons', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const combatPanel = findElement(baseParse(template), (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'combat'))
  assert.ok(combatPanel, 'the character sheet should contain the combat panel')

  const notice = findElement(combatPanel as unknown as RootNode, (element) => element.tag === 'WeaponRiskNotice')
  assert.ok(notice, 'weapons should use the shared contextual risk notice')
  assert.equal(hasIfExpression(notice, 'shouldShowWeaponRisk(weapon)'), true,
    'ordinary single-tag weapons should not show risk details')
  assert.equal(hasDirectiveExpression(notice, 'bind', 'weapon'), true)
})

test('groups each attribute name and code above its value', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')

  const attributeGrid = findElement(baseParse(template), (element) => hasClass(element, 'sheet-attribute-grid'))
  assert.ok(attributeGrid, 'the character sheet should contain the attribute grid')

  const attributeCell = attributeGrid.children.find((child): child is ElementNode => child.type === NodeTypes.ELEMENT)
  assert.ok(attributeCell, 'the attribute grid should render an attribute cell')

  const cellChildren = attributeCell.children.filter((child): child is ElementNode => child.type === NodeTypes.ELEMENT)
  assert.deepEqual(cellChildren.map((child) => child.tag), ['span', 'strong'])
  assert.equal(hasClass(cellChildren[0], 'sheet-attribute-label'), true)
  assert.deepEqual(cellChildren[0].children
    .filter((child): child is ElementNode => child.type === NodeTypes.ELEMENT)
    .map((child) => child.tag), ['small', 'b'])
})

test('moves manual and automatic restore points into one recovery timeline', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)

  const saveTab = findElement(root, (element) => element.tag === 'TabsTrigger'
    && hasAttribute(element, 'value', 'save'))
  const statusPanel = findElement(root, (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'status'))
  const savePanel = findElement(root, (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'save'))

  assert.ok(saveTab, 'the tools dialog should expose a dedicated save tab')
  assert.match(textContent(saveTab), /存档/)
  assert.ok(statusPanel, 'the tools dialog should keep a status tab')
  assert.doesNotMatch(textContent(statusPanel), /自动回退点|action\.title|暂无可用回退点/)
  assert.ok(savePanel, 'the save controls should render in their own tab panel')
  const timeline = findElement(savePanel as unknown as RootNode, (element) => hasClass(element, 'recovery-timeline'))
  assert.ok(timeline, 'manual and automatic points should share one recovery timeline')
  assert.match(textContent(timeline), /当前进度/)
  assert.match(textContent(timeline), /recovery\.title/)
  assert.match(textContent(timeline), /预览并读档|预览并回退/)
  assert.equal(findElement(savePanel as unknown as RootNode,
    (element) => hasClass(element, 'investigator-grid')), undefined,
  'investigator state should not be shown on the recovery timeline')
})

test('uses foreground dialogs for load, rollback, and manual-save deletion confirmation', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const confirmation = findElement(root, (element) => element.tag === 'BaseDialog'
    && hasDirectiveExpression(element, 'bind', 'confirmationTitle'))

  assert.ok(confirmation, 'restore actions should open a dedicated confirmation dialog')
  assert.equal(hasDirectiveExpression(confirmation, 'bind', "'foreground'"), true)
  assert.match(textContent(confirmation), /确认删除当前存档/)
  assert.match(textContent(confirmation), /删除存档并回退/)
})

test('uses the same chat preview for the first load and rollback confirmation', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const confirmation = findElement(root, (element) => element.tag === 'BaseDialog'
    && hasDirectiveExpression(element, 'bind', 'confirmationTitle'))
  assert.ok(confirmation, 'restore actions should open a dedicated confirmation dialog')

  const preview = findElement(confirmation as unknown as RootNode, (element) => hasClass(element, 'rollback-chat-preview'))
  assert.ok(preview, 'the first rollback confirmation should contain a compact chat preview')
  assert.ok(findElement(preview as unknown as RootNode, (element) => hasClass(element, 'retained')))
  assert.ok(findElement(preview as unknown as RootNode, (element) => hasClass(element, 'deleted')))
  assert.ok(findElement(preview as unknown as RootNode, (element) => hasClass(element, 'rollback-chat-boundary')))
  assert.match(textContent(preview), /将读取到这里/)
  assert.match(textContent(preview), /将回退到这里/)
  assert.match(textContent(preview), /\.\.\./)

  const standaloneLoadCopy = findElement(
    confirmation as unknown as RootNode,
    (element) => hasClass(element, 'restore-confirmation-copy')
      && hasIfExpression(element, "restoreConfirmation.action.value === 'load'"),
  )
  assert.equal(standaloneLoadCopy, undefined,
    'load should not bypass the shared chat preview with a text-only confirmation')

  assert.match(source, /prepareRestorePreview\('load', save\.value\.messageBoundaryId\)/,
    'requesting a load should prepare the preview from the manual save boundary')
})

test('places the rollback explanation beside the chat preview on wide dialogs', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const layout = findElement(baseParse(template), (element) => hasClass(element, 'rollback-confirmation-layout'))
  assert.ok(layout, 'the primary rollback confirmation should use a side-by-side layout')

  const children = layout.children.filter((child): child is ElementNode => child.type === NodeTypes.ELEMENT)
  assert.equal(hasClass(children[0]!, 'rollback-chat-preview'), true)
  assert.equal(hasClass(children[1]!, 'rollback-confirmation-sidebar'), true)
})

test('shows target investigator state below the restore explanation', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const sidebar = findElement(baseParse(template), (element) => hasClass(element, 'rollback-confirmation-sidebar'))
  assert.ok(sidebar, 'restore confirmation should reserve a sidebar')
  const investigatorList = findElement(sidebar as unknown as RootNode,
    (element) => hasClass(element, 'rollback-investigator-list'))
  assert.ok(investigatorList, 'the sidebar should show investigator state at the target point')
  assert.ok(findElement(investigatorList as unknown as RootNode,
    (element) => element.tag === 'article'
      && hasDirectiveExpression(element, 'for', 'item in pendingRestoreInvestigators')))
  assert.match(textContent(investigatorList), /HP/)
  assert.match(textContent(investigatorList), /SAN/)
  assert.match(textContent(investigatorList), /MP/)
  assert.match(textContent(investigatorList), /restoreInvestigatorCondition/)
})

test('exposes backend-shaped dice debugging in a dedicated tools tab', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)

  const debugTab = findElement(root, (element) => element.tag === 'TabsTrigger'
    && hasAttribute(element, 'value', 'dice-debug'))
  const debugPanel = findElement(root, (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'dice-debug'))
  const debugComponent = debugPanel && findElement(
    debugPanel as unknown as RootNode,
    (element) => element.tag === 'DiceDebugPanel',
  )

  assert.ok(debugTab, 'the tools dialog should expose a dice debug tab')
  assert.match(textContent(debugTab), /骰子调试/)
  assert.ok(debugPanel, 'the tools dialog should contain the dice debug panel')
  assert.ok(debugComponent, 'the debug panel should render the backend scenario launcher')
  assert.ok(debugComponent.props.some((prop) => prop.type === NodeTypes.DIRECTIVE
    && prop.name === 'on'
    && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.arg.content === 'play'
    && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    && prop.exp.content.includes("emit('debugDice', aggregate)")))
})
