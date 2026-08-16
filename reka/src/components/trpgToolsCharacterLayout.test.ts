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
