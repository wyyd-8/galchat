import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

async function renderCard(model: Record<string, unknown>) {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  try {
    const { default: ModelApiCard } = await vite.ssrLoadModule('/src/components/ModelApiCard.vue')
    return await renderToString(createSSRApp({
      render: () => h(ModelApiCard, { model, testing: false }),
    }))
  } finally {
    await vite.close()
  }
}

test('renders a successful model with capability results and a masked API key', async () => {
  const html = await renderCard({
    id: 7,
    name: 'DeepSeek 主模型',
    baseUrl: 'https://api.deepseek.com/v1',
    modelName: 'deepseek-chat',
    hasApiKey: true,
    apiKeyHint: '8F2A',
    status: 'SUCCESS',
    chatCapability: 'SUPPORTED',
    streamingCapability: 'SUPPORTED',
    toolCallingCapability: 'SUPPORTED',
    reasoningOutputStatus: 'NOT_DETECTED',
    lastTestCode: 'OK',
    lastTestMessage: '测试通过',
    lastTestAt: '2026-08-29T10:00:00',
    createdAt: '2026-08-29T09:00:00',
    updatedAt: '2026-08-29T10:00:00',
  })

  assert.match(html, /DeepSeek 主模型/)
  assert.match(html, /deepseek-chat/)
  assert.match(html, /https:\/\/api\.deepseek\.com\/v1/)
  assert.match(html, /可用/)
  assert.match(html, /基础对话/)
  assert.match(html, /流式输出/)
  assert.match(html, /工具调用/)
  assert.match(html, /推理信息/)
  assert.match(html, /未检测到/)
  assert.match(html, /•••• 8F2A/)
  assert.doesNotMatch(html, /sk-secret/)
  assert.match(html, /aria-label="测试 DeepSeek 主模型"/)
  assert.match(html, /aria-label="编辑 DeepSeek 主模型"/)
  assert.match(html, /aria-label="删除 DeepSeek 主模型"/)
})

test('keeps a failed model actionable and exposes the latest diagnostic', async () => {
  const html = await renderCard({
    id: 7,
    name: '备用模型',
    baseUrl: 'https://api.example.com/v1',
    modelName: 'example-chat',
    hasApiKey: true,
    apiKeyHint: '1234',
    status: 'FAILED',
    chatCapability: 'UNSUPPORTED',
    streamingCapability: 'UNKNOWN',
    toolCallingCapability: 'UNKNOWN',
    reasoningOutputStatus: 'UNKNOWN',
    lastTestCode: 'AUTH_FAILED',
    lastTestMessage: 'API Key 无效',
    lastTestAt: '2026-08-29T10:00:00',
    createdAt: '2026-08-29T09:00:00',
    updatedAt: '2026-08-29T10:00:00',
  })

  assert.match(html, /连接失败/)
  assert.match(html, /API Key 无效/)
  assert.match(html, /AUTH_FAILED/)
  assert.match(html, />重新测试</)
})

test('requires adding an API key before the default model can be tested', async () => {
  const html = await renderCard({
    id: 7,
    name: 'DeepSeek 主模型',
    baseUrl: 'https://api.deepseek.com',
    modelName: 'deepseek-v4-pro',
    hasApiKey: false,
    status: 'UNTESTED',
    chatCapability: 'UNKNOWN',
    streamingCapability: 'UNKNOWN',
    toolCallingCapability: 'UNKNOWN',
    reasoningOutputStatus: 'UNKNOWN',
    createdAt: '2026-08-29T09:00:00',
    updatedAt: '2026-08-29T09:00:00',
  })

  assert.match(html, /deepseek-v4-pro/)
  assert.match(html, /API Key：/)
  assert.match(html, /未保存/)
  assert.match(html, /disabled[^>]*aria-label="测试 DeepSeek 主模型"|aria-label="测试 DeepSeek 主模型"[^>]*disabled/)
  assert.match(html, />配置 Key 后测试</)
})
