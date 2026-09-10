<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ArrowDown, Dices, LoaderCircle, RotateCcw } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import DiceModifierNotice from './DiceModifierNotice.vue'
import { watchMobileDicePreparation } from './mobileDiceLifecycle'
import { createCountdownController } from '@/components/trpgTurnExperiments'
import {
  createDiceAutoPlayPlan, createDiceGroupResultDisplay, createDiceModifierNotice,
  createDicePlayerPreparedResult, createDicePlayerStatus, createDicePlayerSummary,
  resolveDicePlayerMode, shouldShowDiceContinueAction, shouldShowDiceRollAction,
  type DicePlaybackGroupPresentation, type DicePlaybackRequest, type DicePlayerPhase,
} from '@/dice/domain/dicePlayback'
import { createMobileDiceRevealSteps } from '@/dice/domain/mobileDicePlayback'
import { formatDiceGroupLabel } from '@/dice/domain/diceGroupLabel'
import { scrollDiceRowIntoView } from '@/dice/domain/diceGroupMerge'
import { createDiceOutcomeVfxLayout, mergeDiceOutcomeVfxRects } from '@/dice/domain/dicePlayerLayout'
import { DiceRollAudioController, DiceOutcomeAudioController } from '@/dice/audio/diceAudio'
import rollUrl from '@/dice/assets/audio/dice_roll.mp3'
import winUrl from '@/dice/assets/audio/dice_win.mp3'
import loseUrl from '@/dice/assets/audio/dice_lose.mp3'
import superWinUrl from '@/dice/assets/audio/dice_superwin.mp3'
import superLoseUrl from '@/dice/assets/audio/dice_superlose.mp3'
import type { DiceRollResult, ThreeDiceBoard } from '@/dice/renderer/ThreeDice'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ request: DicePlaybackRequest | null; showContinue?: boolean; autoContinue?: boolean }>()
const emit = defineEmits<{ roll: []; complete: []; continue: []; cancelAutoContinue: [] }>()
const stageScroll = ref<HTMLElement | null>(null)
const surface = ref<HTMLElement | null>(null)
const tray = ref<HTMLElement | null>(null)
const renderLayer = ref<HTMLElement | null>(null)
const status = ref<DicePlayerPhase>('loading')
const flowPhase = ref<'rolling' | 'revealing'>('rolling')
const error = ref('')
const activeModule = ref(0)
const follow = ref(true)
const finished = ref<number[]>([])
const mounts = shallowRef<Array<{ module: number; header: HTMLElement; details: HTMLElement }>>([])
interface OutcomeEffect {
  id: string
  tone: 'critical-success' | 'fumble'
  style: Record<string, string>
  particles: Array<Record<string, string>>
}
const outcomeEffects = ref<OutcomeEffect[]>([])
let board: ThreeDiceBoard | undefined
let disposeSharedRenderer: (() => void) | undefined
let generation = 0
let scrollController: AbortController | undefined
let continuingFromAction = false
const continueCountdown = ref(0)
const countdown = createCountdownController({
  seconds: 3,
  onTick: value => { continueCountdown.value = value },
  onComplete: () => handleContinueAction(),
})
const diceAudio = new DiceRollAudioController({ url: rollUrl })
const outcomeAudio = new DiceOutcomeAudioController({ urls: {
  success: winUrl, failure: loseUrl, 'critical-success': superWinUrl, fumble: superLoseUrl,
} })
const modules = computed(() => props.request?.result.modules || [])
const rowCount = computed(() => modules.value.reduce((count, module) => count + Math.ceil(module.dice.length / 2), 0))
const records = computed<DicePlaybackGroupPresentation[]>(() => props.request?.presentation?.groups.length
  ? props.request.presentation.groups
  : modules.value.length ? [{ label: '', checkName: props.request?.result.formula || '', moduleStart: 0,
    moduleCount: modules.value.length, outcomeLabel: '最终结果', outcomeTone: 'none', success: true }] : [])
