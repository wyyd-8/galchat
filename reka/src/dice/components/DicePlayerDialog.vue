<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, CircleAlert, Dices, LoaderCircle, RotateCcw, Swords } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import diceCriticalSuccessUrl from '@/dice/assets/audio/dice_superwin.mp3'
import diceFailureUrl from '@/dice/assets/audio/dice_lose.mp3'
import diceRollEndUrl from '@/dice/assets/audio/dice_full.mp3'
import diceRollStartUrl from '@/dice/assets/audio/ui_dice.mp3'
import diceSuccessUrl from '@/dice/assets/audio/dice_win.mp3'
import diceFumbleUrl from '@/dice/assets/audio/dice_superlose.mp3'
import {
  DiceOutcomeAudioController,
  DiceRollAudioController,
  createSingleCheckOutcomeCuePlan,
} from '@/dice/audio/diceAudio'
import {
  createDicePlayerWindowClass,
  createDicePlayerInitialState,
  createDiceAutoPlayPlan,
  createDiceModuleOutcomeToneMap,
  createDiceOutcomeVfxPlan,
  createDicePlayerPreparedResult,
  createGroupOutcomeVisibility,
  createDicePlayerStatus,
  createDicePlayerSummary,
  resolveDicePlayerMode,
  resolveDiceAnimationGroups,
  shouldShowDiceRollAction,
  type DicePlaybackRequest,
  type DiceGroupOutcomePhase,
  type DiceOutcomeTone,
  type DicePlayerPhase,
} from '@/dice/domain/dicePlayback'
import {
  createDiceGroupMergePlan,
  createDiceGroupMergeSequencePlan,
  createDiceResultRevealPlan,
  createDiceValueMergeTokenLayout,
  scrollDiceRowIntoView,
  shouldMergeDiceModuleValues,
} from '@/dice/domain/diceGroupMerge'
import {
  createDiceOutcomeVfxLayout,
  createDicePlayerLayout,
  createDicePlayerWindowWidth,
  mergeDiceOutcomeVfxRects,
} from '@/dice/domain/dicePlayerLayout'
import { formatDiceGroupLabel } from '@/dice/domain/diceGroupLabel'
import type {
  DiceRollResult,
  ThreeDiceBoard,
} from '@/dice/renderer/ThreeDice'
import { createDicePhysicalSettleDelay } from '@/dice/renderer/rollRotation'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ request: DicePlaybackRequest | null; showContinue?: boolean }>()
const emit = defineEmits<{ roll: []; complete: []; continue: [] }>()

const tray = ref<HTMLElement | null>(null)
const surface = ref<HTMLElement | null>(null)
const stageScroll = ref<HTMLElement | null>(null)
const renderLayer = ref<HTMLElement | null>(null)
const status = ref<DicePlayerPhase>('idle')
const groupOutcomePhase = ref<DiceGroupOutcomePhase>('concealed')
const revealedDiceResultGroups = ref<boolean[]>([])
const error = ref('')
const dialogWidthPx = ref(980)
let board: ThreeDiceBoard | undefined
let disposeSharedRenderer: (() => void) | undefined
let generation = 0
let valueMergeTimers: number[] = []
let groupOutcomeTimers: number[] = []
let outcomeVfxTimers: number[] = []
let rowScrollPlayback: AbortController | undefined
const diceAudio = new DiceRollAudioController({
  startUrl: diceRollStartUrl,
  endUrl: diceRollEndUrl,
  endCueDurationMs: 1_384.49,
})
const diceOutcomeAudio = new DiceOutcomeAudioController({
  urls: {
    'critical-success': diceCriticalSuccessUrl,
    success: diceSuccessUrl,
    failure: diceFailureUrl,
    fumble: diceFumbleUrl,
  },
})

type SpecialOutcomeTone = Extract<DiceOutcomeTone, 'critical-success' | 'fumble'>
interface OutcomeVfxParticle {
  id: number
  style: Record<string, string>
}
interface OutcomeVfxView {
  id: string
  tone: SpecialOutcomeTone
  style: Record<string, string>
  particles: OutcomeVfxParticle[]
}

