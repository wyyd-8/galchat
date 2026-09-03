import { computed, nextTick, onUnmounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { api, createChatSocket, streamChat } from '@/api/client'
import type { Character, ChatHistory, ChatMessagePayload, DirectMessage, ModelApi, UserWorld } from '@/api/types'
import { resetConversationScrollFollowing, scrollConversationToLatest } from '@/components/reasoningScroll'
import { errorMessage, notify } from './useNotice'

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
  const selectedCharacterId = ref<number | null>(null)
  const messages = ref<DirectMessage[]>([])
  const input = ref('')
  const scroller = ref<HTMLElement | null>(null)
  const modelApis = ref<ModelApi[]>([])
  const loading = reactive({ history: false, sending: false, withdrawing: false, model: false })
  const hasOlderMessages = ref(false)
  const selectedCharacter = computed(() => context.characters.value.find((item) => item.characterId === selectedCharacterId.value) || null)
  const canWithdraw = computed(() => {
    if (loading.history || loading.sending || loading.withdrawing) return false
    const conversation = messages.value.filter((item) => item.role === 'user' || item.role === 'assistant')
    return conversation.at(-1)?.role === 'assistant' && conversation.at(-1)?.complete !== false && conversation.some((item) => item.role === 'user')
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
  function historyMessages(history: ChatHistory[]): DirectMessage[] {
    const result: DirectMessage[] = []
    let currentThinking: { message: DirectMessage; reasoning: string; toolCount: number } | null = null
    history.forEach((item, itemIndex) => {
      const role = roleOf(item)
      if (role === 'tool') {
        if (!currentThinking) {
          const message: DirectMessage = {
            id: `history-thinking-${item.userMessageId || item.id || Date.now()}-${item.stepNo ?? itemIndex}`,
            role: 'thinking',
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
          role,
          content,
          time: formatTime(item.timestamp),
        }
        result.push(message)
        currentThinking = { message, reasoning: content, toolCount: 0 }
        return
      }

      currentThinking = null
      const parts = context.world.value?.thinkStatus === false ? splitContent(content) : [content]
      result.push(...parts.map((part, index) => ({
        id: `history-${item.id || Date.now()}-${index}`,
        historyId: item.id,
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

  async function selectCharacter(id: number) {
    selectedCharacterId.value = id; input.value = ''; messages.value = []; hasOlderMessages.value = false; closeSocket()
    const world = context.world.value
    if (!world) return
    loading.history = true
    try {
      const [history, models] = await Promise.all([
        api.history(world.id, id),
        api.modelApis(),
      ])
      messages.value = historyMessages(history)
      modelApis.value = models
      hasOlderMessages.value = history.length === 30
      if (world.thinkStatus === false) void ensureSocket(world.id).catch(() => undefined)
      await scrollToBottom(true)
    } catch (error) { notify('单聊记录加载失败', errorMessage(error), 'danger') }
    finally { loading.history = false }
  }
  async function loadEarlier() {
    const world = context.world.value; const character = selectedCharacter.value
    if (!world || !character || !hasOlderMessages.value || loading.history) return
    const beforeId = messages.value.reduce((minimum, item) => item.historyId ? Math.min(minimum, item.historyId) : minimum, Number.POSITIVE_INFINITY)
    if (!Number.isFinite(beforeId)) return
    const viewport = scroller.value
    const previousHeight = viewport?.scrollHeight ?? 0
    loading.history = true
    try {
      const history = await api.history(world.id, character.characterId, 30, beforeId)
      if (world.id !== context.world.value?.id || character.characterId !== selectedCharacterId.value) return
      const previousTop = viewport?.scrollTop ?? 0
      messages.value = [...historyMessages(history), ...messages.value]
      hasOlderMessages.value = history.length === 30
      await nextTick()
      if (viewport && scroller.value === viewport) viewport.scrollTop = previousTop + viewport.scrollHeight - previousHeight
    } catch (error) { notify('更早记录加载失败', errorMessage(error), 'danger') }
    finally { loading.history = false }
  }
  function close() { selectedCharacterId.value = null; messages.value = []; modelApis.value = []; input.value = ''; hasOlderMessages.value = false; closeSocket() }
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
    notifyPush(item)
    if (item.userWorldId !== context.world.value?.id || item.characterId !== selectedCharacterId.value) return
    messages.value.push(...historyMessages([item])); void scrollToBottom()
    if (roleOf(item) === 'assistant') void context.reloadCharacters()
  }
  function ensureSocket(worldId: number) {
    if (socket?.readyState === WebSocket.OPEN && socketWorldId === worldId) return Promise.resolve(socket)
    if (connecting && socketWorldId === worldId) return connecting
    closeSocket(); socket = createChatSocket(worldId); socketWorldId = worldId
    const current = socket
    connecting = new Promise<WebSocket>((resolve, reject) => {
      let settled = false
      current.addEventListener('open', () => { settled = true; connecting = null; resolve(current) }, { once: true })
      current.addEventListener('message', handleSocketMessage)
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

  async function send() {
    const content = input.value.trim(); const data = payload()
    if (!content || !data || loading.sending) return
    messages.value.push({ id: `user-${Date.now()}`, role: 'user', content, time: '刚刚' })
    ignoreInputWatch = true; input.value = ''; loading.sending = true; await scrollToBottom()
    try {
      data.message = content
      if (context.world.value?.thinkStatus === false) {
        const current = await ensureSocket(data.userWorldId)
        current.send(JSON.stringify({ ...data, type: 'fragment', message: `${content}\n` }))
        current.send(JSON.stringify({ ...data, type: 'typing', message: '', isTyping: false }))
        lastTypingKey = null
      } else {
        let assistant: DirectMessage | null = null; let received = false; let thinkingNeedsSeparator = false
        await streamChat(data, (chunk) => {
          if (chunk.type === 'thinking') {
            const last = messages.value.at(-1); const value = chunk.content || '正在整理记忆'
            if (last?.role === 'thinking') last.content += `${thinkingNeedsSeparator ? '\n\n' : ''}${value}`
            else messages.value.push({ id: `thinking-${Date.now()}-${Math.random()}`, role: 'thinking', content: value })
            thinkingNeedsSeparator = false
          } else if (chunk.type === 'tool') {
            assistant = null
            const value = chunk.content || '调用了工具'
            const last = messages.value.at(-1)
            if (last?.role === 'thinking') last.content += `${last.content ? '\n\n' : ''}${value}`
            else messages.value.push({ id: `thinking-${Date.now()}-${Math.random()}`, role: 'thinking', content: value })
            thinkingNeedsSeparator = true
          } else if (chunk.type === 'response' || chunk.type === 'reponse') {
            if (!assistant) {
              messages.value.push({ id: `assistant-${Date.now()}-${Math.random()}`, role: 'assistant', content: '' })
              assistant = messages.value.at(-1)!
            }
            assistant.content += chunk.content || ''; received ||= Boolean(chunk.content?.trim())
          }
          void scrollToBottom()
        })
        if (!received) messages.value.push({ id: `assistant-empty-${Date.now()}`, role: 'assistant', content: '暂时没有收到角色回复。', complete: false })
        await context.reloadCharacters()
      }
    } catch (error) {
      messages.value.push({ id: `assistant-error-${Date.now()}`, role: 'assistant', content: '发送失败，请稍后再试。', complete: false })
      notify('单聊消息发送失败', errorMessage(error), 'danger')
    } finally { loading.sending = false; await scrollToBottom() }
  }
  async function withdraw() {
    const world = context.world.value; const character = selectedCharacter.value
    if (!world || !character || !canWithdraw.value) return
    loading.withdrawing = true
    try { await api.withdrawMessage(world.id, character.characterId); await Promise.all([selectCharacter(character.characterId), context.reloadCharacters()]); notify('已撤回上一轮消息', '', 'success') }
    catch (error) { notify('撤回失败', errorMessage(error), 'danger') }
    finally { loading.withdrawing = false }
  }

  async function selectModel(modelApiId?: number) {
    const world = context.world.value; const character = selectedCharacter.value
    if (!world || !character || loading.model || loading.sending) return
    loading.model = true
    try {
      const runtime = await api.updateCharacterModel(world.id, character.characterId, modelApiId)
      character.modelApiId = runtime.modelApiId
      notify('回复模型已更新', runtime.modelApiName || '已恢复默认模型', 'success')
    } catch (error) { notify('回复模型更新失败', errorMessage(error), 'danger') }
    finally { loading.model = false }
  }

  onUnmounted(closeSocket)
  return { selectedCharacterId, selectedCharacter, messages, input, scroller, modelApis, loading, canWithdraw, hasOlderMessages, selectCharacter, selectModel, loadEarlier, close, send, withdraw, focus, setComposing }
}
