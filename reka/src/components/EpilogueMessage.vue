<script setup lang="ts">
import { computed } from 'vue'
import { ScrollText } from '@lucide/vue'

interface EpilogueEntry {
  characterId: number
  investigatorName: string
  content: string
}

interface EpiloguePayload {
  schemaVersion: number
  entries: EpilogueEntry[]
}

const props = defineProps<{ content: string }>()

const epilogue = computed<EpiloguePayload | null>(() => {
  try {
    const value: unknown = JSON.parse(props.content)
    if (!value || typeof value !== 'object') return null
    const candidate = value as Partial<EpiloguePayload>
    if (candidate.schemaVersion !== 1 || !Array.isArray(candidate.entries)) return null
    const entries = candidate.entries.filter((entry): entry is EpilogueEntry => Boolean(entry)
      && typeof entry.characterId === 'number'
      && typeof entry.investigatorName === 'string'
      && entry.investigatorName.trim().length > 0
      && typeof entry.content === 'string'
      && entry.content.trim().length > 0)
    if (!entries.length || entries.length !== candidate.entries.length) return null
    return { schemaVersion: 1, entries }
  } catch {
    return null
  }
})
</script>

<template>
  <section class="epilogue-message-card" aria-label="人物后传">
    <header class="epilogue-message-header">
      <span aria-hidden="true"><ScrollText :size="21" /></span>
      <div><small>EPILOGUE</small><strong>人物后传</strong></div>
    </header>
    <div v-if="epilogue" class="epilogue-entry-list">
      <article v-for="entry in epilogue.entries" :key="entry.characterId" class="epilogue-entry">
        <h3>{{ entry.investigatorName }}</h3>
        <p>{{ entry.content }}</p>
      </article>
    </div>
    <p v-else class="epilogue-message-error">人物后传内容暂时无法显示。</p>
    <footer><i />THE END<i /></footer>
  </section>
</template>