const outcomeEffects = ref<OutcomeVfxView[]>([])
const OUTCOME_FOG_VECTORS = [
  { x: -.43, y: -.08, size: .23, delay: 0 },
  { x: -.31, y: -.34, size: .19, delay: 55 },
  { x: -.06, y: -.43, size: .24, delay: 90 },
  { x: .24, y: -.36, size: .18, delay: 35 },
  { x: .44, y: -.12, size: .22, delay: 115 },
  { x: .36, y: .19, size: .2, delay: 70 },
  { x: .08, y: .34, size: .25, delay: 130 },
  { x: -.27, y: .27, size: .21, delay: 80 },
  { x: .02, y: -.2, size: .16, delay: 150 },
] as const

const summary = computed(() => props.request
  ? createDicePlayerSummary(props.request.result, props.request.skin, props.request.presentation)
  : null)
const presentation = computed(() => createDicePlayerStatus(status.value))
const playerLayout = computed(() => createDicePlayerLayout(props.request?.result.modules.length || 0))
const dialogContentStyle = computed(() => ({ width: `${dialogWidthPx.value}px` }))
const dialogContentClass = computed(() => createDicePlayerWindowClass(props.request || undefined))
const stageStyle = computed(() => ({ minHeight: `${playerLayout.value.stageMinHeightPx}px` }))
const dialogDescription = computed(() => summary.value
  ? `${summary.value.modifierLabel} · ${summary.value.diceLabel}`
  : '准备这次掷骰判定')
const isMultiplayerCheck = computed(() => props.request?.presentation?.kind === 'multiplayer-check')
const isOpposedCheck = computed(() => props.request?.presentation?.kind === 'opposed-check')
const isValueRoll = computed(() => props.request?.presentation?.kind === 'value-roll')
const hasAggregateOutcome = computed(() => isMultiplayerCheck.value || isOpposedCheck.value || isValueRoll.value)
const isSeparateGroupCheck = computed(() => (isMultiplayerCheck.value || isValueRoll.value)
  && props.request?.presentation?.groupRule === 'SEPARATE')
const showRollAction = computed(() => props.request
  ? shouldShowDiceRollAction(status.value, props.request.result)
  : false)
const hasOpposedWinner = computed(() => props.request?.presentation?.groups.some((group) => group.winner) === true)
const groupOutcomeVisibility = computed(() => createGroupOutcomeVisibility(
  groupOutcomePhase.value,
  props.request?.presentation?.groupRule,
))
const isWinnerHighlighted = computed(() => isOpposedCheck.value && groupOutcomeVisibility.value.highlightWinner)
const isFinalDiceResultRevealed = computed(() => {
  const groupCount = summary.value?.groups.length || 0
  return groupCount > 0
    && revealedDiceResultGroups.value.length >= groupCount
    && revealedDiceResultGroups.value.slice(0, groupCount).every(Boolean)
})

function isDiceGroupResultRevealed(index: number): boolean {
  return groupOutcomeVisibility.value.revealIndividualResults
    && revealedDiceResultGroups.value[index] === true
}

function resetDiceResultReveal(revealAll = false) {
  revealedDiceResultGroups.value = Array.from(
    { length: summary.value?.groups.length || 0 },
    () => revealAll,
  )
}

function revealDiceResultGroup(index: number, playGeneration: number) {
  if (generation !== playGeneration) return
  const next = [...revealedDiceResultGroups.value]
  next[index] = true
  revealedDiceResultGroups.value = next
}

function clearGroupOutcomeTimers() {
  groupOutcomeTimers.forEach((timer) => window.clearTimeout(timer))
  groupOutcomeTimers = []
  groupOutcomePhase.value = 'concealed'
}

function clearOutcomeVfx() {
  outcomeVfxTimers.forEach((timer) => window.clearTimeout(timer))
  outcomeVfxTimers = []
  outcomeEffects.value = []
}

function applyDiceModuleOutcomeTones(request?: DicePlaybackRequest) {
  if (!tray.value) return
  const toneMap = createDiceModuleOutcomeToneMap(request?.presentation)
  const modules = Array.from(tray.value.querySelectorAll<HTMLElement>('.dice-module'))
  modules.forEach((module, moduleIndex) => {
    const tone = toneMap[moduleIndex]
    if (tone) module.dataset.outcomeTone = tone
    else delete module.dataset.outcomeTone
  })
}

