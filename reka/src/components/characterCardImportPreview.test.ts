import assert from 'node:assert/strict'
import test from 'node:test'

test('summarizes a complete import before it is submitted', async () => {
  const previewModule = await import('./characterCardImportPreview.ts').catch(() => ({})) as {
    analyzeCharacterCardImport?: (text: string) => unknown
  }

  const preview = previewModule.analyzeCharacterCardImport?.(`
周宁，记者，女，27岁
出身上海，现居阿卡姆
时代: 1920s
STR 50 CON 55 SIZ 60 DEX 55
APP 50 INT 60 POW 55 EDU 60
—————————技能—————————
侦查 60%
图书馆使用 70%
  `) as {
    ready?: boolean
    completion?: number
    identity?: { name?: string, occupation?: string, sex?: string, age?: number }
    attributes?: Record<string, number>
    missingAttributes?: string[]
    skillCount?: number
  } | undefined

  assert.equal(preview?.ready, true)
  assert.equal(preview?.completion, 100)
  assert.deepEqual(preview?.identity, { name: '周宁', occupation: '记者', sex: '女', age: 27 })
  assert.deepEqual(preview?.attributes, {
    STR: 50, CON: 55, SIZ: 60, DEX: 55, APP: 50, INT: 60, POW: 55, EDU: 60,
  })
  assert.deepEqual(preview?.missingAttributes, [])
  assert.equal(preview?.skillCount, 2)
})

test('reports missing required fields without treating optional sections as errors', async () => {
  const previewModule = await import('./characterCardImportPreview.ts').catch(() => ({})) as {
    analyzeCharacterCardImport?: (text: string) => unknown
  }

  const preview = previewModule.analyzeCharacterCardImport?.(`
周宁，记者，女，27岁
STR 50 CON 55 SIZ 60 DEX 65
APP 60 INT 70
  `) as {
    ready?: boolean
    missingAttributes?: string[]
    checks?: Array<{ code: string, state: string }>
  } | undefined

  assert.equal(preview?.ready, false)
  assert.deepEqual(preview?.missingAttributes, ['POW', 'EDU'])
  assert.deepEqual(preview?.checks, [
    { code: 'IDENTITY', state: 'complete' },
    { code: 'ATTRIBUTES', state: 'missing' },
    { code: 'SKILLS', state: 'optional' },
    { code: 'BACKGROUND', state: 'optional' },
  ])
})
