<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, LoaderCircle, Minus, Plus, RotateCcw } from '@lucide/vue'
import BaseDialog from './ui/BaseDialog.vue'
import type { Character } from '@/api/types'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ character: Character | null; worldName?: string; canEdit: boolean; saving: boolean }>()
const emit = defineEmits<{ save: [value: number] }>()
const initialValue = ref(0)
const value = ref<number | string>(0)
watch(() => [open.value, props.character?.userWorldId, props.character?.characterId], () => {
  if (!open.value) return
  initialValue.value = props.character?.favorValue ?? 0
  value.value = initialValue.value
}, { immediate: true })
const valid = computed(() => typeof value.value === 'number' && Number.isInteger(value.value) && value.value >= 0 && value.value <= 100)
const locked = computed(() => props.saving || !props.canEdit)
const delta = computed(() => valid.value ? Number(value.value) - initialValue.value : 0)
const sliderValue = computed({
  get: () => valid.value ? Number(value.value) : initialValue.value,
  set: (next: number) => { value.value = next },
})
const progress = computed(() => `${sliderValue.value}%`)
const canSave = computed(() => !locked.value && props.character && valid.value && delta.value !== 0)
function adjust(amount: number) {
  if (locked.value) return
  value.value = Math.max(0, Math.min(100, (valid.value ? Number(value.value) : initialValue.value) + amount))
}
function save() { if (canSave.value) emit('save', Number(value.value)) }
</script>