function playOutcomeVfx(request: DicePlaybackRequest, playGeneration: number) {
  clearOutcomeVfx()
  if (!surface.value || !tray.value) return
  const plans = createDiceOutcomeVfxPlan(request.presentation)
  if (!plans.length) return

  const surfaceRect = surface.value.getBoundingClientRect()
  const modules = Array.from(tray.value.querySelectorAll<HTMLElement>('.dice-module'))
  outcomeEffects.value = plans.flatMap((plan, effectIndex) => {
    const groupRect = mergeDiceOutcomeVfxRects(
      modules
        .slice(plan.moduleStart, plan.moduleStart + plan.moduleCount)
        .map((module) => module.getBoundingClientRect()),
    )
    if (!groupRect) return []
    const layout = createDiceOutcomeVfxLayout(plan.scope, groupRect, surfaceRect)
    return [{
      id: `${playGeneration}-${effectIndex}-${plan.tone}`,
      tone: plan.tone,
      style: {
        '--vfx-left': `${layout.leftPx}px`,
        '--vfx-top': `${layout.topPx}px`,
        '--vfx-size': `${layout.sizePx}px`,
      },
      particles: OUTCOME_FOG_VECTORS.map((particle, particleIndex) => ({
        id: particleIndex,
        style: {
          '--fog-x': `${particle.x * layout.sizePx}px`,
          '--fog-y': `${particle.y * layout.sizePx}px`,
          '--fog-size': `${particle.size * layout.sizePx}px`,
          '--fog-delay': `${particle.delay}ms`,
        },
      })),
    }]
  })
  if (!outcomeEffects.value.length) return
  outcomeVfxTimers.push(window.setTimeout(() => {
    if (generation === playGeneration) outcomeEffects.value = []
  }, 1_450))
}

function scheduleGroupOutcomeMerge(
  request: DicePlaybackRequest,
  playGeneration: number,
  diceMergeCompletionDelayMs: number,
) {
  clearGroupOutcomeTimers()
  if (!request.presentation) return
  groupOutcomePhase.value = 'individual'
  if (request.presentation.kind === 'opposed-check') {
    const highlightDelayMs = diceMergeCompletionDelayMs + 480
    const mergeDelayMs = diceMergeCompletionDelayMs + 1_600
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
  if (request.presentation.groupRule === 'SEPARATE') return
  const mergeDelayMs = diceMergeCompletionDelayMs + 400
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
  board?.refreshLayout()
}

function clearDiceValueMergeTimers() {
  rowScrollPlayback?.abort()
  rowScrollPlayback = undefined
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
  diceAudio.stop()
  diceOutcomeAudio.stop()
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  clearOutcomeVfx()
  applyDiceModuleOutcomeTones()
  if (!board) return
  const staleBoard = board
  board = undefined
  generation += 1
  staleBoard.dispose()
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

function focusDiceRow(row: HTMLElement, durationMs: number) {
  const scrollElement = stageScroll.value
  if (!scrollElement || !row.isConnected) return
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  rowScrollPlayback?.abort()
  rowScrollPlayback = new AbortController()
  scrollDiceRowIntoView(scrollElement, row, {
    reducedMotion,
    durationMs,
    signal: rowScrollPlayback.signal,
  })
}

function prepareDiceValueMerges(request: DicePlaybackRequest, playGeneration: number): number {
  if (!tray.value) return 0
  clearDiceValueMergeTimers()
  const modules = Array.from(tray.value.querySelectorAll<HTMLElement>('.dice-module'))
  const rows = Array.from(tray.value.querySelectorAll<HTMLElement>('.dice-player-row'))
  const mergeableGroups = modules.map((moduleElement) => shouldMergeDiceModuleValues(
    moduleElement.classList.contains('is-value-placeholder'),
  ))
  const sequence = createDiceGroupMergeSequencePlan(
    playerLayout.value.rowGroupCounts,
    mergeableGroups,
  )

  sequence.rowFocuses.forEach((focus) => {
    const row = rows[focus.rowIndex]
    if (!row) return
    valueMergeTimers.push(window.setTimeout(() => {
      if (generation === playGeneration) focusDiceRow(row, focus.durationMs)
    }, focus.delayMs))
  })

  modules.forEach((moduleElement, groupIndex) => {
    const sequenceGroup = sequence.groups[groupIndex]
    if (!sequenceGroup || !mergeableGroups[groupIndex]) return
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
    }, sequenceGroup.mergeDelayMs))
  })

  const resultGroups = request.presentation?.groups.length
    ? request.presentation.groups
    : modules.map((_, moduleStart) => ({ moduleStart, moduleCount: 1 }))
  const revealPlan = createDiceResultRevealPlan(
    resultGroups,
    sequence.groups.map((group) => group.revealDelayMs),
  )
  revealPlan.forEach((result) => {
    valueMergeTimers.push(window.setTimeout(() => {
      revealDiceResultGroup(result.resultGroupIndex, playGeneration)
    }, result.revealDelayMs))
  })
  const outcomeCuePlan = createSingleCheckOutcomeCuePlan(request.presentation, sequence.groups)
  if (outcomeCuePlan) {
    valueMergeTimers.push(window.setTimeout(() => {
      if (generation === playGeneration) diceOutcomeAudio.play(outcomeCuePlan.tone)
    }, outcomeCuePlan.delayMs))
  }
  return Math.max(
    sequence.completionDelayMs,
    ...revealPlan.map((result) => result.revealDelayMs),
  )
}

