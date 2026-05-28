import type {
  ActiveStory,
  ApiResult,
  CharacterTemplate,
  ChatFlux,
  ChatHistory,
  ChatMessagePayload,
  StoryListItem,
  StoryStartPayload,
  UserCharacter,
  UserInfo,
  UserToken,
  UserWorld,
  WorldDetail,
  WorldTemplate,
} from './types'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'
const TOKEN_KEY = 'galchat.token'
export const UNAUTHORIZED_EVENT = 'galchat:unauthorized'

function token() {
  return localStorage.getItem(TOKEN_KEY)
}

function endpoint(path: string) {
  return `${API_BASE}${path}`
}

function wsEndpoint(path: string) {
  const url =
    API_BASE.startsWith('http://') || API_BASE.startsWith('https://')
      ? new URL(path, API_BASE)
      : new URL(path, window.location.href)

  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

async function readError(response: Response) {
  const text = await response.text()
  if (!text) {
    return `${response.status} ${response.statusText}`
  }
  try {
    const result = JSON.parse(text) as ApiResult<unknown>
    return result.msg || text
  } catch {
    return text
  }
}

function handleUnauthorized(response: Response) {
  if (response.status !== 401) {
    return false
  }
  clearSession()
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
  return true
}

async function request<T>(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  const currentToken = token()

  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json')
  }
  if (currentToken) {
    headers.set('token', currentToken)
  }

  const response = await fetch(endpoint(path), {
    ...init,
    headers,
  })

  if (!response.ok) {
    if (handleUnauthorized(response)) {
      throw new Error('登录状态已失效，请重新登录')
    }
    throw new Error(await readError(response))
  }

  const result = (await response.json()) as ApiResult<T>
  if (result.code !== 1) {
    throw new Error(result.msg || '请求失败')
  }

  return result.data as T
}

export function saveSession(session: UserToken) {
  localStorage.setItem(TOKEN_KEY, session.token)
  localStorage.setItem('galchat.userId', String(session.id))
  localStorage.setItem('galchat.username', session.username)
}

export function clearSession() {
  Object.keys(localStorage)
    .filter((key) => key.startsWith('galchat.'))
    .forEach((key) => localStorage.removeItem(key))
}

export function currentSession() {
  const currentToken = localStorage.getItem(TOKEN_KEY)
  const id = Number(localStorage.getItem('galchat.userId'))
  const username = localStorage.getItem('galchat.username') || ''

  return {
    token: currentToken || '',
    id: Number.isFinite(id) && id > 0 ? id : null,
    username,
  }
}

export const api = {
  login(email: string, password: string) {
    return request<UserToken>('/user/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  },
  register(email: string, password: string) {
    return request<UserToken>('/user/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  },
  getUserInfo() {
    return request<UserInfo>('/user/info')
  },
  updateUserInfo(payload: Partial<Pick<UserInfo, 'username' | 'email' | 'birthday'>>) {
    return request<void>('/user/info', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  updatePassword(payload: { email: string; oldPassword: string; newPassword: string }) {
    return request<void>('/user/password', {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  listWorldTemplates() {
    return request<WorldTemplate[]>('/world/templates')
  },
  getWorldTemplate(id: number) {
    return request<WorldTemplate>(`/world/templates/${id}`)
  },
  createWorldTemplate(payload: WorldTemplate) {
    return request<void>('/world/templates', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listWorldDetails(worldId: number) {
    return request<WorldDetail[]>(`/world/templates/${worldId}/details`)
  },
  createWorldDetail(worldId: number, payload: WorldDetail) {
    return request<void>(`/world/templates/${worldId}/details`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  deleteWorldDetail(worldId: number, detailId: number) {
    return request<void>(`/world/templates/${worldId}/${detailId}`, {
      method: 'DELETE',
    })
  },
  createUserWorld(payload: Partial<UserWorld> & { worldId: number }) {
    return request<void>('/world', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  listUserWorlds(userId: number) {
    return request<UserWorld[]>(`/world/user/${userId}`)
  },
  getUserWorld(id: number) {
    return request<UserWorld>(`/world/${id}`)
  },
  listCharacters(userWorldId: number) {
    return request<UserCharacter[]>(`/character/${userWorldId}`)
  },
  createCharacterTemplate(worldId: number, payload: CharacterTemplate) {
    return request<void>(`/character/templates/${worldId}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
  addCharacter(userWorldId: number, characterId: number) {
    return request<void>(`/character/${userWorldId}/${characterId}`, {
      method: 'POST',
    })
  },
  listHistory(userWorldId: number, characterId: number, size = 30) {
    const params = new URLSearchParams({
      userworldid: String(userWorldId),
      characterid: String(characterId),
      size: String(size),
    })
    return request<ChatHistory[]>(`/history?${params.toString()}`)
  },
  listStories(userWorldId: number) {
    return request<StoryListItem[]>(`/worldevent/story/list/${userWorldId}`)
  },
  getActiveStory(userWorldId: number) {
    return request<ActiveStory | null>(`/worldevent/story/active/${userWorldId}`)
  },
  startStory(payload: StoryStartPayload) {
    return request<void>('/worldevent/story/start', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
}

export async function uploadImage(file: File) {
  const formData = new FormData()
  formData.append('file', file)

  const headers = new Headers()
  const currentToken = token()
  if (currentToken) {
    headers.set('token', currentToken)
  }

  const response = await fetch(endpoint('/upload'), {
    method: 'POST',
    headers,
    body: formData,
  })

  if (!response.ok) {
    if (handleUnauthorized(response)) {
      throw new Error('登录状态已失效，请重新登录')
    }
    throw new Error(await readError(response))
  }

  const result = (await response.json()) as ApiResult<string>
  if (result.code !== 1) {
    throw new Error(result.msg || '上传失败')
  }

  return result.data || ''
}

export async function streamChat(
  payload: ChatMessagePayload,
  onMessage: (message: ChatFlux) => void,
) {
  const headers = new Headers({ 'Content-Type': 'application/json' })
  const currentToken = token()

  if (currentToken) {
    headers.set('token', currentToken)
  }

  const response = await fetch(endpoint('/ai/chat'), {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  })

  if (!response.ok) {
    if (handleUnauthorized(response)) {
      throw new Error('登录状态已失效，请重新登录')
    }
    throw new Error(await readError(response))
  }
  if (!response.body) {
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const handleLine = (rawLine: string) => {
    const line = rawLine.trim()
    if (!line || line.startsWith(':')) {
      return
    }

    const data = line.startsWith('data:') ? line.slice(5).trim() : line
    if (!data || data === '[DONE]') {
      return
    }

    try {
      onMessage(JSON.parse(data) as ChatFlux)
    } catch {
      onMessage({ type: 'reponse', content: data })
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const rawLine of lines) {
      handleLine(rawLine)
    }
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    handleLine(buffer)
  }
}

export function createChatSocket(userWorldId: number) {
  const sid =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
  const url = new URL(wsEndpoint(`/ws/${encodeURIComponent(sid)}`))
  url.searchParams.set('userWorldId', String(userWorldId))

  const currentToken = token()
  if (currentToken) {
    url.searchParams.set('token', currentToken)
  }

  return new WebSocket(url.toString())
}
