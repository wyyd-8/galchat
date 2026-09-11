import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import postcss from 'postcss'

test('keeps the desktop hero headline in two intentional lines', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const copyDeclarations = new Map<string, string>()
  const lineDeclarations = new Map<string, string>()

  root.walkRules('.library-hero-copy', (rule) => {
    if (rule.parent?.type !== 'atrule') {
      rule.walkDecls((declaration) => {
        copyDeclarations.set(declaration.prop, declaration.value)
      })
    }
  })
  root.walkRules('.hero-title-line', (rule) => {
    if (rule.parent?.type !== 'atrule') {
      rule.walkDecls((declaration) => {
        lineDeclarations.set(declaration.prop, declaration.value)
      })
    }
  })

  assert.equal(copyDeclarations.get('min-width'), '0')
  assert.equal(lineDeclarations.get('display'), 'block')
  assert.equal(lineDeclarations.get('white-space'), 'nowrap')
})
