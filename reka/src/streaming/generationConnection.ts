type Connect<E> = (receive: (event: E) => void, signal: AbortSignal) => Promise<void>

/** The server rejected the request before opening an event stream. */
export class GenerationRequestRejected extends Error {}
export class GenerationStartRejected extends Error {}

export function isGenerationAbort(error: unknown) {
  return error instanceof Error && error.name === 'AbortError'
}

/** One connection attempt may send; every subsequent attempt only resumes that same generation. */
export async function followGeneration<E>(options: {
  signal: AbortSignal
  initial?: Connect<E>
  resume: (receive: (event: E) => void, after: number, signal: AbortSignal) => Promise<void>
  after?: number
  sequence: (event: E) => number | undefined
  terminal: (event: E) => boolean
  receive: (event: E) => void
  onResume?: () => void
}) {
  let cursor = options.after ?? 0
  let terminal = false
  const receive = (event: E) => {
    if (options.signal.aborted) return
    const sequence = options.sequence(event)
    if (sequence != null && sequence <= cursor) return
    options.receive(event)
    if (sequence != null) cursor = sequence
    terminal ||= options.terminal(event)
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    options.signal.throwIfAborted()
    try {
      if (attempt === 0 && options.initial) await options.initial(receive, options.signal)
      else {
        options.onResume?.()
        await options.resume(receive, cursor, options.signal)
      }
      options.signal.throwIfAborted()
      if (!terminal) throw new Error('回复连接中断')
      return
    } catch (error) {
      options.signal.throwIfAborted()
      if (attempt === 0 && options.initial && cursor === 0 && error instanceof GenerationRequestRejected) {
        throw new GenerationStartRejected(error.message, { cause: error })
      }
      if (attempt === 2) throw error
      await new Promise<void>((resolve) => {
        const finish = () => { clearTimeout(timer); options.signal.removeEventListener('abort', finish); resolve() }
        const timer = setTimeout(finish, 500 * (attempt + 1))
        options.signal.addEventListener('abort', finish, { once: true })
      })
    }
  }
}