const originalSummary = computed(() => props.request
  ? createDicePlayerSummary(props.request.result, props.request.skin, props.request.presentation) : null)
const skinLabel = computed(() => originalSummary.value?.skinLabel || '')
const presentation = computed(() => createDicePlayerStatus(status.value))
const modifierNotice = computed(() => createDiceModifierNotice(props.request?.presentation))
const specialOutcome = computed(() => props.request?.presentation?.kind === 'opposed-check'
  || ['ANY_SUCCESS', 'ALL_SUCCESS'].includes(props.request?.presentation?.groupRule || ''))
const stateLabel = computed(() => status.value === 'complete' ? '全部展示完成'
  : status.value === 'playing' ? flowPhase.value === 'rolling' ? '所有骰子同时投掷中' : `正在展示第 ${activeModule.value + 1} 组结果`
    : presentation.value.label)
const showRollAction = computed(() => props.request
  && shouldShowDiceRollAction(status.value, props.request.result, props.request.completionAction))
const showContinueAction = computed(() => shouldShowDiceContinueAction(status.value, props.request?.completionAction, props.showContinue === true))
const continueActionLabel = computed(() => props.autoContinue && continueCountdown.value > 0 ? `继续（${continueCountdown.value}s）` : '继续')
const groupAt = (index: number) => records.value.find(group => index >= group.moduleStart && index < group.moduleStart + group.moduleCount)
const endsAt = (index: number) => records.value.filter(group => group.moduleStart + group.moduleCount - 1 === index)
const isRevealed = (group: DicePlaybackGroupPresentation) => Array.from({ length: group.moduleCount }, (_, index) => group.moduleStart + index).every(index => finished.value.includes(index))
const scopeLabel = (group: DicePlaybackGroupPresentation) => formatDiceGroupLabel(group.moduleStart, group.moduleCount)
const originalDisplay = (group: DicePlaybackGroupPresentation) => createDiceGroupResultDisplay(group,
  originalSummary.value?.groups[records.value.indexOf(group)]?.result ?? '—', isRevealed(group))
const wait = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms))

