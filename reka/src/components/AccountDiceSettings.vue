<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { Check, Dices, LoaderCircle } from '@lucide/vue'
import DicePlayerDialog from '@/dice/components/DicePlayerDialog.vue'
import { DICE_SKIN_OPTIONS, type DiceSkin, type DicePlaybackRequest, type DicePlayerPhase } from '@/dice/domain/dicePlayback'

const skin = defineModel<DiceSkin>({ required: true })
const previewOpen = ref(true)
const skinLabel = computed(() => DICE_SKIN_OPTIONS.find(option => option.value === skin.value)?.label)
const phase = ref<DicePlayerPhase>('loading')
const canRoll = computed(() => ['ready', 'complete', 'error'].includes(phase.value))
let sequence = 0

function createRequest(value?: number): DicePlaybackRequest {
  const outcomeTone = value === 1 ? 'critical-success'
    : value === 99 || value === 100 ? 'fumble' : undefined
  const outcomeLabel = outcomeTone === 'critical-success' ? '大成功' : '大失败'
  return {
    id: ++sequence,
    skin: skin.value,
    reason: '骰子皮肤预览',
    mode: value === undefined ? 'pending' : 'play',
    autoPlay: value !== undefined,
    completionAction: 'REPLAY',
    presentation: outcomeTone ? {
      kind: 'multiplayer-check',
      resultLabel: '试掷结果', resultValue: outcomeLabel,
      formulaLabel: '掷骰公式', formulaValue: '1D100',
      groups: [{
        label: '', checkName: '1D100', moduleStart: 0, moduleCount: 1,
        rollResult: value, outcomeLabel, outcomeTone,
        success: outcomeTone === 'critical-success',
      }],
    } : undefined,
    result: {
      formula: '1D100', result: value,
      modules: [{
        expression: '1D100', diceCount: 1, diceSides: 100, modifier: 'NORMAL', result: value,
        dice: [
          { sides: 10, value: value === undefined ? undefined : Math.floor((value % 100) / 10), role: 'PERCENTILE_TENS', selected: true },
          { sides: 10, value: value === undefined ? undefined : value % 10, role: 'PERCENTILE_ONES', selected: true },
        ],
      }],
    },
  }
}
const request = shallowRef(createRequest())
function tryRoll() {
  if (!canRoll.value) return
  phase.value = 'loading'
  request.value = createRequest(Math.floor(Math.random() * 100) + 1)
}
watch(skin, () => {
  phase.value = 'loading'
  request.value = createRequest()
})
</script>

<template>
  <div class="account-settings-layout">
    <div class="account-settings-fields">
      <slot />
      <fieldset class="account-skin-picker">
        <legend>骰子皮肤</legend>
        <div class="account-skin-options">
          <button v-for="option in DICE_SKIN_OPTIONS" :key="option.value" type="button"
            class="account-skin-option" :class="{ 'is-active': skin === option.value }"
            :aria-pressed="skin === option.value" @click="skin = option.value">
            <span class="account-skin-swatch" :class="`skin-${option.value}`" aria-hidden="true" />
            <span>{{ option.label }}</span>
            <Check v-if="skin === option.value" class="account-skin-check" :size="12" aria-hidden="true" />
          </button>
        </div>
        <p class="account-skin-note">保存后，新打开的掷骰动画会使用这套皮肤。</p>
      </fieldset>
    </div>
    <section class="account-dice-preview" aria-label="D100 骰子皮肤预览">
      <div class="account-preview-heading"><span>皮肤预览 · {{ skinLabel }}</span><span>D100</span></div>
      <DicePlayerDialog v-model="previewOpen" :request="request" embedded @phase-change="phase = $event" />
      <div class="account-preview-actions">
        <button type="button" class="button" :disabled="!canRoll" @click="tryRoll">
          <LoaderCircle v-if="phase === 'playing'" class="spin" :size="15" aria-hidden="true" />
          <Dices v-else :size="15" aria-hidden="true" />
          {{ phase === 'playing' ? '正在试掷…' : '试掷一下' }}
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.account-settings-layout { display: grid; grid-template-columns: minmax(0, .75fr) minmax(0, 1.4fr); gap: 24px; }
.account-settings-fields { padding: 8px 24px 4px 0; border-right: 1px solid var(--line); min-width: 0; }
.account-skin-picker { padding: 0; margin: 23px 0 0; border: 0; min-width: 0; }
.account-skin-picker legend { padding: 0; margin-bottom: 10px; color: var(--ink); font-size: 12px; font-weight: 600; }
.account-skin-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.account-skin-option { position: relative; min-height: 62px; padding: 10px 8px; display: flex; align-items: center; gap: 8px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-strong); text-align: left; font-size: 12px; cursor: pointer; transition: background 150ms, border-color 150ms; }
.account-skin-option:hover { border-color: var(--line-strong); background: var(--paper); }
.account-skin-option.is-active { border-color: var(--pine); background: var(--pine-soft); box-shadow: inset 0 0 0 .5px var(--pine); }
.account-skin-check { position: absolute; top: 5px; right: 5px; color: var(--pine); }
.account-skin-swatch { width: 20px; height: 20px; flex-shrink: 0; border-radius: 4px; box-shadow: inset 0 0 0 1px rgb(0 0 0 / 6%); }
.skin-classic { background: #b9a276; }
.skin-galaxy { background: #62588d; }
.skin-moonwhite { background: #b8dce4; }
.skin-cinnabar { background: #b04442; }
.account-skin-note { margin: 12px 0 0; font-size: 11px; line-height: 1.7; color: var(--muted); }
.account-dice-preview { padding-top: 8px; min-width: 0; display: flex; flex-direction: column; }
.account-preview-heading { display: flex; justify-content: space-between; gap: 8px; color: var(--muted); font-size: 11px; letter-spacing: .08em; }
.account-preview-heading { margin-bottom: 12px; }
.account-preview-actions { display: flex; justify-content: center; margin-top: 16px; }
.account-preview-actions .button { min-height: 40px; padding-inline: 22px; }
@media (min-width: 768px) {
  .account-dice-preview :deep(.dice-player-tray) { min-height: 0; padding: 24px 16px 18px; }
  :global(.dialog-content.account-settings-dialog) { width: min(1040px, calc(100vw - 32px)); }
}
@media (min-width: 768px) and (max-width: 959px) {
  .account-settings-layout { grid-template-columns: 1fr; }
  .account-settings-fields { padding-right: 0; border-right: 0; }
}
@media (max-width: 767px) {
  .account-settings-layout { grid-template-columns: 1fr; gap: 22px; }
  .account-settings-fields { border-right: 0; padding: 0 0 20px; border-bottom: 1px solid var(--line); }
  .account-dice-preview { padding-top: 0; }
  .account-skin-option { min-height: 58px; padding: 12px; }
}
@media (prefers-reduced-motion: reduce) { .account-skin-option { transition: none; } }
</style>
