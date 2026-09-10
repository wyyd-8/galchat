import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import postcss from 'postcss'

// Reproduce the production load order: component CSS may precede index.css.
// Only simple class selectors targeting the roster participate in this regression.
async function rosterMaxHeight(width: number, ancestors: string[], withMobile = true, targetClass = 'trpg-binding-targets') {
  const css = (await Promise.all([
    readFile(new URL('./mobile-tools.css', import.meta.url), 'utf8'),
    readFile(new URL('../styles/index.css', import.meta.url), 'utf8'),
  ])).filter((_, index) => withMobile || index !== 0).join('\n')
  let winner = { specificity: -1, value: 'none' }
  postcss.parse(css).walkRules(rule => {
    let applicable = true
    for (let parent: postcss.AtRule | postcss.Rule | postcss.Root | postcss.Document | undefined = rule.parent; parent; parent = parent.parent) {
      if (parent.type !== 'atrule' || parent.name !== 'media') continue
      for (const match of parent.params.matchAll(/\((min|max)-width:\s*(\d+)px\)/g)) {
        if (match[1] === 'max' ? width > Number(match[2]) : width < Number(match[2])) applicable = false
      }
    }
    if (!applicable) return
    for (const selector of rule.selectors) {
      if (!selector.trim().endsWith(`.${targetClass}`)) continue
      const classes = [...selector.matchAll(/\.([\w-]+)/g)].map(match => match[1])
      if (!classes.every(name => ancestors.includes(name))) continue
      rule.walkDecls('max-height', declaration => {
        if (classes.length >= winner.specificity) winner = { specificity: classes.length, value: declaration.value }
      })
    }
  })
  return winner.value
}

test('mobile investigator rosters escape the later legacy 210px cap without changing tablet layout', async () => {
  const tools = ['dialog-content', 'trpg-tools', 'trpg-binding-layout', 'trpg-tools-card-layout', 'trpg-binding-targets']
  const binding = ['dialog-content', 'trpg-character-creation-dialog', 'trpg-binding-layout', 'trpg-binding-targets']
  for (const width of [320, 390, 767]) {
    assert.equal(await rosterMaxHeight(width, tools), 'none', `tools roster at ${width}px`)
    assert.equal(await rosterMaxHeight(width, binding), 'none', `binding roster at ${width}px`)
  }
  assert.equal(await rosterMaxHeight(390, tools, false), '210px', 'the legacy narrow-window cap reproduces the reported clipping')
  assert.equal(await rosterMaxHeight(900, tools), 'none', 'tablet retains its existing uncapped desktop roster')
})


test('mobile wizard background and weapon choices use their page scroller instead of desktop inner caps', async () => {
  const wizard = ['dialog-content', 'trpg-character-creation-dialog', 'mobile-binding-workflow', 'creation-background-step']
  const weapons = ['dialog-content', 'mobile-equipment-picker', 'weapon-option-grid']
  for (const width of [320, 390, 767]) {
    assert.equal(await rosterMaxHeight(width, wizard, true, 'creation-background-step'), 'none')
    assert.equal(await rosterMaxHeight(width, weapons, true, 'weapon-option-grid'), 'none')
  }
  assert.equal(await rosterMaxHeight(900, wizard, true, 'creation-background-step'), '480px')
  assert.equal(await rosterMaxHeight(900, weapons, true, 'weapon-option-grid'), '260px')
})
