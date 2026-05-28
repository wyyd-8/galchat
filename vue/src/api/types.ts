export interface ApiResult<T> {
  code: number
  msg: string
  data?: T
}

export interface UserToken {
  token: string
  id: number
  username: string
}

export interface UserInfo {
  id: number
  username: string
  email?: string
  birthday?: string
  createTime?: string
}

export interface WorldTemplate {
  id?: number
  name: string
  image?: string
  author?: string
  background?: string
  characterIds?: number[]
  authorId?: number
  visible?: boolean
}

export interface UserWorld {
  id: number
  userId?: number
  worldId?: number
  name: string
  image?: string
  acitvePushStatus?: boolean
  favorSystemStatus?: string
  eotDetectionStatus?: boolean
  thinkStatus?: boolean
}

export interface UserCharacter {
  userWorldId: number
  characterId: number
  characterName: string
  characterImage?: string
  lastChatTime?: string
  lastChatContent?: string
  favorValue?: number
}

export interface ChatHistory {
  id?: number
  userWorldId?: number
  characterId?: number
  content?: string
  type?: string
  userMessageId?: number
  stepNo?: number
  timestamp?: string
}

export interface ChatMessagePayload {
  type?: string
  worldId: number
  userWorldId: number
  characterId: number
  message: string
  isTyping?: boolean
  length?: number
  revision?: number
  triggerType?: string
}

export interface ChatFlux {
  type: string
  content?: string
}

export interface StoryListItem {
  id: number
  title: string
}

export interface StoryEvent {
  id: number
  userWorldId: number
  title: string
  theme?: string
  currentScene?: string
  opening?: string
  summary?: string
  status?: string
  startedAt?: string
  endedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface StoryCharacter {
  id: number
  storyEventId: number
  characterId: number
  startMessageId?: number
  endMessageId?: number
}

export interface ActiveStory {
  storyEvent: StoryEvent
  characters: StoryCharacter[]
}

export interface StoryStartPayload {
  userWorldId: number
  title: string
  theme?: string
  currentScene: string
  opening?: string
  characterIds: number[]
}
