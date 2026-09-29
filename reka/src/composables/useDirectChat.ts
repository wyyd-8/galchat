import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch, type ComputedRef, type Ref } from 'vue'
import { api, streamChat, resumeChat, currentSession } from '@/api/client'
import type { Character, ChatFlux, ChatHistory, ChatMessagePayload, DirectMessage, GenerationErrorDetail, ModelApi, UserWorld } from '@/api/types'
import { resetConversationScrollFollowing, scrollConversationToLatest } from '@/components/reasoningScroll'
import { clearChatReadingPositions } from '@/components/chatReadingPosition'
import { useScopedChatDraft } from '@/components/chatInputState'
import { errorMessage, notify } from './useNotice'
import { followGeneration, GenerationStartRejected } from '@/streaming/generationConnection'
import { createCarePolling } from './carePolling'

interface DirectChatContext {
  world: ComputedRef<UserWorld | null>
  characters: Ref<Character[]>
  reloadCharacters: () => Promise<void>
}

function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '' }
function newRequestId() { return crypto.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(36).slice(2)}` }
function insertCareMessage(messages: DirectMessage[], message: DirectMessage) {
  if (messages.some(item => item.historyId === message.historyId)) return
  const index = messages.findIndex(item => (item.userMessageId ?? item.historyId ?? Infinity) > message.historyId!)
  messages.splice(index < 0 ? messages.length : index, 0, message)
}
// The server pages primary messages, then expands their replies, reasoning and tools.
function hasOlderHistory(history: ChatHistory[]) {
  return history.filter(item => item.type == null || item.type.toLowerCase() === 'user'
    || (item.type.toLowerCase() === 'assistant' && item.userMessageId == null)).length === 30
}

export function useDirectChat(context: DirectChatContext) {
  interface Failure {
    message: string; detail: GenerationErrorDetail; payload: ChatMessagePayload
    userMessageId?: number; retry: 'withdraw' | 'send' | null
    recovery?: { historyIds: number[] | null; withdrawalRequested?: boolean }
    errorIds: string[]
  }
  interface Generation {
    requestId: string; sequence: number; base: DirectMessage[]; live: DirectMessage[]; following: DirectMessage[]
    assistant: DirectMessage | null; thinkingNeedsSeparator: boolean
    historyIds: number[] | null
    retryErrorIds: string[]
  }
  function freshState(world?: UserWorld, characterId = 0) {
    return reactive({ world, characterId, messages: [] as DirectMessage[], modelApis: [] as ModelApi[],
      loading: { history: false, sending: false, withdrawing: false, model: false },
      hasOlder: false, historyReady: false, generation: null as Generation | null,
      failure: null as Failure | null, failureOpen: false,
      controller: null as AbortController | null, historyRevision: 0, modelListRevision: 0 })
  }
  type State = ReturnType<typeof freshState>
  const states = new Map<string, State>()
  const active = shallowRef(freshState())
  let disposed = false
  let epoch = 0
  let selectionRevision = 0
  const selectedCharacterId = ref<number | null>(null)
  const messages = computed(() => active.value.messages)
  const input = ref('')
  const chatDrafts = useScopedChatDraft(computed(() => context.world.value && selectedCharacterId.value != null ? `world:${context.world.value.id}:direct:${selectedCharacterId.value}` : null), { input })
  const scroller = ref<HTMLElement | null>(null)
  const modelApis = computed(() => active.value.modelApis)
  const loading = reactive({
    get history() { return active.value.loading.history },
    get sending() { return active.value.loading.sending },
    get withdrawing() { return active.value.loading.withdrawing },
    get model() { return active.value.loading.model },
  })
  const hasOlderMessages = computed(() => active.value.hasOlder)
  const generationFailure = computed(() => active.value.failure)
  const generationFailureOpen = computed({ get: () => active.value.failureOpen, set: value => { active.value.failureOpen = value } })
  const storagePrefix = () => `galchat.direct-generation:${typeof localStorage === 'undefined' ? '' : currentSession().id ?? ''}:`
  const stateKey = (worldId: number, characterId: number) => `${storagePrefix()}${worldId}:${characterId}`
  function pending(state: State) {
    try { return sessionStorage.getItem(stateKey(state.world!.id, state.characterId)) } catch { return null }
  }
  function remember(state: State, requestId: string | null) {
    try {
      const key = stateKey(state.world!.id, state.characterId)
      if (requestId) sessionStorage.setItem(key, requestId)
      else sessionStorage.removeItem(key)
    } catch { /* Storage may be unavailable; in-page generation still works. */ }
  }
  function isActive(state: State) { return active.value === state && context.world.value?.id === state.world?.id && selectedCharacterId.value === state.characterId }
  function notifyFor(state: State, title: string, detail: string) { if (isActive(state)) notify(title, detail, 'danger') }
  const selectedCharacter = computed(() => context.characters.value.find((item) => item.characterId === selectedCharacterId.value) || null)
  const withdrawalTarget = computed(() => [...messages.value].reverse().find(item => item.historyId != null
    && item.complete !== false && (item.role === 'user'
      || (item.role === 'assistant' && item.userMessageId == null))))
  const canWithdraw = computed(() => {
    if (!active.value.historyReady || loading.history || loading.sending || loading.withdrawing || active.value.generation) return false
    return withdrawalTarget.value != null
  })
  const canRetryGenerationFailure = computed(() => {
    const state = active.value; const failure = state.failure
    return Boolean(isActive(state) && failure?.retry && state.historyReady && !state.generation
      && !loading.history && !loading.sending && !loading.withdrawing && !loading.model
      && (failure.retry === 'send' || (canWithdraw.value && failure.userMessageId === withdrawalTarget.value?.historyId)))
  })

  let notificationAsked = false

  function roleOf(item: ChatHistory): DirectMessage['role'] {
    if (item.type === 'thinking') return 'thinking'
    if (item.type === 'tool') return 'tool'
    if (item.type === 'ASSISTANT' || item.type === 'assistant') return 'assistant'
    return 'user'
  }
  function historyMessages(history: ChatHistory[]): DirectMessage[] {
    const result: DirectMessage[] = []
    let currentUserMessageId: number | undefined
    let currentThinking: { message: DirectMessage; reasoning: string; toolCount: number } | null = null
    history.forEach((item, itemIndex) => {
      const role = roleOf(item)
      if (role === 'user') currentUserMessageId = item.id
      if (role === 'tool') {
        if (!currentThinking) {
          const message: DirectMessage = {
            id: `history-thinking-${item.userMessageId || item.id || Date.now()}-${item.stepNo ?? itemIndex}`,
            role: 'thinking',
            userMessageId: item.userMessageId ?? currentUserMessageId,
            content: '',
          }
          result.push(message)
          currentThinking = { message, reasoning: '', toolCount: 0 }
        }
        currentThinking.toolCount += 1
        const summary = `调用了${currentThinking.toolCount}次工具`
        currentThinking.message.content = currentThinking.reasoning ? `${summary}\n\n${currentThinking.reasoning}` : summary
        return
      }

      const content = item.content || ''
      if (role === 'thinking') {
        if (currentThinking) {
          currentThinking.reasoning += content
          const summary = currentThinking.toolCount ? `调用了${currentThinking.toolCount}次工具` : ''
          currentThinking.message.content = summary
            ? `${summary}\n\n${currentThinking.reasoning}`
            : currentThinking.reasoning
          return
        }
        const message: DirectMessage = {
          id: `history-${item.id || item.userMessageId || Date.now()}-${item.stepNo ?? itemIndex}`,
          historyId: item.id,
          userMessageId: item.userMessageId ?? currentUserMessageId,
          role,
          content,
          time: formatTime(item.timestamp),
        }
        result.push(message)
        currentThinking = { message, reasoning: content, toolCount: 0 }
        return
      }

      currentThinking = null
      result.push({
        id: `history-${item.id || Date.now()}-0`,
        historyId: item.id,
        userMessageId: item.userMessageId ?? (role === 'user' ? item.id : undefined),
        role,
        content,
        time: formatTime(item.timestamp),
      })
    })
    return result
  }
  function payload(message = ''): ChatMessagePayload | null {
    const world = context.world.value; const character = selectedCharacter.value
    if (!world?.id || !world.worldId || !character) return null
    return { worldId: world.worldId, userWorldId: world.id, characterId: character.characterId, message }
  }
  async function scrollToBottom(force = false) {
    await nextTick()
    const viewport = scroller.value
    if (!viewport) return
    if (force) resetConversationScrollFollowing(viewport)
    scrollConversationToLatest(viewport)
  }

  async function loadHistory(state: State, preserveOlder = false) {
    const revision = ++state.historyRevision; const ownEpoch = epoch
    const messagesAtStart = new Set(state.messages)
    state.loading.history = true
    if (state.failure?.recovery) {
      state.failure.retry = null; state.failure.detail.retryable = false
    }
    const valid = () => !disposed && ownEpoch === epoch && revision === state.historyRevision
    const modelRevision = ++state.modelListRevision
    const validModels = () => !disposed && ownEpoch === epoch && modelRevision === state.modelListRevision
    void api.modelApis().then(models => {
      if (validModels()) state.modelApis = models
    }).catch(error => {
      if (validModels()) notifyFor(state, '模型列表加载失败', errorMessage(error))
    })
    try {
      const history = await api.history(state.world!.id, state.characterId)
      if (!valid()) return
      const errors = state.messages.filter(message => message.complete === false)
      const hasOlder = hasOlderHistory(history)
      const boundary = Math.min(...history.flatMap(item => item.id == null ? [] : [item.id]))
      const older = preserveOlder && hasOlder ? state.messages.filter(message => {
        const id = message.userMessageId ?? message.historyId
        return message.complete !== false && id != null && id < boundary
      }) : []
      const arrivedCare = state.messages.filter(message => !messagesAtStart.has(message)
        && message.role === 'assistant' && message.historyId != null && message.userMessageId == null)
      state.messages = [...older, ...historyMessages(history), ...errors]
      arrivedCare.forEach(message => insertCareMessage(state.messages, message))
      state.historyReady = true
      reconcileFailure(state, history)
      // Retained pages own the oldest cursor, including an already exhausted one.
      if (!older.length) state.hasOlder = hasOlder
      return history
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '单聊记录加载失败', errorMessage(error)) }
    finally { if (revision === state.historyRevision) state.loading.history = false }
  }
  function reconcileFailure(state: State, history: ChatHistory[]) {
    const failure = state.failure
    if (!failure?.recovery || !failure.payload.message) return
    if (failure.userMessageId != null) {
      // Only generation.user identifies the row owned by this request. Matching text
      // may belong to an older round or another browser tab and cannot authorize deletion.
      const present = history.some(message => roleOf(message) === 'user' && message.id === failure.userMessageId)
      // An absent row proves withdrawal only if this page reaches its ID (or is the
      // entire history). A full page of newer messages cannot confirm deletion.
      const coversTarget = !hasOlderHistory(history)
        || history.some(message => message.id != null && message.id < failure.userMessageId!)
      if (!present && coversTarget && failure.recovery.withdrawalRequested) {
        prepareFailureResend(state, failure)
        return
      }
      failure.retry = present ? 'withdraw' : null
    } else {
      const previousIds = failure.recovery.historyIds
      // Without an anchor, resending is safe only if there are no new persisted rows.
      // Keep the pre-send snapshot across failed history reads so a later reload can retry.
      failure.retry = history.length === 0 || (previousIds != null
        && history.every(message => message.id == null || previousIds.includes(message.id))) ? 'send' : null
    }
    failure.detail.retryable = failure.retry != null
    if (failure.retry === 'send') {
      chatDrafts.restoreIfEmpty(`world:${state.world!.id}:direct:${state.characterId}`, { input: failure.payload.message })
    }
  }
  function prepareFailureResend(state: State, failure: Failure) {
    failure.retry = 'send'; failure.userMessageId = undefined; failure.recovery = undefined
    failure.detail.retryable = true
    chatDrafts.restoreIfEmpty(`world:${state.world!.id}:direct:${state.characterId}`, { input: failure.payload.message })
  }
  async function selectCharacter(id: number) {
    const world = context.world.value
    if (!world) return
    const selection = ++selectionRevision
    selectedCharacterId.value = id
    const key = stateKey(world.id, id)
    let state = states.get(key)
    if (!state) { state = freshState({ ...world }, id); states.set(key, state) }
    state.world = { ...world }
    active.value = state
    if (!state.loading.sending && !state.generation) await loadHistory(state)
    if (selection !== selectionRevision || !isActive(state) || disposed) return
    const requestId = state.generation?.requestId || pending(state)
    if (requestId && !state.loading.sending) void consumeGeneration(state, requestId)
    // Keep active/background conversations, bound completed conversation caches.
    for (const [oldKey, old] of states) {
      if (states.size <= 12) break
      if (old !== state && !old.loading.sending && !old.generation) states.delete(oldKey)
    }
    await scrollToBottom(true)
  }
  async function loadEarlier() {
    const state = active.value
    if (!state.world || !state.hasOlder || state.loading.history) return
    const beforeId = state.messages.reduce((min, item) => item.historyId ? Math.min(min, item.historyId) : min, Infinity)
    if (!Number.isFinite(beforeId)) return
    const viewport = scroller.value; const previousHeight = viewport?.scrollHeight ?? 0
    const revision = ++state.historyRevision; const ownEpoch = epoch
    state.loading.history = true
    try {
      const history = await api.history(state.world.id, state.characterId, 30, beforeId)
      if (disposed || ownEpoch !== epoch || revision !== state.historyRevision) return
      const older = historyMessages(history)
      if (state.generation) state.generation.base = [...older, ...state.generation.base]
      state.messages = [...older, ...state.messages]; state.hasOlder = hasOlderHistory(history)
      const previousTop = viewport?.scrollTop ?? 0
      await nextTick()
      if (isActive(state) && viewport && scroller.value === viewport) viewport.scrollTop = previousTop + viewport.scrollHeight - previousHeight
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '更早记录加载失败', errorMessage(error)) }
    finally { if (revision === state.historyRevision) state.loading.history = false }
  }
  function disposeConnections() {
    epoch++; carePolling.stop()
    states.forEach(state => state.controller?.abort())
  }
  function invalidateWorld(worldId: number, characterId?: number) {
    for (const [key, state] of states) {
      if (state.world?.id !== worldId || (characterId != null && state.characterId !== characterId)) continue
      state.controller?.abort(); state.generation = null; state.historyRevision++; state.modelListRevision++
      remember(state, null); states.delete(key)
      if (active.value === state) close()
    }
  }
  function clearDrafts() {
    disposeConnections()
    try {
      const prefix = 'galchat.direct-generation:'
      for (let i = sessionStorage.length - 1; i >= 0; i--) {
        const key = sessionStorage.key(i)
        if (key?.startsWith(prefix)) sessionStorage.removeItem(key)
      }
    } catch { /* Storage may be disabled. */ }
    states.clear(); active.value = freshState()
    chatDrafts.clear(); input.value = ''; clearChatReadingPositions()
  }
  function close() {
    selectionRevision++; selectedCharacterId.value = null; active.value = freshState()
  }
  function notifyPush(item: ChatHistory) {
    if (roleOf(item) !== 'assistant' || !item.content) return
    const speaker = context.characters.value.find((character) => character.characterId === item.characterId)
    const title = `${speaker?.characterName || '角色'} 发来消息`
    notify(title, item.content.length > 90 ? `${item.content.slice(0, 90)}…` : item.content)
    if (!('Notification' in window)) return
    const show = () => new Notification(title, { body: item.content, tag: `galchat-${item.userWorldId}-${item.characterId}` })
    if (Notification.permission === 'granted') show()
    else if (Notification.permission === 'default' && !notificationAsked) {
      notificationAsked = true; void Notification.requestPermission().then((permission) => { if (permission === 'granted') show() })
    }
  }
  function receiveCareMessages(items: ChatHistory[]) {
    for (const item of items) {
      if (disposed || item.userWorldId !== context.world.value?.id || item.id == null) continue
      const state = states.get(stateKey(item.userWorldId!, item.characterId!))
      if (state?.messages.some(message => message.historyId === item.id)) continue
      notifyPush(item)
      if (!state) continue
      const pushed = historyMessages([item])[0]!
      if (state.generation) {
        const generation = state.generation
        const anchor = generation.live[0]?.historyId
        insertCareMessage(anchor != null && item.id < anchor ? generation.base : generation.following, pushed)
        state.messages = [...generation.base, ...generation.live, ...generation.following]
      } else insertCareMessage(state.messages, pushed)
      if (isActive(state)) void scrollToBottom()
    }
    if (items.length) void context.reloadCharacters()
  }
  const carePolling = createCarePolling({
    now: () => Date.now(),
    visible: () => typeof document !== 'undefined' && document.visibilityState !== 'hidden',
    schedule: (callback, delay) => { const timer = setTimeout(callback, delay); return () => clearTimeout(timer) },
    readCursor: key => {
      try {
        const value = localStorage.getItem(key)
        const cursor = value == null ? NaN : Number(value)
        return Number.isSafeInteger(cursor) && cursor >= 0 ? cursor : undefined
      } catch { return undefined }
    },
    writeCursor: (key, cursor) => { try { localStorage.setItem(key, String(cursor)) } catch { /* In-page progress still works. */ } },
    fetchPage: (worldId, after, signal) => api.careMessages(worldId, after, signal),
    receive: receiveCareMessages,
  })
  watch(() => [context.world.value?.id, context.world.value?.acitvePushStatus] as const, ([worldId, enabled]) => {
    if (worldId != null && enabled) {
      carePolling.start(`galchat.care-cursor:${currentSession().id ?? ''}:${worldId}`, worldId)
    } else carePolling.stop()
  }, { immediate: true, flush: 'sync' })
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', carePolling.visibilityChanged)
  if (typeof window !== 'undefined') window.addEventListener?.('focus', carePolling.refresh)

  function applyChunk(state: State, generation: Generation, chunk: ChatFlux) {
    if (chunk.sequence != null) {
      if (chunk.sequence <= generation.sequence) return
      generation.sequence = chunk.sequence
    }
    const live = generation.live
    if (chunk.type === 'generation.started') {
      if (!live.length) live.push({ id: `${generation.requestId}-user`, role: 'user', content: chunk.content || '', time: '刚刚' })
    } else if (chunk.type === 'generation.user') {
      const anchor = Number(chunk.content)
      if (Number.isSafeInteger(anchor) && anchor > 0) {
        const owner = (message: DirectMessage) => message.userMessageId ?? message.historyId ?? 0
        const persisted = [...generation.base, ...generation.following]
        generation.following = persisted.filter(message => owner(message) > anchor)
        generation.base = persisted.filter(message => owner(message) < anchor)
        if (live[0]?.role === 'user') { live[0].historyId = anchor; live[0].userMessageId = anchor }
      }
    } else if (chunk.type === 'thinking' || chunk.type === 'tool') {
      const tool = chunk.type === 'tool'
      const value = chunk.content || (tool ? '调用了工具' : '正在整理记忆')
      const last = live.at(-1)
      if (last?.role === 'thinking') last.content += `${(tool ? last.content : generation.thinkingNeedsSeparator) ? '\n\n' : ''}${value}`
      else live.push({ id: `${generation.requestId}-${live.length}`, role: 'thinking', content: value })
      generation.thinkingNeedsSeparator = tool
      if (tool) generation.assistant = null
    } else if (chunk.type === 'response' || chunk.type === 'reponse') {
      if (!generation.assistant) {
        live.push({ id: `${generation.requestId}-${live.length}`, role: 'assistant', content: '' })
        generation.assistant = live.at(-1)!
      }
      generation.assistant.content += chunk.content || ''
    } else if (chunk.type === 'generation.failed') {
      const message = chunk.errorDetail?.message || chunk.content || '角色回复生成失败，请稍后重试。'
      live.push({ id: `${generation.requestId}-error`, role: 'assistant', content: message, complete: false })
      showGenerationFailure(state, generation, message, { event: chunk }, undefined, chunk.errorDetail)
      state.failure!.recovery = { historyIds: generation.historyIds }
    }
    state.messages = [...generation.base, ...live, ...generation.following]
    if (isActive(state)) void scrollToBottom()
  }

  function showGenerationFailure(state: State, generation: Generation, message: string, response: Record<string, unknown>, error?: unknown, detail?: GenerationErrorDetail) {
    const user = generation.live.find(item => item.role === 'user')
    const payload: ChatMessagePayload = { worldId: state.world!.worldId!, userWorldId: state.world!.id,
      characterId: state.characterId, message: user?.content || '' }
    state.failure = { message, payload, userMessageId: user?.historyId, retry: null,
      errorIds: [...generation.retryErrorIds, `${generation.requestId}-error`], detail: detail ?? {
      errorId: '服务端未提供', code: error ? 'CLIENT_REQUEST_FAILED' : '服务端未提供', category: 'CLIENT', message,
      retryable: false, occurredAt: new Date().toISOString(), operation: 'send-direct-message',
      request: user ? { method: 'POST', path: '/ai/chat', body: { ...payload, clientRequestId: generation.requestId } }
        : { method: 'GET', path: `/ai/chat/${state.world!.id}/${state.characterId}/generations/${encodeURIComponent(generation.requestId)}`, after: generation.sequence },
      response, stack: error instanceof Error ? error.stack || error.message : '服务端未返回堆栈信息。',
    } }
    state.failureOpen = true
  }

  async function consumeGeneration(state: State, requestId: string, payload?: ChatMessagePayload, retryErrorIds: string[] = []) {
    if (state.loading.sending) return
    const ownEpoch = epoch
    const generation = state.generation ?? reactive<Generation>({ requestId, sequence: 0, base: [...state.messages], live: [], following: [], assistant: null, thinkingNeedsSeparator: false,
      retryErrorIds,
      historyIds: payload && state.historyReady ? state.messages.flatMap(message => message.historyId == null ? [] : [message.historyId]) : null })
    state.failure = null; state.failureOpen = false
    state.generation = generation; state.loading.sending = true
    remember(state, requestId)
    if (payload) applyChunk(state, generation, { type: 'generation.started', content: payload.message })
    const controller = new AbortController(); state.controller = controller
    const valid = () => !disposed && ownEpoch === epoch && !controller.signal.aborted && state.generation === generation
    let terminal = false; let expired = false; let failed = false
    const receive = (chunk: ChatFlux) => {
      if (!valid()) return
      applyChunk(state, generation, chunk)
      terminal ||= ['generation.completed', 'generation.failed', 'generation.expired'].includes(chunk.type)
      expired ||= chunk.type === 'generation.expired'
      failed ||= chunk.type === 'generation.failed'
    }
    try {
      await followGeneration<ChatFlux>({
        signal: controller.signal,
        after: generation.sequence,
        initial: payload ? (receive, signal) => streamChat({ ...payload, clientRequestId: requestId }, receive, signal) : undefined,
        resume: (receive, after, signal) => resumeChat(state.world!.id, state.characterId, requestId, after, receive, signal),
        sequence: chunk => chunk.sequence,
        terminal: chunk => ['generation.completed', 'generation.failed', 'generation.expired'].includes(chunk.type),
        receive,
      })
      if (!valid()) return
      if (terminal) {
        remember(state, null); state.generation = null
        if (!failed && !expired) {
          state.messages = state.messages.filter(message => message.complete !== false || !generation.retryErrorIds.includes(message.id))
        }
        const history = await loadHistory(state, true)
        if (disposed || ownEpoch !== epoch || controller.signal.aborted) return
        const userMessage = generation.live.find(message => message.role === 'user')
        if ((failed || expired) && userMessage && userMessage.historyId == null) {
          // The user row can be saved before generation.user reaches the browser.
          // An older message with identical text must not prevent draft recovery.
          const previousIds = new Set(generation.base.map(message => message.historyId))
          const saved = history?.some(message => roleOf(message) === 'user' && message.id != null
            && !previousIds.has(message.id) && message.content === userMessage.content)
          if (!saved) chatDrafts.restoreIfEmpty(`world:${state.world!.id}:direct:${state.characterId}`, { input: userMessage.content })
        }
        if (expired) {
          showGenerationFailure(state, generation, '回复续接已失效，请核对聊天记录后重新发送。', { type: 'generation.expired' })
          if (ownEpoch === epoch && !disposed) notifyFor(state, '回复续接已失效', history
            ? '已重新加载历史；未保存的消息已尝试恢复到草稿，请核对后重试。'
            : '历史加载失败，请重新进入此单聊核对记录后重试。')
        }
        if (isActive(state)) await context.reloadCharacters()
      }
    } catch (error) {
      if (valid()) {
        if (payload && error instanceof GenerationStartRejected) {
          remember(state, null); state.generation = null
          state.messages = [...generation.base, ...generation.following]
          chatDrafts.restoreIfEmpty(`world:${state.world!.id}:direct:${state.characterId}`, { input: payload.message })
          showGenerationFailure(state, generation, errorMessage(error), { message: errorMessage(error) }, error)
          state.failure!.retry = 'send'; state.failure!.detail.retryable = true
        } else {
          showGenerationFailure(state, generation, `${errorMessage(error)}。重新进入此单聊可继续恢复。`, { message: errorMessage(error) }, error)
        }
      }
    } finally {
      if (ownEpoch === epoch && !disposed && state.controller === controller) {
        state.loading.sending = false; state.controller = null
        if (isActive(state)) await scrollToBottom()
      }
    }
  }

  async function send() {
    const state = active.value
    const content = input.value.trim(); const data = payload()
    if (!content || !data || state.loading.sending || state.loading.history || state.loading.withdrawing) return
    const requestId = state.generation?.requestId || pending(state)
    if (requestId) { await consumeGeneration(state, requestId); return }
    input.value = ''
    await consumeGeneration(state, newRequestId(), { ...data, message: content })
  }
  async function withdraw() {
    const state = active.value
    const expectedMessageId = withdrawalTarget.value?.historyId
    if (!state.world || !canWithdraw.value || state.generation || expectedMessageId == null) return
    await withdrawTarget(state, expectedMessageId)
  }
  async function withdrawTarget(state: State, expectedMessageId: number, forRetry = false) {
    const ownEpoch = epoch
    const failure = state.failure
    if (forRetry && failure?.userMessageId === expectedMessageId && failure.recovery) {
      failure.recovery.withdrawalRequested = true
    }
    state.loading.withdrawing = true
    carePolling.pause()
    try {
      state.historyReady = false
      await api.withdrawMessage(state.world!.id, state.characterId, expectedMessageId)
      if (disposed || ownEpoch !== epoch) return
      if (state.failure?.userMessageId === expectedMessageId) {
        if (forRetry) {
          prepareFailureResend(state, state.failure)
        } else { state.failure = null; state.failureOpen = false }
      }
      state.messages = state.messages.filter(message => (message.complete !== false
        || (forRetry && !failure?.errorIds.includes(message.id)))
        && message.historyId !== expectedMessageId && message.userMessageId !== expectedMessageId)
      const history = await loadHistory(state)
      if (disposed || ownEpoch !== epoch) return
      if (!history) {
        notifyFor(state, '消息已撤回，历史刷新失败', forRetry
          ? '请重新进入此单聊加载记录，再重试回复。'
          : '请重新进入此单聊加载记录，再继续撤回。')
        return
      }
      if (isActive(state)) { await context.reloadCharacters(); if (!forRetry) notify('已撤回上一轮消息', '', 'success') }
      return true
    } catch (error) {
      if (ownEpoch === epoch && !disposed) {
        // A stale target or a lost HTTP response requires authoritative history before retrying.
        const history = await loadHistory(state)
        if (history && ownEpoch === epoch && !disposed && forRetry
          && state.failure === failure && failure?.retry === 'send') return true
        if (ownEpoch === epoch && !disposed) notifyFor(state, '撤回失败', errorMessage(error))
      }
    }
    finally { state.loading.withdrawing = false; carePolling.resume() }
  }

  async function retryGenerationFailure() {
    const state = active.value; const failure = state.failure; const ownEpoch = epoch
    if (!failure || !canRetryGenerationFailure.value) return
    if (failure.retry === 'withdraw' && !await withdrawTarget(state, failure.userMessageId!, true)) return
    if (disposed || ownEpoch !== epoch || !isActive(state) || state.failure !== failure) return
    if (input.value === failure.payload.message) input.value = ''
    await consumeGeneration(state, newRequestId(), { ...failure.payload }, failure.errorIds)
  }

  async function selectModel(modelApiId?: number) {
    const state = active.value; const character = selectedCharacter.value
    if (!state.world || !character || state.loading.model || state.loading.sending) return
    const ownEpoch = epoch
    state.loading.model = true
    try {
      const runtime = await api.updateCharacterModel(state.world.id, state.characterId, modelApiId)
      if (disposed || ownEpoch !== epoch) return
      const currentCharacter = context.characters.value.find(item => item.userWorldId === state.world!.id
        && item.characterId === state.characterId)
      if (currentCharacter) currentCharacter.modelApiId = runtime.modelApiId
      if (isActive(state)) notify('回复模型已更新', runtime.modelApiName || '已恢复默认模型', 'success')
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '回复模型更新失败', errorMessage(error)) }
    finally { state.loading.model = false }
  }

  onUnmounted(() => {
    disposed = true; disposeConnections()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', carePolling.visibilityChanged)
    if (typeof window !== 'undefined') window.removeEventListener?.('focus', carePolling.refresh)
  })
  return { selectedCharacterId, selectedCharacter, messages, input, scroller, modelApis, loading, canWithdraw, hasOlderMessages, generationFailure, generationFailureOpen, canRetryGenerationFailure, retryGenerationFailure, selectCharacter, selectModel, loadEarlier, close, clearDrafts, invalidateWorld, send, withdraw }
}
