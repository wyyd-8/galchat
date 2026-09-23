<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ArrowDown, ArrowLeft, BookOpen, BrainCircuit, ChevronRight, History, LoaderCircle, MoreHorizontal, Pencil, Plus, RotateCcw, Send, Settings2, Trash2 } from '@lucide/vue'
import { CollapsibleContent, CollapsibleRoot, CollapsibleTrigger } from 'reka-ui'
import BaseDialog from './ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { rememberChatReadingPosition, restoreChatReadingPosition } from './chatReadingPosition'
import { shouldSubmitChatKey } from './chatInputState'
import { mergeDirectProfileDraft } from './directProfileDraft'
import '@/styles/mobile-chat.css'
import type { Character, DirectMessage, ModelApi, UserWorld } from '@/api/types'
import { resetConversationScrollFollowing, scrollConversationToLatest, updateConversationScrollFollowing, updateReasoningScrollFollowing } from './reasoningScroll'

const input = defineModel<string>('input', { required: true })
const scroller = defineModel<HTMLElement | null>('scroller', { required: true })
const props = defineProps<{ world: UserWorld; character: Character; messages: DirectMessage[]; modelApis: ModelApi[]; loading: { history: boolean; sending: boolean; withdrawing: boolean; model: boolean }; canWithdraw: boolean; hasOlderMessages: boolean; settingsSaving?: boolean; settingsError?: string; canEditTemplate?: boolean; removing?: boolean }>()
const emit = defineEmits<{ saveSettings: [settings: { userInfoPrompt: string; modelApiId?: number }]; editTemplate: []; remove: []; back: []; send: []; withdraw: []; loadEarlier: []; edit: []; selectModel: [modelApiId?: number]; focus: []; composition: [value: boolean, input: string] }>()
const { isMobile } = useMobileViewport()
const profileOpen = ref(false)
const noteOpen = ref(false)
const noteInput = ref<HTMLTextAreaElement | null>(null)
const settingsLocked = computed(() => props.settingsSaving || props.removing || props.loading.history || props.loading.model || props.loading.sending)
const menuOpen = ref(false)
const settingsDraft = reactive({ userInfoPrompt: '', modelApiId: '' })
let profileScope = ''
let savedProfile = { userInfoPrompt: '', modelApiId: '' }
function characterProfile() {
  return { userInfoPrompt: props.character.userInfoPrompt || '', modelApiId: props.character.modelApiId ? String(props.character.modelApiId) : '' }
}
watch(() => [props.world.id, props.character.characterId, props.character.userInfoPrompt, props.character.modelApiId], () => {
  const scope = `${props.world.id}:${props.character.characterId}`
  const next = characterProfile()
  Object.assign(settingsDraft, scope === profileScope ? mergeDirectProfileDraft(settingsDraft, savedProfile, next) : next)
  savedProfile = next
  profileScope = scope
}, { immediate: true, flush: 'sync' })
function closeProfile() {
  Object.assign(settingsDraft, characterProfile())
  savedProfile = characterProfile()
  profileOpen.value = false
  noteOpen.value = false
}
defineExpose({ closeProfile })
async function editNote() {
  settingsDraft.userInfoPrompt = props.character.userInfoPrompt || ''
  noteOpen.value = true
  await nextTick()
  noteInput.value?.focus()
}
function cancelNote() {
  settingsDraft.userInfoPrompt = props.character.userInfoPrompt || ''
  noteOpen.value = false
}
function saveNote() {
  if (settingsLocked.value) return
  emit('saveSettings', { userInfoPrompt: settingsDraft.userInfoPrompt, modelApiId: props.character.modelApiId })
}
function saveSettings() {
  if (props.settingsSaving || props.removing || props.loading.history || props.loading.model || props.loading.sending) return
  emit('saveSettings', { userInfoPrompt: settingsDraft.userInfoPrompt, modelApiId: settingsDraft.modelApiId ? Number(settingsDraft.modelApiId) : undefined })
}
const withdrawOpen = ref(false)
const awayFromLatest = ref(false)
const readingKey = computed(() => `world:${props.world.id}:direct:${props.character.characterId}`)
const inputElement = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
const thinkingOpen = reactive<Record<string, boolean>>({})
const thinkingPhase = new Map<string, 'thinking' | 'main' | 'idle'>()
const lastScrollTop = ref(0)
const initialScrollPending = ref(true)
let restorationBoundary: string | null = null
const conversationalMessages = computed(() => props.messages.filter((item) => item.role === 'user' || item.role === 'assistant').length)
let latestScrollFrame = 0

