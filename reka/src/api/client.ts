import type {
  ApiResult, Character, CharacterCard, CharacterTemplate, ChatFlux, ChatHistory, ChatMessagePayload, CocModule, ContextWindowUsage, Conversation, CurrentTurn,
  DiceResult, DiceRollDetail, DiceRollProgress, DiceRollSummary, GroupChatEvent, GroupMessage, ReplyPlan, Session, TrpgSave, UserInfo, UserToken,
  UserWorld, WorldArchive, WorldArchiveResult, WorldDetail, WorldSave, WorldTemplate,
} from './types'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'
const TOKEN_KEY = 'galchat.token'
export const UNAUTHORIZED_EVENT = 'galchat:unauthorized'

function endpoint(path: string) { return `${API_BASE}${path}` }
function token() { return localStorage.getItem(TOKEN_KEY) }
function wsEndpoint(path: string) {
  const url = API_BASE.startsWith('http://') || API_BASE.startsWith('https://')
    ? new URL(path, API_BASE)
    : new URL(path, window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

async function readError(response: Response) {
  const text = await response.text()
  if (!text) return `${response.status} ${response.statusText}`
  try { return (JSON.parse(text) as ApiResult<unknown>).msg || text } catch { return text }
}

function headers(init?: HeadersInit, json = false) {
  const result = new Headers(init)
  if (json) result.set('Content-Type', 'application/json')
  const current = token()
  if (current) result.set('token', current)
  return result
}

async function raw(path: string, init: RequestInit = {}) {
  const response = await fetch(endpoint(path), { ...init, headers: headers(init.headers, Boolean(init.body) && !(init.body instanceof FormData)) })
  if (response.status === 401) {
    clearSession(); window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT)); throw new Error('登录状态已失效')
  }
  if (!response.ok) throw new Error(await readError(response))
  return response
}

async function request<T>(path: string, init: RequestInit = {}) {
  const response = await raw(path, init)
  const result = await response.json() as ApiResult<T>
  if (result.code !== 1) throw new Error(result.msg || '请求失败')
  return result.data as T
}

const body = (value: unknown) => JSON.stringify(value)

export function saveSession(value: UserToken) {
  localStorage.setItem(TOKEN_KEY, value.token)
  localStorage.setItem('galchat.userId', String(value.id))
  localStorage.setItem('galchat.username', value.username)
}
export function clearSession() {
  Object.keys(localStorage).filter((key) => key.startsWith('galchat.')).forEach((key) => localStorage.removeItem(key))
}
export function currentSession(): Session {
  const id = Number(localStorage.getItem('galchat.userId'))
  return { token: token() || '', id: Number.isFinite(id) && id > 0 ? id : null, username: localStorage.getItem('galchat.username') || '' }
}

