<script setup lang="ts">
import { TerminalSquare } from '@lucide/vue'
const source = defineModel<string>({ required: true })
withDefaults(defineProps<{ error: string; parsed: boolean; standalone?: boolean }>(), { standalone: false })
const emit = defineEmits<{ parse: [] }>()
</script>
<template>
  <section class="model-api-curl-import" :class="{ 'standalone-curl': standalone }">
    <div v-if="!standalone" class="model-api-curl-heading"><span><TerminalSquare :size="16" /></span><div><strong>从 cURL 导入</strong><small>解析仅在本地浏览器中进行，原始 cURL 不会上传或保存。</small></div></div>
    <label v-if="standalone" class="curl-label" for="standalone-curl-source">粘贴 cURL</label>
    <textarea v-model="source" :id="standalone ? 'standalone-curl-source' : undefined" :rows="standalone ? 10 : 7" :spellcheck="false" placeholder="粘贴服务商文档中的 /chat/completions cURL 示例…" aria-label="OpenAI Chat Completions cURL" />
    <p v-if="standalone" class="curl-notice">解析仅在本地浏览器中进行，原始 cURL 不会上传或保存。</p>
    <div v-if="!standalone" class="model-api-curl-actions"><small>解析会填充连接信息，并提取思考模式等额外请求参数。</small><button class="button secondary" :disabled="!source.trim()" @click="emit('parse')">解析并填充</button></div>
    <p v-if="error" class="model-api-curl-error" role="alert">{{ error }}</p><p v-else-if="parsed" class="model-api-curl-success" role="status">已解析，请检查连接信息后再保存。</p>
  </section>
</template>

<style scoped>
@media (max-width: 767px) {
  .model-api-curl-import.standalone-curl { display: block; margin: 0; padding: 0; border: 0; background: transparent; }
  .curl-label { display: block; font-size: 13px; font-weight: 550; margin-bottom: 8px; }
  .standalone-curl > textarea { width: 100%; padding: 12px; min-height: 288px; border: 1px solid var(--line); border-radius: 10px; background: var(--strong, #fffefa); font-size: 16px; line-height: 1.6; resize: vertical; }
  .curl-notice { margin: 16px 0; padding: 14px; border-radius: 11px; background: var(--pine-soft); font-size: 13px; line-height: 1.8; color: #44614f; }
  .model-api-curl-error, .model-api-curl-success { font-size: 13px; line-height: 1.8; }
}
</style>
