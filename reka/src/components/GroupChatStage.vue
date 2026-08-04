<script setup lang="ts">
import { computed, ref } from 'vue'
import { Archive, ChevronDown, CircleStop, Footprints, GripVertical, History, LoaderCircle, MessageSquareText, Play, Plus, RefreshCw, RotateCcw, Save, Send, Trash2, UsersRound } from '@lucide/vue'
import {
  CollapsibleContent, CollapsibleRoot, CollapsibleTrigger, ScrollAreaRoot, ScrollAreaScrollbar, ScrollAreaThumb, ScrollAreaViewport,
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { Character, Conversation, CurrentTurn, GroupMessage, ReplyPlan } from '@/api/types'

const input = defineModel<string>('input', { required: true })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const props = defineProps<{ conversation: Conversation; messages: GroupMessage[]; reasoning: Record<number, string>; characters: Character[]; replyPlan: ReplyPlan; availableCharacters: Character[]; currentTurn: CurrentTurn | null; sending: boolean; loading: boolean; hasOlderMessages: boolean }>()
const emit = defineEmits<{ back: []; savePlan: []; movePlanItem: [from: number, to: number]; deletePlanItem: [index: number]; addPlanItem: [id: number]; loadEarlier: []; withdraw: []; openTools: []; send: []; startTurn: []; selectScene: [optionNo: string]; endExploration: []; retry: [message: GroupMessage]; end: [] }>()
const draggedIndex = ref<number | null>(null)
const addActorId = ref('')
const planOpen = ref(true)
const items = computed(() => props.replyPlan.groups[0]?.items || [])
const waitingForMessage = computed(() => props.conversation.mode !== 'trpg' || (props.currentTurn?.waitingForUser && props.currentTurn.inputType === 'message'))
const selectionOptions = computed(() => Object.entries(props.currentTurn?.sceneOptions || {}))
const canEditPlan = computed(() => props.conversation.mode === 'chat' && props.replyPlan.source === 'USER')
const emptyDescription = computed(() => props.conversation.mode === 'trpg'
  ? '先在跑团工具中确认玩家与 AI 调查员人物卡，再开始行动轮。'
  : '输入消息后，角色会按照右侧保存的顺序依次回应。')
const planTitle = computed(() => props.conversation.mode === 'trpg' ? '当前行动顺序' : '回复顺序')
const planDescription = computed(() => props.conversation.mode === 'trpg'
  ? '由当前场景或战斗流程生成，仅供查看。'
  : '从上到下依次回复；拖动调整，点击移除后保存。')
const turnButtonLabel = computed(() => {
  if (!props.currentTurn) return '开始行动轮'
  if (props.currentTurn.inputType === 'dice') return '检查投骰并继续'
  return '继续行动轮'
})
const composerPlaceholder = computed(() => {
  if (props.conversation.status !== 'active') return '这个会话已经关闭'
  if (waitingForMessage.value) return props.conversation.mode === 'trpg' ? '输入玩家调查员的行动…' : '输入群聊消息…'
  if (props.currentTurn?.inputType === 'selection') return '请从上方选择调查地点'
  if (props.currentTurn?.inputType === 'dice') return '请在跑团工具中完成待处理投骰'
  return '等待当前行动轮推进'
})
function character(id?: number) { return props.characters.find((item) => item.characterId === id) }
function drop(index: number) { if (draggedIndex.value !== null) emit('movePlanItem', draggedIndex.value, index); draggedIndex.value = null }
function addActor() { const id = Number(addActorId.value); if (id) { emit('addPlanItem', id); addActorId.value = '' } }
function keydown(event: KeyboardEvent) { if (!event.isComposing && event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); emit('send') } }
function bindScroller(element: unknown) { scroller.value = element instanceof HTMLElement ? element : null }
</script>