<template>
  <BaseDialog v-model="open" title="调整好感度" description="调整角色在当前世界中的好感度。" content-class="character-favor-dialog" mobile-presentation="sheet">
    <div class="favor-character">
      <span class="character-avatar" :style="character?.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character?.characterImage ? '' : character?.characterName.slice(0, 1) }}</span>
      <div><strong>{{ character?.characterName }}</strong><span>{{ worldName }}</span></div>
      <span class="favor-current">当前 <strong>{{ initialValue }}</strong></span>
    </div>
    <form id="character-favor-form" class="favor-adjustment" @submit.prevent="save">
      <label for="character-favor-value" class="favor-value-label">目标好感度</label>
      <div class="favor-stepper">
        <button type="button" class="favor-step" aria-label="好感度减 1" :disabled="locked || (valid && Number(value) === 0)" @click="adjust(-1)"><Minus :size="16" /></button>
        <div class="favor-number"><input id="character-favor-value" v-model.number="value" type="number" min="0" max="100" step="1" inputmode="numeric" :disabled="locked" :aria-invalid="!valid" :aria-describedby="!valid ? 'character-favor-error' : undefined" /><span>/ 100</span></div>
        <button type="button" class="favor-step" aria-label="好感度加 1" :disabled="locked || (valid && Number(value) === 100)" @click="adjust(1)"><Plus :size="16" /></button>
      </div>
      <p class="favor-delta" :class="{ changed: valid && delta !== 0 }" role="status">{{ !valid ? '等待输入有效数值' : delta > 0 ? `比当前提高 ${delta} 点` : delta < 0 ? `比当前降低 ${-delta} 点` : '与当前好感度相同' }}</p>
      <div class="favor-range-control">
        <input v-model.number="sliderValue" class="favor-range" type="range" min="0" max="100" step="1" aria-label="拖动调整好感度" :disabled="locked" :style="{ '--favor-progress': progress }" />
        <div class="favor-ticks" aria-hidden="true"><span>0</span><span>25</span><span>50</span><span>75</span><span>100</span></div>
      </div>
      <div class="favor-adjustment-bottom"><span>拖动滑杆，或直接输入数值</span><button type="button" class="favor-reset" :disabled="locked || value === initialValue" @click="value = initialValue"><RotateCcw :size="13" />恢复当前值</button></div>
      <p v-if="!valid" id="character-favor-error" class="favor-error" role="alert">请输入 0–100 之间的整数。</p>
      <p v-if="!canEdit" class="favor-permission">只有世界模板的作者可以手动调整好感度。</p>
    </form>
    <template #footer>
      <span v-if="valid && delta !== 0" class="favor-change-preview" aria-label="好感度调整预览">{{ initialValue }}<ArrowRight :size="14" /><strong>{{ value }}</strong></span>
      <button class="button secondary" :disabled="saving" @click="open = false">取消</button>
      <button class="button primary" type="submit" form="character-favor-form" :disabled="!canSave"><LoaderCircle v-if="saving" class="spin" :size="15" />{{ saving ? '保存中…' : '保存好感度' }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.favor-character { display: flex; align-items: center; gap: 12px; padding-bottom: 17px; border-bottom: 1px solid var(--line); }
.favor-character > .character-avatar { width: 40px; height: 46px; border-radius: 10px; flex: 0 0 auto; }
.favor-character > div { min-width: 0; display: grid; gap: 4px; }
.favor-character > div > strong { font-size: 14px; overflow-wrap: anywhere; }
.favor-character > div > span { color: var(--muted); font-size: 11px; overflow-wrap: anywhere; }
.favor-current { margin-left: auto; flex-shrink: 0; display: flex; align-items: baseline; gap: 7px; color: var(--muted); font-size: 11px; }
.favor-current strong { color: var(--ink); font-size: 17px; font-weight: 500; font-variant-numeric: tabular-nums; }
.favor-adjustment { padding: 20px 0 4px; }
.favor-value-label { display: block; color: var(--muted); font-size: 11px; text-align: center; }
.favor-stepper { margin: 10px auto 0; display: flex; align-items: center; justify-content: center; gap: clamp(16px, 4vw, 28px); }
.favor-step { width: 34px; height: 34px; border: 1px solid var(--line); border-radius: 50%; display: grid; place-items: center; flex: 0 0 auto; color: var(--pine); background: var(--surface-strong); cursor: pointer; }
.favor-step:hover:not(:disabled) { border-color: var(--pine); background: var(--pine-soft); }
.favor-number { display: flex; align-items: baseline; gap: 3px; }
.favor-number input[type="number"] { width: 80px; padding: 2px 0; border: 0; border-radius: 7px; appearance: textfield; color: var(--pine); background: transparent; font-size: 36px; line-height: 1.15; font-weight: 500; font-variant-numeric: tabular-nums; text-align: center; }
.favor-number input::-webkit-inner-spin-button, .favor-number input::-webkit-outer-spin-button { margin: 0; appearance: none; }
.favor-number input[aria-invalid="true"] { color: var(--wine); }
.favor-number > span { color: var(--muted); font-size: 11px; white-space: nowrap; }
.favor-delta { min-height: 20px; margin: 8px 0 18px; color: var(--muted); font-size: 11px; text-align: center; }
.favor-delta.changed { color: var(--pine); }
.favor-range-control { padding: 0 2px; }
.favor-range { width: 100%; height: 24px; margin: 0; display: block; appearance: none; background: transparent; cursor: pointer; }
.favor-range::-webkit-slider-runnable-track { height: 6px; border-radius: 8px; background: linear-gradient(to right, var(--pine) var(--favor-progress), var(--pine-soft) var(--favor-progress)); }
.favor-range::-webkit-slider-thumb { width: 20px; height: 20px; margin-top: -7px; border: 3px solid var(--surface-strong); border-radius: 50%; appearance: none; background: var(--pine); box-shadow: 0 0 0 1px var(--pine), 0 2px 5px rgba(41,79,73,.15); }
.favor-range::-moz-range-track { height: 6px; border-radius: 8px; background: var(--pine-soft); }
.favor-range::-moz-range-progress { height: 6px; border-radius: 8px; background: var(--pine); }
.favor-range::-moz-range-thumb { width: 14px; height: 14px; border: 3px solid var(--surface-strong); border-radius: 50%; background: var(--pine); box-shadow: 0 0 0 1px var(--pine); }
.favor-range:disabled { opacity: .5; cursor: not-allowed; }
.favor-ticks { display: flex; justify-content: space-between; margin-top: 5px; color: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.favor-adjustment-bottom { margin-top: 18px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px; color: var(--muted); font-size: 11px; }
.favor-reset { padding: 5px 0; border: 0; display: inline-flex; align-items: center; gap: 5px; color: var(--pine); background: transparent; font-size: 11px; cursor: pointer; }
.favor-error, .favor-permission { margin: 12px 0 0; font-size: 11px; line-height: 1.7; }
.favor-error { color: var(--wine); }
.favor-permission { color: var(--muted); }
.favor-change-preview { margin-right: auto; display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.favor-change-preview strong { color: var(--pine); font-weight: 600; }
@media (pointer: coarse) { .favor-step { width: 44px; height: 44px; } .favor-range { height: 44px; } .favor-reset { min-height: 44px; } }
</style>