async function focusResult(moduleIndex: number) {
  const target = mounts.value.find(host => host.module === moduleIndex)?.details
  if (!follow.value || !stageScroll.value || !target) return
  scrollController?.abort()
  scrollController = new AbortController()
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  scrollDiceRowIntoView(stageScroll.value, target, { durationMs: 420, reducedMotion, signal: scrollController.signal })
  if (!reducedMotion) await wait(450)
  board?.refreshLayout()
}
function pauseFollow() {
  if (status.value !== 'playing') return
  follow.value = false
  scrollController?.abort()
}
function pauseForKeyboard(event: KeyboardEvent) {
  if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) pauseFollow()
}
async function resumeFollow() {
  follow.value = true
  if (flowPhase.value === 'revealing') await focusResult(activeModule.value)
}
function buildRows() {
  if (!tray.value) return
  const elements = Array.from(tray.value.querySelectorAll<HTMLElement>(':scope > .dice-module'))
  mounts.value = elements.map((module, moduleIndex) => {
    const dice = Array.from(module.querySelectorAll<HTMLElement>('.die-slot:not(.die-slot-placeholder)'))
    const header = document.createElement('div')
    header.className = 'mobile-group-heading'
    const rows = document.createElement('div')
    rows.className = 'mobile-group-rows'
    for (let index = 0; index < dice.length; index += 2) {
      const row = document.createElement('div')
      row.className = 'dice-player-row'
      row.dataset.module = String(moduleIndex)
      dice.slice(index, index + 2).forEach((die, localIndex) => {
        const label = document.createElement('span')
        label.className = 'mobile-die-caption'
        label.textContent = die.classList.contains('percentile-ones-die') ? '个位'
          : die.classList.contains('percentile-tens-die') ? `十位${dice.length > 2 ? ` ${index + localIndex + 1}` : ''}`
            : `D${modules.value[moduleIndex]?.diceSides} · 第 ${index + localIndex + 1} 颗`
        die.append(label)
        row.append(die)
      })
      rows.append(row)
    }
    const details = document.createElement('div')
    details.className = 'mobile-inline-host'
    module.replaceChildren(header, rows, details)
    module.dataset.module = String(moduleIndex)
    return { module: moduleIndex, header, details }
  })
  board?.refreshLayout()
}
function playSpecial(group: DicePlaybackGroupPresentation, moduleIndex: number) {
  if ((group.outcomeTone !== 'critical-success' && group.outcomeTone !== 'fumble') || !surface.value) return
  // Anchor to the last visible dice row, excluding the long group header and result block.
  const row = tray.value?.querySelectorAll<HTMLElement>(`.dice-player-row[data-module="${moduleIndex}"]`).item(
    (tray.value?.querySelectorAll(`.dice-player-row[data-module="${moduleIndex}"]`).length || 1) - 1,
  )
  if (!row) return
  const rect = mergeDiceOutcomeVfxRects(Array.from(row.querySelectorAll<HTMLElement>('.three-die-viewport')).map(element => element.getBoundingClientRect()))
  if (!rect) return
  const layout = createDiceOutcomeVfxLayout('local', rect, surface.value.getBoundingClientRect())
  const vectors = [[-.43, -.08, .23, 0], [-.31, -.34, .19, 55], [-.06, -.43, .24, 90], [.24, -.36, .18, 35], [.44, -.12, .22, 115], [.36, .19, .2, 70], [.08, .34, .25, 130], [-.27, .27, .21, 80], [.02, -.2, .16, 150]]
  outcomeEffects.value = [{ id: `${generation}-${moduleIndex}`, tone: group.outcomeTone,
    style: { '--vfx-left': `${layout.leftPx}px`, '--vfx-top': `${layout.topPx}px`, '--vfx-size': `${layout.sizePx}px` },
    particles: vectors.map(vector => ({ '--fog-x': `${vector[0]! * layout.sizePx}px`, '--fog-y': `${vector[1]! * layout.sizePx}px`,
      '--fog-size': `${vector[2]! * layout.sizePx}px`, '--fog-delay': `${vector[3]}ms` })),
  }]
}
function retire() {
  generation++
  scrollController?.abort()
  board?.dispose()
  board = undefined
  diceAudio.stop()
  outcomeAudio.stop()
  outcomeEffects.value = []
  countdown.cancel()
  continueCountdown.value = 0
}
async function prepare() {
  if (!open.value) return
  retire()
  const token = generation
  const request = props.request
  if (!request) return
  status.value = 'loading'
  finished.value = []
  mounts.value = []
  error.value = ''
  follow.value = true
  flowPhase.value = 'rolling'
  activeModule.value = 0
  open.value = true
  await nextTick()
  if (!tray.value || !renderLayer.value || token !== generation) return
  try {
    const renderer = await import('@/dice/renderer/ThreeDice')
    if (token !== generation) return
    disposeSharedRenderer = renderer.disposeSharedDiceRenderer
    board = new renderer.ThreeDiceBoard(tray.value, renderLayer.value)
    board.setSkin(request.skin)
    const prepared = createDicePlayerPreparedResult(request) as DiceRollResult
    await board.prepareResult(prepared)
    if (token !== generation) return
    const mode = resolveDicePlayerMode(request)
    // A value-only result may rebuild its placeholder DOM in showResult.
    if (mode === 'settled') {
      await board.showResult(prepared)
      if (token !== generation) return
    }
    buildRows()
    await nextTick()
    board.refreshLayout()
    if (stageScroll.value) stageScroll.value.scrollTop = 0
    if (mode === 'settled') {
      finished.value = modules.value.map((_, index) => index)
      status.value = 'complete'
      return
    }
    if (mode === 'pending') { status.value = 'ready'; return }
    const plan = createDiceAutoPlayPlan(request)
    status.value = plan.phase
    if (request.autoPlay) {
      if (plan.delayMs) await wait(plan.delayMs)
      if (token !== generation) return
      status.value = 'ready'
      await play()
    }
  } catch (cause) {
    if (token === generation) { status.value = 'error'; error.value = cause instanceof Error ? cause.message : '骰子动画播放失败' }
  }
}
async function play() {
  const request = props.request
  if (!board || !request || !['ready', 'complete'].includes(status.value)) return
  const replay = status.value === 'complete'
  const token = ++generation
  countdown.cancel()
  continueCountdown.value = 0
  status.value = 'playing'
  finished.value = []
  follow.value = true
  outcomeEffects.value = []
  flowPhase.value = 'rolling'
  activeModule.value = 0
  outcomeAudio.stop()
  try {
    if (replay) {
      mounts.value = []
      await nextTick()
      await board.prepareResult(request.result as DiceRollResult)
      if (token !== generation) return
      buildRows()
      await nextTick()
    }
    if (stageScroll.value) stageScroll.value.scrollTop = 0
    diceAudio.play()
    await board.playResult(request.result as DiceRollResult, undefined, { simultaneous: true })
    if (token !== generation) return
    diceAudio.stop()
    flowPhase.value = 'revealing'
    const steps = createMobileDiceRevealSteps(modules.value.length, records.value)
    for (const step of steps) {
      if (token !== generation) return
      activeModule.value = step.moduleIndex
      finished.value = modules.value.flatMap((_, index) => index <= step.moduleIndex ? [index] : [])
      await nextTick()
      await focusResult(step.moduleIndex)
      if (token !== generation) return
      const groups = step.resultIndexes.map(index => records.value[index]!)
      const special = groups.find(group => group.outcomeTone === 'critical-success' || group.outcomeTone === 'fumble')
      if (special) playSpecial(special, step.moduleIndex)
      const tone = special?.outcomeTone || groups.find(group => group.outcomeTone !== 'none')?.outcomeTone
      if (tone && tone !== 'none') outcomeAudio.play(tone)
      await wait(special ? 1450 : 1300)
      if (token !== generation) return
      outcomeEffects.value = []
    }
    finished.value = modules.value.map((_, index) => index)
    status.value = 'complete'
    emit('complete')
  } catch (cause) {
    if (token !== generation) return
    status.value = 'error'
    error.value = cause instanceof Error ? cause.message : '骰子动画播放失败'
    diceAudio.stop()
    outcomeAudio.stop()
    outcomeEffects.value = []
  }
}
function handleRollAction() {
  if (!props.request) return
  if (status.value === 'error') { void prepare(); return }
  if (props.request.mode === 'pending') { status.value = 'loading'; emit('roll'); return }
  void play()
}
function handleContinueAction() {
  continuingFromAction = true
  countdown.cancel()
  continueCountdown.value = 0
  if (props.request?.completionAction === 'CLOSE') open.value = false
  emit('continue')
  void nextTick(() => { continuingFromAction = false })
}
watchMobileDicePreparation(() => open.value, () => props.request?.id, tray, renderLayer, () => { void prepare() })
watch(() => open.value && status.value === 'complete' && showContinueAction.value && props.autoContinue === true, eligible => {
  countdown.cancel()
  continueCountdown.value = 0
  if (eligible) countdown.start()
})
watch(open, (visible, wasVisible) => {
  if (visible) return
  const cancelled = wasVisible && props.autoContinue && !continuingFromAction
  retire()
  if (cancelled) emit('cancelAutoContinue')
})
onBeforeUnmount(() => { retire(); disposeSharedRenderer?.() })
</script>
<template>
<BaseDialog v-model="open" :title="request?.reason||'掷骰检定'" :description="`${originalSummary?.modifierLabel || '掷骰判定'} · ${modules.length} 组 · ${rowCount} 行`" size="lg" layer="foreground" content-class="dice-player-window mobile-v4-window">
 <div class="mobile-progress"><span aria-live="polite"><i/>{{stateLabel}}</span><button v-if="status==='playing'" @click="follow?pauseFollow():resumeFollow()">{{follow?'暂停跟随':'继续跟随'}}<ArrowDown :size="12"/></button><DiceModifierNotice v-else-if="modifierNotice" :notice="modifierNotice" /><span v-else>向下查看各组</span></div>
 <div ref="stageScroll" class="dice-player-stage-scroll" @wheel.passive="pauseFollow" @touchmove.passive="pauseFollow" @keydown="pauseForKeyboard" tabindex="0" aria-label="骰子与分组结果">
  <section ref="surface" class="dice-player-surface" :class="[`is-${status}`, { 'show-die-values': status === 'complete' || flowPhase === 'revealing' }]" :data-skin="request?.skin||'classic'">
   <div v-if="outcomeEffects.length" class="dice-outcome-vfx-layer" aria-hidden="true"><div v-for="e in outcomeEffects" :key="e.id" class="dice-outcome-vfx-burst" :class="`is-${e.tone}`" :style="e.style"><i class="dice-outcome-vfx-core"/><i v-for="(particle,i) in e.particles" :key="i" class="dice-outcome-vfx-fog" :style="particle"/></div></div>
   <div ref="renderLayer" class="dice-render-layer" aria-hidden="true"/><div ref="tray" class="dice-player-tray"/>
   <p v-if="error" class="mobile-error" role="alert">{{error}}</p>
  </section>
 </div>
 <div v-if="showRollAction || showContinueAction" class="mobile-v4-actions">
  <button v-if="showRollAction" class="button" :class="status === 'complete' ? 'secondary' : 'primary'" :disabled="presentation.actionDisabled" @click="handleRollAction"><LoaderCircle v-if="presentation.actionDisabled" class="spin" :size="17"/><RotateCcw v-else-if="status === 'complete' || status === 'error'" :size="17"/><Dices v-else :size="17"/>{{status === 'playing' && flowPhase === 'revealing' ? '展示结果中' : presentation.actionLabel}}</button>
  <button v-if="showContinueAction" class="button primary" @click="handleContinueAction">{{continueActionLabel}}</button>
 </div>
 <template v-for="host in mounts" :key="host.module">
  <Teleport :to="host.header"><div class="mobile-group-title"><span>第 {{host.module+1}} 组 <small>/ {{modules.length}}</small></span><strong>{{[groupAt(host.module)?.label,groupAt(host.module)?.checkName].filter(Boolean).join(' · ')}}</strong></div><div class="mobile-group-sub"><span>{{modules[host.module]?.expression}} · {{Math.ceil((modules[host.module]?.dice.length || 0)/2)}} 行</span><span v-if="(groupAt(host.module)?.moduleCount || 0)>1">{{scopeLabel(groupAt(host.module)!)}} · 详情在第 {{groupAt(host.module)!.moduleStart+groupAt(host.module)!.moduleCount}} 组后</span><span v-else>{{skinLabel}}骰面</span></div></Teleport>
  <Teleport :to="host.details">
   <template v-for="(g,gi) in endsAt(host.module)" :key="gi">
    <article class="mobile-inline-result" :class="[`tone-${isRevealed(g)?g.outcomeTone:'pending'}`,{'is-revealed':isRevealed(g)}]" :data-start="g.moduleStart" :data-end="g.moduleStart+g.moduleCount-1" aria-live="polite">
     <template v-if="request?.presentation">
      <div v-if="host.module===modules.length-1&&!specialOutcome&&gi===0" class="inline-original-context"><span>{{originalSummary?.formulaLabel}}</span><strong>{{originalSummary?.formulaValue}}</strong></div>
      <div class="inline-original-group"><div class="inline-original-person"><strong>{{g.label}}</strong><small>{{scopeLabel(g)}}</small><div><em v-if="g.difficultyLabel">{{g.difficultyLabel}}</em><span>{{g.checkName}}</span></div></div><div class="inline-result-number"><strong :class="{'is-text-value':request.presentation.kind==='value-roll'}">{{originalDisplay(g)?.value || (isRevealed(g) ? '—' : '?')}}</strong><small>{{originalDisplay(g)?.label||(!isRevealed(g)?'等待本组完成':'')}}</small></div></div>
     </template>
     <div v-else class="inline-original-numeric"><div class="inline-result-number"><small>{{originalSummary?.resultLabel}}</small><strong>{{isRevealed(g)?originalSummary?.resultValue:'?'}}</strong></div><div class="inline-original-copy"><span>{{originalSummary?.formulaLabel}}</span><h3>{{originalSummary?.formulaValue}}</h3><div><span>{{originalSummary?.moduleLabel}}</span><span>{{originalSummary?.diceLabel}}</span><span>{{originalSummary?.skinLabel}}骰面</span></div><div v-if="(originalSummary?.groups.length || 0) > 1" class="inline-numeric-groups"><span v-for="group in originalSummary?.groups" :key="group.label">{{ group.label }} · {{ group.expression }}<b v-if="isRevealed(g)"> = {{ group.result }}</b></span></div></div></div>

    </article>
   </template>
   <p v-if="!endsAt(host.module).length" class="mobile-range-note">{{finished.includes(host.module)?'本组骰子已展示。':''}}检定结果将在第 {{(groupAt(host.module)?.moduleStart || 0)+(groupAt(host.module)?.moduleCount || 0)}} 组下方显示。</p>
   <article v-if="specialOutcome&&host.module===modules.length-1" class="mobile-inline-verdict" aria-live="polite"><div class="inline-original-context"><span>{{originalSummary?.formulaLabel}}</span><strong>{{originalSummary?.formulaValue}}</strong></div><small>{{request?.presentation?.resultLabel}}</small><strong>{{finished.length===modules.length?(request?.presentation?.resultHeadline||request?.presentation?.resultValue):'等待覆盖组全部完成'}}</strong><p v-if="finished.length===modules.length">{{request?.presentation?.resultDetail||request?.presentation?.formulaValue}}</p></article>
  </Teleport>
 </template>
