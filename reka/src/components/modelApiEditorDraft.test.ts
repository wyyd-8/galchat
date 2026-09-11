import assert from 'node:assert/strict'
import test from 'node:test'
import { modelApiEditorFingerprint } from './modelApiEditorDraft.ts'

const form = { name: '我的连接', baseUrl: 'https://example.com/v1', modelName: 'chat', apiKey: '', requestOverrides: {} }
test('detects edits to every saved field, including replacing a retained API key', () => {
  const baseline = modelApiEditorFingerprint(form)
  for (const field of ['name', 'baseUrl', 'modelName', 'apiKey'] as const) {
    assert.notEqual(modelApiEditorFingerprint({ ...form, [field]: `${form[field]}changed` }), baseline, field)
  }
  assert.notEqual(modelApiEditorFingerprint({ ...form, requestOverrides: { temperature: .5 } }), baseline)
  assert.equal(modelApiEditorFingerprint({ ...form }), baseline)
})
test('unparsed cURL and parsed connection changes are both retained by the dirty guard', () => {
  const empty = { name: '', baseUrl: '', modelName: '', apiKey: '', requestOverrides: {} }
  const baseline = modelApiEditorFingerprint(empty)
  assert.notEqual(modelApiEditorFingerprint(empty, 'curl https://example.com/v1/chat/completions'), baseline)
  assert.notEqual(modelApiEditorFingerprint({ ...empty, baseUrl: form.baseUrl, modelName: 'parsed-model' }), baseline)
  const accepted = modelApiEditorFingerprint(form, 'curl example')
  assert.equal(modelApiEditorFingerprint({ ...form }, 'curl example'), accepted)
})
test('ignores payload-equivalent whitespace and override object key order', () => {
  assert.equal(modelApiEditorFingerprint({ ...form, name: ' 我的连接 ', apiKey: ' ' }), modelApiEditorFingerprint(form))
  assert.equal(
    modelApiEditorFingerprint({ ...form, requestOverrides: { temperature: 1, reasoning: { budget: 100, enabled: true } } }),
    modelApiEditorFingerprint({ ...form, requestOverrides: { reasoning: { enabled: true, budget: 100 }, temperature: 1 } }),
  )
})
