<script setup lang="ts">
import {
  Check, CircleDot, Dices, MessageCircle, Minus, Pause, X,
} from '@lucide/vue'
import type { Component } from 'vue'
import {
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { TrpgCombatParticipantOverview } from '../api/types'
import { buildCombatHoverCard, type TrpgCombatHoverCard } from './trpgCombatOverview'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

const props = defineProps<{
  scene: TrpgExecutionScene
  combatOverview: TrpgCombatParticipantOverview[]
}>()

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

const activeEventLabels: Partial<Record<string, string>> = {
  running: '行动中',
  waiting_input: '待输入',
  waiting_dice: '待掷骰',
}

function actorIcon(actor: TrpgExecutionActor): Component | undefined {
  return statusIcons[actor.status]
}

function actorKey(actor: TrpgExecutionActor): string {
  const item = actor.item
  return `${item.actorType}-${item.actorId ?? 'none'}-${item.subjectCharacterId ?? 'none'}-${item.order}`
}

function activeEventLabel(actor: TrpgExecutionActor): string | undefined {
  return activeEventLabels[actor.status]
}

function actorHoverCard(actor: TrpgExecutionActor): TrpgCombatHoverCard {
  return buildCombatHoverCard(actor, props.combatOverview) ?? {
    name: actor.name,
    roleLabel: actor.item.actorType === 'kp' ? 'NPC' : '调查员',
    statuses: [],
    metrics: [],
  }
}
</script>

<template>
  <TooltipProvider :delay-duration="220">
    <div v-if="scene.activeActors.length" class="trpg-actor-roster">
      <template v-for="actor in scene.activeActors" :key="actorKey(actor)">
        <TooltipRoot v-if="scene.kind === 'combat'">
          <TooltipTrigger as-child>
            <div class="trpg-actor-row" :class="actor.status" :aria-label="`${actor.name}，${actor.statusLabel}`" tabindex="0">
              <span class="trpg-actor-name">{{ actor.name }}</span>
              <span class="trpg-status-icon" aria-hidden="true">
                <small v-if="activeEventLabel(actor)" class="trpg-active-label">{{ activeEventLabel(actor) }}</small>
                <component :is="actorIcon(actor)" v-if="actorIcon(actor)" :size="13" :stroke-width="1.8" />
              </span>
            </div>
          </TooltipTrigger>
          <TooltipPortal>
            <TooltipContent class="trpg-combat-overview-tooltip" side="left" :side-offset="10">
              <header>
                <span><strong>{{ actorHoverCard(actor).name }}</strong><small>{{ actorHoverCard(actor).roleLabel }}</small></span>
                <em>{{ actor.statusLabel }}</em>
              </header>
              <dl v-if="actorHoverCard(actor).metrics.length" class="trpg-combat-overview-metrics">
                <div v-for="metric in actorHoverCard(actor).metrics" :key="metric.label">
                  <dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
                </div>
              </dl>
              <section class="trpg-combat-overview-statuses">
                <small>当前状态</small>
                <div v-if="actorHoverCard(actor).statuses.length">
                  <span v-for="status in actorHoverCard(actor).statuses" :key="status">{{ status }}</span>
                </div>
                <p v-else>未见特殊状态</p>
              </section>
            </TooltipContent>
          </TooltipPortal>
        </TooltipRoot>
        <div v-else class="trpg-actor-row" :class="actor.status" :title="actor.statusLabel" :aria-label="`${actor.name}，${actor.statusLabel}`">
          <span class="trpg-actor-name">{{ actor.name }}</span>
          <span class="trpg-status-icon" aria-hidden="true">
            <small v-if="activeEventLabel(actor)" class="trpg-active-label">{{ activeEventLabel(actor) }}</small>
            <component :is="actorIcon(actor)" v-if="actorIcon(actor)" :size="13" :stroke-width="1.8" />
          </span>
        </div>
      </template>
    </div>
  </TooltipProvider>
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
