import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import postcss from 'postcss'

test('keeps investigator choice rows separated in setup stages two and three', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const declarations = new Map<string, string>()

  root.walkRules('.character-choice-list', (rule) => {
    rule.walkDecls((declaration) => {
      declarations.set(declaration.prop, declaration.value)
    })
  })

  assert.equal(declarations.get('display'), 'grid')
  assert.equal(declarations.get('gap'), '7px')
})

test('keeps generated card section groups vertically separated', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const declarations = new Map<string, string>()

  root.walkRules('.binding-sheet > .sheet-columns + .sheet-columns', (rule) => {
    rule.walkDecls((declaration) => {
      declarations.set(declaration.prop, declaration.value)
    })
  })

  assert.equal(declarations.get('margin-top'), '9px')
})
