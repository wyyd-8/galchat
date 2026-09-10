/** A shared sentinel makes Back dismiss one mobile dialog without adding one entry per layer. */
export interface DialogHistoryPort {
  state(): unknown
  url(): string
  push(state: unknown): void
  back(): void
  defer(task: () => void): void
  listen(listener: () => void): () => void
}

const SENTINEL = '__galchatMobileDialog'
type CloseResult = boolean | void
interface DialogEntry { id: symbol; close: () => CloseResult | Promise<CloseResult>; closing: boolean }
interface Boundary { state: unknown; url: string }

function sameState(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true
  try { return JSON.stringify(left) === JSON.stringify(right) } catch { return false }
}

export function createMobileDialogHistory(port: DialogHistoryPort, token: string) {
  const entries: DialogEntry[] = []
  let boundary: Boundary | null = null
  let draining = false
  let handlingPop = false
  let stopListening: (() => void) | undefined
  const sentinel = () => {
    const state = port.state()
    const marker = state && typeof state === 'object' ? (state as Record<string, unknown>)[SENTINEL] : null
    return marker && typeof marker === 'object' && (marker as { token?: string }).token === token
      ? marker as { token: string; boundary: Boundary } : null
  }
  const isSentinel = () => Boolean(sentinel())
  const atBoundary = () => Boolean(boundary && port.url() === boundary.url && sameState(port.state(), boundary.state))
  const stopIfIdle = () => {
    if (entries.length || draining) return
    stopListening?.()
    stopListening = undefined
    boundary = null
  }
  const arm = () => {
    if (!entries.length || draining) return
    const existing = sentinel()
    if (existing) { boundary = existing.boundary; return }
    const original = port.state()
    boundary = { state: original, url: port.url() }
    // Keep router-owned fields intact on our temporary entry; never replace the real entry.
    port.push({ ...(original && typeof original === 'object' ? original : {}), [SENTINEL]: { token, boundary } })
  }
  const cleanupIfIdle = () => {
    if (entries.length || draining || handlingPop) return
    if (isSentinel()) {
      draining = true
      // Let same-turn routing or another dialog settle before traversing history.
      port.defer(() => {
        if (!isSentinel()) { draining = false; stopIfIdle(); return }
        port.back()
      })
    } else stopIfIdle()
  }
  const settleClose = (entry: DialogEntry, accepted: boolean) => {
    const index = entries.findIndex(item => item.id === entry.id)
    // A UI close, unmount, or viewport change has already removed this registration.
    if (index < 0) { cleanupIfIdle(); return }
    if (accepted) entries.splice(index, 1)
    else entry.closing = false
    if (entries.length) arm()
    else cleanupIfIdle()
  }
  const onPop = () => {
    // Back from a foreign entry may land on our sentinel. That navigation belongs to its owner.
    if (isSentinel()) return
    if (draining) {
      draining = false
      if (atBoundary()) arm()
      else boundary = null
      stopIfIdle()
      return
    }
    // A navigation beyond our own boundary is not a dialog dismissal.
    if (!atBoundary()) { boundary = null; return }
    const entry = entries.at(-1)
    if (!entry) { stopIfIdle(); return }
    if (entry.closing) { arm(); return }
    // Keep the entry in place until its owner accepts closing. A dirty-editor
    // confirmation may register above it while the model update is being checked.
    entry.closing = true
    let result: CloseResult | Promise<CloseResult>
    handlingPop = true
    try { result = entry.close() }
    catch { result = false }
    finally { handlingPop = false }
    if (result && typeof result === 'object' && 'then' in result) {
      arm()
      void result.then(
        accepted => settleClose(entry, accepted !== false),
        () => settleClose(entry, false),
      )
    } else settleClose(entry, result !== false)
  }

  return {
    register(close: () => CloseResult | Promise<CloseResult>) {
      const entry: DialogEntry = { id: Symbol('dialog'), close, closing: false }
      entries.push(entry)
      stopListening ||= port.listen(onPop)
      arm()
      return () => {
        const index = entries.findIndex(item => item.id === entry.id)
        if (index < 0) return
        entries.splice(index, 1)
        cleanupIfIdle()
      }
    },
  }
}
