import type { CharacterCard } from '../api/types.ts'

export type CardBackground = '1920s' | 'modern'
export type CardFont = 0 | 1 | 2

export function suggestedCardBackground(era?: string): CardBackground | '' {
  if (/1920/i.test(era || '')) return '1920s'
  if (/现代|modern/i.test(era || '')) return 'modern'
  return ''
}

export function cardPdfFilename(name?: string) {
  return `${(name || '调查员').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 100) || '调查员'}-人物卡.pdf`
}

export function createCharacterCardExporter(baseUrl: string) {
  const base = baseUrl.trim().replace(/\/+$/, '')
  return {
    async available(signal?: AbortSignal): Promise<boolean> {
      if (!base) return false
      try {
        const response = await fetch(`${base}/health`, {
          credentials: 'omit', cache: 'no-store',
          signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(2500)]) : AbortSignal.timeout(2500),
        })
        if (!response.ok) return false
        const result = await response.json()
        return result.service === 'character-card-pdf' && result.available === true
      } catch { return false }
    },
    async exportPdf(card: CharacterCard, background: CardBackground, fontIndex: CardFont, signal?: AbortSignal): Promise<Blob> {
      const response = await fetch(`${base}/export`, {
        method: 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ card, background, fontIndex }),
        signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000),
      })
      if (!response.ok) {
        const error = await response.json().catch(() => null)
        throw new Error(typeof error?.detail === 'string' ? error.detail : 'PDF 生成失败，请检查人物卡内容或稍后重试')
      }
      if (!response.headers.get('Content-Type')?.includes('application/pdf')) throw new Error('导出服务未返回 PDF，请检查服务地址')
      return response.blob()
    },
  }
}

// Fetch portraits in the browser, so the PDF server cannot read arbitrary paths/URLs.
export async function prepareCardPortrait(card: CharacterCard, signal: AbortSignal): Promise<{ card: CharacterCard; portraitOmitted: boolean }> {
  const copy = structuredClone(card)
  const source = copy.character.image
  if (!source) return { card: copy, portraitOmitted: false }
  try {
    const url = new URL(source, window.location.href)
    if (!['http:', 'https:', 'data:'].includes(url.protocol)) throw new Error('Unsupported image')
    const response = await fetch(url, { credentials: 'omit', signal: AbortSignal.any([signal, AbortSignal.timeout(5000)]) })
    if (!response.ok) throw new Error('Image unavailable')
    const blob = await response.blob()
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(blob.type) || blob.size > 2_900_000) throw new Error('Unsupported image')
    copy.character.image = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(blob)
    })
    return { card: copy, portraitOmitted: false }
  } catch (error) {
    if (signal.aborted) throw error
    copy.character.image = undefined
    return { card: copy, portraitOmitted: true }
  }
}
