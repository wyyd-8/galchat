<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Braces, KeyRound, LoaderCircle, Plus, ServerCog, TerminalSquare, Trash2, TriangleAlert, X } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import ModelApiCard from '@/components/ModelApiCard.vue'
import { api } from '@/api/client'
import type { ModelApi, ModelApiSavePayload } from '@/api/types'
import { errorMessage, notify } from '@/composables/useNotice'
import {
  apiKeyEditorHint,
  cloneRequestOverrides,
  createModelApiManagerState,
} from '@/components/modelApiManagerState'
import { parseOpenAiCurl, requestOverrideWarnings } from '@/components/modelApiCurlImport'

const open = defineModel<boolean>({ required: true })
const manager = createModelApiManagerState({
  list: api.modelApis,
  create: api.createModelApi,
  update: api.updateModelApi,
  test: api.testModelApi,
  delete: api.deleteModelApi,
})

const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const deleteTarget = ref<ModelApi | null>(null)
const deleteOpen = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => { if (!value) deleteTarget.value = null },
})
const form = reactive({
  name: '',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  requestOverrides: {} as Record<string, unknown>,
})
const curlSource = ref('')
const curlError = ref('')
const curlParsed = ref(false)
const canSave = computed(() => Boolean(
  form.name.trim()
  && form.baseUrl.trim()
  && form.modelName.trim()
  && (editorMode.value === 'edit' || form.apiKey.trim()),
))
const editingModel = computed(() => manager.models.value.find((item) => item.id === editingId.value) || null)
const overrideEntries = computed(() => Object.entries(form.requestOverrides))
const overrideJson = computed(() => JSON.stringify(form.requestOverrides, null, 2))
const overrideWarnings = computed(() => requestOverrideWarnings(form.requestOverrides))

watch(open, (value) => {
  if (!value) return
  void manager.load().catch((error) => notify('模型配置加载失败', errorMessage(error), 'danger'))
})

function openCreate() {
  editorMode.value = 'create'
  editingId.value = null
  Object.assign(form, { name: '', baseUrl: '', modelName: '', apiKey: '', requestOverrides: {} })
  resetCurlImport()
  editorOpen.value = true
}

function openEdit(model: ModelApi) {
  editorMode.value = 'edit'
  editingId.value = model.id
  Object.assign(form, {
    name: model.name,
    baseUrl: model.baseUrl,
    modelName: model.modelName,
    apiKey: '',
    requestOverrides: cloneRequestOverrides(model.requestOverrides || {}),
  })
  resetCurlImport()
  editorOpen.value = true
}

function payload(): ModelApiSavePayload {
  return {
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    modelName: form.modelName.trim(),
    apiKey: form.apiKey.trim() || undefined,
    requestOverrides: cloneRequestOverrides(form.requestOverrides),
  }
}

function resetCurlImport() {
  curlSource.value = ''
  curlError.value = ''
  curlParsed.value = false
}

function importCurl() {
  try {
    const parsed = parseOpenAiCurl(curlSource.value)
    form.baseUrl = parsed.baseUrl
    form.modelName = parsed.modelName
    form.requestOverrides = parsed.requestOverrides
    if (parsed.apiKey) form.apiKey = parsed.apiKey
    curlError.value = ''
    curlParsed.value = true
  } catch (error) {
    curlParsed.value = false
    curlError.value = error instanceof Error ? error.message : 'cURL 解析失败。'
  }
}

function clearOverrides() {
  form.requestOverrides = {}
  curlParsed.value = false
}

async function saveModel() {
  try {
    if (editorMode.value === 'create') await manager.create(payload())
    else if (editingId.value) await manager.update(editingId.value, payload())
    editorOpen.value = false
    notify(editorMode.value === 'create' ? '模型配置已添加' : '模型配置已更新', '可以随时运行连接与能力测试。', 'success')
  } catch (error) {
    notify('模型配置保存失败', errorMessage(error), 'danger')
  }
}