export const api = {
  login: (email: string, password: string) => request<UserToken>('/user/login', { method: 'POST', body: body({ email, password }) }),
  register: (email: string, password: string, verificationCode: string) => request<UserToken>('/user/register', { method: 'POST', body: body({ email, password, verificationCode }) }),
  sendRegisterCode: (email: string) => request<void>('/user/register/email-code', { method: 'POST', body: body({ email }) }),
  userInfo: () => request<UserInfo>('/user/info'),
  updateUserInfo: (payload: Partial<UserInfo>) => request<void>('/user/info', { method: 'PUT', body: body(payload) }),
  sendPasswordCode: (email: string) => request<void>('/user/password/email-code', { method: 'POST', body: body({ email }) }),
  updatePassword: (payload: { email: string; newPassword: string; verificationCode: string }) => request<void>('/user/password', { method: 'PUT', body: body(payload) }),

  worldTemplates: () => request<WorldTemplate[]>('/world/templates'),
  worldTemplate: (id: number) => request<WorldTemplate>(`/world/templates/${id}`),
  myWorldTemplate: (id: number) => request<WorldTemplate>(`/world/templates/my/${id}`),
  createWorldTemplate: (payload: WorldTemplate) => request<void>('/world/templates', { method: 'POST', body: body(payload) }),
  updateWorldTemplate: (id: number, payload: WorldTemplate) => request<void>(`/world/templates/my/${id}`, { method: 'PUT', body: body(payload) }),
  userWorlds: (userId: number) => request<UserWorld[]>(`/world/user/${userId}`),
  userWorld: (id: number) => request<UserWorld>(`/world/${id}`),
  createWorld: (payload: Partial<UserWorld> & { worldId: number }) => request<void>('/world', { method: 'POST', body: body(payload) }),
  updateWorld: (id: number, payload: Partial<UserWorld>) => request<void>(`/world/${id}`, { method: 'PUT', body: body(payload) }),
  deleteWorld: (id: number) => request<void>(`/world/${id}`, { method: 'DELETE' }),
  worldDetails: (worldId: number) => request<WorldDetail[]>(`/world/templates/${worldId}/details`),
  addWorldDetail: (worldId: number, payload: WorldDetail) => request<void>(`/world/templates/${worldId}/details`, { method: 'POST', body: body(payload) }),
  deleteWorldDetail: (worldId: number, detailId: number) => request<void>(`/world/templates/${worldId}/${detailId}`, { method: 'DELETE' }),
  exportWorld: async (id: number) => (await raw(`/world/templates/my/${id}/export`)).text(),
  importWorld: (payload: WorldArchive) => request<WorldArchiveResult>('/world/import', { method: 'POST', body: body(payload) }),
  worldSave: (id: number) => request<WorldSave | null>(`/world-saves/${id}`),
  saveWorld: (id: number, remark: string) => request<WorldSave>(`/world-saves/${id}`, { method: 'POST', body: body({ remark }) }),
  loadWorld: (id: number) => request<void>(`/world-saves/${id}/load`, { method: 'POST' }),

  characters: (worldId: number) => request<Character[]>(`/character/${worldId}`),
  characterTemplates: (worldId: number) => request<CharacterTemplate[]>(`/character/templates/${worldId}`),
  addCharacter: (worldId: number, characterId: number) => request<void>(`/character/${worldId}/${characterId}`, { method: 'POST' }),
  deleteCharacter: (worldId: number, characterId: number) => request<void>(`/character/${worldId}/${characterId}`, { method: 'DELETE' }),
  updatePrompt: (worldId: number, characterId: number, userInfoPrompt: string) => request<void>(`/character/${worldId}/${characterId}/prompt`, { method: 'PUT', body: body({ userInfoPrompt }) }),
  updateFavor: (worldId: number, characterId: number, favorValue: number) => request<void>(`/character/my/${worldId}/${characterId}/favor`, { method: 'PUT', body: body({ favorValue }) }),
  createCharacterTemplate: (worldId: number, payload: CharacterTemplate) => request<void>(`/character/templates/${worldId}`, { method: 'POST', body: body(payload) }),
  myCharacterTemplate: (worldId: number, characterId: number) => request<CharacterTemplate>(`/character/templates/my/${worldId}/${characterId}`),
  updateCharacterTemplate: (worldId: number, characterId: number, payload: CharacterTemplate) => request<void>(`/character/templates/my/${worldId}/${characterId}`, { method: 'PUT', body: body(payload) }),

  cocModules: () => request<CocModule[]>('/coc-modules'),
  cocModule: (id: number) => request<CocModule>(`/coc-modules/${id}`),

  history: (worldId: number, characterId: number, size = 30, beforeId?: number) => request<ChatHistory[]>(`/history?${new URLSearchParams({ userworldid: String(worldId), characterid: String(characterId), size: String(size), ...(beforeId ? { id: String(beforeId) } : {}) })}`),
  withdrawMessage: (worldId: number, characterId: number) => request<void>(`/history/withdraw?${new URLSearchParams({ userworldid: String(worldId), characterid: String(characterId) })}`, { method: 'POST' }),

  conversations: (worldId: number, status?: 'active' | 'closed') => request<Conversation[]>(`/group-chat/conversations?${new URLSearchParams({ userWorldId: String(worldId), ...(status ? { status } : {}) })}`),
  conversation: (id: number) => request<Conversation>(`/group-chat/conversations/${id}`),
  createConversation: (payload: { userWorldId: number; moduleId?: number; mode: string; title: string; characterIds: number[] }) => request<Conversation>('/group-chat/conversations', { method: 'POST', body: body(payload) }),
  closeConversation: (id: number) => request<Conversation>(`/group-chat/conversations/${id}/close`, { method: 'POST' }),
  contextWindow: (id: number) => request<ContextWindowUsage | null>(`/group-chat/conversations/${id}/context-window`),
  groupMessages: (id: number, beforeId?: number, size = 50) => request<GroupMessage[]>(`/group-chat/conversations/${id}/messages?size=${size}${beforeId ? `&beforeId=${beforeId}` : ''}`),
  withdrawGroupTurn: (id: number) => request<void>(`/group-chat/conversations/${id}/withdraw`, { method: 'POST' }),
  replyPlan: (id: number) => request<ReplyPlan>(`/group-chat/conversations/${id}/reply-plan`),
  saveReplyPlan: (id: number, plan: ReplyPlan) => request<ReplyPlan>(`/group-chat/conversations/${id}/reply-plan`, { method: 'PUT', body: body(plan) }),
  finishReplyPlan: (id: number) => request<ReplyPlan>(`/group-chat/conversations/${id}/reply-plan`, { method: 'DELETE' }),
  advanceReplyPlan: (id: number) => request<ReplyPlan>(`/group-chat/conversations/${id}/reply-plan/advance`, { method: 'POST' }),
  currentTurn: (id: number) => request<CurrentTurn | null>(`/group-chat/conversations/${id}/turns/current`),

  characterCard: (runId: number, participantId?: number) => request<CharacterCard | null>(`/character-cards?${new URLSearchParams({ runId: String(runId), ...(participantId ? { participantId: String(participantId) } : {}) })}`),
  characterCardById: (id: number) => request<CharacterCard>(`/character-cards/${id}`),
  createCharacterCard: (payload: { runId: number; participantId?: number; characterText: string }) => request<CharacterCard>('/character-cards', { method: 'POST', body: body(payload) }),
  deleteCharacterCard: (id: number) => request<void>(`/character-cards/${id}`, { method: 'DELETE' }),
  rollCharacterLuck: (id: number) => request<DiceResult>(`/character-cards/${id}/luck`, { method: 'POST' }),

  diceSummary: (id: number) => request<DiceRollSummary>(`/dice-rolls/${id}`),
  diceResults: (id: number) => request<DiceRollDetail[]>(`/dice-rolls/${id}/results`),
  rollDiceResult: (id: number) => request<DiceRollProgress>(`/dice-roll-results/${id}/roll`, { method: 'POST' }),

  trpgSave: (id: number) => request<TrpgSave | null>(`/trpg-saves/${id}`),
  saveTrpg: (id: number, remark: string) => request<TrpgSave>(`/trpg-saves/${id}`, { method: 'POST', body: body({ remark }) }),
  loadTrpg: (id: number) => request<void>(`/trpg-saves/${id}/load`, { method: 'POST' }),
}

