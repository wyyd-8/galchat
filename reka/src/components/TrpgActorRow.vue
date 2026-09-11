<script setup lang="ts">
import {
  Check, ChevronDown, CircleDot, Dices, MessageCircle, Minus, Pause, UserRound, X,
} from '@lucide/vue'
import type { Component } from 'vue'
import {
  CollapsibleContent, CollapsibleRoot, CollapsibleTrigger, TooltipContent, TooltipPortal, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import { useMobileViewport } from '@/composables/useMobileViewport'
import type { InvestigatorCardSummary, TrpgCombatParticipantOverview } from '../api/types'
import { buildCombatHoverCard, combatInvestigatorCardId, type TrpgCombatHoverCard } from './trpgCombatOverview'
import { buildExplorationHoverCard, explorationInvestigatorCardId, type TrpgExplorationHoverCard, type TrpgExplorationMetric } from './trpgExplorationOverview'
import { sceneModelTarget } from './mobileActorModel'
import type { TrpgExecutionActor, TrpgExecutionScene } from './trpgExecutionState'

const props = withDefaults(defineProps<{
  actor: TrpgExecutionActor
  sceneKind: TrpgExecutionScene['kind']
  combatOverview: TrpgCombatParticipantOverview[]
  investigatorCards?: InvestigatorCardSummary[]
  displayState?: 'active' | 'waiting' | 'ready'
  playerControlled?: boolean
}>(), {
  investigatorCards: () => [],
  displayState: 'active',
  playerControlled: false,
})
const { isMobile } = useMobileViewport()
const emit = defineEmits<{ openCard: [cardId: number]; switchModel: [actor: TrpgExecutionActor] }>()

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
  return props.sceneKind === 'combat' || actorExplorationCard() != null || (isMobile.value && sceneModelTarget(props.actor.item) != null)
}

function overviewName(): string {
  return props.sceneKind === 'combat'
    ? actorCombatCard().name
    : actorExplorationCard()?.name ?? props.actor.name
}

function overviewRole(): string {
  return props.sceneKind === 'combat'
    ? actorCombatCard().roleLabel
    : actorExplorationCard()?.roleLabel ?? (props.actor.item.actorType === 'kp' ? (props.actor.genericKp ? 'KP' : 'NPC') : '调查员')
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
  <component :is="isMobile ? CollapsibleRoot : TooltipRoot" v-if="hasOverview()" :class="{ 'mobile-actor-disclosure': isMobile }">
    <component :is="isMobile ? CollapsibleTrigger : TooltipTrigger" as-child>
      <component
        :is="!isMobile && investigatorCardId() == null ? 'div' : 'button'"
        class="trpg-actor-row" :data-avatar="actor.name.slice(0, 1)"
        :class="[rowStatus(), { 'card-link': investigatorCardId() != null }]"
        :type="!isMobile && investigatorCardId() == null ? undefined : 'button'"
        :aria-label="isMobile ? `查看${actor.name}的当前状态` : rowAriaLabel()"
        tabindex="0"
        @click="!isMobile && openCard()"
      >
        <span v-if="isMobile" class="mobile-scene-avatar" aria-hidden="true">{{ actor.name.slice(0, 1) }}</span>
        <span class="trpg-actor-name">{{ actor.name }}<small v-if="isMobile" class="mobile-actor-caption">{{ playerControlled ? '由你控制 · ' : '' }}{{ displayState === 'ready' ? '已完成' : displayState === 'waiting' ? '暂不参与' : actor.statusLabel }}</small></span>
        <span v-if="!isMobile" class="trpg-actor-row-meta">
          <span v-if="playerControlled" class="trpg-player-control-badge" title="由你控制" aria-label="由你控制"><UserRound :size="11" :stroke-width="2" aria-hidden="true" /></span>
          <span class="trpg-status-icon" aria-hidden="true">
            <small v-if="rowStatusLabel()" :class="displayState === 'active' ? 'trpg-active-label' : 'trpg-row-state-label'">{{ rowStatusLabel() }}</small>
            <component :is="actorIcon()" v-if="actorIcon()" :size="13" :stroke-width="1.8" />
          </span>
        </span>
        <ChevronDown v-if="isMobile" class="mobile-actor-chevron" :size="16" aria-hidden="true" />
      </component>
    </component>
    <component :is="isMobile ? 'div' : TooltipPortal">
      <component :is="isMobile ? CollapsibleContent : TooltipContent" class="trpg-combat-overview-tooltip" :class="{ 'mobile-actor-overview': isMobile }" side="left" :side-offset="10">
        <header>
          <span><strong>{{ overviewName() }}</strong><small>{{ overviewRole() }}</small></span>
          <em>{{ actor.statusLabel }}</em>
        </header>
        <dl v-if="sceneKind === 'combat' && actorCombatCard().metrics.length" class="trpg-combat-overview-metrics">
          <div v-for="metric in actorCombatCard().metrics" :key="metric.label">
            <dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd>
          </div>
        </dl>
        <template v-else-if="sceneKind !== 'combat' && actorExplorationCard()">
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
      <button v-if="isMobile" class="button secondary" :disabled="!sceneModelTarget(actor.item)" @click="emit('switchModel', actor)">切换回复模型</button><p v-if="isMobile && !sceneModelTarget(actor.item)" class="mobile-model-unavailable">该调查员由你输入回复，无需设置模型。</p></component>
    </component>
  </component>
  <div v-else class="trpg-actor-row" :data-avatar="actor.name.slice(0, 1)" :class="rowStatus()" :title="actor.statusLabel" :aria-label="rowAriaLabel()">
    <span v-if="isMobile" class="mobile-scene-avatar" aria-hidden="true">{{ actor.name.slice(0, 1) }}</span>
        <span class="trpg-actor-name">{{ actor.name }}<small v-if="isMobile" class="mobile-actor-caption">{{ playerControlled ? '由你控制 · ' : '' }}{{ displayState === 'ready' ? '已完成' : displayState === 'waiting' ? '暂不参与' : actor.statusLabel }}</small></span>
    <span v-if="!isMobile" class="trpg-actor-row-meta">
      <span v-if="playerControlled" class="trpg-player-control-badge" title="由你控制" aria-label="由你控制"><UserRound :size="11" :stroke-width="2" aria-hidden="true" /></span>
      <span class="trpg-status-icon" aria-hidden="true">
        <small v-if="rowStatusLabel()" :class="displayState === 'active' ? 'trpg-active-label' : 'trpg-row-state-label'">{{ rowStatusLabel() }}</small>
        <component :is="actorIcon()" v-if="actorIcon()" :size="13" :stroke-width="1.8" />
      </span>
    </span>
  </div>
</template>
