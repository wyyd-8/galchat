<script setup lang="ts">
import { CircleAlert } from '@lucide/vue'
import {
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { DiceModifierNoticePresentation } from '@/dice/domain/dicePlayback'

defineProps<{ notice: DiceModifierNoticePresentation }>()
</script>

<template>
  <TooltipProvider :delay-duration="250">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <span class="dice-modifier-notice-badge" tabindex="0">
          <CircleAlert :size="10" />影响因素
        </span>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent class="tooltip dice-modifier-notice-tooltip" :side-offset="8">
          <header>
            <CircleAlert :size="15" />
            <strong>本次检定受以下因素影响</strong>
          </header>
          <div class="dice-modifier-notice-participants">
            <section
              v-for="participant in notice.participants"
              :key="participant.label"
              class="dice-modifier-notice-participant"
            >
              <h3 v-if="notice.participants.length > 1">{{ participant.label }}</h3>
              <div v-if="participant.bonusReasons.length" class="is-bonus">
                <h4>奖励因素</h4>
                <ul>
                  <li v-for="reason in participant.bonusReasons" :key="reason">{{ reason }}</li>
                </ul>
              </div>
              <div v-if="participant.penaltyReasons.length" class="is-penalty">
                <h4>惩罚因素</h4>
                <ul>
                  <li v-for="reason in participant.penaltyReasons" :key="reason">{{ reason }}</li>
                </ul>
              </div>
            </section>
          </div>
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
