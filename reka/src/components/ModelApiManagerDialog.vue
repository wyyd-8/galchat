<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Braces, Eye, EyeOff, KeyRound, LoaderCircle, Plus, ServerCog, TerminalSquare, Trash2, TriangleAlert, X } from '@lucide/vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import ModelCurlImport from '@/components/ModelCurlImport.vue'
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
import { modelApiEditorFingerprint } from './modelApiEditorDraft'
import { parseOpenAiCurl, requestOverrideWarnings } from '@/components/modelApiCurlImport'

const open = defineModel<boolean>({ required: true })
const manager = createModelApiManagerState({
  list: api.modelApis,
  create: api.createModelApi,
  update: api.updateModelApi,
  test: api.testModelApi,
  delete: api.deleteModelApi,
})

const { isMobile } = useMobileViewport()
const curlOpen = ref(false)
const resultOpen = ref(false)
const resultTarget = ref<ModelApi | null>(null)
const resultError = ref('')
const resultModel = computed(() => manager.models.value.find((item) => item.id === resultTarget.value?.id) || resultTarget.value)
function showResult(model: ModelApi) { resultTarget.value = model; resultError.value = ''; resultOpen.value = true }
const showKey = ref(false)
const editorVisible = ref(false)
const discardEditorOpen = ref(false)
const editorBaseline = ref('')
const editorOpen = computed({
  get: () => editorVisible.value,
  set: (visible: boolean) => {
    if (!visible && isMobile.value) {
      if (manager.saving.value) return
      if (editorDirty.value) { discardEditorOpen.value = true; return }
    }
    editorVisible.value = visible
  },
})
watch(editorVisible, () => { showKey.value = false; curlOpen.value = false })
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
const editorDirty = computed(() => modelApiEditorFingerprint(form, curlSource.value) !== editorBaseline.value)
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
  editorBaseline.value = modelApiEditorFingerprint(form, curlSource.value)
  discardEditorOpen.value = false
  editorVisible.value = true
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
  editorBaseline.value = modelApiEditorFingerprint(form, curlSource.value)
  discardEditorOpen.value = false
  editorVisible.value = true
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
    if (isMobile.value) curlOpen.value = false
  } catch (error) {
    curlParsed.value = false
    curlError.value = error instanceof Error ? error.message : 'cURL 解析失败。'
  }
}

function discardEditor() {
  discardEditorOpen.value = false
  editorVisible.value = false
}

function clearOverrides() {
  form.requestOverrides = {}
  curlParsed.value = false
}

async function saveModel() {
  try {
    if (editorMode.value === 'create') await manager.create(payload())
    else if (editingId.value) await manager.update(editingId.value, payload())
    editorVisible.value = false
    notify(editorMode.value === 'create' ? '模型配置已添加' : '模型配置已更新', '可以随时运行连接与能力测试。', 'success')
  } catch (error) {
    notify('模型配置保存失败', errorMessage(error), 'danger')
  }
}