watch(() => `${props.loading.sending}:${props.messages.map((message) => `${message.id}:${message.role}:${Boolean(message.content.trim())}`).join('|')}`, syncThinkingState, { immediate: true, flush: 'sync' })
watch(() => `${props.world.id}:${props.character.characterId}`, () => {
  profileOpen.value = false
  noteOpen.value = false
  menuOpen.value = false
  withdrawOpen.value = false
  awayFromLatest.value = false
  lastScrollTop.value = 0
  initialScrollPending.value = true
  restorationBoundary = null
  if (scroller.value) resetConversationScrollFollowing(scroller.value)
}, { immediate: true })
watch(() => props.loading.history, (loading) => {
  if (!loading && initialScrollPending.value) restoreReadingPosition()
}, { immediate: true, flush: 'post' })
watch(() => `${props.loading.sending}:${props.messages.map((message) => `${message.id}:${message.content.length}`).join('|')}`, () => {
  if (props.loading.sending) scrollToLatest()
}, { flush: 'post' })
watch(() => props.loading.sending, (sending, wasSending) => { if (!sending && wasSending) scrollToLatest() }, { flush: 'post' })
onBeforeUnmount(() => {
  if (scroller.value && !props.loading.history && !initialScrollPending.value) rememberChatReadingPosition(readingKey.value, scroller.value)
  cancelAnimationFrame(latestScrollFrame)
})

