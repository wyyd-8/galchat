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

function textContent(node: unknown): string {
  if (!node || typeof node !== 'object') return ''
  const candidate = node as { type?: number, content?: unknown, children?: unknown[] }
  if (candidate.type === NodeTypes.TEXT || candidate.type === NodeTypes.SIMPLE_EXPRESSION) {
    return typeof candidate.content === 'string' ? candidate.content : ''
  }
  if (candidate.type === NodeTypes.INTERPOLATION) return textContent(candidate.content)
  return (candidate.children || []).map(textContent).join('')
}

test('keeps the era on its own row so spending level and cash align in step six', async () => {
  const source = await readFile(new URL('./StepwiseCharacterCardWizard.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template)
  const root = baseParse(template, { isVoidTag: (tag) => tag === 'br' })
  const equipmentStep = findElement(root, (element) => hasClass(element, 'creation-equipment-step'))
  assert.ok(equipmentStep)
  const eraField = findElement(equipmentStep as unknown as RootNode, (element) => element.tag === 'label'
    && textContent(element).includes('时代'))

  assert.ok(eraField && hasClass(eraField, 'field-wide'), 'the era field should occupy the full first row')
})

test('saves step six and binds immediately without a preview confirmation stage', async () => {
  const source = await readFile(new URL('./StepwiseCharacterCardWizard.vue', import.meta.url), 'utf8')

  assert.match(source, /const completedDraft = await api\.saveCharacterCardDraftEquipment/)
  assert.match(source, /await completeDraft\(completedDraft\)/)
  assert.doesNotMatch(source, /确认并绑定人物卡/)
  assert.doesNotMatch(source, /class="creation-complete-step"/)
})
