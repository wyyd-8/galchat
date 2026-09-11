import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import postcss from 'postcss'

import { resolveNormalDiePresentation } from './normalDiePresentation.ts'

test('renders D3 results on D6 faces while keeping the D3 label', () => {
  assert.deepEqual(
    [1, 2, 3].map((value) => resolveNormalDiePresentation(3, value)),
    [
      { modelKey: 'd6', faceLabel: '1', displayValue: '1', typeLabelText: 'D3' },
      { modelKey: 'd6', faceLabel: '2', displayValue: '2', typeLabelText: 'D3' },
      { modelKey: 'd6', faceLabel: '3', displayValue: '3', typeLabelText: 'D3' },
    ],
  )
})

test('renders the sorted group label in constant placeholder headings', async () => {
  const source = await readFile(new URL('./ThreeDice.ts', import.meta.url), 'utf8')
  const placeholderBranch = source.match(/if \(module\.placeholder\) \{([\s\S]*?)return \{ element, dice: \[\] \}/)?.[1]

  assert.ok(placeholderBranch, 'constant results should have a dedicated placeholder branch')
  assert.match(placeholderBranch, /moduleHeading\(module, moduleIndex, false\)/)
})

test('aligns constant placeholder group labels like standard dice headings', async () => {
  const css = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const contextClasses = new Set(['dice-player-surface', 'dice-module', 'is-value-placeholder', 'module-heading'])
  let winningSpecificity = -1
  let winningOrder = -1
  let justifyContent: string | undefined
  let order = 0

  postcss.parse(css).walkRules((rule) => {
    for (const selector of rule.selectors) {
      if (selector.includes(':')) continue
      const classes = [...selector.matchAll(/\.([\w-]+)/g)].map((match) => match[1]!)
      if (!classes.length || !classes.every((className) => contextClasses.has(className))) continue
      rule.walkDecls('justify-content', (declaration) => {
        const specificity = classes.length
        if (specificity > winningSpecificity
            || (specificity === winningSpecificity && order > winningOrder)) {
          winningSpecificity = specificity
          winningOrder = order
          justifyContent = declaration.value
        }
      })
    }
    order += 1
  })

  assert.equal(justifyContent, 'space-between')
})
