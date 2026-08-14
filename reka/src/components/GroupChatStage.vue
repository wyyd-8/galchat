<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch, type Component } from 'vue'
import { Archive, Check, ChevronDown, Circle, CircleDot, CircleStop, Clock3, Footprints, GripVertical, History, LoaderCircle, MessageSquareText, Pause, Pencil, Play, Plus, RefreshCw, RotateCcw, Save, Send, Swords, Trash2, UsersRound, X } from '@lucide/vue'
import {
  CollapsibleContent, CollapsibleRoot, CollapsibleTrigger,
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { Character, Conversation, CurrentTurn, DiceRollAggregate, GroupMessage, ReplyPlan, ReplyPlanItem, TrpgGameTimePeriod } from '@/api/types'
import DiceRollMessage from '@/dice/components/DiceRollMessage.vue'
import TrpgActorRoster from './TrpgActorRoster.vue'
import { replyPlanActorName, replyPlanSignature, shouldShowSavePlan, visibleReplyPlanItems } from './replyPlanState'
import { replyActorPhase, type ReplyActorPhase, type ReplyTurnState } from './replyTurnStatus'
import { syncReasoningDisclosure, type ReasoningPhase } from './reasoningDisclosure'
import { scrollConversationToLatest } from './reasoningScroll'
import { buildTrpgExecutionState, type TrpgExecutionScene } from './trpgExecutionState'

const input = defineModel<string>('input', { required: true })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const props = defineProps<{ conversation: Conversation; username: string; messages: GroupMessage[]; reasoning: Record<number, string>; characters: Character[]; replyPlan: ReplyPlan; replyPlans: ReplyPlan[]; availableCharacters: Character[]; currentTurn: CurrentTurn | null; replyTurnState: ReplyTurnState | null; sending: boolean; loading: boolean; hasOlderMessages: boolean }>()
const emit = defineEmits<{ back: []; savePlan: []; movePlanItem: [from: number, to: number]; deletePlanItem: [index: number]; addPlanItem: [id: number]; loadEarlier: []; withdraw: []; openTools: []; openDice: [aggregate: DiceRollAggregate]; send: []; startTurn: []; selectScene: [optionNo: string]; endExploration: []; retry: [message: GroupMessage]; correctTime: [dayNo: number, period: TrpgGameTimePeriod]; end: [] }>()
const draggedIndex = ref<number | null>(null)
const addActorId = ref('')
const planOpen = ref(true)
const reasoningOpen = reactive<Record<number, boolean>>({})
const reasoningPhase = new Map<number, ReasoningPhase>()
const lastScrollTop = ref(0)
const initialScrollPending = ref(true)
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
const waitingForMessage = computed(() => props.conversation.mode !== 'trpg' || (props.currentTurn?.waitingForUser && props.currentTurn.inputType === 'message'))
const selectionOptions = computed(() => Object.entries(props.currentTurn?.sceneOptions || {}))
const sceneProposalRole = computed(() => props.currentTurn?.waitingForUser && props.currentTurn.actionType === 'trpg_scene'
  ? (props.currentTurn.itemOrder === 1 ? 'lead' : 'contributor')
  : null)
const canEditPlan = computed(() => props.conversation.mode === 'chat' && props.replyPlan.source === 'USER')
const showSavePlan = computed(() => shouldShowSavePlan(canEditPlan.value, loadedPlanSignature.value, items.value))
const replyTurnPhaseLabels = { starting: '准备回复', running: '回复进行中', completed: '本轮已完成', failed: '本轮失败' } as const
const replyActorPhaseLabels: Record<ReplyActorPhase, string> = { waiting: '等待中', replying: '回复中', completed: '已完成', failed: '失败' }
const replyTurnActors = computed(() => {
  const state = props.replyTurnState
  return state ? items.value.map((item) => ({ item, phase: replyActorPhase(state, item, props.messages) })) : []
})
const emptyDescription = computed(() => props.conversation.mode === 'trpg'
  ? '先在跑团工具中确认玩家与 AI 调查员人物卡，再开始行动轮。'
  : '输入消息后，角色会按照右侧保存的顺序依次回应。')
const planTitle = computed(() => props.conversation.mode === 'trpg' ? trpgExecution.value.title : '回复顺序')
const planDescription = computed(() => props.conversation.mode === 'trpg'
  ? `${trpgExecution.value.subtitle} · 由场景或战斗流程实时维护。`
  : '从上到下依次回复；拖动调整，点击移除后保存。')
const turnButtonLabel = computed(() => {
  if (!props.currentTurn) return '开始行动轮'
  if (props.currentTurn.inputType === 'dice') return '检查投骰并继续'
  return '继续行动轮'
})
const composerPlaceholder = computed(() => {
  if (props.conversation.status !== 'active') return '这个会话已经关闭'
  if (waitingForMessage.value && sceneProposalRole.value === 'lead') return '提出一个具体、可执行的场景计划…'
  if (waitingForMessage.value && sceneProposalRole.value === 'contributor') return '回应已有计划，或提出补充与替代方案…'
  if (waitingForMessage.value) return props.conversation.mode === 'trpg' ? '输入玩家调查员的行动…' : '输入群聊消息…'
  if (props.currentTurn?.inputType === 'selection') return '请从上方选择调查地点'
  if (props.currentTurn?.inputType === 'dice') return '请在跑团工具中完成待处理投骰'
  return '等待当前行动轮推进'
})
let latestScrollFrame = 0

watch(() => props.messages.map((message) => `${message.id}:${message.replyStepId || 0}:${message.status}:${Boolean(message.content.trim())}:${Boolean(message.replyStepId && props.reasoning[message.replyStepId])}`).join('|'), syncReasoningState, { immediate: true, flush: 'sync' })
watch(() => props.replyPlan, (plan) => { loadedPlanSignature.value = replyPlanSignature(plan.items) }, { immediate: true, flush: 'sync' })
watch(() => props.conversation.id, () => { lastScrollTop.value = 0; initialScrollPending.value = true; timeEditing.value = false }, { immediate: true })
watch(() => props.loading, (loading) => {
  if (!loading && initialScrollPending.value) { initialScrollPending.value = false; scrollToLatest() }
}, { immediate: true, flush: 'post' })
watch(() => `${props.sending}:${props.messages.map((message) => `${message.id}:${message.content.length}:${message.decisionContent?.length || 0}:${message.replyStepId ? props.reasoning[message.replyStepId]?.length || 0 : 0}`).join('|')}`, () => {
  if (props.sending) scrollToLatest()
}, { flush: 'post' })
watch(() => props.sending, (sending, wasSending) => { if (!sending && wasSending) scrollToLatest() }, { flush: 'post' })
onBeforeUnmount(() => cancelAnimationFrame(latestScrollFrame))

function syncReasoningState() {
  props.messages.forEach((message) => {
    const step = message.replyStepId
    if (!step || !props.reasoning[step]) return
    const phase = message.status === 'streaming' && !message.content.trim() ? 'thinking' : message.content.trim() ? 'main' : 'idle'
    syncReasoningDisclosure(reasoningOpen, reasoningPhase, step, phase)
  })
}

function character(id?: number) { return props.characters.find((item) => item.characterId === id) }
function planCharacter(item: ReplyPlanItem) { return item.actorType === 'character' ? character(item.actorId) : undefined }
function planActorName(item: ReplyPlanItem) { return replyPlanActorName(item, props.username, planCharacter(item)?.characterName) }
function drop(index: number) { if (draggedIndex.value !== null) emit('movePlanItem', draggedIndex.value, index); draggedIndex.value = null }
function addActor() { const id = Number(addActorId.value); if (id) { emit('addPlanItem', id); addActorId.value = '' } }
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
function keydown(event: KeyboardEvent) { if (!event.isComposing && event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); emit('send') } }
function scrollToLatest() {
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
  const currentTop = viewport.scrollTop
  const movingUp = currentTop < lastScrollTop.value
  lastScrollTop.value = currentTop
  if (movingUp && currentTop <= 32 && props.hasOlderMessages && !props.loading) emit('loadEarlier')
}
</script>

