import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'

for (const mobile of [false, true]) {
  test(`account preview embeds the real ${mobile ? 'mobile' : 'desktop'} player without a second dialog`, async context => {
    const audioDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'Audio')
    Object.defineProperty(globalThis, 'Audio', { configurable: true, value: class {
      currentTime = 0
      preload = ''
      pause() {}
      async play() {}
    } })
    context.after(() => {
      if (audioDescriptor) Object.defineProperty(globalThis, 'Audio', audioDescriptor)
      else Reflect.deleteProperty(globalThis, 'Audio')
    })
    const vite = await createServer({
      configFile: false, appType: 'custom',
      root: fileURLToPath(new URL('../..', import.meta.url)),
      plugins: [{
        name: 'account-preview-viewport', enforce: 'pre',
        load(id) {
          if (id.endsWith('/composables/useMobileViewport.ts')) {
            return `import { ref } from 'vue'; export function useMobileViewport() { return { isMobile: ref(${mobile}) } }`
          }
        },
      }, vue()],
      resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
      server: { middlewareMode: true, hmr: false, ws: false },
    })
    context.after(() => vite.close())
    const { default: AccountDiceSettings } = await vite.ssrLoadModule('/src/components/AccountDiceSettings.vue')
    const { createDiceOutcomeVfxPlan } = await vite.ssrLoadModule('/src/dice/domain/dicePlayback.ts')
    const { createSingleCheckOutcomeCuePlan } = await vite.ssrLoadModule('/src/dice/audio/diceAudio.ts')
    for (const skin of ['classic', 'galaxy', 'moonwhite', 'cinnabar']) {
      let state: any
      const app = createSSRApp(AccountDiceSettings, { modelValue: skin })
      app.mixin({ created() {
        if (this.$options.__name === 'AccountDiceSettings') state = (this.$ as any).setupState
      } })
      const html = await renderToString(app)
      assert.match(html, /dialog-embedded/)
      assert.match(html, /dice-player-surface/)
      assert.match(html, mobile ? /mobile-v4-window/ : /data-layout-columns/)
      assert.match(html, new RegExp(`data-skin="${skin}"`))
      assert.doesNotMatch(html, /role="dialog"|dialog-overlay|account-dice-canvas|dice-player-stage-bar|dice-player-result|mobile-progress|mobile-v4-actions|重放动画/)
      const request = state.request
      assert.match(html, /试掷一下/)
      assert.doesNotMatch(html, /示例点数为 47|重播不会计入游戏/)
      assert.equal(request.skin, skin)
      assert.equal(request.mode, 'pending')
      assert.equal(request.completionAction, 'REPLAY')
      assert.equal(request.result.modules.length, 1)
      const dice = request.result.modules[0].dice
      assert.equal(dice.length, 2)
      assert.equal(request.result.result, undefined)
      assert.ok(dice.every((die: any) => die.value === undefined))
      assert.deepEqual(createDiceOutcomeVfxPlan(request.presentation), [])
      const originalRandom = Math.random
      try {
        let previousId = request.id
        for (const [random, expected, tone] of [
          [0, 1, 'critical-success'], [.01, 2, undefined], [.46, 47, undefined],
          [.97, 98, undefined], [.98, 99, 'fumble'], [.99999, 100, 'fumble'],
          [.46, 47, undefined],
        ] as const) {
          Math.random = () => random!
          state.phase = 'ready'
          state.tryRoll()
          const rolled = state.request
          assert.ok(rolled.id > previousId)
          previousId = rolled.id
          assert.equal(rolled.mode, 'play')
          assert.equal(rolled.autoPlay, true)
          assert.equal(rolled.result.result, expected)
          const dice = rolled.result.modules[0].dice
          const value = dice.find((die: any) => die.role === 'PERCENTILE_TENS').value * 10
            + dice.find((die: any) => die.role === 'PERCENTILE_ONES').value
          assert.equal(value || 100, expected)
          assert.deepEqual(createDiceOutcomeVfxPlan(rolled.presentation), tone
            ? [{ tone, moduleStart: 0, moduleCount: 1, scope: 'stage' }] : [])
          assert.equal(createSingleCheckOutcomeCuePlan(rolled.presentation, [])?.tone, tone)
          assert.equal(rolled.presentation?.groups[0].outcomeTone, tone)
          state.tryRoll()
          assert.equal(state.request.id, previousId, 'ignore repeat clicks while loading or playing')
        }
      } finally { Math.random = originalRandom }

    }
  })
}
