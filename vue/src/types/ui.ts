export type SidebarMode = 'worlds' | 'characters'

export interface UiMessage {
  id: string
  role: 'user' | 'assistant' | 'thinking' | 'tool' | 'story'
  content: string
  time?: string
}

export interface FavorabilityRow {
  id: string
  threshold: number | undefined
  prompt: string
}
