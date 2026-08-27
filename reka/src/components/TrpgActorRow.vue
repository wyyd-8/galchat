<script setup lang="ts">
import {
  Check, CircleDot, Dices, MessageCircle, Minus, Pause, X,
} from '@lucide/vue'
import type { Component } from 'vue'
import {
  TooltipContent, TooltipPortal, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { InvestigatorCardSummary, TrpgCombatParticipantOverview } from '../api/types'
import { buildCombatHoverCard, combatInvestigatorCardId, type TrpgCombatHoverCard } from './trpgCombatOverview'
import { buildExplorationHoverCard, explorationInvestigatorCardId, type TrpgExplorationHoverCard, type TrpgExplorationMetric } from './trpgExplorationOverview'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

const props = withDefaults(defineProps<{
  actor: TrpgExecutionActor
  sceneKind: TrpgExecutionScene['kind']
  combatOverview: TrpgCombatParticipantOverview[]
  investigatorCards?: InvestigatorCardSummary[]
  displayState?: 'active' | 'waiting' | 'ready'
}>(), {
  investigatorCards: () => [],
  displayState: 'active',
})
const emit = defineEmits<{ openCard: [cardId: number] }>()

const statusIcons: Partial<Record<string, Component>> = {
  blocked: X,
  cancelled: Minus,
  completed: Check,
  failed: X,
  paused: Pause,
  ready: Check,
  running: CircleDot,
  'scene-paused': Pause,
  waiting: Pause,
  waiting_dice: Dices,
  waiting_input: MessageCircle,
}

const activeEventLabels: Partial<Record<string, string>> = {
  running: '行动中',
  waiting_input: '待输入',
  waiting_dice: '待掷骰',
}

function rowStatus(): string {
  return props.displayState === 'active' ? props.actor.status : props.displayState
}

function rowStatusLabel(): string | undefined {
  if (props.displayState === 'waiting') return '暂缓'
  if (props.displayState === 'ready') return '已完成'
  return activeEventLabels[props.actor.status]
}

function rowAriaLabel(): string {
  const status = props.displayState === 'waiting'
    ? '暂不参与'
    : props.displayState === 'ready' ? '已完成本场景探索' : props.actor.statusLabel
  return `${props.actor.name}，${status}${investigatorCardId() == null ? '' : '，打开人物卡'}`
}

function actorIcon(): Component | undefined {
  return statusIcons[rowStatus()]
}

function actorCombatCard(): TrpgCombatHoverCard {
  return buildCombatHoverCard(props.actor, props.combatOverview) ?? {
    name: props.actor.name,
    roleLabel: props.actor.item.actorType === 'kp' ? 'NPC' : '调查员',
    statuses: [],
    metrics: [],
  }
}

function actorExplorationCard(): TrpgExplorationHoverCard | null {
  return buildExplorationHoverCard(props.actor, props.investigatorCards)
}

function investigatorCardId(): number | null {
  return props.sceneKind === 'combat'
    ? combatInvestigatorCardId(props.actor, props.combatOverview)
    : explorationInvestigatorCardId(props.actor, props.investigatorCards)
}

function openCard() {
  const cardId = investigatorCardId()
  if (cardId != null) emit('openCard', cardId)
}

function hasOverview(): boolean {
  return props.sceneKind === 'combat' || actorExplorationCard() != null
}

function overviewName(): string {
  return props.sceneKind === 'combat'
    ? actorCombatCard().name
    : actorExplorationCard()?.name ?? props.actor.name
}

function overviewRole(): string {
  return props.sceneKind === 'combat'
    ? actorCombatCard().roleLabel
    : actorExplorationCard()?.roleLabel ?? '调查员'
}

function overviewStatuses(): string[] {
  return props.sceneKind === 'combat'
    ? actorCombatCard().statuses
    : actorExplorationCard()?.statuses ?? []
}

function explorationMetrics(
  group: 'resources' | 'commonChecks' | 'specialtyChecks',
): TrpgExplorationMetric[] {
  return actorExplorationCard()?.[group] ?? []
}
</script>

<template>
  <TooltipRoot v-if="hasOverview()">
    <TooltipTrigger as-child>
      <component
        :is="investigatorCardId() == null ? 'div' : 'button'"
        class="trpg-actor-row"
        :class="[rowStatus(), { 'card-link': investigatorCardId() != null }]"
        :type="investigatorCardId() == null ? undefined : 'button'"
        :aria-label="rowAriaLabel()"
        tabindex="0"
        @click="openCard"
      >
        <span class="trpg-actor-name">{{ actor.name }}</span>
        <span class="trpg-status-icon" aria-hidden="true">
          <small v-if="rowStatusLabel()" :class="displayState === 'active' ? 'trpg-active-label' : 'trpg-row-state-label'">{{ rowStatusLabel() }}</small>
          <component :is="actorIcon()" v-if="actorIcon()" :size="13" :stroke-width="1.8" />
        </span>
      </component>
    </TooltipTrigger>
    <TooltipPortal>
      <TooltipContent class="trpg-combat-overview-tooltip" side="left" :side-offset="10">
        <header>
          <span><strong>{{ overviewName() }}</strong><small>{{ overviewRole() }}</small></span>
          <em>{{ actor.statusLabel }}</em>
        </header>
        <dl v-if="sceneKind === 'combat' && actorCombatCard().metrics.length" class="trpg-combat-overview-metrics">
          <div v-for="metric in actorCombatCard().metrics" :key="metric.label">
            <dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
          </div>
        </dl>
        <template v-else-if="sceneKind !== 'combat'">
          <dl class="trpg-combat-overview-metrics">
            <div v-for="metric in explorationMetrics('resources')" :key="metric.label">
              <dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
            </div>
          </dl>
          <section class="trpg-exploration-overview-section">
            <small>常用调查</small>
            <dl class="trpg-combat-overview-metrics">
              <div v-for="metric in explorationMetrics('commonChecks')" :key="metric.label">
                <dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
              </div>
            </dl>
          </section>
          <section v-if="explorationMetrics('specialtyChecks').length" class="trpg-exploration-overview-section">
            <small>擅长领域</small>
            <dl class="trpg-combat-overview-metrics">
              <div v-for="metric in explorationMetrics('specialtyChecks')" :key="metric.label">
                <dt :title="metric.label">{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
              </div>
            </dl>
          </section>
        </template>
        <section class="trpg-combat-overview-statuses">
          <small>当前状态</small>
          <div v-if="overviewStatuses().length">
            <span v-for="status in overviewStatuses()" :key="status">{{ status }}</span>
          </div>
          <p v-else>未见特殊状态</p>
        </section>
      </TooltipContent>
    </TooltipPortal>
  </TooltipRoot>
  <div v-else class="trpg-actor-row" :class="rowStatus()" :title="actor.statusLabel" :aria-label="rowAriaLabel()">
    <span class="trpg-actor-name">{{ actor.name }}</span>
    <span class="trpg-status-icon" aria-hidden="true">
      <small v-if="rowStatusLabel()" :class="displayState === 'active' ? 'trpg-active-label' : 'trpg-row-state-label'">{{ rowStatusLabel() }}</small>
      <component :is="actorIcon()" v-if="actorIcon()" :size="13" :stroke-width="1.8" />
    </span>
  </div>
</template>
