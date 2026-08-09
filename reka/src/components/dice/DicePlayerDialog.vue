<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CircleAlert, Dices, FastForward, LoaderCircle, RotateCcw, Swords } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import {
  createGroupOutcomeVisibility,
  createDicePlayerStatus,
  createDicePlayerSummary,
  type DicePlaybackRequest,
  type DiceGroupOutcomePhase,
  type DicePlayerPhase,
} from './diceDebugState'
import {
  createDiceGroupMergePlan,
  createDiceValueMergeTokenLayout,
} from './diceGroupMerge'
import {
  createDicePlayerLayout,
  createDicePlayerWindowWidth,
} from './dicePlayerLayout'
import type {
  DiceRollResult,
  ThreeDiceBoard,
} from '../../../../dice-lab/src/dice/ThreeDice'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ request: DicePlaybackRequest | null }>()

const tray = ref<HTMLElement | null>(null)
const status = ref<DicePlayerPhase>('idle')
const groupOutcomePhase = ref<DiceGroupOutcomePhase>('concealed')
const error = ref('')
const dialogWidthPx = ref(980)
let board: ThreeDiceBoard | undefined
let generation = 0
let valueMergeTimers: number[] = []
let groupOutcomeTimers: number[] = []

const summary = computed(() => props.request
  ? createDicePlayerSummary(props.request.result, props.request.skin, props.request.presentation)
  : null)
const presentation = computed(() => createDicePlayerStatus(status.value))
const playerLayout = computed(() => createDicePlayerLayout(props.request?.result.modules.length || 0))
const dialogContentStyle = computed(() => ({ width: `${dialogWidthPx.value}px` }))
const stageStyle = computed(() => ({ minHeight: `${playerLayout.value.stageMinHeightPx}px` }))
const dialogDescription = computed(() => summary.value
  ? `${summary.value.modifierLabel} · ${summary.value.diceLabel}`
  : '准备这次掷骰判定')
const isMultiplayerCheck = computed(() => props.request?.presentation?.kind === 'multiplayer-check')
const isOpposedCheck = computed(() => props.request?.presentation?.kind === 'opposed-check')
const hasAggregateOutcome = computed(() => isMultiplayerCheck.value || isOpposedCheck.value)
const hasOpposedWinner = computed(() => props.request?.presentation?.groups.some((group) => group.winner) === true)
const groupOutcomeVisibility = computed(() => createGroupOutcomeVisibility(groupOutcomePhase.value))
const isWinnerHighlighted = computed(() => isOpposedCheck.value && groupOutcomeVisibility.value.highlightWinner)

function clearGroupOutcomeTimers() {
  groupOutcomeTimers.forEach((timer) => window.clearTimeout(timer))
  groupOutcomeTimers = []
  groupOutcomePhase.value = 'concealed'
}

function scheduleGroupOutcomeMerge(request: DicePlaybackRequest, playGeneration: number) {
  clearGroupOutcomeTimers()
  if (!request.presentation) return
  groupOutcomePhase.value = 'individual'
  if (request.presentation.kind === 'opposed-check') {
    const highlightDelayMs = 1_100
    const mergeDelayMs = 2_500
    groupOutcomeTimers.push(window.setTimeout(() => {
      if (generation === playGeneration) groupOutcomePhase.value = 'highlighted'
    }, highlightDelayMs))
    groupOutcomeTimers.push(window.setTimeout(() => {
      if (generation === playGeneration) groupOutcomePhase.value = 'merging'
    }, mergeDelayMs))
    groupOutcomeTimers.push(window.setTimeout(() => {
      if (generation === playGeneration) groupOutcomePhase.value = 'merged'
    }, mergeDelayMs + 620))
    return
  }
  if (request.presentation.kind !== 'multiplayer-check') return
  const mergeDelayMs = 1_300 + Math.max(0, request.presentation.groups.length - 1) * 280
  groupOutcomeTimers.push(window.setTimeout(() => {
    if (generation === playGeneration) groupOutcomePhase.value = 'merging'
  }, mergeDelayMs))
  groupOutcomeTimers.push(window.setTimeout(() => {
    if (generation === playGeneration) groupOutcomePhase.value = 'merged'
  }, mergeDelayMs + 620))
}

