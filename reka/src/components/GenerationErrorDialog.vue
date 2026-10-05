<script setup lang="ts">
import type { GenerationErrorDetail } from '@/api/types'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { mergeGenerationResponseEvents } from './generationErrorFormatting'

const open = defineModel<boolean>({ required: true })
defineProps<{ title: string; message: string; detail: GenerationErrorDetail }>()
function formatDebugValue(value: unknown) {
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}
</script>

<template>
  <BaseDialog v-model="open" :title="title" :description="message" size="lg" content-class="generation-error-dialog" mobile-presentation="page">
    <div class="generation-error-overview">
      <dl>
        <div><dt>错误编号</dt><dd>{{ detail.errorId }}</dd></div>
        <div><dt>错误代码</dt><dd>{{ detail.code }}</dd></div>
        <div><dt>发生时间</dt><dd>{{ detail.occurredAt }}</dd></div>
        <div><dt>操作</dt><dd>{{ detail.operation }}</dd></div>
      </dl>
      <p>详细信息仅保留在当前页面中，刷新后会清除。</p>
    </div>
    <div class="generation-error-details">
      <details open>
        <summary>Request</summary>
        <pre>{{ formatDebugValue(detail.request) }}</pre>
      </details>
      <details>
        <summary>Response</summary>
        <pre>{{ formatDebugValue(mergeGenerationResponseEvents(detail.response)) }}</pre>
      </details>
      <details>
        <summary>Stack trace</summary>
        <pre>{{ formatDebugValue(detail.stack) }}</pre>
      </details>
    </div>
    <template #footer>
      <button class="button ghost" @click="open = false">关闭</button>
      <slot name="actions" />
    </template>
  </BaseDialog>
</template>
