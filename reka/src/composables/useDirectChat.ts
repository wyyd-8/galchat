import { computed, nextTick, onUnmounted, reactive, ref, shallowRef, watch, type ComputedRef, type Ref } from 'vue'
import { api, createChatSocket, streamChat, resumeChat, currentSession } from '@/api/client'
import type { Character, ChatFlux, ChatHistory, ChatMessagePayload, DirectMessage, ModelApi, UserWorld } from '@/api/types'
import { resetConversationScrollFollowing, scrollConversationToLatest } from '@/components/reasoningScroll'
import { clearChatReadingPositions } from '@/components/chatReadingPosition'
import { useScopedChatDraft } from '@/components/chatInputState'
import { errorMessage, notify } from './useNotice'
import { followGeneration, GenerationStartRejected } from '@/streaming/generationConnection'

interface DirectChatContext {
  world: ComputedRef<UserWorld | null>
  characters: Ref<Character[]>
  reloadCharacters: () => Promise<void>
}

function formatTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '' }
function splitContent(value: string) {
  const parts = value.split(/\r?\n/).map((part) => part.trim()).filter(Boolean)
  return parts.length ? parts : [value]
}

export function useDirectChat(context: DirectChatContext) {
  interface Generation {
    requestId: string; sequence: number; base: DirectMessage[]; live: DirectMessage[]; following: DirectMessage[]
    assistant: DirectMessage | null; thinkingNeedsSeparator: boolean
  }
  function freshState(world?: UserWorld, characterId = 0) {
    return reactive({ world, characterId, messages: [] as DirectMessage[], modelApis: [] as ModelApi[],
      loading: { history: false, sending: false, withdrawing: false, model: false },
      hasOlder: false, generation: null as Generation | null,
      controller: null as AbortController | null, historyRevision: 0 })
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
  const canWithdraw = computed(() => {
    if (loading.history || loading.sending || loading.withdrawing || active.value.generation) return false
    return messages.value.some((item) => item.role === 'user')
  })

  let socket: WebSocket | null = null
  let connecting: Promise<WebSocket> | null = null
  let socketWorldId: number | null = null
  let lastTypingKey: string | null = null
  let composing = false
  let ignoreInputWatch = false
  let notificationAsked = false

  function roleOf(item: ChatHistory): DirectMessage['role'] {
    if (item.type === 'thinking') return 'thinking'
    if (item.type === 'tool') return 'tool'
    if (item.type === 'ASSISTANT' || item.type === 'assistant') return 'assistant'
    return 'user'
  }
  function historyMessages(history: ChatHistory[], split = false): DirectMessage[] {
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
      const parts = split ? splitContent(content) : [content]
      result.push(...parts.map((part, index) => ({
        id: `history-${item.id || Date.now()}-${index}`,
        historyId: item.id,
        userMessageId: item.userMessageId ?? (role === 'user' ? item.id : undefined),
        role,
        content: part,
        time: formatTime(item.timestamp),
      })))
    })
    return result
  }
  function payload(message = ''): ChatMessagePayload | null {
    const world = context.world.value; const character = selectedCharacter.value
    if (!world?.id || !world.worldId || !character) return null
    return { type: 'chat', worldId: world.worldId, userWorldId: world.id, characterId: character.characterId, message }
  }
  async function scrollToBottom(force = false) {
    await nextTick()
    const viewport = scroller.value
    if (!viewport) return
    if (force) resetConversationScrollFollowing(viewport)
    scrollConversationToLatest(viewport)
  }

  async function loadHistory(state: State) {
    const revision = ++state.historyRevision; const ownEpoch = epoch
    state.loading.history = true
    try {
      const [history, models] = await Promise.all([api.history(state.world!.id, state.characterId), api.modelApis()])
      if (disposed || ownEpoch !== epoch || revision !== state.historyRevision) return
      const errors = state.messages.filter(message => message.complete === false)
      state.messages = [...historyMessages(history, state.world?.thinkStatus === false), ...errors]
      state.modelApis = models; state.hasOlder = history.length === 30
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '单聊记录加载失败', errorMessage(error)) }
    finally { if (revision === state.historyRevision) state.loading.history = false }
  }
  async function selectCharacter(id: number) {
    const world = context.world.value
    if (!world) return
    const selection = ++selectionRevision
    selectedCharacterId.value = id; closeSocket()
    const key = stateKey(world.id, id)
    let state = states.get(key)
    if (!state) { state = freshState({ ...world }, id); states.set(key, state) }
    state.world = { ...world }
    active.value = state
    if (!state.loading.sending && !state.generation) await loadHistory(state)
    if (selection !== selectionRevision || !isActive(state) || disposed) return
    if (world.thinkStatus === false) void ensureSocket(world.id).catch(() => undefined)
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
      const older = historyMessages(history, state.world.thinkStatus === false)
      if (state.generation) state.generation.base = [...older, ...state.generation.base]
      state.messages = [...older, ...state.messages]; state.hasOlder = history.length === 30
      const previousTop = viewport?.scrollTop ?? 0
      await nextTick()
      if (isActive(state) && viewport && scroller.value === viewport) viewport.scrollTop = previousTop + viewport.scrollHeight - previousHeight
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '更早记录加载失败', errorMessage(error)) }
    finally { if (revision === state.historyRevision) state.loading.history = false }
  }
  function disposeConnections() {
    epoch++; closeSocket()
    states.forEach(state => state.controller?.abort())
  }
  function invalidateWorld(worldId: number, characterId?: number) {
    for (const [key, state] of states) {
      if (state.world?.id !== worldId || (characterId != null && state.characterId !== characterId)) continue
      state.controller?.abort(); state.generation = null; state.historyRevision++
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
    selectionRevision++; selectedCharacterId.value = null; active.value = freshState(); closeSocket()
  }
  function closeSocket() {
    connecting = null; socketWorldId = null; lastTypingKey = null
    if (socket) { socket.close(); socket = null }
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
  function handleSocketMessage(event: MessageEvent<string>) {
    let item: ChatHistory
    try { item = JSON.parse(event.data) as ChatHistory } catch { return }
    if (typeof item.type !== 'string' || typeof item.content !== 'string') return
    if (disposed || item.userWorldId !== socketWorldId || item.userWorldId !== context.world.value?.id) return
    notifyPush(item)
    const state = states.get(stateKey(item.userWorldId!, item.characterId!))
    if (!state) return
    state.messages.push(...historyMessages([item], true))
    if (!isActive(state)) return
    void scrollToBottom()
    if (roleOf(item) === 'assistant') void context.reloadCharacters()
  }
  function ensureSocket(worldId: number) {
    if (socket?.readyState === WebSocket.OPEN && socketWorldId === worldId) return Promise.resolve(socket)
    if (connecting && socketWorldId === worldId) return connecting
    closeSocket(); socket = createChatSocket(worldId); socketWorldId = worldId
    const current = socket
    connecting = new Promise<WebSocket>((resolve, reject) => {
      let settled = false
      current.addEventListener('open', () => { settled = true; if (socket === current) connecting = null; resolve(current) }, { once: true })
      current.addEventListener('message', event => { if (socket === current) handleSocketMessage(event) })
      current.addEventListener('error', () => { if (!settled) reject(new Error('WebSocket 连接失败')) }, { once: true })
      current.addEventListener('close', () => {
        if (socket === current) { socket = null; socketWorldId = null; connecting = null }
        if (!settled) reject(new Error('WebSocket 连接已关闭'))
      }, { once: true })
    })
    return connecting
  }
  async function sendTyping(isTyping: boolean) {
    if (context.world.value?.thinkStatus !== false) return
    const data = payload(); if (!data) return
    const key = `${data.worldId}:${data.userWorldId}:${data.characterId}`
    if (isTyping && lastTypingKey === key) return
    if (!isTyping && lastTypingKey !== key) return
    lastTypingKey = isTyping ? key : null
    try {
      const current = isTyping ? await ensureSocket(data.userWorldId) : socket
      if (current?.readyState === WebSocket.OPEN) current.send(JSON.stringify({ ...data, type: 'typing', isTyping }))
    } catch { if (isTyping) lastTypingKey = null }
  }
  function setComposing(value: boolean, currentInput: string) { composing = value; void sendTyping(composing || currentInput.length > 0) }
  function focus() { if (composing || input.value.length > 0) void sendTyping(true) }
  watch(input, (value) => {
    if (ignoreInputWatch) { ignoreInputWatch = false; return }
    void sendTyping(composing || value.length > 0)
  })

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
        generation.following = generation.base.filter(message => owner(message) > anchor)
        generation.base = generation.base.filter(message => owner(message) < anchor)
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
      live.push({ id: `${generation.requestId}-error`, role: 'assistant', content: chunk.content || '角色回复生成失败，请稍后重试。', complete: false })
    }
    state.messages = [...generation.base, ...live, ...generation.following]
    if (isActive(state)) void scrollToBottom()
  }

  async function consumeGeneration(state: State, requestId: string, payload?: ChatMessagePayload) {
    if (state.loading.sending) return
    const ownEpoch = epoch
    const generation = state.generation ?? reactive<Generation>({ requestId, sequence: 0, base: [...state.messages], live: [], following: [], assistant: null, thinkingNeedsSeparator: false })
    state.generation = generation; state.loading.sending = true
    remember(state, requestId)
    if (payload) applyChunk(state, generation, { type: 'generation.started', content: payload.message })
    const controller = new AbortController(); state.controller = controller
    const valid = () => !disposed && ownEpoch === epoch && !controller.signal.aborted && state.generation === generation
    let terminal = false; let expired = false
    const receive = (chunk: ChatFlux) => {
      if (!valid()) return
      applyChunk(state, generation, chunk)
      terminal ||= ['generation.completed', 'generation.failed', 'generation.expired'].includes(chunk.type)
      expired ||= chunk.type === 'generation.expired'
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
        await loadHistory(state)
        if (expired) {
          if (ownEpoch === epoch && !disposed) notifyFor(state, '回复续接已失效', '已重新加载历史；若回复未完成，可撤回后重新发送。')
        }
        if (isActive(state)) await context.reloadCharacters()
      }
    } catch (error) {
      if (valid()) {
        if (payload && error instanceof GenerationStartRejected) {
          remember(state, null); state.generation = null
          state.messages = generation.base
          if (isActive(state) && !input.value) input.value = payload.message
          notifyFor(state, '单聊消息发送失败', errorMessage(error))
        } else notifyFor(state, '单聊连接中断', `${errorMessage(error)}。重新进入此单聊可继续恢复。`)
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
    ignoreInputWatch = true; input.value = ''
    if (state.world?.thinkStatus !== false) {
      await consumeGeneration(state, crypto.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(36).slice(2)}`, { ...data, message: content })
      return
    }
    const ownEpoch = epoch
    state.messages.push({ id: `user-${Date.now()}`, role: 'user', content, time: '刚刚' }); state.loading.sending = true
    try {
      const current = await ensureSocket(data.userWorldId)
      if (disposed || ownEpoch !== epoch) return
      current.send(JSON.stringify({ ...data, type: 'fragment', message: `${content}\n` }))
      current.send(JSON.stringify({ ...data, type: 'typing', message: '', isTyping: false }))
      lastTypingKey = null
    } catch (error) {
      if (ownEpoch === epoch && !disposed) {
        state.messages.push({ id: `error-${Date.now()}`, role: 'assistant', content: '发送失败，请稍后再试。', complete: false })
        notifyFor(state, '单聊消息发送失败', errorMessage(error))
      }
    } finally { state.loading.sending = false; if (isActive(state)) await scrollToBottom() }
  }
  async function withdraw() {
    const state = active.value
    if (!state.world || !canWithdraw.value || state.generation) return
    const ownEpoch = epoch
    state.loading.withdrawing = true
    try {
      await api.withdrawMessage(state.world.id, state.characterId)
      if (disposed || ownEpoch !== epoch) return
      state.messages = state.messages.filter(message => message.complete !== false)
      await loadHistory(state)
      if (isActive(state)) { await context.reloadCharacters(); notify('已撤回上一轮消息', '', 'success') }
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '撤回失败', errorMessage(error)) }
    finally { state.loading.withdrawing = false }
  }

  async function selectModel(modelApiId?: number) {
    const state = active.value; const character = selectedCharacter.value
    if (!state.world || !character || state.loading.model || state.loading.sending) return
    const ownEpoch = epoch
    state.loading.model = true
    try {
      const runtime = await api.updateCharacterModel(state.world.id, state.characterId, modelApiId)
      if (disposed || ownEpoch !== epoch) return
      character.modelApiId = runtime.modelApiId
      if (isActive(state)) notify('回复模型已更新', runtime.modelApiName || '已恢复默认模型', 'success')
    } catch (error) { if (ownEpoch === epoch && !disposed) notifyFor(state, '回复模型更新失败', errorMessage(error)) }
    finally { state.loading.model = false }
  }

  onUnmounted(() => { disposed = true; disposeConnections() })
  return { selectedCharacterId, selectedCharacter, messages, input, scroller, modelApis, loading, canWithdraw, hasOlderMessages, selectCharacter, selectModel, loadEarlier, close, clearDrafts, invalidateWorld, send, withdraw, focus, setComposing }
}