function syncThinkingState() {
  props.messages.forEach((message, index) => {
    if (message.role !== 'thinking') return
    const following = props.messages.slice(index + 1)
    const mainStarted = following.some((item) => item.role === 'assistant' && Boolean(item.content.trim()))
    const laterTurnStarted = following.some((item) => item.role === 'user')
    const phase = props.loading.sending && !mainStarted && !laterTurnStarted ? 'thinking' : mainStarted ? 'main' : 'idle'
    const previous = thinkingPhase.get(message.id)
    if (!previous) thinkingOpen[message.id] = !isMobile.value && phase === 'thinking'
    else if (previous === 'thinking' && phase !== 'thinking') thinkingOpen[message.id] = false
    thinkingPhase.set(message.id, phase)
  })
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
function handleScroll(event: Event) {
  const viewport = event.currentTarget as HTMLElement
  updateConversationScrollFollowing(viewport)
  if (!initialScrollPending.value && !props.loading.history) rememberChatReadingPosition(readingKey.value, viewport)
  awayFromLatest.value = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop > 32
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
  if (shouldSubmitChatKey(event, isMobile.value, composing.value)) { event.preventDefault(); emit('send') }
}
function restoreReadingPosition() {
  void nextTick(() => {
    cancelAnimationFrame(latestScrollFrame)
    latestScrollFrame = requestAnimationFrame(() => {
      const viewport = scroller.value
      if (!viewport || props.loading.history) return
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
function editCharacter() { profileOpen.value = false; emit('edit') }
watch(input, () => nextTick(resizeInput))
function resizeInput() {
  const target = inputElement.value
  if (!target) return
  target.style.height = 'auto'
  target.style.height = `${Math.min(target.scrollHeight, 130)}px`
}
function selectModel(event: Event) {
  if (settingsLocked.value) return
  const value = (event.target as HTMLSelectElement).value
  emit('selectModel', value ? Number(value) : undefined)
}
</script>

<template>
  <main class="chat-page direct-chat-page">
    <header class="chat-header">
      <div v-if="!isMobile" class="direct-chat-heading"><button class="icon-button bordered" aria-label="返回世界" @click="emit('back')"><ArrowLeft :size="17" /></button><span class="message-avatar large" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span><span><small>与角色单独对话</small><h1>{{ character.characterName }}</h1></span></div>
      <div v-if="!isMobile" class="chat-header-actions"><span class="live-status active"><i />{{ world.thinkStatus === false ? (world.eotDetectionStatus ? '自动识别输入结束' : '连续消息模式') : '逐步显示思考与回复' }}</span></div>
      <template v-if="isMobile"><button class="icon-button" aria-label="返回世界" @click="emit('back')"><ArrowLeft :size="23" /></button><button class="mobile-chat-title" @click="profileOpen = true"><strong>{{ character.characterName }}</strong><small>{{ world.name }} · 单聊</small></button><button class="icon-button" aria-label="角色详情" @click="profileOpen = true"><MoreHorizontal :size="22" /></button></template>
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
            <article v-else class="chat-message" :class="message.role" :data-message-id="message.id">
              <div v-if="message.role === 'assistant'" class="message-avatar" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</div>
              <div class="message-content"><div class="message-meta"><strong>{{ message.role === 'user' ? '你' : character.characterName }}</strong><span v-if="message.complete === false" class="failed-label">未完成</span></div><p>{{ message.content }}</p><small v-if="message.time" class="message-time">{{ message.time }}</small></div>
            </article>
          </template>
        </div></div></div>
        <button v-if="awayFromLatest" class="chat-jump-latest" aria-label="回到最新" title="回到最新" @click="returnToLatest"><ArrowDown :size="16" /><span class="chat-jump-latest-label">回到最新</span></button>
        <div class="composer direct-composer"><button v-if="isMobile" class="icon-button" aria-label="聊天操作" @click="menuOpen = true"><Plus :size="23" /></button><button v-if="!isMobile" class="icon-button withdraw-button" :disabled="!canWithdraw" :title="canWithdraw ? '撤回上一轮' : '暂无可撤回的用户消息，或当前仍在处理消息'" aria-label="撤回上一轮" @click="emit('withdraw')"><LoaderCircle v-if="loading.withdrawing" class="spin" :size="17" /><RotateCcw v-else :size="17" /></button><textarea ref="inputElement" v-model="input" rows="1" placeholder="输入给角色的消息…" :disabled="loading.sending" @input="resizeInput" aria-label="给角色的消息" @focus="emit('focus')" @compositionstart="composition(true, $event)" @compositionend="composition(false, $event)" @keydown="keydown" /><button class="send-button" aria-label="发送消息" :disabled="!input.trim() || loading.sending" @click="emit('send')"><LoaderCircle v-if="loading.sending" class="spin" :size="19" /><Send v-else :size="19" /></button></div>
      </section>
      <div v-if="!isMobile" class="chat-side-host">
        <aside class="direct-character-panel" aria-label="角色面板">
          <div class="direct-panel-heading">
            <span>{{ profileOpen ? '角色设置' : '角色信息' }}</span>
            <button v-if="profileOpen" class="direct-panel-link" :disabled="settingsSaving" @click="closeProfile">返回概览</button>
            <button v-else class="icon-button" aria-label="打开角色设置" :disabled="settingsSaving" @click="profileOpen = true"><Settings2 :size="16" /></button>
          </div>
          <div class="direct-panel-identity">
            <span class="character-avatar direct-panel-portrait" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span>
            <div><h2>{{ character.characterName }}</h2><p><BookOpen :size="13" /><span>{{ world.name }}</span></p></div>
          </div>
          <template v-if="!profileOpen">
            <section class="direct-panel-favor" aria-labelledby="direct-favor-title">
              <div class="direct-panel-row"><h3 id="direct-favor-title">好感度</h3><strong>{{ character.favorValue ?? 0 }} <small>/ 100</small></strong></div>
              <progress :value="character.favorValue ?? 0" max="100" aria-label="好感度" />
              <p class="direct-panel-hint">随你们的对话而变化</p>
            </section>
            <section class="direct-panel-section" aria-labelledby="direct-note-title">
              <div class="direct-panel-row"><h3 id="direct-note-title">希望角色记住的事</h3><button v-if="!noteOpen" class="direct-panel-link" :disabled="settingsLocked" @click="editNote"><Pencil :size="14" />编辑</button></div>
              <form v-if="noteOpen" class="direct-note-editor" @submit.prevent="saveNote">
                <label for="direct-note-input" class="direct-panel-hint">称呼、经历，或你们之间的约定</label>
                <textarea id="direct-note-input" ref="noteInput" v-model="settingsDraft.userInfoPrompt" rows="5" :disabled="settingsSaving" placeholder="写下希望角色记住的事…" />
                <div class="direct-panel-actions"><button type="button" class="button secondary" :disabled="settingsSaving" @click="cancelNote">取消</button><button type="submit" class="button primary" :disabled="settingsLocked"><LoaderCircle v-if="settingsSaving" class="spin" :size="14" />{{ settingsSaving ? '保存中…' : '保存' }}</button></div>
              </form>
              <p v-else class="direct-panel-note" :class="{ empty: !character.userInfoPrompt }">{{ character.userInfoPrompt || '写下希望角色记住的称呼、经历或约定。' }}</p>
            </section>
            <section class="direct-panel-section direct-model-picker">
              <label for="direct-panel-model">回复模型</label>
              <select id="direct-panel-model" :value="character.modelApiId ? String(character.modelApiId) : ''" :disabled="settingsLocked" aria-label="选择单聊回复模型" @change="selectModel"><option value="">默认模型</option><option v-if="character.modelApiId && !modelApis.some(model => model.id === character.modelApiId)" :value="String(character.modelApiId)">原配置不可用（使用默认）</option><option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option></select>
              <p class="direct-panel-hint" role="status">{{ loading.model ? '正在切换模型…' : '新回复将使用所选模型' }}</p>
            </section>
          </template>
          <form v-else class="direct-panel-settings" @submit.prevent="saveSettings">
            <label class="field"><span>回复模型</span><select v-model="settingsDraft.modelApiId" :disabled="settingsLocked" aria-label="选择单聊回复模型"><option value="">默认模型</option><option v-if="character.modelApiId && !modelApis.some(model => model.id === character.modelApiId)" :value="String(character.modelApiId)">原配置不可用（使用默认）</option><option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option></select></label>
            <label class="field"><span>希望角色记住的事</span><textarea v-model="settingsDraft.userInfoPrompt" rows="5" :disabled="settingsSaving" placeholder="写下希望角色记住的称呼、经历或约定…" /></label>
            <section v-if="canEditTemplate" class="direct-panel-section direct-panel-author"><h3>作者设置</h3><button type="button" class="direct-panel-setting-row" :disabled="settingsSaving" @click="emit('editTemplate')"><span>编辑角色模板</span><ChevronRight :size="16" /></button><button type="button" class="direct-panel-setting-row" :disabled="settingsSaving" @click="editCharacter"><span>调整好感度</span><ChevronRight :size="16" /></button></section>
            <div class="direct-panel-actions"><button type="button" class="button secondary" :disabled="settingsSaving" @click="closeProfile">取消</button><button type="submit" class="button primary" :disabled="settingsLocked"><LoaderCircle v-if="settingsSaving" class="spin" :size="14" />{{ settingsSaving ? '保存中…' : '保存设置' }}</button></div>
          </form>
          <button v-if="profileOpen" type="button" class="direct-panel-setting-row direct-panel-remove danger-text" :disabled="settingsLocked" @click="emit('remove')"><span>{{ removing ? '正在移出…' : '移出当前世界' }}</span><LoaderCircle v-if="removing" class="spin" :size="16" /><Trash2 v-else :size="16" /></button>
          <p v-if="settingsError" class="direct-panel-error" role="alert">{{ settingsError }}</p>
          <footer class="direct-panel-footer"><span>{{ hasOlderMessages ? '已加载 ' : '' }}{{ conversationalMessages }} 条对话消息</span><span>与{{ character.characterName }}的对话</span></footer>
        </aside>
      </div>
    </div>

    <BaseDialog v-if="isMobile" v-model="profileOpen" title="角色详情" mobile-presentation="page" content-class="mobile-chat-profile mobile-chat-panel">
      <div class="mobile-profile-hero"><span class="character-avatar portrait" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span><h1>{{ character.characterName }}</h1><p>{{ world.name }} · 角色设置</p></div>
      <section class="mobile-favor-card"><div><span>好感度</span><strong>{{ character.favorValue ?? 0 }} <small>/ 100</small></strong></div><progress :value="character.favorValue ?? 0" max="100" /><p>好感变化会影响角色对你的回应。</p></section>
      <label class="field"><span>回复模型</span><select v-model="settingsDraft.modelApiId" :disabled="loading.history || loading.model || loading.sending || settingsSaving" aria-label="选择单聊回复模型"><option value="">默认模型</option><option v-if="character.modelApiId && !modelApis.some(model => model.id === character.modelApiId)" :value="String(character.modelApiId)">原配置不可用（使用默认）</option><option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option></select></label>
      <label class="field"><span>希望角色记住的事</span><textarea v-model="settingsDraft.userInfoPrompt" rows="4" :disabled="settingsSaving" placeholder="写下希望角色记住的称呼、经历或约定…" /></label>
      <button v-if="canEditTemplate" class="mobile-chat-row" @click="profileOpen = false; emit('editTemplate')"><span><strong>编辑角色模板</strong><small>仅模板作者可用</small></span><span>›</span></button>
      <button v-if="canEditTemplate" class="mobile-chat-row" @click="editCharacter"><span><strong>调整好感度</strong><small>角色作者可修改好感设置</small></span><span>›</span></button>
      <button class="mobile-chat-row danger-text" :disabled="settingsLocked" @click="emit('remove')"><span><strong>{{ removing ? '正在移出…' : '移出当前世界' }}</strong></span><LoaderCircle v-if="removing" class="spin" :size="18" /><Trash2 v-else :size="18" /></button>
      <p v-if="settingsError" class="mobile-chat-error" role="alert">{{ settingsError }}</p>
      <template #footer><button class="button primary" :disabled="settingsSaving || loading.history || loading.model || loading.sending" @click="saveSettings"><LoaderCircle v-if="settingsSaving" class="spin" :size="17" />{{ settingsSaving ? '保存中…' : '保存设置' }}</button></template>
    </BaseDialog>
    <BaseDialog v-if="isMobile" v-model="menuOpen" title="聊天操作" mobile-presentation="sheet" content-class="mobile-chat-menu">
      <button class="mobile-chat-row" :disabled="!canWithdraw" @click="menuOpen = false; withdrawOpen = true"><span><strong>撤回上一轮</strong></span><RotateCcw :size="18" /></button>
      <button class="mobile-chat-row" @click="menuOpen = false; profileOpen = true"><span><strong>角色设置</strong></span><span>›</span></button>
      <button class="mobile-chat-row" :disabled="!hasOlderMessages || loading.history" @click="menuOpen = false; emit('loadEarlier')"><span><strong>加载更早记录</strong></span><History :size="18" /></button>
      <template #footer><button class="button secondary" @click="menuOpen = false">取消</button></template>
    </BaseDialog>
    <BaseDialog v-if="isMobile" v-model="withdrawOpen" title="撤回上一轮" mobile-presentation="page" content-class="mobile-chat-confirm"><p class="mobile-chat-notice mobile-withdraw-notice">撤回会删除上一条用户消息及其触发的回复，并回滚相应好感变化。</p><h3>当前操作范围</h3><p class="mobile-chat-muted">{{ character.characterName }} · 上一轮对话</p><template #footer><button class="button secondary" @click="withdrawOpen = false">取消</button><button class="button danger" :disabled="!canWithdraw || loading.withdrawing" @click="withdrawOpen = false; emit('withdraw')">确认撤回</button></template></BaseDialog>
  </main>
</template>