<template>
  <main class="chat-page">
    <header class="chat-header"><div><button class="text-button" @click="emit('back')">返回当前世界</button><h1>{{ conversation.title }}</h1></div><div class="chat-header-actions"><span class="live-status" :class="conversation.status"><i />{{ conversation.status === 'active' ? '进行中' : '已关闭' }}</span><button v-if="conversation.mode === 'trpg'" class="button ghost" @click="emit('openTools')"><Archive :size="16" />跑团工具</button><button v-if="conversation.mode === 'chat' && conversation.status === 'active'" class="button ghost" :disabled="sending" @click="emit('withdraw')"><RotateCcw :size="16" />撤回一轮</button><button v-if="conversation.status === 'active'" class="button ghost danger-text" @click="emit('end')"><CircleStop :size="16" />关闭会话</button></div></header>
    <div class="chat-layout">
      <section class="chat-main">
        <div class="message-scroll"><div :ref="bindScroller" class="message-viewport" @scroll="handleScroll"><div>
          <div v-if="loading && !messages.length" class="chat-loading"><LoaderCircle class="spin" :size="22" />载入消息</div>
          <button v-else-if="hasOlderMessages" class="load-earlier-button" :disabled="loading" @click="emit('loadEarlier')"><LoaderCircle v-if="loading" class="spin" :size="14" /><History v-else :size="14" />加载更早记录</button>
          <div v-else-if="!messages.length" class="empty-chat"><MessageSquareText :size="30" /><h2>{{ conversation.mode === 'trpg' ? '跑团尚未开始' : '对话从这里开始' }}</h2><p>{{ emptyDescription }}</p></div>
          <article v-for="message in messages" :key="message.id" class="chat-message" :class="[message.speakerType, message.messageKind]" :data-message-id="message.id">
            <DiceRollMessage v-if="message.messageKind === 'dice_roll' && message.diceRoll" :aggregate="message.diceRoll" @open="emit('openDice', message.diceRoll)" />
            <template v-else>
            <div v-if="message.speakerType === 'character'" class="message-avatar" :style="character(message.speakerId)?.characterImage ? { backgroundImage: `url(${character(message.speakerId)?.characterImage})` } : {}">{{ character(message.speakerId)?.characterImage ? '' : (message.speakerName || character(message.speakerId)?.characterName || '?').slice(0, 1) }}</div>
            <div class="message-content">
              <div class="message-meta"><strong>{{ message.speakerType === 'user' ? '你' : message.speakerType === 'narrator' ? '叙事' : message.speakerType === 'kp' ? (message.speakerName || 'KP') : message.speakerName || character(message.speakerId)?.characterName || '角色' }}</strong><span v-if="message.status === 'streaming'" class="typing-dot">正在回应</span><span v-if="message.status === 'failed'" class="failed-label">生成失败</span></div>
              <CollapsibleRoot v-if="message.replyStepId && reasoning[message.replyStepId]" v-model:open="reasoningOpen[message.replyStepId]" class="reasoning-block">
                <CollapsibleTrigger class="reasoning-trigger">思考过程 <ChevronDown :size="14" /></CollapsibleTrigger>
                <CollapsibleContent class="reasoning-content" :data-reasoning-streaming="reasoningPhase.get(message.replyStepId) === 'thinking' ? 'true' : undefined">{{ reasoning[message.replyStepId] }}</CollapsibleContent>
              </CollapsibleRoot>
              <div v-if="message.decisionContent" class="decision-block"><span>角色决策</span><p>{{ message.decisionContent }}</p></div>
              <p>{{ message.content }}<span v-if="message.status === 'streaming'" class="stream-caret" /></p>
              <button v-if="conversation.mode === 'trpg' && message.speakerType === 'character' && message.status === 'failed'" class="retry-step-button" :disabled="sending" @click="emit('retry', message)"><RefreshCw :size="13" />重试该角色行动</button>
            </div>
            </template>
          </article>
        </div></div></div>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'selection'" class="scene-selection-panel">
          <strong>选择调查地点</strong><span>{{ currentTurn.sceneName || 'KP 已给出本轮可选地点' }}</span>
          <div class="scene-selection-options"><button v-for="[number, name] in selectionOptions" :key="number" class="button secondary" :disabled="sending" @click="emit('selectScene', number)"><b>{{ number }}</b>{{ name }}</button></div>
        </div>
        <div v-if="sceneProposalRole" class="scene-selection-panel">
          <strong>{{ sceneProposalRole === 'lead' ? '你是本轮首位提案者' : '回应本轮共同计划' }}</strong>
          <span>{{ sceneProposalRole === 'lead' ? '请先提出一个具体、可执行的计划；其他调查员随后可以补充或提出替代方案。' : '你可以支持、补充、修改或反对已有计划，也可以提出替代方案。' }}</span>
        </div>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && currentTurn.actionType === 'combat_defense'" class="scene-selection-panel">
          <strong>轮到你防守</strong><span>{{ currentTurn.sceneName || '请选择闪避、反击或 KP 给出的其他合法反应' }}</span>
        </div>
        <div class="composer" :class="{ disabled: conversation.status !== 'active' }">
          <button v-if="conversation.mode === 'trpg' && !currentTurn?.waitingForUser" class="button secondary turn-start-button" :disabled="sending || conversation.status !== 'active'" @click="emit('startTurn')"><LoaderCircle v-if="sending" class="spin" :size="17" /><Play v-else :size="17" />{{ turnButtonLabel }}</button>
          <textarea v-else v-model="input" :disabled="conversation.status !== 'active' || sending || !waitingForMessage" rows="1" :placeholder="composerPlaceholder" @keydown="keydown" />
          <button v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && replyPlan.source === 'SCENE'" class="button ghost" :disabled="sending" @click="emit('endExploration')"><Footprints :size="17" />结束探索</button>
          <TooltipProvider v-if="conversation.mode !== 'trpg' || waitingForMessage"><TooltipRoot><TooltipTrigger as-child><button class="send-button" :disabled="!input.trim() || sending || conversation.status !== 'active' || !waitingForMessage" @click="emit('send')"><LoaderCircle v-if="sending" class="spin" :size="19" /><Send v-else :size="19" /></button></TooltipTrigger><TooltipPortal><TooltipContent class="tooltip" :side-offset="8">Enter 发送 · Shift+Enter 换行</TooltipContent></TooltipPortal></TooltipRoot></TooltipProvider>
        </div>
      </section>
      <aside class="reply-panel">
        <section v-if="conversation.mode === 'trpg'" class="trpg-time-panel">
          <header><span><Clock3 :size="17" /><small>当前时间</small><strong>{{ conversation.gameTime?.displayText || '尚未设定' }}</strong></span><button v-if="conversation.gameTime && conversation.status === 'active' && !timeEditing" class="icon-button subtle" :disabled="sending || Boolean(currentTurn)" :title="currentTurn ? '行动轮进行中，暂不能校时' : '校正游戏时间'" @click="beginTimeEdit"><Pencil :size="14" /></button></header>
          <div v-if="timeEditing" class="trpg-time-editor">
            <label>第 <input v-model.number="timeForm.dayNo" min="1" step="1" type="number" /> 天</label>
            <select v-model="timeForm.period"><option v-for="period in timePeriods" :key="period.value" :value="period.value">{{ period.label }}</option></select>
            <button class="icon-button subtle" title="取消" @click="timeEditing = false"><X :size="14" /></button>
            <button class="icon-button bordered" :disabled="!Number.isInteger(timeForm.dayNo) || timeForm.dayNo <= 0" title="确认校时" @click="submitTime"><Check :size="14" /></button>
          </div>
          <p v-if="!conversation.gameTime">由 KP 在首次选景时初始化。</p>
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
                  <TrpgActorRoster :scene="child" />
                </section>
              </div>
              <TrpgActorRoster :scene="scene" />
            </section>
            <div v-if="!trpgExecution.scenes.length" class="plan-empty">暂无场景计划</div>
          </template>
          <template v-else>
            <div v-for="(item, index) in items" :key="`${item.actorType}-${item.actorId}-${item.subjectCharacterId}`" class="reply-plan-item" :draggable="canEditPlan" @dragstart="draggedIndex = index" @dragover.prevent @drop="drop(index)">
              <GripVertical v-if="canEditPlan" class="drag-handle" :size="16" /><span class="reply-order">{{ index + 1 }}</span><span class="reply-avatar" :style="planCharacter(item)?.characterImage ? { backgroundImage: `url(${planCharacter(item)?.characterImage})` } : {}">{{ planCharacter(item)?.characterImage ? '' : planActorName(item).slice(0, 1) }}</span><span class="reply-name">{{ planActorName(item) }}<small>第 {{ index + 1 }} 位回复</small></span><button v-if="canEditPlan" class="icon-button remove-plan" title="移除" @click="emit('deletePlanItem', index)"><Trash2 :size="15" /></button>
            </div>
            <div v-if="!items.length" class="plan-empty">暂无回复角色</div>
          </template>
        </div>
        <div v-if="canEditPlan" class="add-plan-row"><select v-model="addActorId" :disabled="!availableCharacters.length"><option value="">{{ availableCharacters.length ? '添加参与角色' : '没有可添加角色' }}</option><option v-for="item in availableCharacters" :key="item.characterId" :value="String(item.characterId)">{{ item.characterName }}</option></select><button class="icon-button bordered" :disabled="!addActorId" @click="addActor"><Plus :size="17" /></button></div>
        <div v-if="showSavePlan" class="plan-actions"><button class="button secondary save-plan" :disabled="!items.length || sending" @click="emit('savePlan')"><Save :size="16" />保存顺序</button></div>
        <section v-if="conversation.mode === 'chat' && replyTurnState" class="reply-turn-status" :class="replyTurnState.phase">
          <header><span><strong>当前回复状态</strong><small>{{ replyTurnState.turnId ? `Turn #${replyTurnState.turnId}` : '正在创建 Turn' }}</small></span><em>{{ replyTurnPhaseLabels[replyTurnState.phase] }}</em></header>
          <div class="reply-turn-actors">
            <div v-for="actor in replyTurnActors" :key="`${actor.item.actorType}-${actor.item.actorId}`" class="reply-turn-actor" :class="actor.phase"><i /><span>{{ character(actor.item.actorId)?.characterName || `角色 #${actor.item.actorId}` }}</span><small>{{ replyActorPhaseLabels[actor.phase] }}</small></div>
          </div>
          <p v-if="replyTurnState.error">{{ replyTurnState.error }}</p>
        </section>
        <div class="panel-note"><strong>执行规则</strong><span>{{ conversation.mode === 'trpg' ? '场景与战斗会自动维护行动顺序，公共界面不能手动修改。' : '角色依次生成回复，后一位可以看到本轮前面角色刚完成的内容。' }}</span></div>
      </aside>
    </div>
  </main>
</template>
