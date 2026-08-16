import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

function declaredFontSize(css: string, selector: string): number {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = css.match(new RegExp(`(?:^|\\n)${escapedSelector}\\s*\\{([^}]*)\\}`))
  assert.ok(rule, `expected a style rule for ${selector}`)
  const fontSize = rule[1].match(/font-size:\s*(\d+)px/)
  assert.ok(fontSize, `expected ${selector} to declare a pixel font size`)
  return Number(fontSize[1])
}

test('keeps exploration execution status text comfortably readable', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const minimumSizes = new Map([
    ['.reply-panel-title strong', 14],
    ['.reply-panel > p', 11],
    ['.trpg-scene-header strong', 12],
    ['.trpg-actor-row .trpg-actor-name', 11],
  ])

  for (const [selector, minimum] of minimumSizes) {
    assert.ok(declaredFontSize(css, selector) >= minimum, `${selector} should render at ${minimum}px or larger`)
  }
})