<template>
  <main class="chat-page">
    <header class="chat-header"><div><button class="text-button" @click="emit('back')">返回当前世界</button><h1>{{ conversation.title }}</h1></div><div class="chat-header-actions"><span class="live-status" :class="conversation.status"><i />{{ conversation.status === 'active' ? '进行中' : '已关闭' }}</span><button v-if="conversation.mode === 'trpg'" class="button ghost" @click="emit('openTools')"><Archive :size="16" />跑团工具</button><button v-if="conversation.mode === 'chat' && conversation.status === 'active'" class="button ghost" :disabled="sending" @click="emit('withdraw')"><RotateCcw :size="16" />撤回一轮</button><button v-if="conversation.status === 'active'" class="button ghost danger-text" @click="emit('end')"><CircleStop :size="16" />关闭会话</button></div></header>
    <div class="chat-layout">
      <section class="chat-main">
        <ScrollAreaRoot class="message-scroll"><ScrollAreaViewport :ref="bindScroller" class="message-viewport">
          <div v-if="loading && !messages.length" class="chat-loading"><LoaderCircle class="spin" :size="22" />载入消息</div>
          <button v-else-if="hasOlderMessages" class="load-earlier-button" :disabled="loading" @click="emit('loadEarlier')"><LoaderCircle v-if="loading" class="spin" :size="14" /><History v-else :size="14" />加载更早记录</button>
          <div v-else-if="!messages.length" class="empty-chat"><MessageSquareText :size="30" /><h2>{{ conversation.mode === 'trpg' ? '跑团尚未开始' : '对话从这里开始' }}</h2><p>{{ emptyDescription }}</p></div>
          <article v-for="message in messages" :key="message.id" class="chat-message" :class="[message.speakerType, message.messageKind]">
            <div v-if="message.speakerType === 'character'" class="message-avatar" :style="character(message.speakerId)?.characterImage ? { backgroundImage: `url(${character(message.speakerId)?.characterImage})` } : {}">{{ character(message.speakerId)?.characterImage ? '' : (message.speakerName || character(message.speakerId)?.characterName || '?').slice(0, 1) }}</div>
            <div class="message-content">
              <div class="message-meta"><strong>{{ message.speakerType === 'user' ? '你' : message.speakerType === 'narrator' ? '叙事' : message.speakerType === 'kp' ? (message.speakerName || 'KP') : message.speakerName || character(message.speakerId)?.characterName || '角色' }}</strong><span v-if="message.status === 'streaming'" class="typing-dot">正在回应</span><span v-if="message.status === 'failed'" class="failed-label">生成失败</span></div>
              <CollapsibleRoot v-if="message.replyStepId && reasoning[message.replyStepId]" class="reasoning-block">
                <CollapsibleTrigger class="reasoning-trigger">思考过程 <ChevronDown :size="14" /></CollapsibleTrigger>
                <CollapsibleContent class="reasoning-content">{{ reasoning[message.replyStepId] }}</CollapsibleContent>
              </CollapsibleRoot>
              <div v-if="message.decisionContent" class="decision-block"><span>角色决策</span><p>{{ message.decisionContent }}</p></div>
              <p>{{ message.content }}<span v-if="message.status === 'streaming'" class="stream-caret" /></p>
              <button v-if="conversation.mode === 'trpg' && message.speakerType === 'character' && message.status === 'failed'" class="retry-step-button" :disabled="sending" @click="emit('retry', message)"><RefreshCw :size="13" />重试该角色行动</button>
            </div>
          </article>
        </ScrollAreaViewport><ScrollAreaScrollbar orientation="vertical" class="scrollbar"><ScrollAreaThumb class="scrollbar-thumb" /></ScrollAreaScrollbar></ScrollAreaRoot>
        <div v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'selection'" class="scene-selection-panel">
          <strong>选择调查地点</strong><span>{{ currentTurn.sceneName || 'KP 已给出本轮可选地点' }}</span>
          <div class="scene-selection-options"><button v-for="[number, name] in selectionOptions" :key="number" class="button secondary" :disabled="sending" @click="emit('selectScene', number)"><b>{{ number }}</b>{{ name }}</button></div>
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
        <div class="reply-panel-title"><span><UsersRound :size="18" /><strong>{{ planTitle }}</strong></span><button class="icon-button subtle" @click="planOpen = !planOpen"><ChevronDown :size="17" :class="{ rotated: !planOpen }" /></button></div>
        <p>{{ planDescription }}</p>
        <div v-show="planOpen" class="reply-plan-list">
          <div v-for="(item, index) in items" :key="`${item.actorType}-${item.actorId}-${item.subjectCharacterId}`" class="reply-plan-item" :draggable="canEditPlan" @dragstart="draggedIndex = index" @dragover.prevent @drop="drop(index)">
            <GripVertical v-if="canEditPlan" class="drag-handle" :size="16" /><span class="reply-order">{{ index + 1 }}</span><span class="reply-avatar" :style="character(item.actorId)?.characterImage ? { backgroundImage: `url(${character(item.actorId)?.characterImage})` } : {}">{{ character(item.actorId)?.characterImage ? '' : (character(item.actorId)?.characterName || (item.actorType === 'kp' ? 'KP' : '?')).slice(0, 1) }}</span><span class="reply-name">{{ character(item.actorId)?.characterName || (item.actorType === 'kp' ? `KP · NPC #${item.subjectCharacterId}` : `角色 #${item.actorId}`) }}<small>{{ conversation.mode === 'trpg' ? '由跑团流程安排' : `第 ${index + 1} 位回复` }}</small></span><button v-if="canEditPlan" class="icon-button remove-plan" title="移除" @click="emit('deletePlanItem', index)"><Trash2 :size="15" /></button>
          </div>
          <div v-if="!items.length" class="plan-empty">暂无回复角色</div>
        </div>
        <div v-if="canEditPlan" class="add-plan-row"><select v-model="addActorId" :disabled="!availableCharacters.length"><option value="">{{ availableCharacters.length ? '添加参与角色' : '没有可添加角色' }}</option><option v-for="item in availableCharacters" :key="item.characterId" :value="String(item.characterId)">{{ item.characterName }}</option></select><button class="icon-button bordered" :disabled="!addActorId" @click="addActor"><Plus :size="17" /></button></div>
        <div v-if="canEditPlan" class="plan-actions"><button class="button secondary save-plan" :disabled="!items.length || sending" @click="emit('savePlan')"><Save :size="16" />保存顺序</button></div>
        <div class="panel-note"><strong>执行规则</strong><span>{{ conversation.mode === 'trpg' ? '场景与战斗会自动维护行动顺序，公共界面不能手动修改。' : '角色依次生成回复，后一位可以看到本轮前面角色刚完成的内容。' }}</span></div>
      </aside>
    </div>
  </main>
</template>