function disposeBoard() {
  board?.dispose()
  board = undefined
}

function arrangeDiceModuleRows() {
  if (!tray.value) return
  const modules = Array.from(tray.value.querySelectorAll<HTMLElement>(':scope > .dice-module'))
  if (!modules.length) return

  const rows: HTMLElement[] = []
  let moduleIndex = 0
  playerLayout.value.rowGroupCounts.forEach((rowGroupCount, rowIndex) => {
    const row = document.createElement('div')
    row.className = 'dice-player-row'
    row.dataset.row = String(rowIndex + 1)
    row.append(...modules.slice(moduleIndex, moduleIndex + rowGroupCount))
    rows.push(row)
    moduleIndex += rowGroupCount
  })
  tray.value.replaceChildren(...rows)
  dialogWidthPx.value = createDicePlayerWindowWidth(rows.map((row) => row.scrollWidth))
}

function clearDiceValueMergeTimers() {
  valueMergeTimers.forEach((timer) => window.clearTimeout(timer))
  valueMergeTimers = []
  tray.value?.querySelectorAll('.dice-value-merge-token').forEach((token) => token.remove())
  tray.value?.querySelectorAll('.die-value.is-merge-source').forEach((value) => {
    value.classList.remove('is-merge-source')
  })
  tray.value?.querySelectorAll('.dice-module.is-merging-values').forEach((module) => {
    module.classList.remove('is-merging-values')
  })
}

function retireBoard() {
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  if (!board) return
  const staleBoard = board
  board = undefined
  generation += 1
  const diceCount = props.request?.result.modules.reduce((total, module) => total + module.dice.length, 0) || 1
  window.setTimeout(() => staleBoard.dispose(), 4_100 + Math.max(0, diceCount - 1) * 90)
}

function beginDiceValueMerge(
  moduleElement: HTMLElement,
  values: HTMLElement[],
  groupCenter: number,
  moveDurationMs: number,
  fadeDelayMs: number,
  playGeneration: number,
) {
  if (generation !== playGeneration || !moduleElement.isConnected) return
  const moduleRect = moduleElement.getBoundingClientRect()
  const tokens = values.map((value) => {
    const layout = createDiceValueMergeTokenLayout(value.getBoundingClientRect(), moduleRect, groupCenter)
    const token = document.createElement('span')
    token.className = 'dice-value-merge-token'
    token.textContent = value.textContent
    token.style.left = `${layout.left}px`
    token.style.top = `${layout.top}px`
    token.style.setProperty('--group-merge-x', `${layout.offsetX}px`)
    token.style.setProperty('--value-merge-duration', `${moveDurationMs}ms`)
    token.style.setProperty('--value-fade-delay', `${fadeDelayMs}ms`)
    moduleElement.append(token)
    value.classList.add('is-merge-source')
    return token
  })
  moduleElement.classList.add('is-merging-values')
  window.requestAnimationFrame(() => {
    if (generation !== playGeneration) return
    tokens.forEach((token) => token.classList.add('is-moving'))
  })
}

function prepareDiceValueMerges(request: DicePlaybackRequest, playGeneration: number) {
  if (!tray.value) return
  clearDiceValueMergeTimers()
  const modules = Array.from(tray.value.querySelectorAll<HTMLElement>('.dice-module'))
  modules.forEach((moduleElement, groupIndex) => {
    const values = Array.from(moduleElement.querySelectorAll<HTMLElement>('.die-value'))
    const mergeTarget = moduleElement.querySelector<HTMLElement>('.dice-row, .percentile-roll')
    const targetRect = (mergeTarget || moduleElement).getBoundingClientRect()
    const groupCenter = targetRect.left + targetRect.width / 2
    const valueCenters = values.map((value) => {
      const rect = value.getBoundingClientRect()
      return rect.left + rect.width / 2
    })
    const plan = createDiceGroupMergePlan(valueCenters, groupCenter, groupIndex)
    const groupResult = request.result.modules[groupIndex]?.result

    moduleElement.dataset.mergeTotal = Number.isFinite(groupResult) ? String(groupResult) : '—'
    valueMergeTimers.push(window.setTimeout(() => {
      beginDiceValueMerge(
        moduleElement,
        values,
        groupCenter,
        plan.moveDurationMs,
        plan.fadeDelayMs,
        playGeneration,
      )
    }, plan.delayMs))
  })
}

