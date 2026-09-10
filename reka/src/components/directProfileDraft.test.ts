import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeDirectProfileDraft } from './directProfileDraft.ts'

test('late character data hydrates an untouched profile', () => {
  const blank = { userInfoPrompt: '', modelApiId: '' }
  assert.deepEqual(mergeDirectProfileDraft(blank, blank, { userInfoPrompt: '叫我旅人', modelApiId: '4' }), { userInfoPrompt: '叫我旅人', modelApiId: '4' })
})

test('late server updates retain edited fields and refresh untouched fields independently', () => {
  const previous = { userInfoPrompt: '旧称呼', modelApiId: '1' }
  const next = { userInfoPrompt: '服务器称呼', modelApiId: '2' }
  assert.deepEqual(mergeDirectProfileDraft({ userInfoPrompt: '正在输入的新称呼', modelApiId: '1' }, previous, next), { userInfoPrompt: '正在输入的新称呼', modelApiId: '2' })
  assert.deepEqual(mergeDirectProfileDraft({ userInfoPrompt: '旧称呼', modelApiId: '3' }, previous, next), { userInfoPrompt: '服务器称呼', modelApiId: '3' })
})
