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

function findElements(root: RootNode, predicate: (element: ElementNode) => boolean): ElementNode[] {
  const matches: ElementNode[] = []
  const visit = (node: unknown) => {
    if (!node || typeof node !== 'object') return
    const candidate = node as { type?: number, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT) {
      const element = node as ElementNode
      if (predicate(element)) matches.push(element)
    }
    for (const child of candidate.children || []) visit(child)
  }
  visit(root)
  return matches
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

function hasAttribute(element: ElementNode, name: string, value?: string): boolean {
  return element.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE
    && prop.name === name
    && (value === undefined || prop.value?.content === value))
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

test('rolls imported-card luck automatically and removes the bound-card manual luck action', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')

  const sheet = findElement(baseParse(template, { isVoidTag: (tag) => tag === 'br' }), (element) => element.tag === 'section'
    && element.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'class'
      && prop.value?.content.split(/\s+/).includes('binding-sheet')))
  const manualLuckAction = sheet && findElement(sheet as unknown as RootNode, (element) => element.tag === 'button'
    && textContent(element).includes('投掷幸运'))

  assert.equal(manualLuckAction, undefined, 'a bound card should not retain a manual luck action')
  assert.match(source, /const importedCard = await api\.createCharacterCard/)
  assert.match(source, /await api\.rollCharacterLuck\(importedCard\.character\.id\)/)
  assert.match(source, /buildImportedCharacterLuckDicePlayback/)
})

test('uses the same full character-sheet panels as the TRPG tools after binding', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')

  const sheet = findElement(baseParse(template, { isVoidTag: (tag) => tag === 'br' }), (element) => hasClass(element, 'trpg-character-sheet'))
  assert.ok(sheet, 'a bound investigator should use the same full sheet canvas as TRPG tools')

  for (const panel of ['skills', 'combat', 'profile']) {
    assert.ok(findElement(sheet as unknown as RootNode, (element) => element.tag === 'TabsContent'
      && hasAttribute(element, 'value', panel)), `the bound sheet should contain the ${panel} panel`)
  }
})

test('uses contextual risk details only for abnormal or unrecognized weapons in card previews', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')
  const sheet = findElement(baseParse(template, { isVoidTag: (tag) => tag === 'br' }), (element) => hasClass(element, 'binding-sheet'))
  assert.ok(sheet, 'the binding dialog should render the selected character card')

  const warning = findElement(sheet as unknown as RootNode, (element) => element.tag === 'WeaponRiskNotice')

  assert.ok(warning, 'card previews should use the shared contextual risk notice')
  assert.equal(hasIfExpression(warning, 'shouldShowWeaponRisk(weapon)'), true)
})

test('separates standard, AI, and import creation paths before showing their editors', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')
  const root = baseParse(template, { isVoidTag: (tag) => tag === 'br' })
  const picker = findElement(root, (element) => hasClass(element, 'creation-method-grid'))
  const wizard = findElement(root, (element) => element.tag === 'StepwiseCharacterCardWizard')

  assert.ok(picker, 'unbound investigators should see a dedicated creation method picker')
  assert.ok(wizard, 'standard creation should render the stepwise wizard')
})

test('gives automatic creation a briefing stage before the generated dossier review', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')
  const root = baseParse(template, { isVoidTag: (tag) => tag === 'br' })
  const briefing = findElement(root, (element) => hasClass(element, 'auto-creation-briefing'))
  const review = findElement(root, (element) => hasClass(element, 'auto-review-heading'))

  assert.ok(briefing, 'automatic creation should explain its inputs and rolls before generation')
  assert.ok(review, 'automatic drafts should have a distinct review heading before binding')
})

test('reviews an automatic draft in the same workbench language as stepwise creation', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')
  const root = baseParse(template, { isVoidTag: (tag) => tag === 'br' })
  const workbench = findElement(root, (element) => hasClass(element, 'auto-review-workbench'))
  const liveSheet = findElement(root, (element) => hasClass(element, 'auto-review-live-sheet'))
  const categoryTabs = findElements(root, (element) => hasClass(element, 'auto-review-category-tab'))
  const bindAction = findElement(root, (element) => hasClass(element, 'auto-review-bind-action'))

  assert.ok(workbench, 'automatic review should use a dossier workbench instead of flat section blocks')
  assert.ok(liveSheet, 'automatic review should retain the live dossier side sheet used by stepwise creation')
  assert.deepEqual(categoryTabs.map(textContent), ['基础数据', '技能与装备', '人物背景'])
  assert.ok(bindAction, 'the selected automatic draft should expose its own binding action')
})

test('renders text import with an actionable guide and accessible live results', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')
  const root = baseParse(template, { isVoidTag: (tag) => tag === 'br' })
  const editor = findElement(root, (element) => hasClass(element, 'creation-import-editor'))
  const assistant = findElement(root, (element) => hasClass(element, 'creation-import-assistant'))
  const guideSource = await readFile(new URL('./CharacterCardImportGuide.vue', import.meta.url), 'utf8')
  const guideTemplate = guideSource.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(guideTemplate)
  const emptyGuide = findElement(baseParse(guideTemplate), (element) => hasClass(element, 'import-empty-guide'))
  const requiredChecks = findElement(root, (element) => hasClass(element, 'import-required-checks'))
  const optionalChecks = findElement(root, (element) => hasClass(element, 'import-optional-checks'))
  const liveStatus = findElement(root, (element) => hasClass(element, 'import-result-status')
    && hasAttribute(element, 'aria-live', 'polite'))

  assert.ok(editor, 'import should keep the source text in a dedicated editor pane')
  assert.ok(assistant, 'import should explain the format and help the user correct it')
  assert.ok(emptyGuide, 'an empty import should show a format guide instead of a zero-percent audit')
  assert.ok(requiredChecks, 'a populated import should separate actionable required fields')
  assert.ok(optionalChecks, 'optional sections should be visually separated from required fields')
  assert.ok(liveStatus, 'changing source text should announce the latest import result')
})
