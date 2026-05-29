export function imageStyle(image?: string) {
  return image ? { backgroundImage: `url(${image})` } : {}
}

export function firstText(text?: string) {
  return text?.trim().charAt(0) || 'G'
}

export function favorTone(value?: number) {
  const favor = value ?? 0
  if (favor >= 80) return 'success'
  if (favor >= 50) return 'primary'
  if (favor >= 20) return 'warning'
  return 'danger'
}

export function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

export function storyStatusLabel(status?: string) {
  if (status === 'ACTIVE') return '进行中'
  if (status === 'CLOSED') return '已结束'
  return status || '未知'
}

export function storyStatusType(status?: string): 'success' | 'info' | 'warning' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'CLOSED') return 'info'
  return 'warning'
}

export function splitMessageContent(content: string) {
  const parts = content
    .split(/\r?\n/)
    .map((part) => part.trim())
    .filter(Boolean)
  return parts.length > 0 ? parts : [content]
}
