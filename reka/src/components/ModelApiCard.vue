<script setup lang="ts">
import { computed } from 'vue'
import { Check, HelpCircle, LoaderCircle, Minus, Pencil, Play, Trash2, X } from '@lucide/vue'
import type { ModelApi, ModelApiCapability, ReasoningOutputStatus } from '@/api/types'

const props = defineProps<{ model: ModelApi, testing: boolean }>()
const emit = defineEmits<{ test: []; edit: []; delete: [] }>()

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

const apiKeyLabel = computed(() => {
  if (!props.model.hasApiKey) return '未保存'
  const hint = props.model.apiKeyHint?.replace(/^…+/, '') || '••••'
  return `•••• ${hint}`
})

const testLabel = computed(() => {
  if (props.testing) return '测试中…'
  if (!props.model.hasApiKey) return '配置 Key 后测试'
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
  <article class="model-api-card" :class="`is-${statusView.tone}`">
    <header class="model-api-card-header">
      <div class="model-api-identity">
        <div class="model-api-title-line">
          <h3>{{ model.name }}</h3>
          <span class="model-api-status" :class="`is-${statusView.tone}`"><i />{{ statusView.label }}</span>
        </div>
        <code class="model-api-model-name">{{ model.modelName }}</code>
        <code class="model-api-base-url">{{ model.baseUrl }}</code>
      </div>
      <div class="model-api-card-actions">
        <button class="button secondary model-api-test-button" :disabled="testing || !model.hasApiKey" :aria-label="`测试 ${model.name}`" @click="emit('test')">
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
