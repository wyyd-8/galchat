<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Expand, ImageOff, ImagePlus, ImageUp, LoaderCircle } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { uploadImage } from '@/api/client'
import { createTemplateImageUpload } from './templateImageUpload'

const image = defineModel<string>({ default: '' })
const props = withDefaults(defineProps<{
  kind: 'world' | 'character'
  name?: string
  disabled?: boolean
}>(), { name: '', disabled: false })
const emit = defineEmits<{ busyChange: [value: boolean] }>()
const label = computed(() => props.kind === 'world' ? '世界封面' : '角色图片')
const labelId = useId()
const picker = ref<HTMLInputElement | null>(null)
const previewOpen = ref(false)
const imageFailed = ref(false)
const dragDepth = ref(0)
const upload = createTemplateImageUpload({
  upload: uploadImage,
  update: value => { image.value = value },
  busy: value => emit('busyChange', value),
})
const { uploading, error, filename } = upload
const locked = computed(() => props.disabled || uploading.value)
watch(image, () => { imageFailed.value = false; previewOpen.value = false })
onBeforeUnmount(upload.reset)

function choose() { if (!locked.value) picker.value?.click() }
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file && !locked.value) void upload.select(file)
}
function dropFile(event: DragEvent) {
  dragDepth.value = 0
  const file = event.dataTransfer?.files[0]
  if (file && !locked.value) void upload.select(file)
}
</script>

<template>
  <div class="field template-image-field">
    <div class="template-image-heading"><span :id="labelId">{{ label }}</span><small>可选</small></div>
    <div
      class="template-image-card"
      :class="{ 'is-empty': !image && !uploading, 'is-dragging': dragDepth > 0 && !locked, 'is-character': kind === 'character' }"
      role="group"
      :aria-labelledby="labelId"
      :aria-busy="uploading"
      @dragenter.prevent="dragDepth += 1"
      @dragover.prevent
      @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)"
      @drop.prevent="dropFile"
    >
      <template v-if="image || uploading">
        <button v-if="image" type="button" class="template-image-thumbnail" :aria-label="`预览${label}`" @click="previewOpen = true">
          <img v-if="!imageFailed" :src="image" :alt="label" @error="imageFailed = true" />
          <span v-else class="template-image-fallback"><ImageOff :size="23" /><span>预览不可用</span></span>
          <span class="template-image-expand"><Expand :size="14" /></span>
        </button>
        <div v-else class="template-image-thumbnail template-image-placeholder" aria-hidden="true"><ImagePlus :size="28" /></div>
        <div class="template-image-info">
          <strong>{{ filename || (name ? `${name} · ${label}` : label) }}</strong>
          <div class="template-image-status" role="status">
            <template v-if="uploading"><LoaderCircle class="template-image-spinner" :size="14" />正在上传图片…</template>
            <template v-else-if="filename">已上传 · 保存模板后生效</template>
            <template v-else>当前图片 · 点击查看大图</template>
          </div>
          <div class="template-image-actions">
            <button type="button" class="button secondary" :disabled="locked" @click="choose"><ImageUp :size="15" />{{ error ? '重新选择' : '更换图片' }}</button>
            <button type="button" class="button ghost template-image-remove" :disabled="locked" @click="upload.remove">移除</button>
          </div>
        </div>
      </template>
      <button v-else type="button" class="template-image-empty" :disabled="locked" @click="choose">
        <span class="template-image-empty-icon"><ImagePlus :size="23" /></span>
        <strong>{{ error ? '重新选择图片' : '点击上传图片' }}</strong>
        <span>也可以将图片拖到这里</span>
      </button>
    </div>
    <input ref="picker" class="template-image-input" type="file" accept="image/jpeg,image/png,image/gif,image/webp,image/bmp,image/x-ms-bmp" :aria-label="`上传${label}`" :disabled="locked" @change="selectFile" />
    <p v-if="error" class="template-image-error" role="alert">{{ error }}</p>
    <p class="template-image-help">JPG、PNG、GIF、WebP、BMP · 最大 4MB<span>{{ kind === 'world' ? '封面会显示在世界列表中' : '图片会显示在角色列表与聊天中' }}，保存模板后生效。</span></p>
    <BaseDialog v-model="previewOpen" :title="label" size="lg" layer="foreground">
      <img v-if="image && !imageFailed" class="template-image-full-preview" :src="image" :alt="label" />
      <p v-else class="template-image-help">暂时无法加载这张图片，可以关闭预览后重新上传。</p>
    </BaseDialog>
  </div>
</template>

<style scoped>
.template-image-field { min-width: 0; gap: 9px; }
.template-image-heading { display: flex; align-items: baseline; gap: 8px; }
.template-image-heading > small { color: var(--muted); font-size: 11px; font-weight: 400; }
.template-image-card { min-width: 0; display: flex; align-items: center; gap: 20px; padding: 14px; border: 1px solid var(--line); border-radius: 13px; background: #f4f4ed; transition: border-color 140ms ease, background 140ms ease; }
.template-image-card.is-empty { border-style: dashed; }
.template-image-card.is-dragging { border-color: var(--pine); background: var(--pine-soft); }
.template-image-thumbnail { width: 168px; height: 112px; flex-shrink: 0; position: relative; display: grid; place-items: center; padding: 0; overflow: hidden; border: 0; border-radius: 8px; color: var(--pine); background: var(--pine-soft); cursor: pointer; }
.is-character .template-image-thumbnail { width: 112px; }
.template-image-thumbnail > img { display: block; width: 100%; height: 100%; object-fit: cover; }
.template-image-placeholder { cursor: default; }
.template-image-fallback { display: grid; justify-items: center; gap: 5px; font-size: 11px; font-weight: 400; }
.template-image-expand { position: absolute; top: 7px; right: 7px; display: grid; place-items: center; padding: 5px; border-radius: 5px; color: #fff; background: rgba(24, 53, 46, .7); }
.template-image-info { min-width: 0; flex: 1; }
.template-image-info > strong { display: block; color: var(--ink); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.template-image-status { min-height: 20px; display: flex; align-items: center; gap: 5px; margin: 3px 0 11px; color: var(--muted); font-size: 11px; font-weight: 400; }
.template-image-actions { display: flex; flex-wrap: wrap; gap: 7px; }
.template-image-actions > .button { min-height: 34px; padding: 6px 11px; font-size: 12px; }
.template-image-remove { color: var(--muted); font-weight: 400; }
.template-image-remove:hover:not(:disabled) { color: var(--wine); background: #f2e8e6; }
.template-image-empty { width: 100%; min-height: 126px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 8px; border: 0; color: var(--ink); background: transparent; cursor: pointer; }
.template-image-empty-icon { display: grid; place-items: center; width: 39px; height: 39px; margin-bottom: 3px; border-radius: 10px; color: var(--pine); background: var(--surface-strong); }
.template-image-empty > strong { font-size: 13px; font-weight: 600; }
.template-image-empty > span:last-child { color: var(--muted); font-size: 11px; font-weight: 400; }
.template-image-input { display: none; }
.template-image-error, .template-image-help { margin: 0; color: var(--muted); font-size: 11px; font-weight: 400; line-height: 1.65; }
.template-image-help > span { display: block; }
.template-image-error { color: var(--wine); }
.template-image-full-preview { display: block; max-width: 100%; max-height: 70dvh; margin: 0 auto; object-fit: contain; border-radius: 8px; }
.template-image-spinner { flex-shrink: 0; animation: template-image-spin 1s linear infinite; }
@keyframes template-image-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .template-image-spinner { animation: none; } }
</style>
