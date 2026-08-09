import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode, type RootNode } from '@vue/compiler-dom'

function findElementByClass(root: RootNode, className: string): ElementNode | undefined {
  const visit = (node: unknown): ElementNode | undefined => {
    if (!node || typeof node !== 'object') return undefined
    const candidate = node as { type?: number, children?: unknown[] }

    if (candidate.type === NodeTypes.ELEMENT) {
      const element = node as ElementNode
      const classAttribute = element.props.find((prop) => (
        prop.type === NodeTypes.ATTRIBUTE && prop.name === 'class'
      ))
      if (
        classAttribute?.type === NodeTypes.ATTRIBUTE
        && classAttribute.value?.content.split(/\s+/).includes(className)
      ) {
        return element
      }
    }

    if (Array.isArray(candidate.children)) {
      for (const child of candidate.children) {
        const match = visit(child)
        if (match) return match
      }
    }
    return undefined
  }

  return visit(root)
}

test('renders the opposed-check separator as an accessible crossed-swords icon', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const marker = findElementByClass(baseParse(template), 'dice-opposed-versus')

  assert.ok(marker, 'opposed checks should render a central separator')
  assert.equal(marker.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'aria-label'
      && prop.value?.content === '对抗'
  )), true)
  assert.equal(marker.children.some((child) => (
    child.type === NodeTypes.ELEMENT && child.tag === 'Swords'
  )), true)
  assert.equal(marker.children.some((child) => (
    child.type === NodeTypes.ELEMENT
      && child.children.some((grandchild) => (
        grandchild.type === NodeTypes.TEXT && grandchild.content.trim() === 'VS'
      ))
  )), false)
})
