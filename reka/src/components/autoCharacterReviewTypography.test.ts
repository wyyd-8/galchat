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

test('keeps automatic-card review typography within the stepwise workbench scale', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')

  assert.ok(declaredFontSize(css, '.auto-review-main .auto-review-heading h3') <= 20)
  assert.ok(declaredFontSize(css, '.auto-review-identity h3') <= 22)
  assert.ok(declaredFontSize(css, '.auto-review-section > header strong') <= 15)
  assert.ok(declaredFontSize(css, '.auto-review-attributes strong') <= 15)
})
