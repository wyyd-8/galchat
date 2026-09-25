import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

test('desktop and mobile image editors show existing images and device-specific upload controls', async context => {
  const vite = await createServer({
    configFile: false, appType: 'custom',
    root: fileURLToPath(new URL('..', import.meta.url)), plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  for (const device of ['Desktop', 'Mobile']) {
    const { default: component } = await vite.ssrLoadModule(`/src/components/${device}TemplateImageUpload.vue`)
    for (const kind of ['world', 'character']) {
      const render = (modelValue = '', disabled = false) => renderToString(createSSRApp({
        render: () => h(component, { kind, modelValue, disabled, name: '骑士学院' }),
      }))
      const empty = await render()
      assert.doesNotMatch(empty, /<img /)
      assert.doesNotMatch(empty, />移除<\/button>/)
      if (device === 'Mobile') {
        assert.match(empty, /从相册选择/)
        assert.doesNotMatch(empty, /拖到这里/)
      } else {
        assert.match(empty, /拖到这里/)
      }
      const filled = await render('https://example.test/image.png')
      assert.match(filled, /<img [^>]*src="https:\/\/example.test\/image.png"/)
      assert.match(filled, />更换图片<\/button>/)
      assert.match(filled, />移除<\/button>/)
      assert.match(filled, kind === 'world' ? /世界封面/ : /角色图片/)
      const locked = await render('https://example.test/image.png', true)
      assert.match(locked, /<button [^>]*disabled[^>]*>.*?更换图片<\/button>/s)
      assert.match(locked, /<button [^>]*disabled[^>]*>移除<\/button>/)
    }
  }
})
