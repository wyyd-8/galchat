<script setup lang="ts">
import { computed } from 'vue'
import { Swords } from '@lucide/vue'

const props = defineProps<{ content: string }>()

const entries = computed(() => {
  const body = props.content.trim().replace(/^战斗结果[：:]\s*/, '')
  if (!body) return ['本场战斗已经结束。']
  return body.split(/\r?\n/)
    .map((line) => line.trim().replace(/^\d+[.、]\s*/, ''))
    .filter(Boolean)
})
</script>

<template>
  <section class="combat-result-card" aria-label="战斗结算">
    <header class="combat-result-header">
      <span class="combat-result-icon" aria-hidden="true"><Swords :size="20" /></span>
      <span class="combat-result-heading">
        <small>战斗记录</small>
        <strong>战斗结算</strong>
      </span>
      <em>战斗结束</em>
    </header>
    <ol class="combat-result-list">
      <li v-for="(entry, index) in entries" :key="`${index}-${entry}`">
        <span>{{ index + 1 }}</span>
        <p>{{ entry }}</p>
      </li>
    </ol>
    <footer><i />回合结果已归档<i /></footer>
  </section>
</template>
