<script setup lang="ts">
import { computed } from 'vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { Check, HelpCircle, LoaderCircle, Minus, Pencil, Play, Trash2, X } from '@lucide/vue'
import type { ModelApi, ModelApiCapability, ReasoningOutputStatus } from '@/api/types'

const { isMobile } = useMobileViewport()
const props = defineProps<{ model: ModelApi, testing: boolean }>()
const emit = defineEmits<{ test: []; edit: []; delete: []; details: [] }>()

const statusView = computed(() => ({
  UNTESTED: { label: '未测试', tone: 'neutral' },
  SUCCESS: { label: '可用', tone: 'success' },
  PARTIAL: { label: '部分可用', tone: 'partial' },
  FAILED: { label: '连接失败', tone: 'failed' },
}[props.model.status]))

function capabilityView(value: ModelApiCapability) {
  return {
    SUPPORTED: { label: '支持', tone: 'supported', icon: Check },
    UNSUPPORTED: { label: '不支持', tone: 'unsupported', icon: X },
    INCONCLUSIVE: { label: '未确认', tone: 'inconclusive', icon: HelpCircle },
    UNKNOWN: { label: '未测试', tone: 'unknown', icon: Minus },
  }[value]
}

function reasoningView(value: ReasoningOutputStatus) {
  return {
    DETECTED: { label: '已检测到', tone: 'supported', icon: Check },
    NOT_DETECTED: { label: '未检测到', tone: 'neutral', icon: Minus },
    UNKNOWN: { label: '未测试', tone: 'unknown', icon: Minus },
  }[value]
}

const capabilities = computed(() => [
  { name: '基础对话', ...capabilityView(props.model.chatCapability) },
  { name: '流式输出', ...capabilityView(props.model.streamingCapability) },
  { name: '工具调用', ...capabilityView(props.model.toolCallingCapability) },
  { name: '推理信息', ...reasoningView(props.model.reasoningOutputStatus) },
])
const overrideCount = computed(() => Object.keys(props.model.requestOverrides || {}).length)

const apiKeyLabel = computed(() => {
  const hint = props.model.apiKeyHint.replace(/^…+/, '')
  return `•••• ${hint}`
})

const testLabel = computed(() => {
  if (props.testing) return '测试中…'
  if (props.model.status === 'FAILED') return '重新测试'
  if (props.model.status === 'UNTESTED') return '开始测试'
  return '测试'
})

function formatTestTime(value?: string) {
  if (!value) return '尚未测试'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)
}
</script>

<template>
  <article v-if="isMobile" class="mobile-model-card">
    <header><strong>{{ model.name }}</strong><button class="mobile-model-tag mobile-model-result-link" :class="{ failed: model.status === 'FAILED' }" :disabled="model.status === 'UNTESTED'" :aria-label="`查看 ${model.name} 的测试结果`" @click="emit('details')">{{ model.status === 'SUCCESS' ? '连接正常' : statusView.label }}</button></header>
    <p>{{ model.modelName }} · 密钥末四位 {{ model.apiKeyHint.replace(/^…+/, '') }}</p>
    <div class="mobile-model-chips"><span class="mobile-model-tag">推理{{ reasoningView(model.reasoningOutputStatus).label }}</span><span v-if="overrideCount" class="mobile-model-tag">{{ overrideCount }} 项额外参数</span></div>

    <div class="mobile-model-buttons"><button class="button secondary" :aria-label="`编辑 ${model.name}`" @click="emit('edit')">编辑</button><button class="button secondary" :disabled="testing" :aria-label="`测试 ${model.name}`" @click="emit('test')"><LoaderCircle v-if="testing" :size="14" class="spin" />{{ testing ? '测试中…' : model.status === 'FAILED' ? '重新测试' : '测试连接' }}</button></div>
  </article>
  <article v-else class="model-api-card" :class="`is-${statusView.tone}`">
    <header class="model-api-card-header">
      <div class="model-api-identity">
        <div class="model-api-title-line">
          <h3>{{ model.name }}</h3>
          <span class="model-api-status" :class="`is-${statusView.tone}`"><i />{{ statusView.label }}</span>
        </div>
        <div class="model-api-model-meta">
          <code class="model-api-model-name">{{ model.modelName }}</code>
          <span v-if="overrideCount" class="model-api-override-count">额外参数 {{ overrideCount }}</span>
        </div>
        <code class="model-api-base-url">{{ model.baseUrl }}</code>
      </div>
      <div class="model-api-card-actions">
        <button class="button secondary model-api-test-button" :disabled="testing" :aria-label="`测试 ${model.name}`" @click="emit('test')">
          <LoaderCircle v-if="testing" :size="14" class="spin" />
          <Play v-else :size="14" />{{ testLabel }}
        </button>
        <button class="icon-button bordered" :aria-label="`编辑 ${model.name}`" @click="emit('edit')"><Pencil :size="15" /></button>
        <button class="icon-button bordered danger-text" :aria-label="`删除 ${model.name}`" @click="emit('delete')"><Trash2 :size="15" /></button>
      </div>
    </header>

    <div class="model-api-capabilities" aria-label="模型能力">
      <div v-for="capability in capabilities" :key="capability.name" class="model-api-capability" :class="`is-${capability.tone}`">
        <component :is="capability.icon" :size="13" />
        <span><strong>{{ capability.name }}</strong><small>{{ capability.label }}</small></span>
      </div>
    </div>

    <div v-if="model.lastTestMessage && (model.status === 'FAILED' || model.status === 'PARTIAL')" class="model-api-diagnostic" :class="{ 'is-error': model.status === 'FAILED' }">
      <span>{{ model.lastTestMessage }}</span><code v-if="model.lastTestCode">{{ model.lastTestCode }}</code>
    </div>

    <footer class="model-api-card-footer">
      <span>API Key：<code>{{ apiKeyLabel }}</code></span>
      <span>最近测试：{{ formatTestTime(model.lastTestAt) }}</span>
    </footer>
  </article>
</template>

<style scoped>
.mobile-model-card { margin: 12px 0; padding: 17px; background: var(--surface); border: 1px solid var(--line); border-radius: 13px; }
.mobile-model-card > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 7px 0; font-size: 13px; }
.mobile-model-card header strong { font-weight: 550; overflow-wrap: anywhere; }
.mobile-model-card p { font-size: 13px; line-height: 1.8; color: var(--muted); margin: 8px 0 12px; overflow-wrap: anywhere; }
.mobile-model-tag { font-size: 11px; color: var(--pine); background: var(--pine-soft); padding: 4px 8px; border-radius: 6px; line-height: 1.6; flex-shrink: 0; }
.mobile-model-tag.failed { background: #f0e0e2; color: var(--wine); }
.mobile-model-chips { display: flex; gap: 6px; flex-wrap: wrap; }
.mobile-model-buttons { display: flex; gap: 10px; margin-top: 16px; }
.mobile-model-buttons > button { flex: 1; min-width: 0; }
.mobile-model-result-link { border: 0; font-weight: 400; }
.mobile-model-result-link:disabled { opacity: 1; }
</style>