async function prepare(request: DicePlaybackRequest) {
  const currentGeneration = ++generation
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  dialogWidthPx.value = 980
  open.value = true
  status.value = 'loading'
  error.value = ''
  await nextTick()
  if (!tray.value || currentGeneration !== generation) return

  try {
    if (!board) {
      const { ThreeDiceBoard: DiceBoard } = await import('../../../../dice-lab/src/dice/ThreeDice')
      if (currentGeneration !== generation) return
      board = new DiceBoard(tray.value)
    }
    const activeBoard = board
    activeBoard.setSkin(request.skin)
    const playableResult = request.result as DiceRollResult
    await activeBoard.prepareResult(playableResult)
    if (currentGeneration !== generation) return
    arrangeDiceModuleRows()
    status.value = 'ready'
  } catch (cause) {
    if (currentGeneration !== generation) return
    status.value = 'error'
    error.value = cause instanceof Error ? cause.message : '骰子动画播放失败'
  }
}

async function roll() {
  const request = props.request
  if (!request || !board || (status.value !== 'ready' && status.value !== 'complete')) return
  const currentGeneration = ++generation
  const needsPreparation = status.value === 'complete'
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  status.value = 'playing'
  error.value = ''

  try {
    const playableResult = request.result as DiceRollResult
    if (needsPreparation) {
      await board.prepareResult(playableResult)
      if (currentGeneration !== generation) return
      arrangeDiceModuleRows()
    }
    await board.playResult(playableResult)
    if (currentGeneration !== generation) return
    prepareDiceValueMerges(request, currentGeneration)
    status.value = 'complete'
    scheduleGroupOutcomeMerge(request, currentGeneration)
  } catch (cause) {
    if (currentGeneration !== generation) return
    status.value = 'error'
    clearGroupOutcomeTimers()
    error.value = cause instanceof Error ? cause.message : '骰子动画播放失败'
  }
}

function handleRollAction() {
  if (!props.request) return
  if (status.value === 'error') {
    void prepare(props.request)
    return
  }
  void roll()
}

watch(() => props.request?.id, () => {
  if (props.request) void prepare(props.request)
}, { immediate: true })
watch(open, (visible) => {
  if (!visible) retireBoard()
})

onBeforeUnmount(() => {
  generation += 1
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  disposeBoard()
})
</script>

