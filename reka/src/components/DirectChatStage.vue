<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ArrowLeft, BrainCircuit, History, LoaderCircle, RotateCcw, Send, Settings2 } from '@lucide/vue'
import { CollapsibleContent, CollapsibleRoot, CollapsibleTrigger } from 'reka-ui'
import type { Character, DirectMessage, UserWorld } from '@/api/types'
import { resetConversationScrollFollowing, scrollConversationToLatest, updateConversationScrollFollowing, updateReasoningScrollFollowing } from './reasoningScroll'

const input = defineModel<string>('input', { required: true })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const props = defineProps<{ world: UserWorld; character: Character; messages: DirectMessage[]; loading: { history: boolean; sending: boolean; withdrawing: boolean }; canWithdraw: boolean; hasOlderMessages: boolean }>()
const emit = defineEmits<{ back: []; send: []; withdraw: []; loadEarlier: []; edit: []; focus: []; composition: [value: boolean, input: string] }>()
const composing = ref(false)
const thinkingOpen = reactive<Record<string, boolean>>({})
const thinkingPhase = new Map<string, 'thinking' | 'main' | 'idle'>()
const lastScrollTop = ref(0)
const initialScrollPending = ref(true)
const conversationalMessages = computed(() => props.messages.filter((item) => item.role === 'user' || item.role === 'assistant').length)
let latestScrollFrame = 0

watch(() => `${props.loading.sending}:${props.messages.map((message) => `${message.id}:${message.role}:${Boolean(message.content.trim())}`).join('|')}`, syncThinkingState, { immediate: true, flush: 'sync' })
watch(() => props.character.characterId, () => {
  lastScrollTop.value = 0
  initialScrollPending.value = true
  if (scroller.value) resetConversationScrollFollowing(scroller.value)
}, { immediate: true })
watch(() => props.loading.history, (loading) => {
  if (!loading && initialScrollPending.value) { initialScrollPending.value = false; scrollToLatest() }
}, { immediate: true, flush: 'post' })
watch(() => `${props.loading.sending}:${props.messages.map((message) => `${message.id}:${message.content.length}`).join('|')}`, () => {
  if (props.loading.sending) scrollToLatest()
}, { flush: 'post' })
watch(() => props.loading.sending, (sending, wasSending) => { if (!sending && wasSending) scrollToLatest() }, { flush: 'post' })
onBeforeUnmount(() => cancelAnimationFrame(latestScrollFrame))

function syncThinkingState() {
  props.messages.forEach((message, index) => {
    if (message.role !== 'thinking') return
    const following = props.messages.slice(index + 1)
    const mainStarted = following.some((item) => item.role === 'assistant' && Boolean(item.content.trim()))
    const laterTurnStarted = following.some((item) => item.role === 'user')
    const phase = props.loading.sending && !mainStarted && !laterTurnStarted ? 'thinking' : mainStarted ? 'main' : 'idle'
    const previous = thinkingPhase.get(message.id)
    if (!previous) thinkingOpen[message.id] = phase === 'thinking'
    else if (previous === 'thinking' && phase !== 'thinking') thinkingOpen[message.id] = false
    thinkingPhase.set(message.id, phase)
  })
}

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
function handleScroll(event: Event) {
  const viewport = event.currentTarget as HTMLElement
  updateConversationScrollFollowing(viewport)
  const currentTop = viewport.scrollTop
  const movingUp = currentTop < lastScrollTop.value
  lastScrollTop.value = currentTop
  if (movingUp && currentTop <= 32 && props.hasOlderMessages && !props.loading.history) emit('loadEarlier')
}
function handleReasoningScroll(event: Event) {
  updateReasoningScrollFollowing(event.currentTarget as HTMLElement)
}
function composition(value: boolean, event: CompositionEvent) {
  composing.value = value; const target = event.target as HTMLTextAreaElement; emit('composition', value, target.value)
}
function keydown(event: KeyboardEvent) {
  if (!composing.value && !event.isComposing && event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); emit('send') }
}
</script>

