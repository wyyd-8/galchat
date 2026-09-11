import assert from 'node:assert/strict'
import test from 'node:test'
import { computed, reactive } from 'vue'
import { cloneCocModuleData } from './cocModuleData.ts'

test('copies reactive module data into a structured-clone-safe value', () => {
  const source = reactive({
    name: '雾中来客',
    context: { truthBackground: '测试背景' },
    locations: [{ name: '旧宅', content: '门窗紧闭' }],
  })

  const copied = cloneCocModuleData(source)

  assert.doesNotThrow(() => structuredClone(copied))
  assert.deepEqual(copied, {
    name: '雾中来客',
    context: { truthBackground: '测试背景' },
    locations: [{ name: '旧宅', content: '门窗紧闭' }],
  })
  assert.notEqual(copied, source)
  assert.notEqual(copied.context, source.context)
})

test('copies reactive values nested inside a plain wrapper', () => {
  const card = reactive({
    character: { name: '阿利斯泰尔', str: 50 },
    skills: [{ displayName: '潜行', value: 40 }],
  })
  const wrapped = { ...card, profile: reactive({ notes: '教导儿子打猎的父亲' }) }

  const copied = cloneCocModuleData(wrapped)

  assert.doesNotThrow(() => structuredClone(copied))
  assert.deepEqual(copied, {
    character: { name: '阿利斯泰尔', str: 50 },
    skills: [{ displayName: '潜行', value: 40 }],
    profile: { notes: '教导儿子打猎的父亲' },
  })
})

test('tracks nested reactive edits when a module fingerprint is computed', () => {
  const source = reactive({
    name: '雾中来客',
    context: { truthBackground: '旧背景' },
  })
  const fingerprint = computed(() => JSON.stringify(cloneCocModuleData(source)))
  const savedFingerprint = fingerprint.value

  source.context.truthBackground = '新背景'

  assert.notEqual(fingerprint.value, savedFingerprint)
  assert.equal(fingerprint.value, '{"name":"雾中来客","context":{"truthBackground":"新背景"}}')
})
