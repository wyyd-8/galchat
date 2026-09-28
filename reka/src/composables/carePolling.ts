import type { CareMessagePage, ChatHistory } from '../api/types'

const MINUTE = 60_000
const DAY = 24 * 60 * MINUTE
const STARTS = [8, 13, 19, 21].map(hour => hour * 60 * MINUTE)

// Use Beijing time regardless of the device's local timezone.
export function carePollingWindow(now: number) {
  const time = ((now + 8 * 60 * MINUTE) % DAY + DAY) % DAY
  const start = STARTS.find(start => time >= start && time < start + 40 * MINUTE)
  if (start != null) return { active: true, delay: Math.min(MINUTE, start + 40 * MINUTE - time) }
  const next = STARTS.find(start => start > time) ?? STARTS[0]! + DAY
  return { active: false, delay: next - time }
}

interface PollingOptions {
  now: () => number
  visible: () => boolean
  schedule: (callback: () => void, delay: number) => () => void
  readCursor: (key: string) => number | undefined
  writeCursor: (key: string, cursor: number) => void
  fetchPage: (worldId: number, after: number | undefined, signal: AbortSignal) => Promise<CareMessagePage>
  receive: (messages: ChatHistory[]) => void
}

export function createCarePolling(options: PollingOptions) {
  let scope: { key: string; worldId: number; cursor?: number } | undefined
  let revision = 0
  let running = false
  let pauses = 0
  let cancelTimer: (() => void) | undefined
  let controller: AbortController | undefined

  function suspend() {
    revision++; cancelTimer?.(); cancelTimer = undefined
    controller?.abort(); controller = undefined; running = false
  }
  function stop() { suspend(); scope = undefined }
  // Multiple conversations can withdraw while the user switches between them.
  function pause() { pauses++; suspend() }
  function resume() {
    if (pauses === 0) return
    pauses--
    if (pauses === 0) void poll(true)
  }
  function schedule() {
    cancelTimer?.(); cancelTimer = undefined
    if (!scope || pauses > 0 || !options.visible()) return
    cancelTimer = options.schedule(() => void poll(false), carePollingWindow(options.now()).delay)
  }
  async function poll(force: boolean) {
    if (!scope || pauses > 0 || running || !options.visible()) return
    if (!force && !carePollingWindow(options.now()).active) { schedule(); return }
    cancelTimer?.(); cancelTimer = undefined
    const current = scope; const ownRevision = revision
    const request = new AbortController(); controller = request; running = true
    const valid = () => scope === current && revision === ownRevision && !request.signal.aborted
    try {
      let hasMore = true
      while (hasMore && valid()) {
        const cancelTimeout = options.schedule(() => request.abort(), 45_000)
        let page: CareMessagePage
        try { page = await options.fetchPage(current.worldId, current.cursor, request.signal) }
        finally { cancelTimeout() }
        if (!valid()) return
        if (!Number.isSafeInteger(page.nextCursor) || page.nextCursor < (current.cursor ?? 0)
          || (page.hasMore && page.nextCursor === current.cursor)) throw new Error('Invalid care message cursor')
        options.receive(page.messages)
        current.cursor = page.nextCursor
        options.writeCursor(current.key, page.nextCursor)
        hasMore = page.hasMore
      }
    } catch {
      // Keep the last successfully delivered cursor; retry at the next scheduled check.
    } finally {
      if (scope === current && revision === ownRevision) {
        controller = undefined; running = false; schedule()
      }
    }
  }
  function start(key: string, worldId: number) {
    stop()
    scope = { key, worldId, cursor: options.readCursor(key) }
    void poll(true)
  }
  function visibilityChanged() {
    suspend()
    if (options.visible()) void poll(true)
  }
  return { start, stop, pause, resume, visibilityChanged, refresh: () => poll(true) }
}