export async function streamChat(payload: ChatMessagePayload, onMessage: (message: ChatFlux) => void) {
  const response = await raw('/ai/chat', { method: 'POST', body: body(payload) })
  if (!response.body) return
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
  const consume = (rawLine: string) => {
    const line = rawLine.trim()
    if (!line || line.startsWith(':')) return
    const data = line.startsWith('data:') ? line.slice(5).trim() : line
    if (!data || data === '[DONE]') return
    try { onMessage(JSON.parse(data) as ChatFlux) } catch { onMessage({ type: 'response', content: data }) }
  }
  while (true) {
    const { done, value } = await reader.read(); if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/); buffer = lines.pop() || ''
    lines.forEach(consume)
  }
  buffer += decoder.decode(); if (buffer.trim()) consume(buffer)
}

export function createChatSocket(userWorldId: number) {
  const sid = crypto.randomUUID?.() || `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
  const url = new URL(wsEndpoint(`/ws/${encodeURIComponent(sid)}`))
  url.searchParams.set('userWorldId', String(userWorldId))
  const current = token(); if (current) url.searchParams.set('token', current)
  return new WebSocket(url.toString())
}

export async function uploadImage(file: File) {
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp', 'image/x-ms-bmp'].includes(file.type)) throw new Error('仅支持 JPG、PNG、GIF、WEBP、BMP 图片')
  if (file.size > 4 * 1024 * 1024) throw new Error('图片不能超过 4MB')
  const data = new FormData(); data.append('file', file)
  const result = await (await raw('/upload', { method: 'POST', body: data })).json() as ApiResult<string>
  if (result.code !== 1) throw new Error(result.msg || '上传失败')
  return result.data || ''
}

export async function streamGroupMessage(id: number, payload: { clientRequestId: string; content: string }, onEvent: (event: GroupChatEvent) => void) {
  return streamGroupTurn(`/group-chat/conversations/${id}/messages`, payload, onEvent)
}

async function streamGroupTurn(path: string, payload: unknown, onEvent: (event: GroupChatEvent) => void) {
  const response = await raw(path, { method: 'POST', body: body(payload), headers: { Accept: 'text/event-stream' } })
  if (!response.body) return
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
  const consume = (block: string) => {
    const data = block.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
    if (!data || data === '[DONE]') return
    onEvent(JSON.parse(data) as GroupChatEvent)
  }
  while (true) {
    const { done, value } = await reader.read(); if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split(/\r?\n\r?\n/); buffer = blocks.pop() || ''
    blocks.forEach(consume)
  }
  buffer += decoder.decode(); if (buffer.trim()) consume(buffer)
}

export const streamTrpgTurn = {
  continue: (id: number, clientRequestId: string, onEvent: (event: GroupChatEvent) => void) =>
    streamGroupTurn(`/group-chat/conversations/${id}/turns/continue`, { clientRequestId }, onEvent),
  message: (id: number, turnId: number, stepId: number, payload: { clientRequestId: string; content: string }, onEvent: (event: GroupChatEvent) => void) =>
    streamGroupTurn(`/group-chat/conversations/${id}/turns/${turnId}/steps/${stepId}/message`, payload, onEvent),
  selection: (id: number, turnId: number, stepId: number, payload: { clientRequestId: string; optionNo: string }, onEvent: (event: GroupChatEvent) => void) =>
    streamGroupTurn(`/group-chat/conversations/${id}/turns/${turnId}/steps/${stepId}/selection`, payload, onEvent),
  endExploration: (id: number, turnId: number, stepId: number, clientRequestId: string, onEvent: (event: GroupChatEvent) => void) =>
    streamGroupTurn(`/group-chat/conversations/${id}/turns/${turnId}/steps/${stepId}/end-exploration`, { clientRequestId }, onEvent),
  retry: (id: number, turnId: number, stepId: number, onEvent: (event: GroupChatEvent) => void) =>
    streamGroupTurn(`/group-chat/conversations/${id}/turns/${turnId}/steps/${stepId}/retry`, undefined, onEvent),
}
