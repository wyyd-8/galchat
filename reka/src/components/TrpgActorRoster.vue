<script setup lang="ts">
import { Check, Pause } from '@lucide/vue'
import { TooltipProvider } from 'reka-ui'
import type { InvestigatorCardSummary, TrpgCombatParticipantOverview } from '../api/types'
import TrpgActorRow from './TrpgActorRow.vue'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

const props = withDefaults(defineProps<{
  scene: TrpgExecutionScene
  combatOverview: TrpgCombatParticipantOverview[]
  investigatorCards?: InvestigatorCardSummary[]
}>(), {
  investigatorCards: () => [],
})
const emit = defineEmits<{ openCard: [cardId: number] }>()

function actorKey(actor: TrpgExecutionActor): string {
  const item = actor.item
  return `${item.actorType}-${item.actorId ?? 'none'}-${item.subjectCharacterId ?? 'none'}-${item.order}`
}
</script>

<template>
  <TooltipProvider :delay-duration="220">
    <div v-if="scene.activeActors.length" class="trpg-actor-roster">
      <div v-for="actor in scene.activeActors" :key="actorKey(actor)" class="trpg-actor-stack">
        <TrpgActorRow
          :actor="actor"
          :scene-kind="scene.kind"
          :combat-overview="combatOverview"
          :investigator-cards="investigatorCards"
          @open-card="emit('openCard', $event)"
        />
        <div v-if="actor.routedActor" class="trpg-routed-actor">
          <TrpgActorRow
            :actor="actor.routedActor"
            :scene-kind="scene.kind"
            :combat-overview="combatOverview"
            :investigator-cards="investigatorCards"
            @open-card="emit('openCard', $event)"
          />
        </div>
      </div>
    </div>
    <section v-if="scene.waitingActors.length" class="trpg-participant-group waiting">
      <header class="trpg-participant-label">
        <span><Pause :size="12" :stroke-width="1.8" /><strong>暂不参与</strong></span><small>{{ scene.waitingActors.length }} 人</small>
      </header>
      <div class="trpg-participant-list">
        <TrpgActorRow
          v-for="actor in scene.waitingActors"
          :key="actorKey(actor)"
          :actor="actor"
          :scene-kind="scene.kind"
          :combat-overview="combatOverview"
          :investigator-cards="investigatorCards"
          display-state="waiting"
          @open-card="emit('openCard', $event)"
        />
      </div>
    </section>
    <section v-if="scene.readyActors.length" class="trpg-participant-group ready">
      <header class="trpg-participant-label">
        <span><Check :size="12" :stroke-width="1.8" /><strong>已完成</strong></span><small>{{ scene.readyActors.length }} 人</small>
      </header>
      <div class="trpg-participant-list">
        <TrpgActorRow
          v-for="actor in scene.readyActors"
          :key="actorKey(actor)"
          :actor="actor"
          :scene-kind="scene.kind"
          :combat-overview="combatOverview"
          :investigator-cards="investigatorCards"
          display-state="ready"
          @open-card="emit('openCard', $event)"
        />
      </div>
    </section>
  </TooltipProvider>
</template>
