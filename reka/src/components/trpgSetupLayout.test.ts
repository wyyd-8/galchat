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

test('lays out three creation methods and keeps a live dossier beside the active step', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const methodGrid = new Map<string, string>()
  const workbench = new Map<string, string>()

  root.walkRules('.creation-method-grid', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      methodGrid.set(declaration.prop, declaration.value)
    })
  })
  root.walkRules('.creation-workbench', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      workbench.set(declaration.prop, declaration.value)
    })
  })

  assert.equal(methodGrid.get('display'), 'grid')
  assert.equal(methodGrid.get('grid-template-columns'), 'repeat(3, minmax(0, 1fr))')
  assert.equal(workbench.get('display'), 'grid')
  assert.equal(workbench.get('grid-template-columns'), 'minmax(0, 1fr) 270px')
})

test('keeps a readable user-facing assistant beside the import editor', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const autoLayout = new Map<string, string>()
  const importLayout = new Map<string, string>()
  const editorText = new Map<string, string>()
  const assistantText = new Map<string, string>()

  root.walkRules('.auto-briefing-layout', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      autoLayout.set(declaration.prop, declaration.value)
    })
  })
  root.walkRules('.creation-import-layout', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      importLayout.set(declaration.prop, declaration.value)
    })
  })
  root.walkRules('.creation-import-editor textarea', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      editorText.set(declaration.prop, declaration.value)
    })
  })
  root.walkRules('.import-assistant-heading p', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      assistantText.set(declaration.prop, declaration.value)
    })
  })

  assert.equal(autoLayout.get('display'), 'grid')
  assert.equal(autoLayout.get('grid-template-columns'), 'minmax(0, 1fr) 250px')
  assert.equal(importLayout.get('display'), 'grid')
  assert.equal(importLayout.get('grid-template-columns'), 'minmax(0, 1.45fr) minmax(320px, .75fr)')
  assert.equal(editorText.get('font-size'), '13px')
  assert.equal(assistantText.get('font-size'), '11px')
})

test('uses the stepwise dossier split and three readable categories for automatic review', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const workbench = new Map<string, string>()
  const categories = new Map<string, string>()

  root.walkRules('.auto-review-workbench', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      workbench.set(declaration.prop, declaration.value)
    })
  })
  root.walkRules('.auto-review-category-tabs', (rule) => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls((declaration) => {
      categories.set(declaration.prop, declaration.value)
    })
  })

  assert.equal(workbench.get('display'), 'grid')
  assert.equal(workbench.get('grid-template-columns'), 'minmax(0, 1fr) 250px')
  assert.equal(categories.get('display'), 'grid')
  assert.equal(categories.get('grid-template-columns'), 'repeat(3, minmax(0, 1fr))')
})

test('keeps automatic review details above the previous tiny card-preview type scale', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const fontSizes = new Map<string, string>()

  for (const selector of ['.auto-review-category-tab', '.auto-review-skill-grid span', '.auto-review-story-grid p']) {
    root.walkRules(selector, (rule) => {
      if (rule.parent?.type !== 'root') return
      rule.walkDecls('font-size', (declaration) => {
        fontSizes.set(selector, declaration.value)
      })
    })
  }

  assert.equal(fontSizes.get('.auto-review-category-tab'), '11px')
  assert.equal(fontSizes.get('.auto-review-skill-grid span'), '11px')
  assert.equal(fontSizes.get('.auto-review-story-grid p'), '11px')
})

test('keeps creation guidance readable across method selection and stepwise creation', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const root = postcss.parse(css)
  const fontSizes = new Map<string, string>()

  for (const selector of [
    '.creation-method-copy p',
    '.auto-source-grid small',
    '.auto-briefing-note p',
    '.creation-dossier-tabs span',
    '.creation-step-intro',
    '.creation-live-sheet > section p',
  ]) {
    root.walkRules(selector, (rule) => {
      if (rule.parent?.type !== 'root') return
      rule.walkDecls('font-size', (declaration) => {
        fontSizes.set(selector, declaration.value)
      })
    })
  }

  assert.equal(fontSizes.get('.creation-method-copy p'), '11px')
  assert.equal(fontSizes.get('.auto-source-grid small'), '10px')
  assert.equal(fontSizes.get('.auto-briefing-note p'), '10px')
  assert.equal(fontSizes.get('.creation-dossier-tabs span'), '10px')
  assert.equal(fontSizes.get('.creation-step-intro'), '11px')
  assert.equal(fontSizes.get('.creation-live-sheet > section p'), '10px')
})
