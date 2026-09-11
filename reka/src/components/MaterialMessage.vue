<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BaseDialog from './ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { FileImage, Minus, Plus } from '@lucide/vue'

interface MaterialPayload {
  schemaVersion: number
  materialId: number
  title: string
  description: string
  imageUrl: string
}

const { isMobile } = useMobileViewport()
const imageOriginal = ref(false)
const imageOpen = ref(false)
const imageZoom = ref(1)
watch(imageOpen, open => { if (!open) { imageZoom.value = 1; imageOriginal.value = false } })
const props = defineProps<{ content: string }>()

const material = computed<MaterialPayload | null>(() => {
  try {
    const value: unknown = JSON.parse(props.content)
    if (!value || typeof value !== 'object') return null
    const candidate = value as Partial<MaterialPayload>
    if (candidate.schemaVersion !== 1
      || typeof candidate.materialId !== 'number'
      || typeof candidate.title !== 'string'
      || typeof candidate.description !== 'string'
      || typeof candidate.imageUrl !== 'string') return null
    return candidate as MaterialPayload
  } catch {
    return null
  }
})
</script>

<template>
  <section class="material-message-card" :aria-label="material ? `展示材料：${material.title}` : '展示材料无法读取'">
    <button v-if="material?.imageUrl && !isMobile" type="button" class="material-image-open" :aria-label="`放大查看${material.title}`" @click="imageOpen = true"><img class="material-message-image" :src="material.imageUrl" :alt="material.title"><span>点击放大</span></button>
    <div class="material-message-copy">
      <span><FileImage :size="14" />展示材料</span>
      <template v-if="material">
        <strong>{{ material.title }}</strong>
        <p>{{ material.description }}</p>
      </template>
      <p v-else class="material-message-error">材料内容暂时无法显示。</p>
      <button v-if="isMobile && material?.imageUrl" class="button secondary mobile-material-read" @click="imageOpen = true">查看图片资料</button>
    </div>
  </section>
  <BaseDialog v-model="imageOpen" :title="isMobile ? '图片资料' : material?.title || '展示材料'" mobile-presentation="page" size="lg" content-class="material-image-viewer"><div class="material-image-canvas"><img v-if="material?.imageUrl" :src="material.imageUrl" :alt="material.title" :style="{ width: imageOriginal ? 'auto' : `${imageZoom * 100}%` }"></div><template v-if="isMobile"><h3>{{ material?.title }}</h3><p class="material-reader-description">{{ material?.description }}</p><button class="button secondary mobile-material-read" @click="imageOriginal = !imageOriginal; imageZoom = 1">{{ imageOriginal ? '适合屏幕' : '查看原尺寸' }}</button></template><template #footer><button class="button secondary" :disabled="imageZoom <= 1 && !imageOriginal" aria-label="缩小材料图片" @click="imageOriginal = false; imageZoom = Math.max(1, imageZoom - .5)"><Minus :size="18" /></button><span>{{ imageOriginal ? '原尺寸' : `${Math.round(imageZoom * 100)}%` }}</span><button class="button secondary" :disabled="imageZoom >= 3" aria-label="放大材料图片" @click="imageOriginal = false; imageZoom = Math.min(3, imageZoom + .5)"><Plus :size="18" /></button></template></BaseDialog>
</template>

<style>
.material-image-open { position: relative; display: block; width: 100%; border: 0; padding: 0; background: transparent; cursor: zoom-in; }
.material-image-open > span { position: absolute; right: 10px; bottom: 10px; padding: 4px 8px; border-radius: 6px; background: #252724c9; color: white; font-size: 12px; }
.material-image-canvas { overflow: auto; max-height: 65dvh; }
.material-image-canvas img { display: block; max-width: none; height: auto; }
.material-image-viewer .dialog-footer { align-items: center; }
@media (max-width: 767px) {
  .chat-page .material-message-card { display: block; width: 100%; padding: 17px; border: 1px solid var(--line); border-radius: 13px; background: #fbfaf6; }
  .chat-page .material-message-copy { padding: 0; }
  .chat-page .material-message-copy > span { font-size: 10px; letter-spacing: 1.5px; color: var(--muted); }
  .chat-page .material-message-copy > strong { display: block; margin-top: 10px; font-size: 16px; }
  .mobile-material-read { width: 100%; margin-top: 7px; min-height: 46px; justify-content: center; }
  .material-image-viewer .material-image-canvas { max-height: none; overflow: auto; overscroll-behavior: contain; }
  .material-image-viewer h3 { font-size: 16px; margin: 23px 0 12px; }
  .material-reader-description { font-size: 14px; line-height: 1.8; color: var(--muted); white-space: pre-wrap; }
}
</style>
