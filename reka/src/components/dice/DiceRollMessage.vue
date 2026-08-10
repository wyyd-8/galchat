<script setup lang="ts">
import { computed } from 'vue'
import { Dices } from '@lucide/vue'
import type { DiceRollAggregate } from '@/api/types'
import { createDiceMessagePresentation } from './diceDebugState'

const props = defineProps<{ aggregate: DiceRollAggregate }>()
const emit = defineEmits<{ open: [] }>()
const presentation = computed(() => createDiceMessagePresentation(props.aggregate))
</script>

<template>
  <button
    type="button"
    class="dice-message-card"
    :class="`is-${presentation.tone}`"
    @click="emit('open')"
  >
    <span class="dice-message-title"><Dices :size="15" />{{ presentation.title }}</span>
    <span class="dice-message-status"><i />{{ presentation.statusLabel }}</span>
  </button>
</template>
