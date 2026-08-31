import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const componentFiles = [
  './TrpgCharacterBindingDialog.vue',
  './StepwiseCharacterCardWizard.vue',
]

async function creationTemplates() {
  const sources = await Promise.all(componentFiles.map((file) => readFile(new URL(file, import.meta.url), 'utf8')))
  return sources.map((source) => source.match(/<template>([\s\S]*)<\/template>/)?.[1] || '').join('\n')
}

test('character creation screens use player-facing copy instead of demo and implementation labels', async () => {
  const templates = await creationTemplates()
  const internalLabels = [
    'AI CREATION ORDER',
    'INVESTIGATOR FILE',
    'LIVE DOSSIER',
    '草稿 #',
    '服务端',
    '本版本',
    '档案员注',
    '结构化武器',
    '尚未归档',
    '规则校验',
    '随机表 + AI',
    '<strong>{{ item.label }}</strong><small>{{ item.code }}</small>',
    '解析并入档',
    '审阅并入档',
    '盖章归档并绑定',
  ]

  for (const label of internalLabels) {
    assert.doesNotMatch(templates, new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `remove internal-facing copy: ${label}`)
  }

  for (const guidance of ['选择建卡方式', '第 {{ activeStepIndex + 1 }} 步，共 6 步', '请填写完整基本信息后继续', '1920年代', '绑定说明', '导入并绑定人物卡']) {
    assert.match(templates, new RegExp(guidance.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `keep actionable guidance: ${guidance}`)
  }
})
