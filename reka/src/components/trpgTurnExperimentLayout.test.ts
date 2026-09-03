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
      if (classAttribute?.type === NodeTypes.ATTRIBUTE
        && classAttribute.value?.content.split(/\s+/).includes(className)) return element
    }
    for (const child of candidate.children || []) {
      const match = visit(child)
      if (match) return match
    }
    return undefined
  }
  return visit(root)
}

function directive(element: ElementNode, name: string): string | undefined {
  const value = element.props.find((prop) => (
    prop.type === NodeTypes.DIRECTIVE && prop.name === name
  ))
  return value?.type === NodeTypes.DIRECTIVE
    && value.exp?.type === NodeTypes.SIMPLE_EXPRESSION
    ? value.exp.content
    : undefined
}

function findElementByTag(root: RootNode, tag: string): ElementNode | undefined {
  const visit = (node: unknown): ElementNode | undefined => {
    if (!node || typeof node !== 'object') return undefined
    const candidate = node as { type?: number, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT && (node as ElementNode).tag === tag) {
      return node as ElementNode
    }
    for (const child of candidate.children || []) {
      const match = visit(child)
      if (match) return match
    }
    return undefined
  }
  return visit(root)
}

test('shows the experiment settings entry only between turns while auto advance is off', async () => {
  const source = await readFile(new URL('./GroupChatStage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template)

  const root = baseParse(template)
  const settings = findElementByClass(root, 'turn-experiment-settings')
  const settingsRoot = findElementByTag(root, 'PopoverRoot')

  assert.ok(settings)
  assert.ok(settingsRoot)
  assert.equal(directive(settingsRoot, 'if'), 'betweenTrpgTurns && !autoAdvance')
})

test('replaces the between-turn action with start countdown and cancel controls', async () => {
  const source = await readFile(new URL('./GroupChatStage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template)
  const root = baseParse(template)

  const split = findElementByClass(root, 'turn-auto-advance-actions')
  const cancel = findElementByClass(root, 'turn-auto-advance-cancel')

  assert.ok(split)
  assert.equal(directive(split, 'if'), 'betweenTrpgTurns && autoAdvance')
  assert.ok(cancel)
})
