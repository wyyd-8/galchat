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
  assert.equal(findElement(sheet as unknown as RootNode, (element) => element.tag === 'button'), undefined)
})

test('keeps manual saves separate while showing turn rollback in status', async () => {
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
  assert.doesNotMatch(textContent(statusPanel), /跑团存档/)
  assert.match(textContent(statusPanel), /行动轮自动存档/)
  assert.match(textContent(statusPanel), /回滚最近一轮/)
  assert.doesNotMatch(textContent(statusPanel), /如果某一轮出现异常/)
  const autoSaveCard = findElement(statusPanel as unknown as RootNode, (element) => element.tag === 'section'
    && textContent(element).includes('行动轮自动存档'))
  assert.ok(autoSaveCard, 'status should contain the automatic turn save card')
  const autoSaveHeading = findElement(autoSaveCard as unknown as RootNode, (element) => hasClass(element, 'tool-card-heading'))
  assert.ok(autoSaveHeading, 'the automatic save card should contain a heading')
  const rollbackButton = findElement(autoSaveHeading as unknown as RootNode, (element) => element.tag === 'button')
  assert.ok(rollbackButton, 'the rollback action should sit in the card heading')
  assert.equal(hasClass(rollbackButton, 'danger'), true)
  assert.deepEqual(autoSaveHeading.children
    .filter((child): child is ElementNode => child.type === NodeTypes.ELEMENT)
    .map((child) => child.tag), ['span', 'button'])
  assert.ok(savePanel, 'the save controls should render in their own tab panel')
  assert.match(textContent(savePanel), /跑团存档/)
  assert.doesNotMatch(textContent(savePanel), /行动轮自动存档|回滚最近一轮/)
})

test('renders save and load actions in separate cards', async () => {
  const source = await readFile(new URL('./TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const savePanel = findElement(baseParse(template), (element) => element.tag === 'TabsContent'
    && hasAttribute(element, 'value', 'save'))
  assert.ok(savePanel, 'the tools dialog should contain the save panel')

  const cards = savePanel.children.filter((child): child is ElementNode => child.type === NodeTypes.ELEMENT
    && child.tag === 'section'
    && hasClass(child, 'tool-card'))
  const saveButton = cards[0] && findElement(cards[0] as unknown as RootNode, (element) => element.tag === 'button')
  const loadButton = cards[1] && findElement(cards[1] as unknown as RootNode, (element) => element.tag === 'button')
  const savedRemark = cards[1] && findElement(cards[1] as unknown as RootNode, (element) => hasClass(element, 'save-remark'))

  assert.equal(cards.length, 2)
  assert.ok(saveButton, 'the save card should contain its own action')
  assert.ok(loadButton, 'the load card should contain its own action')
  assert.match(textContent(saveButton), /创建存档|覆盖存档/)
  assert.doesNotMatch(textContent(saveButton), /读取存档|确认读档/)
  assert.match(textContent(loadButton), /读取存档/)
  assert.doesNotMatch(textContent(loadButton), /创建存档|覆盖存档/)
  assert.ok(savedRemark, 'the load card should display the remark stored in the save')
  assert.match(textContent(savedRemark), /存档备注/)
  assert.match(textContent(savedRemark), /save\.remark/)
})