async function testModel(model: ModelApi) {
  try {
    const tested = await manager.test(model.id)
    if (tested.status === 'SUCCESS') notify('模型测试通过', tested.name, 'success')
    else if (tested.status === 'PARTIAL') notify('模型部分能力可用', tested.lastTestMessage || tested.name)
    else notify('模型测试失败', tested.lastTestMessage || tested.name, 'danger')
  } catch (error) {
    notify('模型测试失败', errorMessage(error), 'danger')
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  const target = deleteTarget.value
  try {
    await manager.delete(target.id)
    deleteTarget.value = null
    notify('模型配置已删除', target.name, 'success')
  } catch (error) {
    notify('模型配置删除失败', errorMessage(error), 'danger')
  }
}
</script>

<template>
  <BaseDialog v-model="open" title="模型管理" description="添加并测试 OpenAI 兼容模型连接。保存后，可在单聊、群聊或跑团中为角色选择回复模型。" size="lg" content-class="model-api-manager-dialog">
    <div class="model-api-toolbar">
      <div>
        <span class="eyebrow"><ServerCog :size="13" /> 模型连接</span>
        <p>已保存 {{ manager.models.value.length }} 个模型配置</p>
      </div>
      <button class="button primary" @click="openCreate"><Plus :size="15" />添加模型</button>
    </div>

    <div v-if="manager.loading.value" class="model-api-loading" role="status">
      <LoaderCircle :size="20" class="spin" /><span>正在读取模型配置…</span>
    </div>

    <div v-else-if="manager.models.value.length" class="model-api-list">
      <ModelApiCard
        v-for="model in manager.models.value"
        :key="model.id"
        :model="model"
        :testing="manager.testingId.value === model.id"
        @test="testModel(model)"
        @edit="openEdit(model)"
        @delete="deleteTarget = model"
      />
    </div>

    <div v-else class="model-api-empty">
      <span><ServerCog :size="24" /></span>
      <strong>还没有模型配置</strong>
      <p>添加一个 OpenAI 兼容接口，然后测试它的连接与模型能力。</p>
      <button class="button secondary" @click="openCreate"><Plus :size="14" />添加第一个模型</button>
    </div>
  </BaseDialog>

  <BaseDialog
    v-model="editorOpen"
    :title="editorMode === 'create' ? '添加模型' : '编辑模型'"
    :description="editorMode === 'create' ? '一条配置对应一个模型。保存后可单独运行能力测试。' : '修改连接信息后，原有测试结果会重置。'"
    layer="foreground"
    size="lg"
    content-class="model-api-editor-dialog"
  >
    <div class="form-stack">
      <section class="model-api-curl-import">
        <div class="model-api-curl-heading">
          <span><TerminalSquare :size="16" /></span>
          <div>
            <strong>从 cURL 导入</strong>
            <small>解析仅在本地浏览器中进行，原始 cURL 不会上传或保存。</small>
          </div>
        </div>
        <textarea
          v-model="curlSource"
          rows="5"
          :spellcheck="false"
          placeholder="粘贴服务商文档中的 /chat/completions cURL 示例…"
          aria-label="OpenAI Chat Completions cURL"
        />
        <div class="model-api-curl-actions">
          <small>解析会填充下方连接信息，并提取思考模式等额外请求参数。</small>
          <button class="button secondary" :disabled="!curlSource.trim()" @click="importCurl">解析并填充</button>
        </div>
        <p v-if="curlError" class="model-api-curl-error">{{ curlError }}</p>
        <p v-else-if="curlParsed" class="model-api-curl-success">已解析，请检查下方信息后再保存。</p>
      </section>

      <label class="field"><span>配置名称</span><input v-model.trim="form.name" maxlength="100" placeholder="例如：DeepSeek 主模型" /></label>
      <label class="field"><span>API 地址</span><input v-model.trim="form.baseUrl" type="url" placeholder="https://api.example.com/v1" /><small>仅支持解析到公网地址的 HTTPS 接口。</small></label>
      <label class="field"><span>模型名称</span><input v-model.trim="form.modelName" maxlength="255" placeholder="例如：deepseek-chat" /></label>
      <label class="field">
        <span>API Key</span>
        <div class="model-api-key-field"><KeyRound :size="15" /><input v-model="form.apiKey" type="text" autocomplete="off" :spellcheck="false" :placeholder="editorMode === 'edit' ? '留空表示保持原密钥' : '请输入 API Key'" /></div>
        <small v-if="editorMode === 'edit' && editingModel">{{ apiKeyEditorHint(editingModel) }}</small>
        <small v-else>填写期间明文可见，保存后只显示密钥末四位。</small>
      </label>

      <section v-if="overrideEntries.length" class="model-api-overrides">
        <header>
          <span><Braces :size="15" /></span>
          <div><strong>额外请求参数</strong><small>已从 cURL 提取 {{ overrideEntries.length }} 项，调用时将作为顶层字段传递。</small></div>
          <button class="icon-button" aria-label="清空额外请求参数" title="清空额外请求参数" @click="clearOverrides"><X :size="14" /></button>
        </header>
        <pre>{{ overrideJson }}</pre>
        <div v-for="warning in overrideWarnings" :key="warning" class="model-api-override-warning">
          <TriangleAlert :size="14" /><span>{{ warning }}</span>
        </div>
      </section>
    </div>
    <template #footer>
      <button class="button ghost" :disabled="manager.saving.value" @click="editorOpen = false">取消</button>
      <button class="button primary" :disabled="!canSave || manager.saving.value" @click="saveModel">
        <LoaderCircle v-if="manager.saving.value" :size="14" class="spin" />{{ manager.saving.value ? '保存中…' : '保存' }}
      </button>
    </template>
  </BaseDialog>

  <BaseDialog v-model="deleteOpen" title="删除模型配置" description="删除后无法恢复，但不会影响模型服务商一侧的账号或密钥。" size="sm" layer="foreground">
    <div v-if="deleteTarget" class="model-api-delete-confirmation">
      <span><Trash2 :size="18" /></span>
      <div><strong>{{ deleteTarget.name }}</strong><p>{{ deleteTarget.modelName }} · {{ deleteTarget.baseUrl }}</p></div>
    </div>
    <template #footer>
      <button class="button ghost" :disabled="manager.deletingId.value !== null" @click="deleteTarget = null">取消</button>
      <button class="button danger" :disabled="manager.deletingId.value !== null" @click="confirmDelete">
        <LoaderCircle v-if="manager.deletingId.value !== null" :size="14" class="spin" />删除
      </button>
    </template>
  </BaseDialog>
</template>
