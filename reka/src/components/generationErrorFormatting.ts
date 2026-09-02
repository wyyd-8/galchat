type DebugRecord = Record<string, unknown>

function isRecord(value: unknown): value is DebugRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isStreamingDelta(value: unknown): value is DebugRecord & { eventType: string; delta: string } {
  return isRecord(value)
    && typeof value.eventType === 'string'
    && value.eventType.endsWith('.delta')
    && typeof value.delta === 'string'
}

export function mergeGenerationResponseEvents(value: unknown) {
  if (!isRecord(value) || !Array.isArray(value.events)) return value
  const events: unknown[] = []
  value.events.forEach((event) => {
    if (!isRecord(event)) {
      events.push(event)
      return
    }
    const displayedEvent = { ...event }
    const previous = events.at(-1)
    if (isStreamingDelta(previous)
      && isStreamingDelta(displayedEvent)
      && previous.eventType === displayedEvent.eventType
      && previous.turnId === displayedEvent.turnId
      && previous.replyStepId === displayedEvent.replyStepId
      && previous.messageId === displayedEvent.messageId) {
      previous.delta += displayedEvent.delta
      return
    }
    events.push(displayedEvent)
  })
  return { ...value, events }
}
