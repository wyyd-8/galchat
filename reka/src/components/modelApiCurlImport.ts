export interface ModelApiCurlImportResult {
  baseUrl: string
  modelName: string
  apiKey?: string
  requestOverrides: Record<string, unknown>
  warnings: string[]
}

const RUNTIME_OWNED_FIELDS = new Set([
  'model',
  'messages',
  'input',
  'stream',
  'stream_options',
  'tools',
  'tool_choice',
  'parallel_tool_calls',
  'response_format',
  'n',
])

const VALUE_OPTIONS = new Set([
  '-X', '--request', '-H', '--header', '-d', '--data', '--data-raw',
  '--data-binary', '--url', '-u', '--user', '--connect-timeout', '--max-time',
])

const FLAG_OPTIONS = new Set([
  '-L', '--location', '-s', '--silent', '-S', '--show-error', '--compressed',
  '--fail', '--fail-with-body', '--http1.1', '--http2', '--insecure',
])

export function parseOpenAiCurl(source: string): ModelApiCurlImportResult {
  if (!source.trim()) throw new Error('请粘贴 cURL 请求示例。')
  if (source.length > 200_000) throw new Error('cURL 内容过长，请只保留单次请求示例。')

  const tokens = tokenizeShell(source)
  if (!tokens.length || !/(^|\/)curl$/.test(tokens[0])) {
    throw new Error('请粘贴以 curl 开头的请求示例。')
  }

  let endpoint = ''
  let bodyText = ''
  const headers: string[] = []
  for (let index = 1; index < tokens.length; index += 1) {
    const token = tokens[index]
    const equals = token.match(/^(--(?:url|header|data|data-raw|data-binary|request))=(.*)$/s)
    if (equals) {
      consumeOption(equals[1], equals[2])
      continue
    }
    if (VALUE_OPTIONS.has(token)) {
      const value = tokens[index + 1]
      if (value === undefined) throw new Error(`cURL 参数 ${token} 缺少值。`)
      consumeOption(token, value)
      index += 1
      continue
    }
    if (FLAG_OPTIONS.has(token)) continue
    if (token.startsWith('-')) {
      throw new Error(`暂不支持 cURL 参数 ${token}，请删除该参数后重试。`)
    }
    if (!endpoint) endpoint = token
  }

  function consumeOption(option: string, value: string) {
    if (option === '--url') endpoint = value
    else if (option === '-H' || option === '--header') headers.push(value)
    else if (option === '-d' || option === '--data' || option === '--data-raw' || option === '--data-binary') {
      if (value.startsWith('@')) throw new Error('不支持从本地文件读取请求体，请粘贴完整 JSON。')
      if (bodyText) throw new Error('检测到多个请求体参数，请只保留一个 JSON 请求体。')
      bodyText = value
    }
  }

  const baseUrl = chatCompletionsBaseUrl(endpoint)
  if (!bodyText) throw new Error('cURL 中缺少 JSON 请求体。')

  let body: unknown
  try {
    body = JSON.parse(bodyText)
  } catch {
    throw new Error('cURL 请求体不是有效的 JSON。')
  }
  if (!isPlainObject(body)) throw new Error('cURL 请求体必须是一个 JSON 对象。')

  const modelName = typeof body.model === 'string' ? body.model.trim() : ''
  if (!modelName) throw new Error('cURL 请求体中缺少 model。')

  const requestOverrides = Object.fromEntries(
    Object.entries(body).filter(([key]) => !RUNTIME_OWNED_FIELDS.has(key)),
  )
  const apiKey = literalBearerKey(headers)
  return {
    baseUrl,
    modelName,
    ...(apiKey ? { apiKey } : {}),
    requestOverrides,
    warnings: requestOverrideWarnings(requestOverrides),
  }
}

function chatCompletionsBaseUrl(rawEndpoint: string) {
  if (!rawEndpoint) throw new Error('cURL 中缺少请求地址。')
  let endpoint: URL
  try {
    endpoint = new URL(rawEndpoint)
  } catch {
    throw new Error('cURL 中的请求地址无效。')
  }
  const suffix = '/chat/completions'
  const pathname = endpoint.pathname.replace(/\/+$/, '')
  if (!pathname.endsWith(suffix)) {
    throw new Error('仅支持 OpenAI Chat Completions 格式，请使用以 /chat/completions 结尾的 cURL。')
  }
  endpoint.pathname = pathname.slice(0, -suffix.length) || '/'
  endpoint.search = ''
  endpoint.hash = ''
  return endpoint.toString().replace(/\/$/, '')
}

function literalBearerKey(headers: string[]) {
  for (const header of headers) {
    const match = header.match(/^authorization\s*:\s*bearer\s+(.+)$/i)
    if (!match) continue
    const value = match[1].trim()
    if (!value || /\$|\{|\}|<|>|YOUR[_ -]?/i.test(value)) return undefined
    return value
  }
  return undefined
}

export function requestOverrideWarnings(overrides: Record<string, unknown>) {
  const value = overrides.max_completion_tokens
  if (typeof value !== 'number' || !Number.isFinite(value) || value >= 4096) return []
  return [
    `max_completion_tokens 当前为 ${value}，群聊或跑团中的长回复可能因上限较小而被截断。建议至少设置为 4096。`,
  ]
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function tokenizeShell(source: string) {
  const tokens: string[] = []
  let current = ''
  let quote: "'" | '"' | null = null
  let tokenStarted = false

  for (let index = 0; index < source.length; index += 1) {
    const character = source[index]
    if (quote === "'") {
      if (character === "'") quote = null
      else current += character
      tokenStarted = true
      continue
    }
    if (quote === '"') {
      if (character === '"') quote = null
      else if (character === '\\') {
        const next = source[index + 1]
        if (next === '\n' || next === '\r') {
          index += next === '\r' && source[index + 2] === '\n' ? 2 : 1
        } else if (next !== undefined) {
          current += next
          index += 1
        }
      } else current += character
      tokenStarted = true
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
      tokenStarted = true
    } else if (character === '\\') {
      const next = source[index + 1]
      if (next === '\n' || next === '\r') {
        index += next === '\r' && source[index + 2] === '\n' ? 2 : 1
      } else if (next !== undefined) {
        current += next
        tokenStarted = true
        index += 1
      }
    } else if (/\s/.test(character)) {
      if (tokenStarted) {
        tokens.push(current)
        current = ''
        tokenStarted = false
      }
    } else {
      current += character
      tokenStarted = true
    }
  }
  if (quote) throw new Error('cURL 中存在未闭合的引号。')
  if (tokenStarted) tokens.push(current)
  return tokens
}
