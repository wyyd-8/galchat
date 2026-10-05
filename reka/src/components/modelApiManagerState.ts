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

  let revision = 0
  let mutationRevision = 0
  const mutations = new Map<number, { revision: number; model: ModelApi | null }>()

  function upsert(model: ModelApi) {
    mutations.set(model.id, { revision: ++mutationRevision, model })
    const index = models.value.findIndex((item) => item.id === model.id)
    if (index < 0) models.value = [model, ...models.value]
    else models.value = models.value.map((item) => item.id === model.id ? model : item)
  }

  async function load() {
    const request = ++revision
    const beforeMutations = mutationRevision
    loading.value = true
    try {
      const rows = await gateway.list()
      if (request === revision) {
        const latest = new Map(rows.map(model => [model.id, model]))
        for (const [id, change] of mutations) {
          if (change.revision <= beforeMutations) continue
          if (change.model) latest.set(id, change.model)
          else latest.delete(id)
        }
        models.value = [...latest.values()]
      }
    } finally {
      if (request === revision) loading.value = false
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
      mutations.set(id, { revision: ++mutationRevision, model: null })
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