<template>
  <main class="chat-page direct-chat-page">
    <header class="chat-header">
      <div class="direct-chat-heading"><button class="icon-button bordered" aria-label="返回世界" @click="emit('back')"><ArrowLeft :size="17" /></button><span class="message-avatar large" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span><span><small>与角色单独对话</small><h1>{{ character.characterName }}</h1></span></div>
      <div class="chat-header-actions"><span class="live-status active"><i />{{ world.thinkStatus === false ? '输入检测模式' : '流式思考模式' }}</span><button class="button ghost" @click="emit('edit')"><Settings2 :size="16" />角色资料</button></div>
    </header>
    <div class="direct-chat-layout">
      <section class="chat-main">
        <div class="message-scroll"><div :ref="bindScroller" class="message-viewport" @scroll="handleScroll"><div>
          <div v-if="loading.history && !messages.length" class="chat-loading"><LoaderCircle class="spin" :size="22" />载入消息</div>
          <button v-else-if="hasOlderMessages" class="load-earlier-button" :disabled="loading.history" @click="emit('loadEarlier')"><LoaderCircle v-if="loading.history" class="spin" :size="14" /><History v-else :size="14" />加载更早记录</button>
          <div v-else-if="!messages.length" class="empty-chat"><BrainCircuit :size="30" /><h2>和 {{ character.characterName }} 开始对话</h2><p>角色会结合世界背景、历史记忆和好感度回应。</p></div>
          <template v-for="message in messages" :key="message.id">
            <CollapsibleRoot v-if="message.role === 'thinking'" v-model:open="thinkingOpen[message.id]" class="direct-thinking"><CollapsibleTrigger class="reasoning-trigger">思考过程</CollapsibleTrigger><CollapsibleContent class="reasoning-content" :data-reasoning-streaming="thinkingPhase.get(message.id) === 'thinking' ? 'true' : undefined" @scroll="handleReasoningScroll">{{ message.content }}</CollapsibleContent></CollapsibleRoot>
            <div v-else-if="message.role === 'tool'" class="direct-tool">{{ message.content }}</div>
            <article v-else class="chat-message" :class="message.role">
              <div v-if="message.role === 'assistant'" class="message-avatar" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</div>
              <div class="message-content"><div class="message-meta"><strong>{{ message.role === 'user' ? '你' : character.characterName }}</strong><span v-if="message.complete === false" class="failed-label">未完成</span></div><p>{{ message.content }}</p><small v-if="message.time" class="message-time">{{ message.time }}</small></div>
            </article>
          </template>
        </div></div></div>
        <div class="composer direct-composer"><button class="icon-button withdraw-button" :disabled="!canWithdraw" title="撤回上一轮" @click="emit('withdraw')"><LoaderCircle v-if="loading.withdrawing" class="spin" :size="17" /><RotateCcw v-else :size="17" /></button><textarea v-model="input" rows="1" placeholder="输入给角色的消息…" :disabled="loading.sending" @focus="emit('focus')" @compositionstart="composition(true, $event)" @compositionend="composition(false, $event)" @keydown="keydown" /><button class="send-button" :disabled="!input.trim() || loading.sending" @click="emit('send')"><LoaderCircle v-if="loading.sending" class="spin" :size="19" /><Send v-else :size="19" /></button></div>
      </section>
      <aside class="direct-character-panel"><span class="character-avatar portrait" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span><h2>{{ character.characterName }}</h2><small>{{ world.name }}</small><div class="favor-card"><span>好感度</span><strong>{{ character.favorValue ?? 0 }}</strong><progress :value="character.favorValue ?? 0" max="100" /></div><div class="profile-note"><strong>角色长期记住的用户信息</strong><p>{{ character.userInfoPrompt || '暂未记录' }}</p></div><div class="profile-note"><strong>最近对话</strong><p>{{ character.lastChatContent || `${conversationalMessages} 条对话消息` }}</p><small>{{ character.lastChatTime || '' }}</small></div><button class="button secondary" @click="emit('edit')"><Settings2 :size="16" />编辑角色资料</button></aside>
    </div>
  </main>
</template>
