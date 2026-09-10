import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import postcss from 'postcss'

type Node = { classes: string[]; attributes?: Record<string, string> }
type Winner = { value: string; specificity: number; important: boolean }
const dialog: Node = { classes: ['dialog-content', 'dice-player-window', 'mobile-v4-window'], attributes: { 'data-mobile-presentation': 'sheet' } }

// This regression follows only class/attribute descendant selectors for four
// concrete dice elements, not a general CSS layout or browser implementation.
function matchCompound(selector: string, node: Node): boolean {
  const tokens = selector.match(/\.[\w-]+|\[[\w-]+(?:=["'][^"']*["'])?\]/g) || []
  if (tokens.join('') !== selector) return false
  return tokens.every(token => {
    if (token.startsWith('.')) return node.classes.includes(token.slice(1))
    const attribute = token.match(/^\[([\w-]+)(?:=["']([^"']*)["'])?\]$/)!
    return attribute[1]! in (node.attributes || {})
      && (attribute[2] === undefined || node.attributes?.[attribute[1]!] === attribute[2])
  })
}
function matches(selector: string, path: Node[]): boolean {
  const parts = selector.trim().split(/\s+/)
  if (!matchCompound(parts.pop()!, path.at(-1)!)) return false
  let ancestor = path.length - 2
  while (parts.length) {
    const part = parts.pop()!
    while (ancestor >= 0 && !matchCompound(part, path[ancestor]!)) ancestor--
    if (ancestor < 0) return false
    ancestor--
  }
  return true
}
function resolved(css: postcss.Root, width: number, path: Node[], property: string): Winner | undefined {
  let winner: Winner | undefined
  css.walkRules(rule => {
    for (let parent: postcss.AtRule | postcss.Rule | postcss.Root | postcss.Document | undefined = rule.parent; parent; parent = parent.parent) {
      if (parent.type !== 'atrule' || parent.name !== 'media') continue
      for (const match of parent.params.matchAll(/\((min|max)-width:\s*(\d+)px\)/g)) {
        if (match[1] === 'max' ? width > Number(match[2]) : width < Number(match[2])) return
      }
    }
    for (const selector of rule.selectors) {
      if (!matches(selector, path)) continue
      const specificity = (selector.match(/\.[\w-]+|\[[^\]]+\]/g) || []).length
      rule.walkDecls(property, declaration => {
        const important = Boolean(declaration.important)
        if (!winner || (important && !winner.important)
          || (important === winner.important && specificity >= winner.specificity)) {
          winner = { value: declaration.value, specificity, important }
        }
      })
    }
  })
  return winner
}
async function productionCss(legacyPrefix = false) {
  const [component, index, mobile] = await Promise.all([
    readFile(new URL('./MobileDicePlayer.vue', import.meta.url), 'utf8'),
    readFile(new URL('../../styles/index.css', import.meta.url), 'utf8'),
    readFile(new URL('../../styles/mobile.css', import.meta.url), 'utf8'),
  ])
  let style = component.match(/<style>([\s\S]*?)<\/style>/)?.[1]
  assert.ok(style)
  if (legacyPrefix) style = style.replaceAll('.dialog-content.mobile-v4-window[data-mobile-presentation]', '.mobile-v4-window')
  // main.ts imports App/components before index.css and mobile.css.
  return postcss.parse([style, index, mobile].join('\n'))
}
function surfacePath(skin: string): Node[] {
  return [dialog, { classes: ['dialog-body'] }, { classes: ['dice-player-stage-scroll'] }, { classes: ['dice-player-surface'], attributes: { 'data-skin': skin } }]
}

test('approved mobile dice colors, title, captions and size survive later shared styles', async () => {
  const css = await productionCss()
  const title = [dialog, { classes: ['dialog-header'] }, { classes: ['dialog-title'] }]
  for (const width of [320, 390, 767]) {
    for (const skin of ['classic', 'galaxy', 'moonwhite', 'cinnabar']) {
      const surface = surfacePath(skin)
      assert.equal(resolved(css, width, surface, 'background')?.value, '#fbfaf6', `${skin} surface at ${width}px`)
      const slot = [...surface, { classes: ['dice-player-tray'] }, { classes: ['dice-module'] }, { classes: ['mobile-group-rows'] }, { classes: ['dice-player-row'] }, { classes: ['die-slot'] }]
      assert.equal(resolved(css, width, slot, 'max-width')?.value, '188px', `${skin} die cap at ${width}px`)
      assert.equal(resolved(css, width, [...slot, { classes: ['mobile-die-caption'] }], 'font-size')?.value, '10px', `${skin} caption at ${width}px`)
    }
    assert.equal(resolved(css, width, title, 'font-size')?.value, '24px', `title at ${width}px`)
  }
})

test('the old weak prefix reproduces skin backgrounds and title overriding the dice sheet', async () => {
  const legacy = await productionCss(true)
  assert.match(resolved(legacy, 390, surfacePath('galaxy'), 'background')?.value || '', /radial-gradient/, 'later galaxy skin previously won the equal-specificity tie')
  assert.equal(resolved(legacy, 390, [dialog, { classes: ['dialog-header'] }, { classes: ['dialog-title'] }], 'font-size')?.value, '17px', 'shared mobile dialog title previously overrode the dice title')
})
