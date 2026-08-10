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

function cssRule(source: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))?.[1] || ''
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

test('renders structured dice messages with the dedicated compact component', async () => {
  const source = await readFile(new URL('../GroupChatStage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'GroupChatStage should contain a template')

  const root = baseParse(template)
  const visit = (node: unknown): boolean => {
    if (!node || typeof node !== 'object') return false
    const candidate = node as { type?: number, tag?: string, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT && candidate.tag === 'DiceRollMessage') return true
    return candidate.children?.some(visit) || false
  }

  assert.equal(visit(root), true)
})

test('offers an explicit continue action below replay after the first player roll', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const action = findElementByClass(baseParse(template), 'dice-player-continue')

  assert.ok(action, 'the player should render a continue action')
  assert.equal(action.children.some((child) => (
    child.type === NodeTypes.TEXT && child.content.trim() === '继续'
  )), true)
})

test('stretches each dice message card across the chat message row', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')

  assert.match(card, /width:\s*100%/)
  assert.match(card, /box-sizing:\s*border-box/)
})

test('uses the semantic accent for the dice card border, icon, underline, and status', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')
  const title = cssRule(styles, '.dice-message-title')
  const icon = cssRule(styles, '.dice-message-title svg')
  const status = cssRule(styles, '.dice-message-status')

  assert.match(card, /border:\s*2px solid var\(--dice-message-accent\)/)
  assert.match(title, /text-decoration-line:\s*underline/)
  assert.match(title, /text-decoration-color:\s*var\(--dice-message-accent\)/)
  assert.match(icon, /color:\s*var\(--dice-message-accent\)/)
  assert.match(status, /color:\s*var\(--dice-message-accent\)/)
})