</BaseDialog>
</template>

<style>
/* Keep the approved dice sheet independent from shared dialog and desktop skin rules. */
@media (max-width: 767px) {
.dialog-content.mobile-v4-window[data-mobile-presentation]{height:min(92dvh,900px);max-height:min(calc(100dvh - 32px),var(--app-viewport-height,100dvh));width:100%;max-width:100%;border:0;border-radius:28px 28px 0 0;background:#fbfaf6;box-shadow:0 -16px 70px #21372d22}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dialog-header{min-height:86px;padding:14px 22px 12px;flex-shrink:0;background:transparent;border:0;gap:10px;align-items:center}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dialog-title{font-family:var(--font-ui);font-size:24px;line-height:1.4;letter-spacing:-.6px;font-weight:650;color:#263b34;overflow-wrap:anywhere}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dialog-description{font-size:12px;line-height:1.5;margin-top:7px;color:#858b7e}
.dialog-content.mobile-v4-window[data-mobile-presentation] .icon-button{width:44px;height:44px;border:0;background:#eff1e9;box-shadow:none;color:#596454}
.dialog-content.mobile-v4-window[data-mobile-presentation]::before{width:34px;height:4px;margin:12px auto 0;background:#d8ddd0}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dialog-body{flex:1;min-height:0;padding:0;overflow:hidden;display:flex;flex-direction:column;background:#fbfaf6}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-progress{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 22px;min-height:42px;flex-shrink:0;border-bottom:1px solid #e5e8df;color:#87907e;font-size:11px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-progress>span:first-child{display:flex;align-items:center;gap:6px;color:#5e7352}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-progress i{width:5px;height:5px;border-radius:50%;background:#769366}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-progress button{display:flex;gap:5px;align-items:center;min-height:44px;border:0;background:transparent;color:#607851;font-size:11px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-stage-scroll{max-height:none;flex:1;min-height:0;overflow-x:hidden;overflow-y:auto;scrollbar-gutter:stable;scrollbar-width:thin;scroll-padding:16px;touch-action:pan-y}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface{min-height:0!important;background:#fbfaf6;box-shadow:none}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-tray{display:flex;flex-direction:column;gap:28px;width:100%;min-height:0;padding:18px 20px 28px;overflow:visible}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .dice-module{min-width:0;width:100%;padding:0;border:0;border-radius:0;background:transparent;box-shadow:none;flex:none}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .dice-module::after{display:none}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-row{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));align-items:start;justify-content:stretch;gap:12px;min-width:0;width:100%;position:relative;padding:8px 0 4px;justify-items:center}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-row:has(>.die-slot:only-child){grid-template-columns:repeat(2,minmax(0,1fr))}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .die-slot{width:100%;max-width:188px;height:calc(min((100cqw - 12px)/2,188px) + 44px);min-height:0;max-height:none;border:0;padding:0}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .three-die-viewport{left:0;top:24px;width:100%;height:auto;aspect-ratio:1;max-height:none}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .die-shadow{width:86%;left:50%;height:26px;bottom:26px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .die-value{min-width:40px;min-height:27px;bottom:0;font-size:16px;font-weight:650;box-shadow:none;background:#edf2e6}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .die-slot.is-dimmed .die-value{background:#f0efeb;color:#9a9e94}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface.show-die-values .die-slot.is-settled{transform:translateY(-4px)}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface.show-die-values .die-slot.is-dimmed.is-settled{transform:translateY(-4px) scale(.94)}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-die-caption{position:absolute;left:0;right:0;top:4px;display:block;text-align:center;font-size:10px;letter-spacing:.3px;color:#91a082}
.dialog-content.mobile-v4-window[data-mobile-presentation] .is-selected .mobile-die-caption:after{content:' · 采用';color:#52794b}
.dialog-content.mobile-v4-window[data-mobile-presentation] .is-dimmed .mobile-die-caption:after{content:' · 舍弃';color:#a1a398}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-heading{padding-bottom:8px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-title{display:flex;align-items:baseline;justify-content:space-between;gap:12px;line-height:1.6}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-title>span{font-size:14px;font-weight:650;color:#36513c;white-space:nowrap}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-title small{font-size:10px;font-weight:400;color:#a1ab95}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-title strong{font-size:12px;font-weight:500;color:#627257;overflow-wrap:anywhere;text-align:right}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-sub{display:flex;justify-content:space-between;gap:12px;color:#909b84;font-size:10px;line-height:1.6;margin-top:4px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-sub>span:last-child{text-align:right}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-rows{position:relative;border-radius:16px;background:radial-gradient(ellipse at center,#edf2e6 0,#fbfaf6 72%)}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-rows>.dice-player-row+.dice-player-row{margin-top:10px;border-top:1px dashed #e2e8d9;padding-top:14px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-host{position:relative;z-index:4;padding-top:12px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-result{border-top:1px solid #dbe4d3;padding-top:14px;color:#5c6d50;position:relative}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-result+.mobile-inline-result{margin-top:20px}



.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-result-number{display:flex;flex-direction:column;align-items:flex-end;min-width:60px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-result-number strong{font-size:42px;font-weight:650;line-height:1;letter-spacing:-1.5px;color:#2e5845}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-result-number small{font-size:10px;color:#859676;margin-top:5px}



.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-range-note{font-size:11px;line-height:1.8;margin:0;padding:12px;background:#f1f3eb;border-radius:10px;color:#8a997b}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict{margin-top:16px;background:#edf2e3;border:1px solid #dce6d0;border-radius:14px;padding:15px 16px;color:#607d48}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict>small{font-size:10px;color:#8da07a}.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict>strong{display:block;margin-top:6px;font-size:22px;font-weight:600}.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict p{font-size:10px;margin:7px 0 0;line-height:1.6}.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict>div{font-size:10px;line-height:1.6;margin-top:8px;color:#94a283}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-v4-actions{display:flex;gap:12px;padding:16px 22px max(20px,env(safe-area-inset-bottom));background:#fbfaf6;border-top:1px solid #e4e8dc;flex-shrink:0;z-index:8}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-v4-actions .button{flex:1;height:50px;border-radius:14px;font-size:14px;font-weight:550;box-shadow:none;min-width:0}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-v4-actions .button.primary{background:#294f49;border:1px solid #294f49;color:#fff}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-v4-actions .button.secondary{background:transparent;border:1px solid #dce3d4;color:#627654}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-v4-actions .button:disabled{opacity:.6}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-error{margin:20px;color:#a45d5c;font-size:13px}@media(max-width:360px){.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-tray{padding-inline:16px}.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-row{gap:10px}}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-context{display:flex;flex-direction:column;gap:4px;margin-bottom:14px;color:#859477}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-context>span{font-size:10px;color:#99a48e}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-context>strong{font-size:12px;line-height:1.7;font-weight:500;color:#687b58}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-group{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:90px;padding:0 0 4px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person>strong{display:block;font-size:15px;font-weight:550;color:#485f43}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person>small{display:block;font-size:10px;color:#90a080;margin:5px 0}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person>div{display:flex;gap:6px;align-items:center;font-size:12px;color:#788b67}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person em{font-size:10px;font-style:normal;background:#edf2e6;border-radius:5px;padding:3px 5px;color:#81956e}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-group .inline-result-number>small{font-size:12px;color:#628152}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-result-number .is-text-value{font-size:28px;letter-spacing:-.5px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-failure .inline-result-number strong,.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-failure .inline-result-number small,.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-fumble .inline-result-number strong,.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-fumble .inline-result-number small{color:#a45d5c}
.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-critical-success .inline-result-number strong,.dialog-content.mobile-v4-window[data-mobile-presentation] .tone-critical-success .inline-result-number small{color:#a37d31}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-numeric{display:grid;grid-template-columns:85px minmax(0,1fr);gap:16px;align-items:center;min-height:105px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-numeric .inline-result-number{align-items:flex-start;gap:7px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-copy>span{font-size:10px;color:#94a087}.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-copy h3{font-size:24px;color:#485e42;margin:6px 0 10px;font-weight:550}.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-copy>div{display:flex;flex-wrap:wrap;gap:7px;font-size:10px;color:#8e9c81}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict .inline-original-context{margin-bottom:12px;padding-bottom:10px;border-bottom:1px solid #dce6d1}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface::before,.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface::after{display:none}
.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-group-rows{container-type:inline-size}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person{min-width:0;overflow-wrap:anywhere}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-person>div{flex-wrap:wrap}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-result-number{max-width:58%;overflow-wrap:anywhere}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-context,.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-original-copy,.dialog-content.mobile-v4-window[data-mobile-presentation] .mobile-inline-verdict{overflow-wrap:anywhere}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-numeric-groups{display:flex;flex-direction:column;margin-top:8px}
.dialog-content.mobile-v4-window[data-mobile-presentation] .inline-numeric-groups b{font-weight:500}
.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-modifier-notice-badge{min-height:44px;font-size:12px;--dice-accent:#294f49;--dice-accent-rgb:41,79,73}


@media(max-width:360px){.dialog-content.mobile-v4-window[data-mobile-presentation] .dice-player-surface .die-slot{height:calc(min((100cqw - 10px)/2,188px) + 44px)}}

}
</style>
