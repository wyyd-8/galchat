interface ModelEditorDraft {
  name: string
  baseUrl: string
  modelName: string
  apiKey: string
  requestOverrides: Record<string, unknown>
}

function ordered(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(ordered)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
      .map(([key, entry]) => [key, ordered(entry)]))
  }
  return value
}

/** Compare the submitted connection fields and any unparsed cURL draft. */
export function modelApiEditorFingerprint(form: ModelEditorDraft, curlSource = ''): string {
  return JSON.stringify({
    name: form.name.trim(), baseUrl: form.baseUrl.trim(), modelName: form.modelName.trim(),
    apiKey: form.apiKey.trim(), requestOverrides: ordered(form.requestOverrides), curlSource: curlSource.trim(),
  })
}
