import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode, type RootNode } from '@vue/compiler-dom'

function findElementByClass(root: RootNode | ElementNode, className: string): ElementNode | undefined {
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
  const mobileSettings = findElementByClass(root, 'turn-experiment-settings')
  const desktopPopover = findElementByTag(root, 'PopoverRoot')
  assert.ok(mobileSettings, 'mobile should have its own settings button')
  assert.ok(desktopPopover, 'desktop should retain its settings popover')
  assert.ok(findElementByClass(desktopPopover, 'turn-experiment-settings'), 'the desktop entry must remain inside its popover')
  const mobileCondition = directive(mobileSettings, 'if')
  const desktopCondition = directive(desktopPopover, 'if')
  assert.ok(mobileCondition)
  assert.ok(desktopCondition)
  const visible = (condition: string, isMobile: boolean, betweenTrpgTurns: boolean, autoAdvance: boolean) =>
    Boolean(new Function('isMobile', 'betweenTrpgTurns', 'autoAdvance', `return (${condition})`)(isMobile, betweenTrpgTurns, autoAdvance))
  for (const isMobile of [false, true]) {
    for (const betweenTrpgTurns of [false, true]) {
      for (const autoAdvance of [false, true]) {
        const state = { isMobile, betweenTrpgTurns, autoAdvance }
        const eligible = betweenTrpgTurns && !autoAdvance
        assert.equal(visible(mobileCondition, isMobile, betweenTrpgTurns, autoAdvance), isMobile && eligible, `mobile entry: ${JSON.stringify(state)}`)
        assert.equal(visible(desktopCondition, isMobile, betweenTrpgTurns, autoAdvance), !isMobile && eligible, `desktop popover: ${JSON.stringify(state)}`)
      }
    }
  }
  assert.equal(directive(mobileSettings, 'on'), 'turnSettingsOpen = true', 'mobile opens its independent full-page settings')
  assert.match(template, /v-model="turnSettingsOpen"[^>]*mobile-presentation="page"/, 'mobile settings must use a page instead of the desktop popover')
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
