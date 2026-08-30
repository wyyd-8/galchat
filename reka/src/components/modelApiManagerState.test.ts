import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import {
  apiKeyEditorHint,
  cloneRequestOverrides,
  createModelApiManagerState,
  type ModelApiGateway,
} from './modelApiManagerState.ts'

const untestedModel = {
  id: 7,
  name: 'DeepSeek 主模型',
  baseUrl: 'https://api.deepseek.com/v1',
  modelName: 'deepseek-chat',
  requestOverrides: {},
  apiKeyHint: '8F2A',
  status: 'UNTESTED' as const,
  chatCapability: 'UNKNOWN' as const,
  streamingCapability: 'UNKNOWN' as const,
  toolCallingCapability: 'UNKNOWN' as const,
  reasoningOutputStatus: 'UNKNOWN' as const,
  lastTestCode: undefined,
  lastTestMessage: undefined,
  lastTestAt: undefined,
  createdAt: '2026-08-29T09:00:00',
  updatedAt: '2026-08-29T09:00:00',
}

const testedModel = {
  ...untestedModel,
  status: 'SUCCESS' as const,
  chatCapability: 'SUPPORTED' as const,
  streamingCapability: 'SUPPORTED' as const,
  toolCallingCapability: 'SUPPORTED' as const,
  reasoningOutputStatus: 'DETECTED' as const,
  lastTestCode: 'OK',
  lastTestMessage: '模型连接及能力测试通过',
  lastTestAt: '2026-08-29T10:00:00',
  updatedAt: '2026-08-29T10:00:00',
}

test('loads saved models and replaces a card with the latest probe result', async () => {
  const calls: string[] = []
  const gateway: ModelApiGateway = {
    list: async () => { calls.push('list'); return [untestedModel] },
    create: async () => { throw new Error('not used') },
    update: async () => { throw new Error('not used') },
    test: async (id) => { calls.push(`test:${id}`); return testedModel },
    delete: async () => { throw new Error('not used') },
  }
  const manager = createModelApiManagerState(gateway)

  await manager.load()
  assert.deepEqual(manager.models.value, [untestedModel])

  await manager.test(7)
  assert.deepEqual(calls, ['list', 'test:7'])
  assert.equal(manager.testingId.value, null)
  assert.deepEqual(manager.models.value, [testedModel])
})

test('creates, updates, and deletes models while keeping the visible list in sync', async () => {
  const created = { ...untestedModel, id: 8, name: '新模型' }
  const updated = { ...created, name: '新模型（更新）', modelName: 'deepseek-reasoner' }
  const calls: Array<{ action: string, id?: number, payload?: unknown }> = []
  const gateway: ModelApiGateway = {
    list: async () => [],
    create: async (payload) => { calls.push({ action: 'create', payload }); return created },
    update: async (id, payload) => { calls.push({ action: 'update', id, payload }); return updated },
    test: async () => { throw new Error('not used') },
    delete: async (id) => { calls.push({ action: 'delete', id }) },
  }
  const manager = createModelApiManagerState(gateway)
  const createPayload = {
    name: '新模型',
    baseUrl: 'https://api.deepseek.com/v1',
    modelName: 'deepseek-chat',
    apiKey: 'sk-secret',
    requestOverrides: {},
  }

  await manager.create(createPayload)
  assert.deepEqual(manager.models.value, [created])

  await manager.update(8, { ...createPayload, name: '新模型（更新）', modelName: 'deepseek-reasoner', apiKey: undefined })
  assert.deepEqual(manager.models.value, [updated])

  await manager.delete(8)
  assert.deepEqual(manager.models.value, [])
  assert.deepEqual(calls, [
    { action: 'create', payload: createPayload },
    { action: 'update', id: 8, payload: { ...createPayload, name: '新模型（更新）', modelName: 'deepseek-reasoner', apiKey: undefined } },
    { action: 'delete', id: 8 },
  ])
})

test('always clears operation indicators when the upstream request fails', async () => {
  const gateway: ModelApiGateway = {
    list: async () => { throw new Error('list failed') },
    create: async () => { throw new Error('create failed') },
    update: async () => { throw new Error('update failed') },
    test: async () => { throw new Error('test failed') },
    delete: async () => { throw new Error('delete failed') },
  }
  const manager = createModelApiManagerState(gateway)

  await assert.rejects(manager.load(), /list failed/)
  assert.equal(manager.loading.value, false)

  await assert.rejects(manager.test(7), /test failed/)
  assert.equal(manager.testingId.value, null)

  await assert.rejects(manager.delete(7), /delete failed/)
  assert.equal(manager.deletingId.value, null)
})

test('formats the saved key hint without exposing the full key', () => {
  assert.equal(apiKeyEditorHint(untestedModel),
    '已保存密钥：8F2A，不会在页面中回显。')
})

test('copies reactive request overrides into a plain JSON object', () => {
  const overrides = reactive({
    thinking: { type: 'enabled' },
    reasoning_effort: 'high',
  })

  const copied = cloneRequestOverrides(overrides)

  assert.deepEqual(copied, {
    thinking: { type: 'enabled' },
    reasoning_effort: 'high',
  })
  assert.notEqual(copied, overrides)
  assert.notEqual(copied.thinking, overrides.thinking)
  assert.doesNotThrow(() => structuredClone(copied))
})
