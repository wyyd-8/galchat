<script setup lang="ts">
import { Check, Pause } from '@lucide/vue'
import { TooltipProvider } from 'reka-ui'
import { useMobileViewport } from '@/composables/useMobileViewport'
import type { GroupActorRuntime, InvestigatorCardSummary, TrpgCombatParticipantOverview } from '../api/types'
import TrpgActorRow from './TrpgActorRow.vue'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

const props = withDefaults(defineProps<{
  scene: TrpgExecutionScene
  combatOverview: TrpgCombatParticipantOverview[]
  investigatorCards?: InvestigatorCardSummary[]
  actorRuntimes?: GroupActorRuntime[]
}>(), {
  investigatorCards: () => [],
  actorRuntimes: () => [],
})
const { isMobile } = useMobileViewport()
const emit = defineEmits<{
  openCard: [cardId: number]
  switchModel: [actor: TrpgExecutionActor]
}>()

function actorKey(actor: TrpgExecutionActor): string {
  const item = actor.item
  return `${item.actorType}-${item.actorId ?? 'none'}-${item.subjectCharacterId ?? 'none'}-${item.order}`
}

function isUserControlled(actor: TrpgExecutionActor): boolean {
  const subjectCharacterId = actor.item.subjectCharacterId
  const playerControlled = subjectCharacterId != null && props.investigatorCards.some((card) =>
    card.actorType === 'PLAYER' && card.cardId === subjectCharacterId)
  if (playerControlled) return true
  return actor.item.actorType === 'character' && actor.item.actorId != null
    && props.actorRuntimes.some((runtime) => runtime.actorType === 'character'
      && runtime.actorId === actor.item.actorId
      && runtime.controlMode === 'MANUAL')
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
          :player-controlled="isUserControlled(actor)"
          @open-card="emit('openCard', $event)"
          @switch-model="emit('switchModel', $event)"
        />
        <div v-if="actor.routedActor" class="trpg-routed-actor">
          <p v-if="isMobile" class="mobile-route-label">响应{{ actor.name }}</p>
          <TrpgActorRow
            :actor="actor.routedActor"
            :scene-kind="scene.kind"
            :combat-overview="combatOverview"
            :investigator-cards="investigatorCards"
            :player-controlled="isUserControlled(actor.routedActor)"
            @open-card="emit('openCard', $event)"
          @switch-model="emit('switchModel', $event)"
          />
        </div>
      </div>
    </div>
    <section v-if="scene.waitingActors.length" class="trpg-participant-group waiting">
      <header class="trpg-participant-label">
        <span><Pause :size="12" :stroke-width="1.8" /><strong>暂不参与</strong></span><small>{{ scene.waitingActors.length }} 人</small>
      </header>
      <div class="trpg-participant-list">
        <div v-for="actor in scene.waitingActors" :key="actorKey(actor)" class="trpg-actor-stack">
          <TrpgActorRow
            :actor="actor"
            :scene-kind="scene.kind"
            :combat-overview="combatOverview"
            :investigator-cards="investigatorCards"
            :player-controlled="isUserControlled(actor)"
            display-state="waiting"
            @open-card="emit('openCard', $event)"
          @switch-model="emit('switchModel', $event)"
          />
        </div>
      </div>
    </section>
    <section v-if="scene.readyActors.length" class="trpg-participant-group ready">
      <header class="trpg-participant-label">
        <span><Check :size="12" :stroke-width="1.8" /><strong>已完成</strong></span><small>{{ scene.readyActors.length }} 人</small>
      </header>
      <div class="trpg-participant-list">
        <div v-for="actor in scene.readyActors" :key="actorKey(actor)" class="trpg-actor-stack">
          <TrpgActorRow
            :actor="actor"
            :scene-kind="scene.kind"
            :combat-overview="combatOverview"
            :investigator-cards="investigatorCards"
            :player-controlled="isUserControlled(actor)"
            display-state="ready"
            @open-card="emit('openCard', $event)"
          @switch-model="emit('switchModel', $event)"
          />
        </div>
      </div>
    </section>
  </TooltipProvider>
</template>
