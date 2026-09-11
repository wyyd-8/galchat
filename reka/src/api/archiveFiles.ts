export const ARCHIVE_ACCEPT = '.zip,application/zip,application/x-zip-compressed,.json,application/json'

export async function archiveRequest(file: File): Promise<{ zip: boolean; body: FormData | string }> {
  const extension = file.name.split('.').at(-1)?.toLowerCase()
  if (extension !== 'zip' && extension !== 'json') throw new Error('请选择 ZIP 或 JSON 归档文件')
  if (!file.size) throw new Error('归档文件为空')
  const limit = extension === 'zip' ? 64 : 4
  if (file.size > limit * 1024 * 1024) throw new Error(`${extension.toUpperCase()} 文件不能超过 ${limit} MB`)
  if (extension === 'zip') {
    const form = new FormData()
    form.append('file', file)
    return { zip: true, body: form }
  }
  try {
    const value: unknown = JSON.parse(await file.text())
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid archive')
    return { zip: false, body: JSON.stringify(value) }
  } catch { throw new Error('JSON 文件格式无效，请选择导出的归档文件') }
}

export function downloadArchive(blob: Blob, name: string) {
  const link = document.createElement('a')
  const url = URL.createObjectURL(blob)
  link.href = url
  link.download = `${name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_') || 'galchat-archive'}.zip`
  document.body.append(link)
  link.click()
  link.remove()
  // Mobile browsers may consume the blob after the click handler returns.
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
