<script setup lang="ts">
import type { DicePlayerPhase } from '../domain/dicePlayback'

defineProps<{ phase: DicePlayerPhase; showContinue: boolean; hasPrevious?: boolean; hasNext?: boolean; roundPosition?: number; roundCount?: number }>()
defineEmits<{ previous: []; next: [] }>()
</script>

<template>
  <nav v-if="!showContinue && (phase === 'ready' || phase === 'complete') && (hasPrevious || hasNext)" class="dice-round-navigation" aria-label="同组掷骰轮次">
    <button v-if="hasPrevious" class="dice-round-button is-previous" type="button" aria-label="上一轮" title="上一轮" @click="$emit('previous')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
    </button>
    <span class="dice-round-position" :aria-label="`第 ${roundPosition} 轮，共 ${roundCount} 轮`">{{ roundPosition }} / {{ roundCount }}</span>
    <button v-if="hasNext" class="dice-round-button is-next" type="button" aria-label="下一轮" title="下一轮" @click="$emit('next')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m10 6 6 6-6 6" /></svg>
    </button>
  </nav>
</template>

<style scoped>
.dice-round-navigation { display: grid; grid-template-columns: 28px minmax(40px, auto) 28px; gap: 4px; justify-content: center; align-items: center; min-height: 32px; }
.dice-round-button { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; border: 0; border-radius: 7px; background: transparent; color: #535b50; cursor: pointer; transition: background-color .15s; }
.dice-round-button.is-previous { grid-column: 1; grid-row: 1; }
.dice-round-button.is-next { grid-column: 3; grid-row: 1; }
.dice-round-position { grid-column: 2; grid-row: 1; text-align: center; color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.dice-round-button:hover { background: #ffffff80; }
.dice-round-button:active { background: #ffffffb3; }
.dice-round-button:focus-visible { outline: 2px solid var(--pine); outline-offset: 2px; }
@media (max-width: 767px) {
  .dice-round-navigation { flex: 1; grid-template-columns: 36px minmax(40px, auto) 36px; }
  .dice-round-button { width: 36px; height: 44px; }
}
</style>
