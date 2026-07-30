<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, CircleStop, Footprints, GripVertical, LoaderCircle, MessageSquareText, Play, Plus, RefreshCw, Save, Send, Trash2, UsersRound } from '@lucide/vue'
import {
  CollapsibleContent, CollapsibleRoot, CollapsibleTrigger, ScrollAreaRoot, ScrollAreaScrollbar, ScrollAreaThumb, ScrollAreaViewport,
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { Character, Conversation, CurrentTurn, GroupMessage, ReplyPlan } from '@/api/types'

const input = defineModel<string>('input', { required: true })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const props = defineProps<{ conversation: Conversation; messages: GroupMessage[]; reasoning: Record<number, string>; characters: Character[]; replyPlan: ReplyPlan; availableCharacters: Character[]; currentTurn: CurrentTurn | null; sending: boolean; loading: boolean }>()
const emit = defineEmits<{ back: []; savePlan: []; movePlanItem: [from: number, to: number]; deletePlanItem: [index: number]; addPlanItem: [id: number]; send: []; startTurn: []; selectScene: [optionNo: string]; endExploration: []; retry: [message: GroupMessage]; end: [] }>()
const draggedIndex = ref<number | null>(null)
const addActorId = ref('')
const planOpen = ref(true)
const items = computed(() => props.replyPlan.groups[0]?.items || [])
const waitingForMessage = computed(() => props.conversation.mode !== 'trpg' || (props.currentTurn?.waitingForUser && props.currentTurn.inputType === 'message'))
const selectionOptions = computed(() => Object.entries(props.currentTurn?.sceneOptions || {}))
function character(id?: number) { return props.characters.find((item) => item.characterId === id) }
function drop(index: number) { if (draggedIndex.value !== null) emit('movePlanItem', draggedIndex.value, index); draggedIndex.value = null }
function addActor() { const id = Number(addActorId.value); if (id) { emit('addPlanItem', id); addActorId.value = '' } }
function keydown(event: KeyboardEvent) { if (!event.isComposing && event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); emit('send') } }
function bindScroller(element: unknown) { scroller.value = element instanceof HTMLElement ? element : null }
</script>

<template>
  <main class="chat-page">
    <header class="chat-header"><div><button class="text-button" @click="emit('back')">{{ conversation.mode === 'trpg' ? '跑团房间' : '群聊房间' }}</button><h1>{{ conversation.title }}</h1></div><div class="chat-header-actions"><span class="live-status" :class="conversation.status"><i />{{ conversation.status === 'active' ? '进行中' : '已结束' }}</span><button v-if="conversation.status === 'active'" class="button ghost danger-text" @click="emit('end')"><CircleStop :size="16" />结束群聊</button></div></header>
    <div class="chat-layout">
      <section class="chat-main">
        <ScrollAreaRoot class="message-scroll"><ScrollAreaViewport :ref="bindScroller" class="message-viewport">
          <div v-if="loading" class="chat-loading"><LoaderCircle class="spin" :size="22" />载入消息</div>
          <div v-else-if="!messages.length" class="empty-chat"><MessageSquareText :size="30" /><h2>对话从这里开始</h2><p>输入一句话，角色会按照右侧安排依次回应。</p></div>
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
          <button v-if="conversation.mode === 'trpg' && !currentTurn?.waitingForUser" class="button secondary turn-start-button" :disabled="sending || conversation.status !== 'active'" @click="emit('startTurn')"><LoaderCircle v-if="sending" class="spin" :size="17" /><Play v-else :size="17" />开始/继续行动轮</button>
          <textarea v-else v-model="input" :disabled="conversation.status !== 'active' || sending || !waitingForMessage" rows="1" :placeholder="conversation.status !== 'active' ? '这个群聊已经结束' : waitingForMessage ? '输入调查员的行动…' : '等待当前行动轮推进'" @keydown="keydown" />
          <button v-if="conversation.mode === 'trpg' && currentTurn?.waitingForUser && currentTurn.inputType === 'message' && replyPlan.source === 'SCENE'" class="button ghost" :disabled="sending" @click="emit('endExploration')"><Footprints :size="17" />结束探索</button>
          <TooltipProvider v-if="conversation.mode !== 'trpg' || waitingForMessage"><TooltipRoot><TooltipTrigger as-child><button class="send-button" :disabled="!input.trim() || sending || conversation.status !== 'active' || !waitingForMessage" @click="emit('send')"><LoaderCircle v-if="sending" class="spin" :size="19" /><Send v-else :size="19" /></button></TooltipTrigger><TooltipPortal><TooltipContent class="tooltip" :side-offset="8">Enter 发送 · Shift+Enter 换行</TooltipContent></TooltipPortal></TooltipRoot></TooltipProvider>
        </div>
      </section>
      <aside class="reply-panel">
        <div class="reply-panel-title"><span><UsersRound :size="18" /><strong>回复编排</strong></span><button class="icon-button subtle" @click="planOpen = !planOpen"><ChevronDown :size="17" :class="{ rotated: !planOpen }" /></button></div>
        <p>从上到下依次回复。拖动调整，点击移除。</p>
        <div v-show="planOpen" class="reply-plan-list">
          <div v-for="(item, index) in items" :key="`${item.actorType}-${item.actorId}-${item.subjectCharacterId}`" class="reply-plan-item" :draggable="replyPlan.source !== 'COMBAT'" :class="{ running: item.status === 'running', done: item.status === 'completed' }" @dragstart="draggedIndex = index" @dragover.prevent @drop="drop(index)">
            <GripVertical v-if="replyPlan.source !== 'COMBAT'" class="drag-handle" :size="16" /><span class="reply-order">{{ index + 1 }}</span><span class="reply-avatar" :style="character(item.actorId)?.characterImage ? { backgroundImage: `url(${character(item.actorId)?.characterImage})` } : {}">{{ character(item.actorId)?.characterImage ? '' : (character(item.actorId)?.characterName || (item.actorType === 'kp' ? 'KP' : '?')).slice(0, 1) }}</span><span class="reply-name">{{ character(item.actorId)?.characterName || (item.actorType === 'kp' ? `KP · NPC #${item.subjectCharacterId}` : `角色 #${item.actorId}`) }}<small>{{ item.status === 'running' ? '回复中' : item.status === 'completed' ? '本轮已完成' : '等待回复' }}</small></span><button v-if="replyPlan.source !== 'COMBAT'" class="icon-button remove-plan" title="移除" @click="emit('deletePlanItem', index)"><Trash2 :size="15" /></button>
          </div>
          <div v-if="!items.length" class="plan-empty">暂无回复角色</div>
        </div>
        <div v-if="replyPlan.source !== 'COMBAT'" class="add-plan-row"><select v-model="addActorId" :disabled="!availableCharacters.length"><option value="">{{ availableCharacters.length ? '添加参与角色' : '没有可添加角色' }}</option><option v-for="item in availableCharacters" :key="item.characterId" :value="String(item.characterId)">{{ item.characterName }}</option></select><button class="icon-button bordered" :disabled="!addActorId" @click="addActor"><Plus :size="17" /></button></div>
        <button v-if="replyPlan.source !== 'COMBAT'" class="button secondary save-plan" :disabled="!items.length || sending" @click="emit('savePlan')"><Save :size="16" />保存回复顺序</button>
        <div class="panel-note"><strong>当前规则</strong><span>每位角色依次生成，后一位能看到前一位刚完成的回复。</span></div>
      </aside>
    </div>
  </main>
</template>
