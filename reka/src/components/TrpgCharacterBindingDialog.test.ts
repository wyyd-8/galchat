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

test('offers luck rolling while reviewing a bound card in the third setup stage', async () => {
  const source = await readFile(new URL('./TrpgCharacterBindingDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgCharacterBindingDialog should contain a template')

  const sheet = findElement(baseParse(template, { isVoidTag: (tag) => tag === 'br' }), (element) => element.tag === 'section'
    && element.props.some((prop) => prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'class'
      && prop.value?.content.split(/\s+/).includes('binding-sheet')))
  const luckIcon = sheet && findElement(sheet as unknown as RootNode, (element) => element.tag === 'Dices')

  assert.ok(luckIcon, 'the third setup stage should offer luck rolling for a bound card')
})
