import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

for (const mobile of [false, true]) {
  test(`${mobile ? 'mobile menu' : 'desktop toolbar'} accepts ZIP and JSON world/module imports`, async context => {
    const vite = await createServer({
      configFile: false, appType: 'custom', root: fileURLToPath(new URL('..', import.meta.url)),
      plugins: [{ name: 'archive-viewport', enforce: 'pre',
        load(id) { if (id.endsWith('/composables/useMobileViewport.ts')) return `import { ref } from 'vue'; export function useMobileViewport() { return { isMobile: ref(${mobile}) } }` },
        transform(_code, id) { if (id.endsWith('/ui/BaseDialog.vue')) return '<template><section><slot /><slot name="footer" /></section></template>' },
      }, vue()],
      resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
      server: { middlewareMode: true, hmr: false, ws: false },
    })
    context.after(() => vite.close())
    for (const name of ['WorldLibrary', 'CocModuleLibrary']) {
      const { default: component } = await vite.ssrLoadModule(`/src/components/${name}.vue`)
      const html = await renderToString(createSSRApp({ render: () => h(component, name === 'WorldLibrary' ? { worlds: [], templates: [], loading: false, archiveBusy: true } : {}) }))
      const inputs = html.match(/<input[^>]*type="file"[^>]*>/g) || []
      const archives = inputs.filter(input => input.includes('application/json'))
      assert.ok(archives.length, `${name} needs an import picker`)
      assert.ok(archives.every(input => input.includes('.zip') && input.includes('.json')))
      assert.match(html, /ZIP/)
      if (name === 'WorldLibrary') assert.ok(archives.every(input => input.includes('disabled')))
    }
  })
}