<template>
  <BaseDialog
    v-model="open"
    :title="request?.reason || '掷骰判定'"
    :description="dialogDescription"
    size="lg"
    content-class="dice-player-window"
    :content-style="dialogContentStyle"
  >
    <section
      class="dice-player-surface"
      :class="[
        `is-${status}`,
        {
          'show-die-values': presentation.showDieValues,
        },
      ]"
      :style="stageStyle"
      :data-skin="request?.skin || 'classic'"
      :data-layout-columns="playerLayout.columns"
      :data-layout-rows="playerLayout.rows"
    >
      <div v-if="summary" class="dice-player-stage-bar">
        <div class="dice-player-state" aria-live="polite">
          <i aria-hidden="true" />
          <span>
            <strong>{{ presentation.label }}</strong>
            <small>{{ presentation.hint }}</small>
          </span>
        </div>
        <span class="dice-player-modifier">{{ summary.modifierLabel }}</span>
      </div>
      <div ref="tray" class="dice-player-tray" />
      <div v-if="summary" class="dice-player-selection">
        <span><i class="selected" />计入结果</span>
        <span v-if="summary.selectionLabel.includes('舍弃')"><i class="discarded" />未采用</span>
        <b>{{ summary.selectionLabel }}</b>
      </div>
      <div v-if="status === 'error'" class="dice-player-error" role="alert">
        <CircleAlert :size="18" />
        <div><strong>没有完成这次播放</strong><span>{{ error }}</span></div>
      </div>
    </section>
    <footer
      v-if="request && summary"
      class="dice-player-result"
      :class="{
        'has-semantic-result': request.presentation && !hasAggregateOutcome,
        'has-group-outcome': hasAggregateOutcome,
        'has-opposed-outcome': isOpposedCheck,
      }"
      aria-live="polite"
    >
      <div
        v-if="hasAggregateOutcome && request.presentation"
        class="dice-group-outcome-flow"
        :class="[`is-${groupOutcomePhase}`, { 'is-opposed': isOpposedCheck }]"
      >
        <div class="dice-group-outcome-heading">
          <span>{{ summary.formulaLabel }}</span>
          <strong>{{ summary.formulaValue }}</strong>
        </div>
        <div v-if="groupOutcomeVisibility.showIndividuals" class="dice-group-outcome-track">
          <div class="dice-group-result-boxes">
            <template v-for="(group, index) in summary.groups" :key="group.label">
              <article
                class="dice-group-result-box"
                :class="[
                  groupOutcomeVisibility.revealIndividualResults
                    ? request.presentation.groups[index]?.success ? 'is-success' : 'is-failure'
                    : 'is-concealed',
                  {
                    'is-winner': isWinnerHighlighted && request.presentation.groups[index]?.winner,
                    'is-loser': isWinnerHighlighted && hasOpposedWinner && !request.presentation.groups[index]?.winner,
                  },
                ]"
              >
                <span><strong>{{ group.label }}</strong><small>{{ group.expression }}</small></span>
                <b v-if="groupOutcomeVisibility.revealIndividualResults">{{ group.result }}</b>
              </article>
              <div
                v-if="isOpposedCheck && index < summary.groups.length - 1"
                class="dice-opposed-versus"
                aria-label="对抗"
              >
                <i aria-hidden="true" />
                <Swords :size="16" :stroke-width="1.7" aria-hidden="true" />
                <i aria-hidden="true" />
              </div>
            </template>
          </div>
          <div
            v-if="groupOutcomeVisibility.showTransition"
            class="dice-group-merge-arrow"
            aria-label="汇总为最终结果"
          >
            <FastForward :size="21" :stroke-width="1.7" aria-hidden="true" />
          </div>
          <div
            v-if="groupOutcomeVisibility.showFinal"
            class="dice-group-final-box"
            :class="isOpposedCheck ? 'is-opposed' : summary.resultValue === '成功' ? 'is-success' : 'is-failure'"
          >
            <span>{{ summary.resultLabel }}</span>
            <strong>{{ summary.resultValue }}</strong>
          </div>
        </div>
        <div v-else class="dice-group-result-placeholder">
          <span>{{ summary.resultLabel }}</span>
          <strong>?</strong>
        </div>
      </div>
      <template v-else>
        <div
          class="dice-player-score"
          :class="{
            'is-concealed': !presentation.revealResult,
            'is-semantic': request.presentation,
          }"
        >
          <span>{{ summary.resultLabel }}</span>
          <strong>{{ presentation.revealResult ? summary.resultValue : '?' }}</strong>
        </div>
        <div class="dice-player-result-copy">
          <span>{{ summary.formulaLabel }}</span>
          <h3>{{ summary.formulaValue }}</h3>
          <div class="dice-player-result-meta">
            <template v-if="summary.groups.length > 1">
              <span
                v-for="group in summary.groups"
                :key="group.label"
                class="dice-player-group-result"
              >
                <small>{{ group.label }}</small>
                <b>{{ group.expression }}</b>
                <em v-if="presentation.revealResult">{{ group.result }}</em>
              </span>
            </template>
            <span v-else>{{ summary.moduleLabel }}</span>
            <span>{{ summary.diceLabel }}</span>
            <span>{{ summary.skinLabel }}骰面</span>
          </div>
        </div>
      </template>
      <button
        class="button secondary dice-player-replay"
        type="button"
        :disabled="presentation.actionDisabled"
        @click="handleRollAction"
      >
        <LoaderCircle v-if="presentation.actionDisabled" class="spin" :size="15" />
        <RotateCcw v-else-if="status === 'complete' || status === 'error'" :size="15" />
        <Dices v-else :size="15" />
        {{ presentation.actionLabel }}
      </button>
    </footer>
  </BaseDialog>
</template>
