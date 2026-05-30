export type SidebarMode = 'worlds' | 'characters'

export interface UiMessage {
  id: string
  role: 'user' | 'assistant' | 'thinking' | 'tool' | 'story'
  content: string
  time?: string
  complete?: boolean
}

export interface FavorabilityRow {
  id: string
  threshold: number | undefined
  prompt: string
}
