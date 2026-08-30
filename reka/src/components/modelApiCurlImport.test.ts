import assert from 'node:assert/strict'
import test from 'node:test'
import { parseOpenAiCurl } from './modelApiCurlImport.ts'

test('extracts connection fields and provider overrides from a chat completions curl', () => {
  const result = parseOpenAiCurl(String.raw`curl https://api.deepseek.com/chat/completions \
    -H "Authorization: Bearer \${DEEPSEEK_API_KEY}" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "deepseek-chat",
      "messages": [{"role": "user", "content": "hello"}],
      "thinking": {"type": "enabled"},
      "reasoning_effort": "high",
      "stream": true,
      "max_completion_tokens": 512
    }'`)

  assert.equal(result.baseUrl, 'https://api.deepseek.com')
  assert.equal(result.modelName, 'deepseek-chat')
  assert.equal(result.apiKey, undefined)
  assert.deepEqual(result.requestOverrides, {
    thinking: { type: 'enabled' },
    reasoning_effort: 'high',
    max_completion_tokens: 512,
  })
  assert.deepEqual(result.warnings, [
    'max_completion_tokens 当前为 512，群聊或跑团中的长回复可能因上限较小而被截断。建议至少设置为 4096。',
  ])
})

test('supports --url, --header and --data-raw while extracting a literal bearer key', () => {
  const result = parseOpenAiCurl(String.raw`curl --request POST \
    --url 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' \
    --header 'Authorization: Bearer sk-literal-value' \
    --header 'Content-Type: application/json' \
    --data-raw '{"model":"qwen-plus","messages":[],"enable_thinking":true,"thinking_budget":8192,"stream_options":{"include_usage":true}}'`)

  assert.equal(result.baseUrl, 'https://dashscope.aliyuncs.com/compatible-mode/v1')
  assert.equal(result.modelName, 'qwen-plus')
  assert.equal(result.apiKey, 'sk-literal-value')
  assert.deepEqual(result.requestOverrides, {
    enable_thinking: true,
    thinking_budget: 8192,
  })
  assert.deepEqual(result.warnings, [])
})

test('keeps nested provider parameters without allowing runtime-owned fields through', () => {
  const result = parseOpenAiCurl(String.raw`curl 'https://openrouter.ai/api/v1/chat/completions' \
    -H 'Authorization: Bearer $OPENROUTER_API_KEY' \
    -d '{"model":"openai/gpt-5","messages":[],"reasoning":{"effort":"high"},"tools":[],"tool_choice":"auto","response_format":{"type":"json_object"},"n":2}'`)

  assert.deepEqual(result.requestOverrides, {
    reasoning: { effort: 'high' },
  })
})

test('rejects non-chat-completions examples with an actionable message', () => {
  assert.throws(
    () => parseOpenAiCurl(String.raw`curl https://api.openai.com/v1/responses -d '{"model":"gpt-5","input":"hello"}'`),
    /仅支持 OpenAI Chat Completions 格式/,
  )
})

test('rejects malformed or incomplete curl examples', () => {
  assert.throws(() => parseOpenAiCurl('not curl'), /请粘贴以 curl 开头/)
  assert.throws(
    () => parseOpenAiCurl(String.raw`curl https://api.example.com/v1/chat/completions -d '{bad json}'`),
    /请求体不是有效的 JSON/,
  )
  assert.throws(
    () => parseOpenAiCurl(String.raw`curl https://api.example.com/v1/chat/completions -d '{"messages":[]}'`),
    /请求体中缺少 model/,
  )
})
