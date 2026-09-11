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

function declaredVerticalPadding(css: string, selector: string): number {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = css.match(new RegExp(`(?:^|\\n)${escapedSelector}\\s*\\{([^}]*)\\}`))
  assert.ok(rule, `expected a style rule for ${selector}`)
  const padding = rule[1].match(/padding:\s*(\d+)px(?:\s+\d+px)?/)
  assert.ok(padding, `expected ${selector} to declare pixel padding`)
  return Number(padding[1])
}

test('keeps the TRPG character-card details at a readable minimum size', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const detailSelectors = [
    '.sheet-resource small',
    '.sheet-attribute-grid small',
    '.sheet-data-table th',
    '.sheet-data-table td small',
    '.sheet-profile-grid section > p',
    '.sheet-wealth dt',
  ]

  for (const selector of detailSelectors) {
    assert.ok(declaredFontSize(css, selector) >= 9, `${selector} should not render below 9px`)
  }
})

test('keeps the compact skill list readable without tiny labels or rates', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')

  assert.ok(declaredFontSize(css, '.sheet-skill-heading > strong') >= 11,
    'the skill heading should be at least 11px')
  assert.ok(declaredFontSize(css, '.sheet-skill-heading small') >= 10,
    'the check-level legend should be at least 10px')
  assert.ok(declaredFontSize(css, '.sheet-skill-item strong') >= 12,
    'skill names should be at least 12px')
  assert.ok(declaredFontSize(css, '.sheet-skill-item small') >= 10,
    'skill categories should be at least 10px')
  assert.ok(declaredFontSize(css, '.sheet-skill-item .check-rate') >= 11,
    'three-level skill rates should be at least 11px')
})

test('matches combat equipment table typography to the skill table', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')

  assert.equal(
    declaredFontSize(css, '.weapon-data-table'),
    declaredFontSize(css, '.sheet-skill-item .check-rate'),
    'weapon detail cells should match skill rates',
  )
  assert.equal(
    declaredFontSize(css, '.weapon-data-table th'),
    declaredFontSize(css, '.sheet-skill-heading small'),
    'weapon headings should match the skill legend',
  )
  assert.equal(
    declaredFontSize(css, '.weapon-data-table td strong'),
    declaredFontSize(css, '.sheet-skill-item strong'),
    'weapon names should match skill names',
  )
  assert.equal(
    declaredFontSize(css, '.weapon-data-table td small'),
    declaredFontSize(css, '.sheet-skill-item small'),
    'weapon notes should match skill categories',
  )
  assert.equal(
    declaredFontSize(css, '.weapon-data-table .check-rate'),
    declaredFontSize(css, '.sheet-skill-item .check-rate'),
    'weapon check rates should match skill check rates',
  )
})

test('gives the combat equipment table slightly taller rows', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')

  assert.ok(declaredVerticalPadding(css, '.weapon-data-table th') >= 9,
    'weapon table headings should have at least 9px vertical padding')
  assert.ok(declaredVerticalPadding(css, '.weapon-data-table td') >= 9,
    'weapon table cells should have at least 9px vertical padding')
})
