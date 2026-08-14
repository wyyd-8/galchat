<script setup lang="ts">
import {
  Check, CircleDot, Dices, MessageCircle, Minus, Pause, X,
} from '@lucide/vue'
import type { Component } from 'vue'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

defineProps<{ scene: TrpgExecutionScene }>()

const statusIcons: Partial<Record<string, Component>> = {
  blocked: X,
  cancelled: Minus,
  completed: Check,
  failed: X,
  paused: Pause,
  running: CircleDot,
  'scene-paused': Pause,
  waiting_dice: Dices,
  waiting_input: MessageCircle,
}

function actorIcon(actor: TrpgExecutionActor): Component | undefined {
  return statusIcons[actor.status]
}

function actorKey(actor: TrpgExecutionActor): string {
  const item = actor.item
  return `${item.actorType}-${item.actorId ?? 'none'}-${item.subjectCharacterId ?? 'none'}-${item.order}`
}
</script>

<template>
  <div v-if="scene.activeActors.length" class="trpg-actor-roster">
    <div v-for="actor in scene.activeActors" :key="actorKey(actor)" class="trpg-actor-row" :class="actor.status" :title="actor.statusLabel" :aria-label="`${actor.name}，${actor.statusLabel}`">
      <span class="trpg-actor-name">{{ actor.name }}</span>
      <span class="trpg-status-icon" aria-hidden="true">
        <component :is="actorIcon(actor)" v-if="actorIcon(actor)" :size="13" :stroke-width="1.8" />
      </span>
    </div>
  </div>
  <div v-if="scene.waitingActors.length" class="trpg-participant-group waiting">
    <div v-for="actor in scene.waitingActors" :key="actorKey(actor)" class="trpg-actor-row waiting" :aria-label="`${actor.name}，暂不参与`">
      <span class="trpg-actor-name">{{ actor.name }}</span>
      <span class="trpg-status-icon" title="暂不参与" aria-hidden="true"><Minus :size="13" :stroke-width="1.8" /></span>
    </div>
  </div>
  <div v-if="scene.readyActors.length" class="trpg-participant-group ready">
    <div v-for="actor in scene.readyActors" :key="actorKey(actor)" class="trpg-actor-row ready" :aria-label="`${actor.name}，已完成本场景探索`">
      <span class="trpg-actor-name">{{ actor.name }}</span>
      <span class="trpg-status-icon" title="已完成本场景探索" aria-hidden="true"><Check :size="13" :stroke-width="1.8" /></span>
    </div>
  </div>
</template>
