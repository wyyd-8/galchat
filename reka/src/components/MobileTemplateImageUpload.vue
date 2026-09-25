<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Expand, ImageOff, ImagePlus, Images, LoaderCircle } from '@lucide/vue'
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
</script>

<template>
  <section class="mobile-template-image" :class="{ 'is-character': kind === 'character' }" :aria-labelledby="labelId" :aria-busy="uploading">
    <header class="mobile-template-image-heading"><span :id="labelId">{{ label }}</span><small>可选</small></header>
    <div class="mobile-template-image-stage">
      <button v-if="image" type="button" class="mobile-template-image-preview" :aria-label="`查看${label}原图`" :disabled="uploading" @click="previewOpen = true">
        <img v-if="!imageFailed" :src="image" :alt="name ? `${name}的${label}` : label" @error="imageFailed = true" />
        <span v-else class="mobile-template-image-fallback"><ImageOff :size="28" /><span>暂时无法预览</span></span>
        <span v-if="!uploading" class="mobile-template-image-expand"><Expand :size="14" />查看原图</span>
      </button>
      <button v-else type="button" class="mobile-template-image-empty" :disabled="locked" @click="choose">
        <span class="mobile-template-image-empty-icon"><ImagePlus :size="28" /></span>
        <strong>{{ kind === 'world' ? '为世界添加一张封面' : '添加角色形象' }}</strong>
        <span>轻点从相册选择</span>
      </button>
      <div v-if="uploading" class="mobile-template-image-loading" role="status"><LoaderCircle :size="25" /><span>正在上传图片…</span></div>
    </div>
    <div v-if="image || filename" class="mobile-template-image-caption" role="status">
      <strong>{{ filename || name || label }}</strong>
      <span>{{ uploading ? '上传完成后即可保存' : filename ? '新图片已就绪，保存模板后生效' : '当前图片' }}</span>
    </div>
    <div class="mobile-template-image-actions" :class="{ 'has-image': image }">
      <button type="button" class="mobile-template-image-select" :disabled="locked" @click="choose"><Images :size="18" />{{ error ? '重新选择图片' : image ? '更换图片' : '从相册选择' }}</button>
      <button v-if="image" type="button" class="mobile-template-image-remove" :disabled="locked" @click="upload.remove">移除</button>
    </div>
    <input ref="picker" class="mobile-template-image-input" type="file" accept="image/jpeg,image/png,image/gif,image/webp,image/bmp,image/x-ms-bmp" :aria-label="`选择${label}`" :disabled="locked" @change="selectFile" />
    <p v-if="error" class="mobile-template-image-error" role="alert">{{ error }}</p>
    <p class="mobile-template-image-help">支持 JPG、PNG、GIF、WebP、BMP，最大 4MB。<br />{{ kind === 'world' ? '用于世界列表的封面' : '用于角色列表与聊天头像' }}，保存模板后生效。</p>
    <BaseDialog v-model="previewOpen" :title="label" size="lg" layer="foreground" mobile-presentation="page">
      <img v-if="image && !imageFailed" class="mobile-template-image-original" :src="image" :alt="label" />
      <p v-else class="mobile-template-image-help">暂时无法加载这张图片，请返回后重新上传。</p>
    </BaseDialog>
  </section>
</template>

<style scoped>
.mobile-template-image { min-width: 0; display: grid; gap: 12px; grid-column: 1 / -1; }
.mobile-template-image-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; color: var(--ink); font-size: 14px; font-weight: 550; }
.mobile-template-image-heading > small { color: var(--muted); font-size: 12px; font-weight: 400; }
.mobile-template-image-stage { position: relative; overflow: hidden; border-radius: 16px; background: #e9eee5; }
.mobile-template-image-preview, .mobile-template-image-empty { width: 100%; min-height: 168px; aspect-ratio: 16 / 9; position: relative; display: grid; place-items: center; padding: 0; border: 0; color: var(--pine); background: transparent; cursor: pointer; }
.mobile-template-image-preview > img { width: 100%; height: 100%; position: absolute; inset: 0; object-fit: contain; }
.is-character .mobile-template-image-stage { width: 184px; max-width: 100%; justify-self: center; }
.is-character .mobile-template-image-preview, .is-character .mobile-template-image-empty { aspect-ratio: 1; min-height: 0; }
.mobile-template-image-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 20px 14px; border: 1px dashed #b8c8bc; border-radius: 16px; }
.mobile-template-image-empty-icon { width: 48px; height: 48px; display: grid; place-items: center; border-radius: 15px; background: var(--surface-strong); margin-bottom: 4px; }
.mobile-template-image-empty > strong { color: var(--ink); font-size: 14px; font-weight: 550; }
.mobile-template-image-empty > span:last-child { color: var(--muted); font-size: 12px; }
.mobile-template-image-expand { position: absolute; right: 10px; bottom: 10px; display: inline-flex; align-items: center; gap: 5px; padding: 6px 9px; border-radius: 7px; background: rgba(27, 52, 45, .8); color: #fff; font-size: 11px; }
.mobile-template-image-fallback { display: grid; justify-items: center; gap: 9px; font-size: 13px; }
.mobile-template-image-loading { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: var(--pine); background: rgba(237, 243, 234, .92); font-size: 13px; pointer-events: none; }
.mobile-template-image-loading > svg { animation: mobile-template-image-spin 1s linear infinite; }
.mobile-template-image-caption { min-width: 0; display: grid; gap: 3px; text-align: center; }
.mobile-template-image-caption > strong { color: var(--ink); font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }
.mobile-template-image-caption > span { color: var(--muted); font-size: 12px; }
.mobile-template-image-actions { display: grid; gap: 10px; }
.mobile-template-image-actions.has-image { grid-template-columns: minmax(0, 1fr) 76px; }
.mobile-template-image-select, .mobile-template-image-remove { min-height: 48px; padding: 10px 12px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 11px; font-size: 14px; font-weight: 500; cursor: pointer; }
.mobile-template-image-select { color: var(--pine); background: var(--pine-soft); }
.mobile-template-image-select:active:not(:disabled) { background: #d6e4dc; }
.mobile-template-image-remove { color: var(--muted); background: #edeae3; font-weight: 400; }
.mobile-template-image-remove:active:not(:disabled) { color: var(--wine); background: #f2e2e4; }
.mobile-template-image-input { display: none; }
.mobile-template-image-error, .mobile-template-image-help { margin: 0; color: var(--muted); font-size: 12px; font-weight: 400; line-height: 1.7; }
.mobile-template-image-error { color: var(--wine); }
.mobile-template-image-original { display: block; max-width: 100%; max-height: calc(100dvh - 140px); object-fit: contain; margin: 0 auto; border-radius: 12px; }
@keyframes mobile-template-image-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .mobile-template-image-loading > svg { animation: none; } }
</style>
