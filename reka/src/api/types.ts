export interface ApiResult<T> { code: number; msg: string; data?: T }
export interface Session { token: string; id: number | null; username: string }
export interface UserToken { token: string; id: number; username: string }
export interface UserInfo { id: number; username: string; email?: string; birthday?: string; createTime?: string }

export interface WorldTemplate {
  id?: number; name: string; image?: string; author?: string; background?: string; authorId?: number; visible?: boolean
}
export interface UserWorld {
  id: number; userId?: number; worldId?: number; name: string; image?: string; acitvePushStatus?: boolean
  dailyCompanionMode?: boolean; favorSystemStatus?: string; eotDetectionStatus?: boolean; thinkStatus?: boolean
  addSpecialPrompt?: boolean; myWorld?: boolean
}
export interface WorldDetail { id?: number; worldId?: number; about?: string; details?: string }
export interface WorldSave {
  userWorldId: number; savedAt?: string; remark?: string
  characterFavors?: Array<{ characterId: number; characterName: string; favorValue?: number }>
}
export interface WorldArchive {
  formatVersion: number
  world: { name: string; image?: string; author?: string; background: string; visible?: boolean }
  details?: Array<{ about?: string; details?: string }>
  characters?: Array<CharacterTemplate>
}
export interface WorldArchiveResult { worldId: number; name: string; detailCount: number; characterCount: number }

export interface Character {
  userWorldId: number; characterId: number; characterName: string; characterImage?: string
  lastChatTime?: string; lastChatContent?: string; favorValue?: number; userInfoPrompt?: string
}
export interface CharacterTemplate {
  id?: number; worldId?: number; name: string; image?: string; background?: string; personality?: string
  favorability?: Record<string, string>; initFavor?: number
}

export interface ChatHistory {
  id?: number; userWorldId?: number; characterId?: number; content?: string; type?: string
  userMessageId?: number; stepNo?: number; timestamp?: string
}
export interface ChatMessagePayload {
  type?: string; worldId: number; userWorldId: number; characterId: number; message: string
  isTyping?: boolean; length?: number; revision?: number; triggerType?: string
}
export interface ChatFlux { type: string; content?: string }
export interface DirectMessage {
  id: string; role: 'user' | 'assistant' | 'thinking' | 'tool'; content: string; time?: string; complete?: boolean
}

export type ConversationMode = 'chat' | 'trpg'
export type ConversationStatus = 'active' | 'closed'
export interface Conversation {
  id: number; userWorldId: number; worldId: number; activeReplyPlanId?: number; mode: ConversationMode
  title: string; opening?: string; summary?: string; status: ConversationStatus; version?: number
  createdAt?: string; updatedAt?: string; endedAt?: string
}
export interface GroupMessage {
  id: number; conversationId: number; turnId?: number; replyStepId?: number
  speakerType: 'user' | 'character' | 'narrator'; speakerId?: number; speakerName?: string
  messageKind: 'dialogue' | 'narration' | 'system_event'; content: string; sequenceNo: number
  status: string; createdAt?: string
}
export interface GroupSpeaker { type: string; id?: number; name?: string; avatar?: string }
export interface GroupChatEvent {
  eventType: 'turn.accepted' | 'reply.started' | 'reasoning.delta' | 'message.delta' | 'message.completed' | 'reply.failed' | 'turn.completed'
  conversationId?: number; turnId?: number; replyStepId?: number; messageId?: number; sequence?: number
  speaker?: GroupSpeaker; delta?: string; content?: string; error?: string
}
export interface ReplyPlanItem { id?: number; order: number; actorType: string; actorId: number; status?: string }
export interface ReplyPlanGroup { key: string; name: string; order: number; items: ReplyPlanItem[] }
export interface ReplyPlan {
  id?: number; source: 'USER' | 'SCENE' | 'COMBAT'; contextId?: number; resumePlanId?: number; groups: ReplyPlanGroup[]
}
