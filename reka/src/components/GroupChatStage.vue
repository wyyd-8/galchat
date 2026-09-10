<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type Component } from 'vue'
import { Archive, ArrowDown, ArrowLeft, ArrowUp, Check, ChevronDown, Circle, CircleDot, CircleStop, Clock3, Footprints, GripVertical, History, LoaderCircle, MessageCircleQuestion, MessageSquareText, MoreHorizontal, Pause, Pencil, Play, Plus, RotateCcw, Save, Send, Settings2, Swords, Trash2, UsersRound, X } from '@lucide/vue'
import {
  CollapsibleContent, CollapsibleRoot, CollapsibleTrigger,
  PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger,
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { Character, Conversation, CurrentTurn, DiceRollAggregate, GroupActorRuntime, GroupActorRuntimeSavePayload, GroupMessage, InvestigatorCardSummary, ModelApi, ReplyPlan, ReplyPlanItem, TrpgCombatParticipantOverview, TrpgComposerIntent, TrpgGameTimePeriod } from '@/api/types'
import BaseDialog from './ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { rememberChatReadingPosition, restoreChatReadingPosition } from './chatReadingPosition'
import { shouldSubmitChatKey } from './chatInputState'
import '@/styles/mobile-chat.css'
import DiceRollMessage from '@/dice/components/DiceRollMessage.vue'
import CombatResultMessage from './CombatResultMessage.vue'
import EpilogueMessage from './EpilogueMessage.vue'
import MaterialMessage from './MaterialMessage.vue'
import TrpgActorRoster from './TrpgActorRoster.vue'
import MobileActorModelDialog from './MobileActorModelDialog.vue'
import { replyPlanActorName, replyPlanSignature, shouldShowSavePlan, visibleReplyPlanItems } from './replyPlanState'
import { replyActorPhase, type ReplyActorPhase, type ReplyTurnState } from './replyTurnStatus'
import { syncReasoningDisclosure, type ReasoningPhase } from './reasoningDisclosure'
import { resetConversationScrollFollowing, scrollConversationToLatest, updateConversationScrollFollowing, updateReasoningScrollFollowing } from './reasoningScroll'
import { buildTrpgExecutionState, trpgTurnActionLabel, type TrpgExecutionScene, type TrpgExecutionActor } from './trpgExecutionState'
import { createCountdownController, isBetweenTrpgTurns } from './trpgTurnExperiments'

const input = defineModel<string>('input', { required: true })
const inquiryInput = defineModel<string>('inquiryInput', { default: '' })
const composerIntent = defineModel<TrpgComposerIntent>('composerIntent', { default: 'action' })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const autoAdvance = defineModel<boolean>('autoAdvance', { default: false })
const directionEnabled = defineModel<boolean>('directionEnabled', { default: false })
const investigatorDirection = defineModel<string>('investigatorDirection', { default: '' })
const props = withDefaults(defineProps<{ conversation: Conversation; username: string; messages: GroupMessage[]; reasoning: Record<number, string>; characters: Character[]; replyPlan: ReplyPlan; replyPlans: ReplyPlan[]; availableCharacters: Character[]; currentTurn: CurrentTurn | null; actorRuntimes?: GroupActorRuntime[]; modelApis?: ModelApi[]; combatOverview?: TrpgCombatParticipantOverview[]; investigatorCards?: InvestigatorCardSummary[]; replyTurnState: ReplyTurnState | null; sending: boolean; loading: boolean; hasOlderMessages: boolean; completionBusy?: boolean; completionError?: string; persistActorRuntime?: (payload: GroupActorRuntimeSavePayload) => Promise<GroupActorRuntime | undefined> }>(), {
  actorRuntimes: () => [],
  modelApis: () => [],
  combatOverview: () => [],
  investigatorCards: () => [],
})
const emit = defineEmits<{ back: []; openCompletion: []; generateCompletion: []; skipCompletion: []; savePlan: []; movePlanItem: [from: number, to: number]; deletePlanItem: [index: number]; addPlanItem: [id: number]; saveActorRuntime: [payload: GroupActorRuntimeSavePayload]; loadEarlier: []; withdraw: []; openTools: []; openCharacterCard: [cardId: number]; openDice: [aggregate: DiceRollAggregate]; send: []; askKp: []; startTurn: [investigatorDirection?: string]; selectScene: [optionNo: string]; endExploration: []; correctTime: [dayNo: number, period: TrpgGameTimePeriod]; end: [] }>()
const { isMobile } = useMobileViewport()
const panelOpen = ref(false)
const modelActor = ref<TrpgExecutionActor | null>(null)
const modelDialogOpen = computed({ get: () => modelActor.value != null, set: (value: boolean) => { if (!value) modelActor.value = null } })
watch(panelOpen, visible => { if (!visible) modelActor.value = null })
const turnSettingsOpen = ref(false)
const selectedReplyActor = ref('')
const mobileSelectedActor = computed(() => items.value.find(item => `${item.actorType}:${item.actorId}` === selectedReplyActor.value) || items.value[0])
const mobileScenes = computed(() => trpgExecution.value.scenes)
const mobileSceneName = computed(() => props.currentTurn?.sceneName
  || mobileScenes.value.flatMap(scene => [scene, ...scene.childScenes]).find(scene => scene.status === 'current')?.plan.displayName
  || '场景尚未开始')
const mobileSceneStatus = computed(() => {
  if (props.conversation.status !== 'active') return '会话已结束'
  if (['failed', 'blocked'].includes(props.currentTurn?.status || '')) return '行动受阻，返回会话后重试'
  if (props.currentTurn?.inputType === 'dice') return '等待完成掷骰'
  if (props.currentTurn?.waitingForUser) return props.currentTurn.inputType === 'selection' ? '等待选择场景' : '等待你的回应'
  if (props.sending || props.currentTurn?.status === 'running') return '行动轮进行中'
  return props.currentTurn ? '等待继续行动轮' : '等待开始下一行动轮'
})
function openScene() {
  panelOpen.value = true
}
defineExpose({ openTurnSettings: () => { turnSettingsOpen.value = true }, openScene })
const moreOpen = ref(false)
const withdrawOpen = ref(false)
const awayFromLatest = ref(false)
const readingKey = computed(() => `world:${props.conversation.userWorldId}:group:${props.conversation.id}`)
const inputElement = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
const draggedIndex = ref<number | null>(null)
const addActorId = ref('')
const planOpen = ref(true)
const reasoningOpen = reactive<Record<number, boolean>>({})
const reasoningPhase = new Map<number, ReasoningPhase>()
const lastScrollTop = ref(0)
const initialScrollPending = ref(true)
let restorationBoundary: string | null = null
const timeEditing = ref(false)
const timeForm = reactive<{ dayNo: number; period: TrpgGameTimePeriod }>({ dayNo: 1, period: 'MORNING' })
const timePeriods: Array<{ value: TrpgGameTimePeriod; label: string }> = [
  { value: 'DAWN', label: '清晨' }, { value: 'MORNING', label: '上午' },
  { value: 'NOON', label: '中午' }, { value: 'AFTERNOON', label: '下午' },
  { value: 'EVENING', label: '晚上' }, { value: 'LATE_NIGHT', label: '深夜' },
]
const planItems = computed(() => props.replyPlan.items)
const items = computed(() => visibleReplyPlanItems(props.conversation.mode, planItems.value))
const trpgExecution = computed(() => buildTrpgExecutionState(props.replyPlans, props.currentTurn))
const loadedPlanSignature = ref('')
const waitingForMessage = computed(() => props.conversation.mode !== 'trpg' || (props.currentTurn?.waitingForUser && (props.currentTurn.inputType === 'message' || props.currentTurn.inputType === 'clarification')))
const canAskKp = computed(() => props.conversation.mode === 'trpg'
  && props.currentTurn?.waitingForUser
  && props.currentTurn.inputType === 'message'
  && props.currentTurn.canAskKp === true)
const effectiveComposerIntent = computed<TrpgComposerIntent>(() =>
  composerIntent.value === 'inquiry' && canAskKp.value ? 'inquiry' : 'action')
const composerValue = computed({
  get: () => effectiveComposerIntent.value === 'inquiry' ? inquiryInput.value : input.value,
  set: (value: string) => {
    if (effectiveComposerIntent.value === 'inquiry') inquiryInput.value = value
    else input.value = value
  },
})
const actionDescription = '告诉 KP，你的调查员现在要做什么。比如走近查看、打开抽屉、与人交谈或发动攻击。'
const inquiryDescription = '请 KP 补充你此刻本就能知道的事。比如眼前有什么、距离多远，或确认刚才提到的细节。得到回答后，再决定怎么做。'
const selectionOptions = computed(() => Object.entries(props.currentTurn?.sceneOptions || {}))
const sceneProposalRole = computed(() => props.currentTurn?.waitingForUser && props.currentTurn.actionType === 'trpg_scene'
  ? (props.currentTurn.itemOrder === 1 ? 'lead' : 'contributor')
  : null)
const canEditPlan = computed(() => props.conversation.mode === 'chat' && props.replyPlan.source === 'USER')
const planLocked = computed(() => props.sending || props.conversation.status !== 'active')
const showSavePlan = computed(() => shouldShowSavePlan(canEditPlan.value, loadedPlanSignature.value, items.value))
const replyTurnPhaseLabels = { starting: '准备回复', running: '回复进行中', completed: '本轮已完成', failed: '本轮失败' } as const
const replyActorPhaseLabels: Record<ReplyActorPhase, string> = { waiting: '等待中', replying: '回复中', completed: '已完成', failed: '失败' }
const replyTurnActors = computed(() => {
  const state = props.replyTurnState
  return state ? items.value.map((item) => ({ item, phase: replyActorPhase(state, item, props.messages) })) : []
})
const emptyDescription = computed(() => props.conversation.mode === 'trpg'
  ? '先在跑团工具中确认玩家与 AI 调查员人物卡，再开始行动轮。'
  : isMobile.value ? '输入消息后，角色会按照保存的回复顺序依次回应。' : '输入消息后，角色会按照右侧保存的顺序依次回应。')
const planTitle = computed(() => props.conversation.mode === 'trpg' ? trpgExecution.value.title : '回复顺序')
const planDescription = computed(() => props.conversation.mode === 'trpg'
  ? trpgExecution.value.subtitle
  : isMobile.value ? '使用上移、下移调整回复顺序，修改后保存。' : '从上到下依次回复；拖动调整，点击移除后保存。')
const turnButtonLabel = computed(() => trpgTurnActionLabel(props.currentTurn))
const completionAvailable = computed(() => Boolean(props.conversation.completionStatus && (props.conversation.completionStatus !== 'requested' || props.currentTurn?.status === 'completed')))
const completionPending = computed(() => completionAvailable.value && props.conversation.completionStatus !== 'ready')
const summaryBusy = computed(() => props.sending || props.completionBusy)
const summaryFailed = computed(() => ['failed', 'blocked'].includes(props.currentTurn?.status || ''))
const skipSummaryDisabled = computed(() => summaryBusy.value || (!!props.currentTurn && !['completed', 'failed', 'blocked', 'cancelled'].includes(props.currentTurn.status)))
const betweenTrpgTurns = computed(() => !completionAvailable.value && props.conversation.mode === 'trpg'
  && props.conversation.status === 'active'
  && !props.loading
  && isBetweenTrpgTurns(props.currentTurn))
const turnCountdownRemaining = ref(0)
const turnCountdown = createCountdownController({
  seconds: 3,
  onTick: (remaining) => { turnCountdownRemaining.value = remaining },
  onComplete: () => requestTurnStart(),
})
const autoStartLabel = computed(() => `${Math.max(1, turnCountdownRemaining.value)}s后开始行动轮`)
const composerPlaceholder = computed(() => {
  if (props.conversation.status !== 'active') return '这个会话已经结束'
  if (effectiveComposerIntent.value === 'inquiry') return '向 KP 询问公开事实或当前可见信息……'
  if (waitingForMessage.value && sceneProposalRole.value === 'lead') return '提出一个具体、可执行的场景计划…'
  if (waitingForMessage.value && sceneProposalRole.value === 'contributor') return '回应已有计划，或提出补充与替代方案…'
  if (props.currentTurn?.inputType === 'clarification') return '回答KP；可以补充、修改或放弃原行动…'
  if (waitingForMessage.value) return props.conversation.mode === 'trpg' ? '输入玩家调查员的行动…' : '输入群聊消息…'
  if (props.currentTurn?.inputType === 'selection') return '请从上方选择调查地点'
  if (props.currentTurn?.inputType === 'dice') return '请在跑团工具中完成待处理投骰'
  return '等待当前行动轮推进'
})
let latestScrollFrame = 0

watch(() => props.messages.map((message) => `${message.id}:${message.replyStepId || 0}:${message.status}:${Boolean(message.content.trim())}:${Boolean(props.reasoning[message.id])}`).join('|'), syncReasoningState, { immediate: true, flush: 'sync' })
watch(() => props.replyPlan, (plan) => { loadedPlanSignature.value = replyPlanSignature(plan.items) }, { immediate: true, flush: 'sync' })
watch(() => props.conversation.id, () => {
  panelOpen.value = false
  turnSettingsOpen.value = false
  withdrawOpen.value = false
  selectedReplyActor.value = ''
  moreOpen.value = false
  awayFromLatest.value = false
  lastScrollTop.value = 0
  initialScrollPending.value = true
  restorationBoundary = null
  timeEditing.value = false
  if (scroller.value) resetConversationScrollFollowing(scroller.value)
}, { immediate: true })
watch(() => props.loading, (loading) => {
  if (!loading && initialScrollPending.value) restoreReadingPosition()
}, { immediate: true, flush: 'post' })
watch(() => `${props.sending}:${props.messages.map((message) => `${message.id}:${message.content.length}:${message.decisionContent?.length || 0}:${props.reasoning[message.id]?.length || 0}`).join('|')}`, () => {
  if (props.sending) scrollToLatest()
}, { flush: 'post' })
watch(() => props.sending, (sending, wasSending) => { if (!sending && wasSending) scrollToLatest() }, { flush: 'post' })
watch(
  () => betweenTrpgTurns.value && autoAdvance.value && !props.sending,
  (eligible) => {
    turnCountdown.cancel()
    turnCountdownRemaining.value = 0
    if (eligible) turnCountdown.start()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  if (scroller.value && !props.loading && !initialScrollPending.value) rememberChatReadingPosition(readingKey.value, scroller.value)
  cancelAnimationFrame(latestScrollFrame)
  turnCountdown.cancel()
})

function syncReasoningState() {
  props.messages.forEach((message) => {
    const messageId = message.id
    if (!props.reasoning[messageId]) return
    const phase = message.status === 'streaming' && !message.content.trim() ? 'thinking' : message.content.trim() ? 'main' : 'idle'
    const firstSeenOnMobile = isMobile.value && !reasoningPhase.has(messageId)
    syncReasoningDisclosure(reasoningOpen, reasoningPhase, messageId, phase)
    if (firstSeenOnMobile) reasoningOpen[messageId] = false
  })
}

function character(id?: number) { return props.characters.find((item) => item.characterId === id) }
function planCharacter(item: ReplyPlanItem) { return item.actorType === 'character' ? character(item.actorId) : undefined }
function planActorName(item: ReplyPlanItem) { return replyPlanActorName(item, props.username, planCharacter(item)?.characterName) }
function actorRuntime(item: ReplyPlanItem) {
  return props.actorRuntimes.find((runtime) => runtime.actorType === item.actorType && runtime.actorId === item.actorId)
}
function actorModelValue(item: ReplyPlanItem) {
  const runtime = actorRuntime(item)
  return runtime?.modelApiAvailable && runtime.modelApiId != null ? String(runtime.modelApiId) : ''
}
function selectActorModel(item: ReplyPlanItem, event: Event) {
  if (item.actorType !== 'character' || item.actorId == null) return
  const value = (event.target as HTMLSelectElement).value
  emit('saveActorRuntime', {
    actorType: 'character',
    actorId: item.actorId,
    controlMode: 'MODEL',
    modelApiId: value ? Number(value) : undefined,
  })
}
function drop(index: number) { if (!planLocked.value && draggedIndex.value !== null) emit('movePlanItem', draggedIndex.value, index); draggedIndex.value = null }
async function openToolsFromScene() {
  panelOpen.value = false
  await nextTick()
  emit('openTools')
}
async function openCardFromScene(cardId: number) {
  panelOpen.value = false
  await nextTick()
  emit('openCharacterCard', cardId)
}
function addActor() { if (planLocked.value) return; const id = Number(addActorId.value); if (id) { emit('addPlanItem', id); addActorId.value = '' } }
function beginTimeEdit() {
  if (!props.conversation.gameTime) return
  timeForm.dayNo = props.conversation.gameTime.dayNo
  timeForm.period = props.conversation.gameTime.period
  timeEditing.value = true
}
function submitTime() {
  if (!Number.isInteger(timeForm.dayNo) || timeForm.dayNo <= 0) return
  emit('correctTime', timeForm.dayNo, timeForm.period)
  timeEditing.value = false
}
function selectComposerIntent(intent: TrpgComposerIntent) {
  if (props.sending || (intent === 'inquiry' && !canAskKp.value)) return
  composerIntent.value = intent
}
function submitComposer() {
  if (!composerValue.value.trim() || props.sending || props.conversation.status !== 'active' || !waitingForMessage.value) return
  if (effectiveComposerIntent.value === 'inquiry') emit('askKp')
  else emit('send')
}
function requestedInvestigatorDirection(): string | undefined {
  if (!betweenTrpgTurns.value || !directionEnabled.value) return undefined
  return investigatorDirection.value.trim() || undefined
}
function requestTurnStart() {
  turnCountdown.cancel()
  turnCountdownRemaining.value = 0
  emit('startTurn', requestedInvestigatorDirection())
}
function cancelAutoAdvance() {
  turnCountdown.cancel()
  turnCountdownRemaining.value = 0
  autoAdvance.value = false
}
function messageSpeakerName(message: GroupMessage): string {
  if (message.speakerType === 'user') return '你'
  if (message.speakerType === 'narrator') return '叙事'
  if (message.speakerType === 'kp') return message.speakerName || 'KP'
  const roleName = message.speakerName || character(message.speakerId)?.characterName || '角色'
  const investigator = props.conversation.mode === 'trpg'
    ? props.investigatorCards.find((card) => card.actorType === 'BOT' && card.participantId === message.speakerId)
    : undefined
  return investigator && investigator.name !== roleName ? `${roleName} 饰演 ${investigator.name}` : roleName
}
function keydown(event: KeyboardEvent) { if (shouldSubmitChatKey(event, isMobile.value, composing.value)) { event.preventDefault(); submitComposer() } }
function restoreReadingPosition() {
  void nextTick(() => {
    cancelAnimationFrame(latestScrollFrame)
    latestScrollFrame = requestAnimationFrame(() => {
      const viewport = scroller.value
      if (!viewport || props.loading) return
      if (!restoreChatReadingPosition(readingKey.value, viewport, props.hasOlderMessages)) {
        const boundary = String(props.messages[0]?.id ?? '')
        if (boundary !== restorationBoundary) { restorationBoundary = boundary; emit('loadEarlier'); return }
        // A failed or empty history page must not start an automatic retry loop.
        restoreChatReadingPosition(readingKey.value, viewport, false)
      }
      initialScrollPending.value = false
      awayFromLatest.value = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop > 32
    })
  })
}
function returnToLatest() {
  initialScrollPending.value = false
  if (scroller.value) resetConversationScrollFollowing(scroller.value)
  awayFromLatest.value = false
  scrollToLatest()
}
watch(composerValue, () => nextTick(resizeInput))
function resizeInput() {
  const target = inputElement.value
  if (!target) return
  target.style.height = 'auto'
  target.style.height = `${Math.min(target.scrollHeight, 130)}px`
}
function scrollToLatest() {
  if (initialScrollPending.value) return
  void nextTick(() => {
    cancelAnimationFrame(latestScrollFrame)
    latestScrollFrame = requestAnimationFrame(() => {
      const viewport = scroller.value
      if (viewport) scrollConversationToLatest(viewport)
    })
  })
}
function bindScroller(element: unknown) {
  scroller.value = element instanceof HTMLElement ? element : null
  lastScrollTop.value = scroller.value?.scrollTop ?? 0
}
function sceneIcon(scene: TrpgExecutionScene): Component {
  if (scene.kind === 'combat') return Swords
  if (scene.status === 'current') return CircleDot
  if (scene.status === 'waiting-child') return Pause
  return Circle
}
function handleScroll(event: Event) {
  const viewport = event.currentTarget as HTMLElement
  updateConversationScrollFollowing(viewport)
  if (!initialScrollPending.value && !props.loading) rememberChatReadingPosition(readingKey.value, viewport)
  awayFromLatest.value = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop > 32
  const currentTop = viewport.scrollTop
  const movingUp = currentTop < lastScrollTop.value
  lastScrollTop.value = currentTop
  if (movingUp && currentTop <= 32 && props.hasOlderMessages && !props.loading) emit('loadEarlier')
}
function handleReasoningScroll(event: Event) {
  updateReasoningScrollFollowing(event.currentTarget as HTMLElement)
}
</script>

<template>
  <main class="chat-page group-chat-page">
    <header class="chat-header"><template v-if="isMobile"><button class="icon-button" aria-label="返回当前世界" @click="emit('back')"><ArrowLeft :size="23" /></button><div class="mobile-chat-title"><strong>{{ conversation.title }}</strong><small>{{ conversation.status === 'active' ? '进行中' : '已结束' }} · {{ conversation.mode === 'trpg' ? 'CoC 跑团' : '群聊' }}</small></div><button class="icon-button" :aria-label="conversation.mode === 'trpg' ? '跑团工具' : '回复顺序'" @click="conversation.mode === 'trpg' ? emit('openTools') : panelOpen = true"><span v-if="conversation.mode === 'trpg'">工具</span><MoreHorizontal v-else :size="22" /></button></template><div v-else><button class="text-button" @click="emit('back')">返回当前世界</button><h1>{{ conversation.title }}</h1></div><div v-if="!isMobile" class="chat-header-actions"><span class="live-status" :class="conversation.status"><i />{{ conversation.status === 'active' ? '进行中' : '已结束' }}</span><button v-if="conversation.mode === 'trpg'" class="button ghost" @click="emit('openTools')"><Archive :size="16" />跑团工具</button><button class="button ghost" :disabled="sending || (conversation.mode === 'trpg' && !!currentTurn && !['completed', 'failed', 'blocked', 'cancelled'].includes(currentTurn.status))" @click="emit('end')"><CircleStop :size="16" />{{ conversation.status === 'active' ? (conversation.mode === 'trpg' ? '结束跑团' : '结束群聊') : '会话操作' }}</button></div></header>

    <BaseDialog v-model="moreOpen" title="会话操作" mobile-presentation="sheet" content-class="mobile-chat-menu"><button v-if="conversation.mode === 'chat'" class="mobile-chat-row" :disabled="sending || conversation.status !== 'active'" @click="moreOpen = false; withdrawOpen = true"><span><strong>撤回上一轮</strong></span><RotateCcw :size="18" /></button><button class="mobile-chat-row" :disabled="loading || !hasOlderMessages" @click="moreOpen = false; emit('loadEarlier')"><span><strong>加载更早记录</strong></span><History :size="18" /></button><p class="mobile-chat-status">{{ conversation.status === 'active' ? '会话进行中' : '会话已结束' }}</p><button class="button secondary" @click="moreOpen = false; panelOpen = true"><UsersRound :size="18" />{{ conversation.mode === 'trpg' ? '场景与队伍' : '回复顺序' }}</button><button v-if="conversation.mode === 'trpg'" class="button secondary" @click="moreOpen = false; emit('openTools')"><Archive :size="18" />跑团工具</button><button class="button ghost" :disabled="sending || (conversation.mode === 'trpg' && !!currentTurn && !['completed', 'failed', 'blocked', 'cancelled'].includes(currentTurn.status))" @click="moreOpen = false; emit('end')"><CircleStop :size="18" />{{ conversation.status === 'active' ? (conversation.mode === 'trpg' ? '结束跑团' : '结束群聊') : '会话操作' }}</button></BaseDialog>
    <div class="chat-layout">
      <section class="chat-main">
        <div class="message-scroll"><div :ref="bindScroller" class="message-viewport" @scroll="handleScroll"><div>
          <template v-if="isMobile"><div v-if="conversation.mode === 'trpg'" class="mobile-scene-strip"><span>{{ conversation.gameTime?.displayText || '游戏时间尚未设定' }}</span><strong>{{ currentTurn?.sceneName || '场景尚未开始' }}</strong><button class="text-button" @click="openScene">场景与队伍</button></div><button v-else class="mobile-chat-row mobile-round-summary" @click="panelOpen = true"><span class="mobile-chat-avatar sand"><UsersRound :size="22" /></span><span><strong>{{ replyTurnState ? `本轮回复 · ${replyTurnActors.filter(actor => actor.phase === 'completed').length} / ${items.length}` : '回复顺序' }}</strong><small>{{ replyTurnActors.find(actor => actor.phase === 'replying') ? `${planActorName(replyTurnActors.find(actor => actor.phase === 'replying')!.item)}正在回应…` : `${items.length} 位角色 · ${replyTurnState ? replyTurnPhaseLabels[replyTurnState.phase] : '按顺序回应'}` }}</small></span><span>查看 ›</span></button></template>

          <div v-if="loading && !messages.length" class="chat-loading"><LoaderCircle class="spin" :size="22" />载入消息</div>
          <button v-else-if="hasOlderMessages" class="load-earlier-button" :disabled="loading" @click="emit('loadEarlier')"><LoaderCircle v-if="loading" class="spin" :size="14" /><History v-else :size="14" />加载更早记录</button>
          <div v-else-if="!messages.length" class="empty-chat"><MessageSquareText :size="30" /><h2>{{ conversation.mode === 'trpg' ? '跑团尚未开始' : '对话从这里开始' }}</h2><p>{{ emptyDescription }}</p></div>
          <article v-for="message in messages" :key="message.id" class="chat-message" :class="[message.speakerType, message.messageKind]" :data-message-id="message.id">
            <DiceRollMessage v-if="message.messageKind === 'dice_roll' && message.diceRoll" :aggregate="message.diceRoll" @open="emit('openDice', $event)" />
            <MaterialMessage v-else-if="message.messageKind === 'material'" :content="message.content" />
            <CombatResultMessage v-else-if="message.messageKind === 'combat_result'" :content="message.content" />
            <EpilogueMessage v-else-if="message.messageKind === 'epilogue'" :content="message.content" />
            <template v-else>
            <div v-if="message.speakerType === 'character' || (isMobile && ['kp', 'narrator'].includes(message.speakerType))" class="message-avatar" :style="character(message.speakerId)?.characterImage ? { backgroundImage: `url(${character(message.speakerId)?.characterImage})` } : {}">{{ character(message.speakerId)?.characterImage ? '' : (message.speakerType === 'kp' ? '✦' : message.speakerType === 'narrator' ? '叙' : message.speakerName || character(message.speakerId)?.characterName || '?').slice(0, 1) }}</div>
            <div class="message-content">
              <div class="message-meta"><strong>{{ messageSpeakerName(message) }}</strong><span v-if="message.status === 'streaming'" class="typing-dot">正在回应</span><span v-if="message.status === 'failed'" class="failed-label">生成失败</span></div>
              <CollapsibleRoot v-if="reasoning[message.id]" v-model:open="reasoningOpen[message.id]" class="reasoning-block">
                <CollapsibleTrigger class="reasoning-trigger">思考过程 <ChevronDown :size="14" /></CollapsibleTrigger>
                <CollapsibleContent class="reasoning-content" :data-reasoning-streaming="reasoningPhase.get(message.id) === 'thinking' ? 'true' : undefined" @scroll="handleReasoningScroll">{{ reasoning[message.id] }}</CollapsibleContent>
              </CollapsibleRoot>
              <div v-if="message.decisionContent" class="decision-block"><span>角色决策</span><p>{{ message.decisionContent }}</p></div>
              <p>{{ message.content }}<span v-if="message.status === 'streaming'" class="stream-caret" /></p>
            </div>
            </template>
          </article>
          <template v-if="isMobile && conversation.status !== 'active'"><p class="mobile-chat-notice">本次{{ conversation.mode === 'trpg' ? '跑团' : '会话' }}已经结束，你可以继续查看聊天记录{{ conversation.mode === 'trpg' ? '与调查档案' : '' }}。</p><button v-if="conversation.mode === 'trpg'" class="mobile-chat-row" @click="emit('openTools')"><span class="mobile-chat-avatar"><Archive :size="21" /></span><span><strong>跑团档案</strong><small>人物卡、掷骰与恢复点</small></span><span>›</span></button><button class="mobile-chat-row mobile-chat-danger" @click="emit('end')"><span><strong>会话管理</strong><small>查看永久删除与关联数据范围</small></span><span>›</span></button></template>
        </div></div></div>
        <button v-if="awayFromLatest" class="chat-jump-latest" @click="returnToLatest"><ArrowDown :size="16" />回到最新</button>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'selection'" class="scene-selection-panel">
          <strong>选择调查地点</strong><span>{{ currentTurn.sceneName || 'KP 已给出本轮可选地点' }}</span>
          <div class="scene-selection-options"><button v-for="[number, name] in selectionOptions" :key="number" class="button secondary" :disabled="sending" @click="emit('selectScene', number)"><b>{{ number }}</b>{{ name }}</button></div>
        </div>
        <div v-if="sceneProposalRole" class="scene-selection-panel">
          <strong>{{ sceneProposalRole === 'lead' ? '你是本轮首位提案者' : '回应本轮共同计划' }}</strong>
          <span>{{ sceneProposalRole === 'lead' ? '请先提出一个具体、可执行的计划；其他调查员随后可以补充或提出替代方案。' : '你可以支持、补充、修改或反对已有计划，也可以提出替代方案。' }}</span>
        </div>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && currentTurn.actionType === 'combat_defense'" class="clarification-prompt" role="status">
          <span class="clarification-prompt-icon" aria-hidden="true"><Swords :size="18" /></span>
          <span class="clarification-prompt-copy">
            <small>战斗防守</small>
            <strong>轮到你防守</strong>
            <span>{{ currentTurn.sceneName || '请选择闪避、反击或 KP 给出的其他合法反应' }}</span>
          </span>
          <span class="clarification-prompt-status"><i />等待行动</span>
        </div>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'clarification'" class="clarification-prompt" role="status">
          <span class="clarification-prompt-icon" aria-hidden="true"><MessageSquareText :size="18" /></span>
          <span class="clarification-prompt-copy">
            <small>KP 追问</small>
            <strong>等待你的确认</strong>
            <span>可以补充细节、调整行动，或放弃原行动。</span>
          </span>
          <span class="clarification-prompt-status"><i />等待回复</span>
        </div>
        <div v-if="isMobile && conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'dice'" class="mobile-pending-dice" role="status"><span>等待掷骰检定</span><button class="button secondary" @click="emit('openTools')">前往掷骰</button></div>
        <div v-if="!isMobile || conversation.status === 'active' || completionPending" class="composer" :class="{ disabled: conversation.status !== 'active', 'has-intent-toggle': canAskKp, 'has-withdraw': conversation.mode === 'chat', 'has-turn-experiments': betweenTrpgTurns }">
          <div v-if="betweenTrpgTurns && autoAdvance" class="turn-auto-advance-actions">
            <button class="button secondary turn-auto-advance-start" :disabled="sending" @click="requestTurnStart"><LoaderCircle v-if="sending" class="spin" :size="17" /><Play v-else :size="17" />{{ autoStartLabel }}</button>
            <button class="button ghost turn-auto-advance-cancel" :disabled="sending" @click="cancelAutoAdvance">取消自动推进</button>
          </div>
          <div v-else-if="completionPending" class="turn-completion-actions">
            <button class="button secondary" :disabled="summaryBusy" @click="emit('generateCompletion')"><LoaderCircle v-if="summaryBusy" class="spin" :size="17" /><Archive v-else :size="17" />{{ summaryBusy ? '正在生成跑团总结…' : summaryFailed ? '重新生成跑团总结并归档' : '生成跑团总结并归档' }}</button>
            <button class="button ghost" :disabled="skipSummaryDisabled" title="保留现有记录，跳过人物后传与完成报告" @click="emit('skipCompletion')"><CircleStop :size="17" />直接结束跑团</button>
          </div>
          <button v-else-if="completionAvailable" class="button secondary turn-start-button" @click="emit('openCompletion')">翻阅完成记录</button>
          <button v-else-if="conversation.mode === 'trpg' && !currentTurn?.waitingForUser" class="button secondary turn-start-button" title="开始下一轮，由参与者依次行动" :disabled="sending || conversation.status !== 'active'" @click="requestTurnStart"><LoaderCircle v-if="sending" class="spin" :size="17" /><Play v-else :size="17" />{{ turnButtonLabel }}</button>
          <button v-if="isMobile && betweenTrpgTurns && !autoAdvance" class="icon-button turn-experiment-settings" aria-label="行动轮设置" @click="turnSettingsOpen = true"><Settings2 :size="20" /></button><PopoverRoot v-if="!isMobile && betweenTrpgTurns && !autoAdvance">
            <PopoverTrigger as-child><button class="icon-button bordered turn-experiment-settings" type="button" title="行动轮设置" aria-label="行动轮设置"><Settings2 :size="17" /></button></PopoverTrigger>
            <PopoverPortal><PopoverContent class="turn-experiment-popover" side="top" align="end" :side-offset="10">
              <header><span><strong>行动轮设置</strong><small>实验功能</small></span></header>
              <label class="turn-experiment-option">
                <span><strong>自动推进</strong><small>行动轮之间及非用户掷骰后，倒计时 3 秒继续。</small></span>
                <input v-model="autoAdvance" type="checkbox" />
              </label>
              <label class="turn-experiment-option">
                <span><strong>修正方向</strong><small>临时调整下一轮全部 AI 调查员的探索或战斗方向。</small></span>
                <input v-model="directionEnabled" type="checkbox" />
              </label>
              <label v-if="directionEnabled" class="turn-direction-field">
                <textarea v-model="investigatorDirection" maxlength="1000" rows="4" placeholder="例如：优先确认地下室入口，不要继续与门卫纠缠。" />
                <small>{{ investigatorDirection.length }}/1000 · 仅下一行动轮的 AI 调查员可见</small>
              </label>
              <p>设置只在当前页面生效；刷新后重置，不写入存档或重试。</p>
            </PopoverContent></PopoverPortal>
          </PopoverRoot>
          <button v-if="!isMobile && conversation.mode === 'chat'" class="icon-button withdraw-button" :disabled="sending || conversation.status !== 'active'" title="撤回上一轮" aria-label="撤回上一轮" @click="emit('withdraw')"><RotateCcw :size="17" /></button>
          <div v-if="isMobile && canAskKp" class="composer-intent-toggle" role="group" aria-label="选择发言方式"><button :class="{ active: composerIntent === 'action' }" :aria-pressed="composerIntent === 'action'" :disabled="sending" @click="selectComposerIntent('action')">行动</button><button :class="{ active: composerIntent === 'inquiry' }" :aria-pressed="composerIntent === 'inquiry'" :disabled="sending" @click="selectComposerIntent('inquiry')">询问 KP</button></div>
          <TooltipProvider v-if="!isMobile && canAskKp">
            <div class="composer-intent-toggle" role="group" aria-label="选择发言方式">
              <TooltipRoot>
                <TooltipTrigger as-child><button type="button" data-intent="action" :class="{ active: composerIntent === 'action' }" :aria-pressed="composerIntent === 'action'" :aria-description="actionDescription" :disabled="sending" @click="selectComposerIntent('action')"><Play :size="13" /><span>行动</span></button></TooltipTrigger>
                <TooltipPortal><TooltipContent class="tooltip composer-intent-tooltip" side="top" :side-offset="9">{{ actionDescription }}</TooltipContent></TooltipPortal>
              </TooltipRoot>
              <TooltipRoot>
                <TooltipTrigger as-child><button type="button" data-intent="inquiry" :class="{ active: composerIntent === 'inquiry' }" :aria-pressed="composerIntent === 'inquiry'" :aria-description="inquiryDescription" :disabled="sending" @click="selectComposerIntent('inquiry')"><MessageCircleQuestion :size="13" /><span>询问</span></button></TooltipTrigger>
                <TooltipPortal><TooltipContent class="tooltip composer-intent-tooltip" side="top" :side-offset="9">{{ inquiryDescription }}</TooltipContent></TooltipPortal>
              </TooltipRoot>
            </div>
          </TooltipProvider>
          <button v-if="isMobile && (conversation.mode !== 'trpg' || waitingForMessage)" class="icon-button mobile-composer-menu" aria-label="聊天操作" @click="conversation.mode === 'trpg' ? emit('openTools') : moreOpen = true"><Plus :size="23" /></button>
          <textarea ref="inputElement" v-if="conversation.mode !== 'trpg' || currentTurn?.waitingForUser" v-model="composerValue" :maxlength="effectiveComposerIntent === 'inquiry' ? 200 : undefined" :disabled="conversation.status !== 'active' || sending || !waitingForMessage" rows="1" :placeholder="composerPlaceholder" aria-label="会话消息" @input="resizeInput" @compositionstart="composing = true" @compositionend="composing = false" @keydown="keydown" />
          <div v-if="conversation.mode !== 'trpg' || waitingForMessage" class="composer-actions">
            <button v-if="!isMobile && effectiveComposerIntent === 'action' && conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && currentTurn.actionType !== 'trpg_interaction_response' && replyPlan.source === 'SCENE'" class="button ghost" :disabled="sending" @click="emit('endExploration')"><Footprints :size="17" />结束探索</button>
            <TooltipProvider><TooltipRoot><TooltipTrigger as-child><button class="send-button" :aria-label="effectiveComposerIntent === 'inquiry' ? '询问 KP' : '发送消息'" :disabled="!composerValue.trim() || sending || conversation.status !== 'active' || !waitingForMessage" @click="submitComposer"><LoaderCircle v-if="sending" class="spin" :size="19" /><Send v-else :size="19" /></button></TooltipTrigger><TooltipPortal><TooltipContent class="tooltip" :side-offset="8">{{ isMobile ? '点击发送 · 回车换行' : effectiveComposerIntent === 'inquiry' ? 'Enter 询问 KP · Shift+Enter 换行' : 'Enter 发送 · Shift+Enter 换行' }}</TooltipContent></TooltipPortal></TooltipRoot></TooltipProvider>
          </div>
        </div>
        <div v-if="isMobile && conversation.status !== 'active' && !completionPending" class="mobile-closed-chat-footer"><button v-if="completionAvailable" class="button secondary" @click="emit('openCompletion')">翻阅完成记录</button><button class="button primary" @click="emit('back')">返回当前世界</button></div>
        <p v-if="completionPending && completionError" class="turn-completion-error" role="alert">{{ completionError }}</p>
      </section>
      <component v-if="!isMobile" :is="'div'" v-model="panelOpen" :title="conversation.mode === 'trpg' ? '场景与队伍' : '回复顺序'" mobile-presentation="page" content-class="mobile-chat-panel" :class="{ 'chat-side-host': !isMobile }"><aside class="reply-panel">
        <section v-if="conversation.mode === 'trpg'" class="trpg-time-panel">
          <header><span><Clock3 :size="17" /><small>当前时间</small><strong>{{ conversation.gameTime?.displayText || '尚未设定' }}</strong></span><button v-if="conversation.gameTime && conversation.status === 'active' && !timeEditing" class="icon-button subtle" :disabled="sending || Boolean(currentTurn)" :title="currentTurn ? '行动轮进行中，暂不能校时' : '校正游戏时间'" @click="beginTimeEdit"><Pencil :size="14" /></button></header>
          <div v-if="timeEditing" class="trpg-time-editor">
            <label>第 <input v-model.number="timeForm.dayNo" min="1" step="1" type="number" /> 天</label>
            <select v-model="timeForm.period"><option v-for="period in timePeriods" :key="period.value" :value="period.value">{{ period.label }}</option></select>
            <button class="icon-button subtle" title="取消" @click="timeEditing = false"><X :size="14" /></button>
            <button class="icon-button bordered" :disabled="!Number.isInteger(timeForm.dayNo) || timeForm.dayNo <= 0" title="确认校时" @click="submitTime"><Check :size="14" /></button>
          </div>
          <p v-if="!conversation.gameTime">由 KP 在首次选景时初始化。</p><p v-else-if="isMobile && currentTurn">行动轮进行中，暂不能校正游戏时间。</p>
        </section>
        <div class="reply-panel-title"><span><UsersRound :size="18" /><strong>{{ planTitle }}</strong></span><button class="icon-button subtle" @click="planOpen = !planOpen"><ChevronDown :size="17" :class="{ rotated: !planOpen }" /></button></div>
        <p>{{ planDescription }}</p>
        <div v-show="planOpen" class="reply-plan-list">
          <template v-if="conversation.mode === 'trpg'">
            <section v-for="scene in trpgExecution.scenes" :key="scene.plan.id" class="trpg-execution-scene" :class="[scene.kind, scene.status]">
              <header class="trpg-scene-header">
                <strong>{{ scene.plan.displayName }}</strong>
                <span class="trpg-scene-status-icon" :title="scene.statusLabel" role="img" :aria-label="scene.statusLabel"><component :is="sceneIcon(scene)" :size="14" :stroke-width="1.8" /></span>
              </header>
              <div v-if="scene.childScenes.length" class="trpg-child-scenes">
                <section v-for="child in scene.childScenes" :key="child.plan.id" class="trpg-execution-scene child" :class="child.status">
                  <header class="trpg-scene-header">
                    <strong>{{ child.plan.displayName }}</strong>
                    <span class="trpg-scene-status-icon" :title="child.statusLabel" role="img" :aria-label="child.statusLabel"><component :is="sceneIcon(child)" :size="13" :stroke-width="1.8" /></span>
                  </header>
                  <TrpgActorRoster :scene="child" :combat-overview="combatOverview" :investigator-cards="investigatorCards" :actor-runtimes="actorRuntimes" @open-card="openCardFromScene($event)" />
                </section>
              </div>
              <TrpgActorRoster :scene="scene" :combat-overview="combatOverview" :investigator-cards="investigatorCards" :actor-runtimes="actorRuntimes" @open-card="openCardFromScene($event)" />
            </section>
            <div v-if="!trpgExecution.scenes.length" class="plan-empty">暂无场景计划</div>
          </template>
          <template v-else>
            <div v-for="(item, index) in items" :key="`${item.actorType}-${item.actorId}-${item.subjectCharacterId}`" class="reply-plan-item" :draggable="canEditPlan && !planLocked && !isMobile" @dragstart="draggedIndex = index" @dragover.prevent @drop="drop(index)">
              <GripVertical v-if="canEditPlan && !isMobile" class="drag-handle" :size="16" /><span class="reply-order">{{ index + 1 }}</span><span class="reply-avatar" :style="planCharacter(item)?.characterImage ? { backgroundImage: `url(${planCharacter(item)?.characterImage})` } : {}">{{ planCharacter(item)?.characterImage ? '' : planActorName(item).slice(0, 1) }}</span><span class="reply-name">{{ planActorName(item) }}<small>第 {{ index + 1 }} 位回复</small></span><button v-if="canEditPlan" class="icon-button remove-plan" :disabled="planLocked" :aria-label="`移除${planActorName(item)}`" title="移除" @click="emit('deletePlanItem', index)"><Trash2 :size="15" /></button>
              <div v-if="isMobile && canEditPlan" class="mobile-reply-order-actions"><button class="button ghost" :disabled="planLocked || index === 0" :aria-label="`上移${planActorName(item)}`" @click="emit('movePlanItem', index, index - 1)"><ArrowUp :size="16" />上移</button><button class="button ghost" :disabled="planLocked || index === items.length - 1" :aria-label="`下移${planActorName(item)}`" @click="emit('movePlanItem', index, index + 1)"><ArrowDown :size="16" />下移</button></div>
              <label class="reply-model-picker" @mousedown.stop @click.stop>
                <span>回复模型</span>
                <select :value="actorModelValue(item)" :aria-label="`选择${planActorName(item)}的回复模型`" :disabled="sending || conversation.status !== 'active'" @change="selectActorModel(item, $event)">
                  <option value="">默认模型</option>
                  <option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option>
                </select>
              </label>
            </div>
            <div v-if="!items.length" class="plan-empty">暂无回复角色</div>
          </template>
        </div>
        <div v-if="canEditPlan" class="add-plan-row"><select v-model="addActorId" :disabled="planLocked || !availableCharacters.length"><option value="">{{ availableCharacters.length ? '添加参与角色' : '没有可添加角色' }}</option><option v-for="item in availableCharacters" :key="item.characterId" :value="String(item.characterId)">{{ item.characterName }}</option></select><button class="icon-button bordered" :disabled="planLocked || !addActorId" @click="addActor"><Plus :size="17" /></button></div>
        <div v-if="showSavePlan" class="plan-actions"><button class="button secondary save-plan" :disabled="!items.length || planLocked" @click="emit('savePlan')"><Save :size="16" />保存顺序</button></div>
        <section v-if="conversation.mode === 'chat' && replyTurnState" class="reply-turn-status" :class="replyTurnState.phase">
          <header><span><strong>当前回复状态</strong><small>{{ replyTurnState.turnId ? `Turn #${replyTurnState.turnId}` : '正在创建 Turn' }}</small></span><em>{{ replyTurnPhaseLabels[replyTurnState.phase] }}</em></header>
          <div class="reply-turn-actors">
            <div v-for="actor in replyTurnActors" :key="`${actor.item.actorType}-${actor.item.actorId}`" class="reply-turn-actor" :class="actor.phase"><i /><span>{{ character(actor.item.actorId)?.characterName || `角色 #${actor.item.actorId}` }}</span><small>{{ replyActorPhaseLabels[actor.phase] }}</small></div>
          </div>
          <p v-if="replyTurnState.error">{{ replyTurnState.error }}</p>
        </section>
        <div class="panel-note"><strong>执行规则</strong><span>{{ conversation.mode === 'trpg' ? '行动顺序由系统根据当前场景或战斗自动安排。' : '角色依次生成回复，后一位可以看到本轮前面角色刚完成的内容。' }}</span></div>
      </aside></component>
    </div>

    <BaseDialog v-if="isMobile" v-model="turnSettingsOpen" title="行动轮设置" mobile-presentation="page" content-class="mobile-turn-settings">
      <p class="mobile-chat-notice">实验功能 · 仅在当前页面生效，刷新后重置。</p>
      <label class="mobile-chat-toggle"><span><strong>自动推进</strong><small>行动轮之间及非用户掷骰后，倒计时 3 秒继续。</small></span><input v-model="autoAdvance" type="checkbox" /><i /></label>
      <label class="mobile-chat-toggle"><span><strong>修正方向</strong><small>仅影响下一轮 AI 调查员</small></span><input v-model="directionEnabled" type="checkbox" /><i /></label>
      <label v-if="directionEnabled" class="field"><span>下一轮探索或战斗方向</span><textarea v-model="investigatorDirection" maxlength="1000" rows="5" placeholder="例如：优先确认地下室入口，不要继续与门卫纠缠。" /><small>{{ investigatorDirection.length }}/1000 · 仅下一行动轮的 AI 调查员可见</small></label>
      <p class="mobile-chat-muted">本设置不写入存档或重试。</p>
      <section v-if="turnCountdownRemaining > 0" class="mobile-chat-card" role="status"><strong>自动推进中 · {{ turnCountdownRemaining }} 秒</strong><p>下一行动轮即将开始。</p><button class="button secondary" @click="cancelAutoAdvance">取消自动推进</button></section>
      <template #footer><button class="button primary" @click="turnSettingsOpen = false">完成</button></template>
    </BaseDialog>
    <BaseDialog v-if="isMobile" v-model="panelOpen" :title="conversation.mode === 'trpg' ? '场景与队伍' : '回复顺序'" mobile-presentation="page" content-class="mobile-chat-panel mobile-reply-page">
      <template v-if="conversation.mode === 'chat'">
        <p v-if="planLocked" class="mobile-chat-notice">{{ sending ? '当前轮正在生成，顺序暂不可编辑。' : '会话已经结束，顺序不可编辑。' }}</p>
        <section v-if="replyTurnState" class="mobile-current-replies"><h3>当前回复状态</h3><div v-for="actor in replyTurnActors" :key="`${actor.item.actorType}:${actor.item.actorId}`" class="mobile-chat-row"><span class="mobile-chat-avatar" :style="planCharacter(actor.item)?.characterImage ? {backgroundImage: `url(${planCharacter(actor.item)?.characterImage})`} : {}">{{ planCharacter(actor.item)?.characterImage ? '' : planActorName(actor.item).slice(0, 1) }}</span><span><strong>{{ planActorName(actor.item) }}</strong><small>{{ replyActorPhaseLabels[actor.phase] }}</small></span><span>{{ actor.phase === 'completed' ? '✓' : actor.phase === 'replying' ? '•••' : actor.phase === 'failed' ? '失败' : '等待' }}</span></div><p v-if="replyTurnState.error" class="mobile-chat-error">{{ replyTurnState.error }}</p></section>
        <h3>下一轮回复顺序</h3>
        <div class="mobile-chat-card mobile-order-card"><div v-for="(item, index) in items" :key="`${item.actorType}:${item.actorId}`" class="mobile-order-item"><button class="mobile-order-name" :class="{ selected: mobileSelectedActor === item }" @click="selectedReplyActor = `${item.actorType}:${item.actorId}`"><small>{{ String(index + 1).padStart(2, '0') }}</small>{{ planActorName(item) }}</button><button v-if="canEditPlan" class="button secondary" :disabled="planLocked || index === 0" :aria-label="`上移${planActorName(item)}`" @click="emit('movePlanItem', index, index - 1)">上移</button><button v-if="canEditPlan" class="button secondary" :disabled="planLocked || index === items.length - 1" :aria-label="`下移${planActorName(item)}`" @click="emit('movePlanItem', index, index + 1)">下移</button></div><p v-if="!items.length" class="mobile-chat-muted">暂无回复角色</p></div>
        <template v-if="canEditPlan"><label class="field"><span>添加参与角色</span><select v-model="addActorId" :disabled="planLocked || !availableCharacters.length"><option value="">{{ availableCharacters.length ? '选择角色' : '没有可添加角色' }}</option><option v-for="actor in availableCharacters" :key="actor.characterId" :value="String(actor.characterId)">{{ actor.characterName }}</option></select></label><button class="button secondary" :disabled="planLocked || !addActorId" @click="addActor"><Plus :size="17" />添加到回复列表</button></template>
        <label v-if="mobileSelectedActor" class="field"><span>{{ planActorName(mobileSelectedActor) }}的回复模型</span><select :value="actorModelValue(mobileSelectedActor)" :disabled="planLocked" @change="selectActorModel(mobileSelectedActor, $event)"><option value="">默认模型</option><option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option></select><small>点选上方角色，可以分别设置回复模型。</small></label>
        <button v-if="canEditPlan && mobileSelectedActor" class="mobile-chat-row mobile-chat-danger" :disabled="planLocked" @click="emit('deletePlanItem', items.indexOf(mobileSelectedActor))"><span><strong>移除 {{ planActorName(mobileSelectedActor) }}</strong><small>保存顺序后生效</small></span><Trash2 :size="18" /></button>
      </template>
      <div v-else class="mobile-scene-page">
        <section class="mobile-scene-summary">
          <span class="mobile-scene-eyebrow">当前场景</span>
          <h2>{{ mobileSceneName }}</h2>
          <button class="mobile-time-link" :aria-expanded="timeEditing" @click="timeEditing = !timeEditing; timeEditing && beginTimeEdit()"><Clock3 :size="14" /><span>{{ conversation.gameTime?.displayText || '游戏时间尚未设定' }}</span><ChevronDown :size="14" /></button>
          <p class="mobile-scene-status" role="status"><CircleDot :size="14" />{{ mobileSceneStatus }}</p>
        </section>
        <section v-if="timeEditing" class="mobile-chat-card"><h3>校正游戏时间</h3><p v-if="currentTurn" class="mobile-chat-muted">行动轮进行中，暂不能校正游戏时间。</p><template v-else-if="conversation.gameTime"><label class="field"><span>第几天</span><input v-model.number="timeForm.dayNo" min="1" step="1" type="number" /></label><label class="field"><span>时段</span><select v-model="timeForm.period"><option v-for="period in timePeriods" :key="period.value" :value="period.value">{{ period.label }}</option></select></label><button class="button primary" :disabled="sending || conversation.status !== 'active' || !Number.isInteger(timeForm.dayNo) || timeForm.dayNo <= 0" @click="submitTime">确认校时</button></template><p v-else class="mobile-chat-muted">由 KP 在首次选景时初始化。</p></section>
        <div class="mobile-scene-section-title"><h3>行动顺序</h3><span>点按角色查看状态</span></div>
        <section v-for="scene in mobileScenes" :key="scene.plan.id" class="mobile-scene-roster">
          <header><strong>{{ scene.plan.displayName }}</strong><small :class="{ current: scene.status === 'current' }">{{ scene.status === 'current' ? '当前进行' : scene.status === 'waiting-child' ? '暂时挂起' : '等待进入' }}</small></header>
          <TrpgActorRoster :scene="scene" :combat-overview="combatOverview" :investigator-cards="investigatorCards" :actor-runtimes="actorRuntimes" @open-card="openCardFromScene($event)" @switch-model="modelActor = $event" />
          <section v-for="child in scene.childScenes" :key="child.plan.id" class="mobile-child-scene">
            <header><span><small>关联场景</small><strong>{{ child.plan.displayName }}</strong></span><small :class="{ current: child.status === 'current' }">{{ child.status === 'current' ? '当前进行' : '等待进入' }}</small></header>
            <TrpgActorRoster :scene="child" :combat-overview="combatOverview" :investigator-cards="investigatorCards" :actor-runtimes="actorRuntimes" @open-card="openCardFromScene($event)" @switch-model="modelActor = $event" />
          </section>
        </section>
        <p v-if="!mobileScenes.length" class="mobile-scene-empty">尚未建立场景计划，开始行动后将在这里显示队伍与行动顺序。</p>
        <div class="mobile-scene-links">
        <button class="mobile-chat-row" @click="openToolsFromScene()"><span><strong>人物卡与角色控制</strong><small>查看调查员资料、控制方式与回复模型</small></span><span>›</span></button>
        <button v-if="effectiveComposerIntent === 'action' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && currentTurn.actionType !== 'trpg_interaction_response' && replyPlan.source === 'SCENE'" class="mobile-chat-row" :disabled="sending" @click="panelOpen = false; emit('endExploration')"><span><strong>结束当前探索</strong></span><Footprints :size="18" /></button>
        </div>
      </div>
      <template v-if="conversation.mode === 'chat'" #footer><button class="button primary" :disabled="canEditPlan && (!showSavePlan || !items.length || planLocked)" @click="canEditPlan ? emit('savePlan') : panelOpen = false">{{ canEditPlan ? '保存顺序' : '返回群聊' }}</button></template>
    </BaseDialog>
    <MobileActorModelDialog v-if="isMobile && modelActor && persistActorRuntime" v-model="modelDialogOpen" :actor="modelActor" :actor-runtimes="actorRuntimes" :model-apis="modelApis" :locked="sending || loading || conversation.status !== 'active'" :save-runtime="persistActorRuntime" />
    <BaseDialog v-if="isMobile" v-model="withdrawOpen" title="撤回上一轮" mobile-presentation="page" content-class="mobile-chat-confirm"><p class="mobile-chat-notice mobile-withdraw-notice">撤回会删除上一轮用户消息及其触发的角色回复。</p><h3>当前操作范围</h3><p class="mobile-chat-muted">{{ conversation.title }} · 上一轮对话</p><template #footer><button class="button secondary" @click="withdrawOpen = false">取消</button><button class="button danger" :disabled="sending || conversation.status !== 'active'" @click="withdrawOpen = false; emit('withdraw')">确认撤回</button></template></BaseDialog>
  </main>
</template>

<style>
.turn-completion-actions { grid-column: 1 / -1; min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 6px; }
.turn-completion-actions .button { min-width: 0; justify-content: center; white-space: normal; }
.turn-completion-actions svg { flex-shrink: 0; }
.turn-completion-error { margin: 8px 0 0; color: var(--wine); font-size: 12px; }
</style>