async function testModel(model: ModelApi) {
  if (isMobile.value) showResult(model)
  try {
    const tested = await manager.test(model.id)
    if (tested.status === 'SUCCESS') notify('模型测试通过', tested.name, 'success')
    else if (tested.status === 'PARTIAL') notify('模型部分能力可用', tested.lastTestMessage || tested.name)
    else notify('模型测试失败', tested.lastTestMessage || tested.name, 'danger')
  } catch (error) {
    resultError.value = errorMessage(error)
    notify('模型测试失败', resultError.value, 'danger')
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  const target = deleteTarget.value
  try {
    await manager.delete(target.id)
    deleteTarget.value = null
    if (editingId.value === target.id) editorVisible.value = false
    if (resultTarget.value?.id === target.id) resultOpen.value = false
    notify('模型配置已删除', target.name, 'success')
  } catch (error) {
    notify('模型配置删除失败', errorMessage(error), 'danger')
  }
}
</script>

<template>
  <BaseDialog v-model="open" mobile-presentation="page" title="模型管理" :description="isMobile ? undefined : '添加并测试 OpenAI 兼容模型连接。保存后，可在单聊、群聊或跑团中为角色选择回复模型。'" size="lg" content-class="model-api-manager-dialog">
    <div v-if="isMobile" class="mobile-model-intro"><h1>回复模型</h1><p>添加并测试模型连接。</p><button class="button primary" @click="openCreate"><Plus :size="16" />添加模型</button><h3>已保存的配置</h3></div>
    <div v-else class="model-api-toolbar">
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
        @details="showResult(model)"
      />
    </div>

    <div v-else class="model-api-empty">
      <span><ServerCog :size="24" /></span>
      <strong>还没有模型配置</strong>
      <p>添加一个 OpenAI 兼容接口，然后测试它的连接与模型能力。</p>
      <button v-if="!isMobile" class="button secondary" @click="openCreate"><Plus :size="14" />添加第一个模型</button>
    </div>
  </BaseDialog>

  <BaseDialog
    v-model="editorOpen"
    mobile-presentation="page"
    :title="isMobile ? '模型配置' : editorMode === 'create' ? '添加模型' : '编辑模型'"
    :description="isMobile ? undefined : editorMode === 'create' ? '一条配置对应一个模型。保存后可单独运行能力测试。' : '修改连接信息后，原有测试结果会重置。'"
    layer="foreground"
    size="lg"
    content-class="model-api-editor-dialog"
  >
    <div class="form-stack">
      <button v-if="isMobile" class="button secondary" @click="curlOpen = true"><TerminalSquare :size="17" />从 cURL 导入连接</button>
      <ModelCurlImport v-else v-model="curlSource" :error="curlError" :parsed="curlParsed" @parse="importCurl" />

      <label class="field"><span>配置名称</span><input v-model.trim="form.name" maxlength="100" placeholder="例如：DeepSeek 主模型" /></label>
      <label class="field"><span>API 地址</span><input v-model.trim="form.baseUrl" type="url" placeholder="https://api.example.com/v1" /><small>仅支持解析到公网地址的 HTTPS 接口。</small></label>
      <label class="field"><span>模型名称</span><input v-model.trim="form.modelName" maxlength="255" placeholder="例如：deepseek-chat" /></label>
      <label class="field">
        <span>API Key</span>
        <div class="model-api-key-field"><KeyRound :size="15" /><input v-model="form.apiKey" :type="showKey ? 'text' : 'password'" autocomplete="off" :spellcheck="false" :placeholder="editorMode === 'edit' ? '留空表示保持原密钥' : '请输入 API Key'" /><button type="button" class="icon-button" :aria-label="showKey ? '隐藏密钥' : '显示密钥'" :aria-pressed="showKey" @click="showKey = !showKey"><EyeOff v-if="showKey" :size="16" /><Eye v-else :size="16" /></button></div>
        <small v-if="editorMode === 'edit' && editingModel">{{ apiKeyEditorHint(editingModel) }}</small>
        <small v-else>密钥默认隐藏，可点右侧按钮查看；保存后只显示末四位。</small>
      </label>

      <details v-if="isMobile && overrideEntries.length" class="mobile-model-overrides"><summary>额外请求参数 · {{ overrideEntries.length }} 项</summary><p>从 cURL 提取的额外请求字段，请核对后保存。</p><pre>{{ overrideJson }}</pre><p v-for="warning in overrideWarnings" :key="warning">{{ warning }}</p><button class="button secondary" @click="clearOverrides">清空额外请求参数</button></details>
      <section v-else-if="overrideEntries.length" class="model-api-overrides">
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
      <p v-if="isMobile" class="mobile-model-notice">修改连接信息后，原测试结果重置。保存后可重新测试。</p>
      <button v-if="isMobile && editingModel" class="button ghost danger-text" @click="deleteTarget = editingModel">删除此配置</button>
    </div>
    <template #footer>
      <button v-if="!isMobile" class="button ghost" :disabled="manager.saving.value" @click="editorOpen = false">取消</button>
      <button class="button primary" :disabled="!canSave || manager.saving.value" @click="saveModel">
        <LoaderCircle v-if="manager.saving.value" :size="14" class="spin" />{{ manager.saving.value ? '保存中…' : isMobile ? '保存配置' : '保存' }}
      </button>
    </template>
  </BaseDialog>

  <BaseDialog v-model="discardEditorOpen" title="放弃未保存的模型修改？" description="连接信息与 cURL 草稿尚未保存。继续编辑可保留当前内容。" mobile-presentation="sheet" layer="foreground">
    <template #footer><button class="button secondary" @click="discardEditorOpen = false">继续编辑</button><button class="button danger" @click="discardEditor">放弃修改</button></template>
  </BaseDialog>
  <BaseDialog v-model="curlOpen" title="从 cURL 导入" mobile-presentation="page" layer="foreground">
    <ModelCurlImport v-model="curlSource" :error="curlError" :parsed="curlParsed" standalone @parse="importCurl" />
    <template #footer><button class="button primary" :disabled="!curlSource.trim()" @click="importCurl">解析并填充</button></template>
  </BaseDialog>
  <BaseDialog v-model="resultOpen" title="测试结果" mobile-presentation="page" layer="foreground" size="md">
    <template v-if="resultModel">
      <p class="mobile-model-notice" :class="{ 'is-error': resultError || resultModel.status === 'FAILED' }" role="status">{{ manager.testingId.value === resultModel.id ? '正在测试连接与模型能力…' : resultError ? '测试未完成' : ({ UNTESTED: '尚未测试', SUCCESS: '连接正常', PARTIAL: '部分能力可用', FAILED: '连接失败' })[resultModel.status] }}</p>
      <section class="mobile-test-card"><h2>{{ resultModel.name }}</h2><p>{{ resultModel.modelName }}</p><dl><template v-for="capability in ([['基础对话', resultModel.chatCapability], ['流式输出', resultModel.streamingCapability], ['工具调用', resultModel.toolCallingCapability]] as const)" :key="capability[0]"><dt>{{ capability[0] }}</dt><dd>{{ ({ SUPPORTED: '支持', UNSUPPORTED: '不支持', INCONCLUSIVE: '未确认', UNKNOWN: '未测试' })[capability[1]] }}</dd></template><dt>推理输出</dt><dd>{{ ({ DETECTED: '已检测', NOT_DETECTED: '未检测', UNKNOWN: '未测试' })[resultModel.reasoningOutputStatus] }}</dd><dt>额外请求参数</dt><dd>{{ Object.keys(resultModel.requestOverrides || {}).length }} 项</dd></dl></section>
      <details v-if="resultError || resultModel.lastTestMessage" class="mobile-model-overrides" :open="Boolean(resultError) || resultModel.status === 'FAILED'"><summary>诊断详情</summary><p>{{ resultError || resultModel.lastTestMessage }}</p><code v-if="resultModel.lastTestCode">{{ resultModel.lastTestCode }}</code></details>
      <button class="button secondary mobile-test-retry" :disabled="manager.testingId.value === resultModel.id" @click="testModel(resultModel)">{{ manager.testingId.value === resultModel.id ? '测试中…' : '重新测试' }}</button>
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

<style scoped>
.field .model-api-key-field > input { padding-right: 48px; }
.model-api-key-field > .icon-button { position: absolute; right: 4px; top: 50%; transform: translateY(-50%); width: 34px; height: 34px; }
.mobile-model-notice { margin: 16px 0; padding: 14px; border-radius: 11px; background: var(--pine-soft); color: #44614f; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.mobile-model-notice.is-error { background: #f0e1e2; color: #843d47; }
.mobile-model-overrides { margin: 15px 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; color: var(--muted); font-size: 12px; }
.mobile-model-overrides summary { min-height: 22px; cursor: pointer; }
.mobile-model-overrides p { font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.mobile-model-overrides pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
.mobile-test-card { margin: 12px 0; padding: 17px; border: 1px solid var(--line); border-radius: 13px; background: var(--surface); }
.mobile-test-card h2 { font-size: 23px; font-weight: 550; line-height: 1.4; margin: 12px 0; }
.mobile-test-card p { font-size: 13px; color: var(--muted); overflow-wrap: anywhere; }
.mobile-test-card dl { display: grid; grid-template-columns: 1fr auto; gap: 12px; font-size: 13px; line-height: 1.8; }
.mobile-test-card dd { margin: 0; color: var(--muted); }
.mobile-test-retry { width: 100%; }
@media (max-width: 767px) {
  .mobile-model-intro { padding: 8px 0 0; }
  .mobile-model-intro h1 { font-size: 28px; font-weight: 600; letter-spacing: -.6px; line-height: 1.35; margin: 12px 0 10px; }
  .mobile-model-intro p { font-size: 13px; line-height: 1.8; color: var(--muted); margin: 0 0 18px; }
  .mobile-model-intro > button { width: 100%; margin: 7px 0; }
  .mobile-model-intro h3 { font-size: 16px; font-weight: 600; margin: 23px 0 12px; min-height: 30px; }
  .model-api-list { display: block; }

  .field .model-api-key-field > input:not([type=range]):not([type=checkbox]):not([type=radio]) { padding: 12px 54px 12px 36px; min-height: 48px; }
  .model-api-key-field > .icon-button { width: 44px; height: 44px; }
}
</style>