async function prepare(request: DicePlaybackRequest) {
  const currentGeneration = ++generation
  diceAudio.stop()
  diceOutcomeAudio.stop()
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  clearOutcomeVfx()
  applyDiceModuleOutcomeTones()
  dialogWidthPx.value = 980
  resetDiceResultReveal()
  open.value = true
  status.value = 'loading'
  error.value = ''
  await nextTick()
  if (!tray.value || currentGeneration !== generation) return
  if (stageScroll.value) stageScroll.value.scrollTop = 0

  try {
    const mode = resolveDicePlayerMode(request)
    const initial = createDicePlayerInitialState(
      mode,
      request.presentation?.kind,
      request.presentation?.groupRule,
    )
    groupOutcomePhase.value = initial.groupOutcomePhase
    resetDiceResultReveal(initial.groupOutcomePhase !== 'concealed')
    if (!board) {
      const rendererModule = await import('@/dice/renderer/ThreeDice')
      if (currentGeneration !== generation) return
      if (!renderLayer.value) return
      const { ThreeDiceBoard: DiceBoard } = rendererModule
      disposeSharedRenderer = rendererModule.disposeSharedDiceRenderer
      board = new DiceBoard(tray.value, renderLayer.value)
    }
    const activeBoard = board
    activeBoard.setSkin(request.skin)
    const playableResult = createDicePlayerPreparedResult(request) as DiceRollResult
    await activeBoard.prepareResult(playableResult)
    if (currentGeneration !== generation) return
    arrangeDiceModuleRows()
    if (mode === 'pending') {
      status.value = initial.phase
      return
    }
    if (mode === 'settled') {
      await activeBoard.showResult(playableResult)
      if (currentGeneration !== generation) return
      applyDiceModuleOutcomeTones(request)
      status.value = 'complete'
      return
    }
    const autoPlayPlan = createDiceAutoPlayPlan(request)
    status.value = autoPlayPlan.phase
    if (request.autoPlay) {
      if (autoPlayPlan.delayMs > 0) {
        await new Promise<void>((resolve) => window.setTimeout(resolve, autoPlayPlan.delayMs))
        if (currentGeneration !== generation) return
      }
      status.value = 'ready'
      await roll()
    }
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
  diceOutcomeAudio.stop()
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  clearOutcomeVfx()
  applyDiceModuleOutcomeTones()
  resetDiceResultReveal()
  if (stageScroll.value) stageScroll.value.scrollTop = 0
  status.value = 'playing'
  error.value = ''

  try {
    const playableResult = request.result as DiceRollResult
    if (needsPreparation) {
      await board.prepareResult(playableResult)
      if (currentGeneration !== generation) return
      arrangeDiceModuleRows()
    }
    const animationGroups = resolveDiceAnimationGroups(request, needsPreparation)
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    diceAudio.play(createDicePhysicalSettleDelay(
      playableResult.modules.map((module) => module.dice.length),
      animationGroups,
      reducedMotion,
    ))
    await board.playResult(
      playableResult,
      animationGroups,
    )
    if (currentGeneration !== generation) return
    applyDiceModuleOutcomeTones(request)
    playOutcomeVfx(request, currentGeneration)
    const diceMergeCompletionDelayMs = prepareDiceValueMerges(request, currentGeneration)
    status.value = 'complete'
    scheduleGroupOutcomeMerge(request, currentGeneration, diceMergeCompletionDelayMs)
    emit('complete')
  } catch (cause) {
    if (currentGeneration !== generation) return
    diceAudio.stop()
    diceOutcomeAudio.stop()
    status.value = 'error'
    clearGroupOutcomeTimers()
    clearOutcomeVfx()
    applyDiceModuleOutcomeTones()
    error.value = cause instanceof Error ? cause.message : '骰子动画播放失败'
  }
}

function handleRollAction() {
  if (!props.request) return
  if (status.value === 'error') {
    void prepare(props.request)
    return
  }
  if (props.request.mode === 'pending') {
    status.value = 'loading'
    emit('roll')
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
  diceAudio.stop()
  diceOutcomeAudio.stop()
  clearDiceValueMergeTimers()
  clearGroupOutcomeTimers()
  clearOutcomeVfx()
  disposeBoard()
  disposeSharedRenderer?.()
  disposeSharedRenderer = undefined
})
</script>

<template>
  <BaseDialog
    v-model="open"
    :title="request?.reason || '掷骰判定'"
    :description="dialogDescription"
    size="lg"
    layer="foreground"
    :content-class="dialogContentClass"
    :content-style="dialogContentStyle"
  >
    <div ref="stageScroll" class="dice-player-stage-scroll">
      <section
        ref="surface"
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
        <div v-if="outcomeEffects.length" class="dice-outcome-vfx-layer" aria-hidden="true">
          <div
            v-for="effect in outcomeEffects"
            :key="effect.id"
            class="dice-outcome-vfx-burst"
            :class="`is-${effect.tone}`"
            :style="effect.style"
          >
            <i class="dice-outcome-vfx-core" />
            <i
              v-for="particle in effect.particles"
              :key="particle.id"
              class="dice-outcome-vfx-fog"
              :style="particle.style"
            />
          </div>
        </div>
        <div ref="renderLayer" class="dice-render-layer" aria-hidden="true" />
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
    </div>
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
        :class="[`is-${groupOutcomePhase}`, {
          'is-opposed': isOpposedCheck,
          'is-separate': isSeparateGroupCheck,
        }]"
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
                  isDiceGroupResultRevealed(index)
                    ? isValueRoll ? 'is-value'
                      : request.presentation.groups[index]?.outcomeTone !== 'none'
                        ? `is-${request.presentation.groups[index]?.outcomeTone}`
                        : request.presentation.groups[index]?.success ? 'is-success' : 'is-failure'
                    : 'is-concealed',
                  {
                    'is-winner': isWinnerHighlighted && request.presentation.groups[index]?.winner,
                    'is-loser': isWinnerHighlighted && hasOpposedWinner && !request.presentation.groups[index]?.winner,
                  },
                ]"
              >
                <span>
                  <strong>{{ group.label }}</strong>
                  <small class="dice-group-result-number">{{ formatDiceGroupLabel(request.presentation.groups[index]!.moduleStart, request.presentation.groups[index]!.moduleCount) }}</small>
                  <small class="dice-group-check">
                    <em
                      v-if="request.presentation.groups[index]?.difficultyLabel"
                      class="dice-check-difficulty"
                      :class="`is-${request.presentation.groups[index]?.difficulty?.toLowerCase()}`"
                    >{{ request.presentation.groups[index]?.difficultyLabel }}</em>
                    <span>{{ group.expression }}</span>
                  </small>
                </span>
                <b v-if="isDiceGroupResultRevealed(index)">{{ group.result }}</b>
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
            <ArrowRight :size="21" :stroke-width="1.7" aria-hidden="true" />
          </div>
          <div
            v-if="groupOutcomeVisibility.showFinal"
            class="dice-group-final-box"
            :class="isOpposedCheck
              ? `is-${summary.resultTone || 'no-winner'}`
              : summary.resultValue === '成功' ? 'is-success' : 'is-failure'"
          >
            <span>{{ summary.resultLabel }}</span>
            <strong>{{ summary.resultHeadline || summary.resultValue }}</strong>
            <small v-if="summary.resultDetail" class="dice-group-final-detail">
              {{ summary.resultDetail }}
            </small>
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
            'is-concealed': !presentation.revealResult || !isFinalDiceResultRevealed,
            'is-semantic': request.presentation,
          }"
        >
          <span>{{ summary.resultLabel }}</span>
          <strong>{{ presentation.revealResult && isFinalDiceResultRevealed ? summary.resultValue : '?' }}</strong>
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
      <div v-if="showRollAction || showContinue" class="dice-player-actions" :class="{ 'has-continue': showContinue }">
        <button
          v-if="showRollAction"
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
        <button v-if="showContinue" class="button primary dice-player-continue" type="button" @click="emit('continue')">继续</button>
      </div>
    </footer>
  </BaseDialog>
</template>
