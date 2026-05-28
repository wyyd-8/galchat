import type {
  ActiveStory,
  ApiResult,
  ChatFlux,
  ChatHistory,
  ChatMessagePayload,
  StoryListItem,
  StoryStartPayload,
  UserCharacter,
  UserInfo,
  UserToken,
  UserWorld,
  WorldTemplate,
} from './types'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'
const TOKEN_KEY = 'galchat.token'

function token() {
  return localStorage.getItem(TOKEN_KEY)
}

function endpoint(path: string) {
  return `${API_BASE}${path}`
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
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem('galchat.userId')
  localStorage.removeItem('galchat.username')
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
    throw new Error(await readError(response))
  }
  if (!response.body) {
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line || line.startsWith(':')) {
        continue
      }

      const data = line.startsWith('data:') ? line.slice(5).trim() : line
      if (!data || data === '[DONE]') {
        continue
      }

      try {
        onMessage(JSON.parse(data) as ChatFlux)
      } catch {
        onMessage({ type: 'reponse', content: data })
      }
    }
  }
}
