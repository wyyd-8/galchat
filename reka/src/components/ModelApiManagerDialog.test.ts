import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('keeps the API key visible while it is being entered', async () => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: {
      alias: [
        {
          find: '@/components/ui/BaseDialog.vue',
          replacement: fileURLToPath(new URL('../../test/fixtures/BaseDialogStub.vue', import.meta.url)),
        },
        { find: '@', replacement: fileURLToPath(new URL('..', import.meta.url)) },
      ],
    },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  try {
    const { default: ModelApiManagerDialog } = await vite.ssrLoadModule(
      '/src/components/ModelApiManagerDialog.vue',
    )
    const html = await renderToString(createSSRApp({
      render: () => h(ModelApiManagerDialog, { modelValue: true }),
    }))

    assert.match(html, /<input[^>]*type="text"[^>]*autocomplete="off"[^>]*>/)
    assert.doesNotMatch(html, /<input[^>]*type="password"[^>]*>/)
  } finally {
    await vite.close()
  }
})

test('offers curl import and explains that parsed values are reviewed before saving', async () => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: {
      alias: [
        {
          find: '@/components/ui/BaseDialog.vue',
          replacement: fileURLToPath(new URL('../../test/fixtures/BaseDialogStub.vue', import.meta.url)),
        },
        { find: '@', replacement: fileURLToPath(new URL('..', import.meta.url)) },
      ],
    },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  try {
    const { default: ModelApiManagerDialog } = await vite.ssrLoadModule(
      '/src/components/ModelApiManagerDialog.vue',
    )
    const html = await renderToString(createSSRApp({
      render: () => h(ModelApiManagerDialog, { modelValue: true }),
    }))

    assert.match(html, /从 cURL 导入/)
    assert.match(html, /解析仅在本地浏览器中进行/)
  } finally {
    await vite.close()
  }
})
