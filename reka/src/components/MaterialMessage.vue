<script setup lang="ts">
import { computed } from 'vue'
import { FileImage } from '@lucide/vue'

interface MaterialPayload {
  schemaVersion: number
  materialId: number
  title: string
  description: string
  imageUrl: string
}

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
    <img v-if="material?.imageUrl" class="material-message-image" :src="material.imageUrl" :alt="material.title">
    <div class="material-message-copy">
      <span><FileImage :size="14" />展示材料</span>
      <template v-if="material">
        <strong>{{ material.title }}</strong>
        <p>{{ material.description }}</p>
      </template>
      <p v-else class="material-message-error">材料内容暂时无法显示。</p>
    </div>
  </section>
</template>
