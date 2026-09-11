import { ref } from 'vue'
import type { ModelApi, ModelApiSavePayload } from '@/api/types'

export interface ModelApiGateway {
  list: () => Promise<ModelApi[]>
  create: (payload: ModelApiSavePayload) => Promise<ModelApi>
  update: (id: number, payload: ModelApiSavePayload) => Promise<ModelApi>
  test: (id: number) => Promise<ModelApi>
  delete: (id: number) => Promise<void>
}

export function apiKeyEditorHint(model: Pick<ModelApi, 'apiKeyHint'>) {
  const hint = model.apiKeyHint.replace(/^…+/, '')
  return `已保存密钥：${hint}，不会在页面中回显。`
}

export function cloneRequestOverrides(
  requestOverrides: Record<string, unknown>,
): Record<string, unknown> {
  return JSON.parse(JSON.stringify(requestOverrides)) as Record<string, unknown>
}

export function createModelApiManagerState(gateway: ModelApiGateway) {
  const models = ref<ModelApi[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const testingId = ref<number | null>(null)
  const deletingId = ref<number | null>(null)

  function upsert(model: ModelApi) {
    const index = models.value.findIndex((item) => item.id === model.id)
    if (index < 0) models.value = [model, ...models.value]
    else models.value = models.value.map((item) => item.id === model.id ? model : item)
  }

  async function load() {
    loading.value = true
    try {
      models.value = await gateway.list()
    } finally {
      loading.value = false
    }
  }

  async function create(payload: ModelApiSavePayload) {
    saving.value = true
    try {
      const model = await gateway.create(payload)
      upsert(model)
      return model
    } finally {
      saving.value = false
    }
  }

  async function update(id: number, payload: ModelApiSavePayload) {
    saving.value = true
    try {
      const model = await gateway.update(id, payload)
      upsert(model)
      return model
    } finally {
      saving.value = false
    }
  }

  async function test(id: number) {
    testingId.value = id
    try {
      const model = await gateway.test(id)
      upsert(model)
      return model
    } finally {
      testingId.value = null
    }
  }

  async function deleteModel(id: number) {
    deletingId.value = id
    try {
      await gateway.delete(id)
      models.value = models.value.filter((item) => item.id !== id)
    } finally {
      deletingId.value = null
    }
  }

  return {
    models,
    loading,
    saving,
    testingId,
    deletingId,
    load,
    create,
    update,
    test,
    delete: deleteModel,
  }
}
