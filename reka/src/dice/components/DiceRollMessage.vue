<script setup lang="ts">
import { computed } from 'vue'
import { Dices } from '@lucide/vue'
import type { DiceRollAggregate } from '@/api/types'
import { createDiceMessagePresentation, splitDiceAggregateByRound } from '@/dice/domain/dicePlayback'

const props = withDefaults(defineProps<{ aggregate: DiceRollAggregate; showIcon?: boolean }>(), {
  showIcon: true,
})
const emit = defineEmits<{ open: [aggregate: DiceRollAggregate] }>()
const cards = computed(() => splitDiceAggregateByRound(props.aggregate).map((aggregate) => ({
  aggregate,
  presentation: createDiceMessagePresentation(aggregate),
})))
</script>

<template>
  <div class="dice-message-rounds">
    <button
      v-for="card in cards"
      :key="`${card.aggregate.summary.id}:${card.aggregate.results[0]?.roundNo || 1}`"
      type="button"
      class="dice-message-card"
      :class="`is-${card.presentation.tone}`"
      @click="emit('open', card.aggregate)"
    >
      <span class="dice-message-title">
        <Dices v-if="showIcon" :size="15" />
        <span class="dice-message-title-text">{{ card.presentation.title }}</span>
      </span>
      <span class="dice-message-status"><i />{{ card.presentation.statusLabel }}</span>
    </button>
  </div>
</template>
