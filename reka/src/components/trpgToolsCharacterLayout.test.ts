import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

function declarations(css: string, selector: string): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rule = css.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  assert.ok(rule, `expected a style rule for ${selector}`)
  return rule[1]
}

test('gives the tools character sheet enough desktop canvas to keep its panels tiled', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const dialog = declarations(css, '.trpg-binding-dialog.trpg-tools-character-dialog')
  const dialogWidthCap = dialog.match(/width:\s*min\([^;]*,\s*(\d+)px\)/)
  const dialogViewportHeight = dialog.match(/max-height:\s*min\((\d+)vh/)
  assert.ok(dialogWidthCap, 'the character-sheet dialog should declare a desktop width cap')
  assert.ok(Number(dialogWidthCap[1]) >= 1280, 'the character-sheet dialog should be at least 1280px wide')
  assert.ok(dialogViewportHeight, 'the character-sheet dialog should declare its own viewport height')
  assert.ok(Number(dialogViewportHeight[1]) >= 90, 'the character-sheet dialog should use at least 90vh')

  const layout = declarations(css, '.trpg-tools-card-layout')
  const layoutHeightCap = layout.match(/height:\s*min\((\d+)px/)
  const sidebarWidth = layout.match(/grid-template-columns:\s*(\d+)px\s+minmax/)
  assert.ok(layoutHeightCap, 'the character-sheet layout should declare a height cap')
  assert.ok(Number(layoutHeightCap[1]) >= 680, 'the character-sheet layout should be at least 680px tall')
  assert.ok(sidebarWidth, 'the character-sheet layout should declare a fixed investigator sidebar width')
  assert.ok(Number(sidebarWidth[1]) >= 250, 'the investigator sidebar should be wide enough to keep its heading copy together')
})

test('uses the same balanced sidebar width in setup binding and TRPG tools', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const creationLayout = declarations(css, '.trpg-character-creation-dialog .trpg-binding-layout')
  const toolsLayout = declarations(css, '.trpg-tools-card-layout')
  const creationSidebar = creationLayout.match(/grid-template-columns:\s*(\d+)px\s+minmax/)
  const toolsSidebar = toolsLayout.match(/grid-template-columns:\s*(\d+)px\s+minmax/)

  assert.ok(creationSidebar, 'the setup binding dialog should declare its investigator sidebar width')
  assert.ok(toolsSidebar, 'the TRPG tools should declare its investigator sidebar width')
  assert.equal(creationSidebar[1], toolsSidebar[1], 'both character-card surfaces should use the same sidebar width')
})

test('expands skills over the statistic rows while keeping the controls desktop-only', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const expanded = declarations(css, '.sheet-detail-tabs.skill-panel-expanded')
  const toggle = declarations(css, '.sheet-skill-expand-toggle')
  const expandedTop = expanded.match(/top:\s*(\d+)px/)

  assert.match(expanded, /position:\s*absolute/, 'the expanded skill panel should cover the sheet statistics')
  assert.match(expanded, /z-index:\s*[1-9]/, 'the expanded skill panel should render above the sheet statistics')
  assert.ok(expandedTop, 'the expanded skill panel should declare its top offset')
  assert.ok(Number(expandedTop[1]) >= 107,
    'the expanded skill panel and its arrow should stay below the investigator identity copy')
  assert.match(toggle, /opacity:\s*0/, 'the arrow should remain hidden until the green heading is hovered')
  assert.match(css, /\.sheet-skill-heading:hover \.sheet-skill-expand-toggle[^}]*opacity:\s*1/s,
    'hovering the green heading should reveal its integrated arrow')
  assert.match(css, /@media \(max-width: 760px\)[\s\S]*\.sheet-skill-expand-toggle, \.sheet-skill-toolbar\s*\{[^}]*display:\s*none/s,
    'small screens should hide expansion and filtering controls')
})
