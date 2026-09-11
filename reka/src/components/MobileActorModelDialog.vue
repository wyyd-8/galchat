<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Check, ChevronRight, LoaderCircle } from '@lucide/vue'
import type { GroupActorRuntime, GroupActorRuntimeSavePayload, ModelApi } from '@/api/types'
import type { TrpgExecutionActor } from './trpgExecutionState'
import { sceneModelPayload, sceneModelTarget } from './mobileActorModel'
import BaseDialog from './ui/BaseDialog.vue'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  actor: TrpgExecutionActor
  actorRuntimes: GroupActorRuntime[]
  modelApis: ModelApi[]
  locked: boolean
  saveRuntime: (payload: GroupActorRuntimeSavePayload) => Promise<GroupActorRuntime | undefined>
}>()
const target = computed(() => sceneModelTarget(props.actor.item))
const runtime = computed(() => props.actorRuntimes.find(value => value.actorType === target.value?.actorType
  && (value.actorType === 'kp' || value.actorId === target.value?.actorId)))
const unavailable = computed(() => !!runtime.value?.modelApiId && (runtime.value.modelApiAvailable === false
  || !props.modelApis.some(model => model.id === runtime.value?.modelApiId)))
const currentId = computed(() => unavailable.value ? '' : runtime.value?.modelApiId == null ? '' : String(runtime.value.modelApiId))
const currentName = computed(() => props.modelApis.find(model => String(model.id) === currentId.value)?.name || '默认模型')
const selection = ref('')
const saving = ref(false)
const error = ref('')
const payload = computed(() => target.value ? sceneModelPayload(target.value, runtime.value, selection.value, props.modelApis) : null)
const canSave = computed(() => !!payload.value && !props.locked && !saving.value && selection.value !== currentId.value)
watch(open, visible => {
  if (visible) { selection.value = currentId.value; error.value = '' }
}, { immediate: true })
function statusLabel(model: ModelApi) {
  return { SUCCESS: '可用', PARTIAL: '部分可用', FAILED: '连接异常', UNTESTED: '未测试' }[model.status]
}
async function confirm() {
  if (!canSave.value || !payload.value) return
  saving.value = true
  error.value = ''
  try {
    const saved = await props.saveRuntime(payload.value)
    if (saved) open.value = false
    else error.value = '保存未成功，请重试。所选模型已保留。'
  } catch {
    error.value = '保存未成功，请检查连接后重试。'
  } finally { saving.value = false }
}
</script>

<template>
  <BaseDialog v-model="open" title="切换回复模型" mobile-presentation="sheet" layer="foreground" content-class="mobile-scene-model-dialog">
    <div class="scene-model-identity"><span class="scene-model-avatar" aria-hidden="true">{{ actor.name.slice(0, 1) }}</span><div><strong>{{ actor.name }}</strong><small>当前使用 · {{ currentName }}</small></div></div>
    <p v-if="target?.actorType === 'kp'" class="scene-model-notice">{{ actor.genericKp ? 'KP 与场景中的 NPC 共用此模型。' : '此角色由 KP 扮演，切换将同时影响 KP 与其他 NPC。' }}</p>
    <p v-if="runtime?.controlMode === 'MANUAL'" class="scene-model-notice">当前由你手动回复。所选模型将在切回 AI 控制后使用。</p>
    <p v-if="unavailable" class="scene-model-warning">原模型已不可用，当前使用默认模型。</p>
    <p v-if="locked" class="scene-model-warning">当前暂不能切换，请在回复结束且会话可用时重试。</p>
    <fieldset class="scene-model-options" :disabled="saving || locked"><legend>选择回复模型</legend>
      <label class="scene-model-option" :class="{ selected: selection === '' }"><input v-model="selection" type="radio" name="scene-reply-model" value="" /><span class="scene-model-copy"><strong>默认模型</strong><small>跟随系统默认配置</small></span><span class="scene-model-radio" aria-hidden="true"><Check v-if="selection === ''" :size="12" /></span></label>
      <label v-for="model in modelApis" :key="model.id" class="scene-model-option" :class="{ selected: selection === String(model.id) }"><input v-model="selection" type="radio" name="scene-reply-model" :value="String(model.id)" /><span class="scene-model-copy"><strong>{{ model.name }}<em v-if="String(model.id) === currentId">当前</em></strong><small>{{ model.modelName }}</small><small class="scene-model-health" :class="{ warning: model.status === 'FAILED' || model.status === 'PARTIAL' }">{{ statusLabel(model) }}</small></span><span class="scene-model-radio" aria-hidden="true"><Check v-if="selection === String(model.id)" :size="12" /></span></label>
    </fieldset>
    <p v-if="!modelApis.length" class="scene-model-empty">还没有自定义模型。可在“我的 → 模型接口”中添加。</p>
    <p class="scene-model-hint">确认后用于后续回复，已生成的消息保持不变。</p>
    <p v-if="error" class="scene-model-error" role="alert">{{ error }}</p>
    <template #footer><button class="button secondary" :disabled="saving" @click="open = false">取消</button><button class="button primary" :disabled="!canSave" @click="confirm"><LoaderCircle v-if="saving" class="spin" :size="16" />{{ saving ? '正在保存' : '确认切换' }}<ChevronRight v-if="!saving" :size="16" /></button></template>
  </BaseDialog>
</template>

<style>
@media (max-width: 767px) {
  .dialog-content.mobile-scene-model-dialog[data-mobile-presentation] { max-height: min(85dvh, 760px); border-radius: 22px 22px 0 0; background: #f4f1e9; }
  .mobile-scene-model-dialog .scene-model-identity { display: flex; align-items: center; gap: 12px; margin-bottom: 17px; padding: 4px 0 16px; border-bottom: 1px solid #ddded3; }
  .scene-model-identity > div { min-width: 0; }
  .scene-model-avatar { display: grid; place-items: center; flex-shrink: 0; width: 42px; height: 42px; border-radius: 13px; color: #315b52; background: #e0e8dd; font-size: 18px; }
  .scene-model-identity strong { display: block; font-size: 15px; line-height: 1.5; overflow-wrap: anywhere; }
  .scene-model-identity small { display: block; margin-top: 4px; color: #798172; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
  .scene-model-notice, .scene-model-warning, .scene-model-error { margin: 0 0 14px; padding: 11px 12px; border-radius: 9px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
  .scene-model-notice { background: #e8ede2; color: #4e6957; }
  .scene-model-warning { background: #f2e8d6; color: #886133; }
  .scene-model-error { background: #f2e1e1; color: #843d47; }
  .scene-model-options { min-width: 0; padding: 0; margin: 0; border: 0; }
  .scene-model-options legend { margin-bottom: 10px; padding: 0; color: #798172; font-size: 11px; }
  .scene-model-option { position: relative; display: flex; align-items: center; gap: 12px; min-height: 69px; padding: 13px 14px; margin-bottom: 9px; border: 1px solid #ddded3; border-radius: 11px; background: #fcfbf6; cursor: pointer; }
  .scene-model-option.selected { border-color: #8ea28e; background: #e8eee2; }
  .scene-model-option input { position: absolute; width: 1px; height: 1px; opacity: 0; }
  .scene-model-option:focus-within { outline: 2px solid #7e9b8b; outline-offset: 2px; }
  .scene-model-options:disabled { opacity: .65; }
  .scene-model-copy { flex: 1; min-width: 0; }
  .scene-model-copy strong { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 14px; font-weight: 550; line-height: 1.5; overflow-wrap: anywhere; }
  .scene-model-copy small { display: block; margin-top: 3px; color: #798172; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
  .scene-model-copy em { border-radius: 4px; padding: 1px 5px; background: #d8e3d2; color: #446548; font-size: 9px; font-style: normal; font-weight: 400; }
  .scene-model-copy .scene-model-health { color: #4e7558; font-size: 10px; }
  .scene-model-copy .scene-model-health.warning { color: #916337; }
  .scene-model-radio { display: grid; place-items: center; flex-shrink: 0; width: 19px; height: 19px; border: 1px solid #b7c1b2; border-radius: 50%; color: #fff; }
  .selected .scene-model-radio { border-color: #315b52; background: #315b52; }
  .scene-model-hint, .scene-model-empty { color: #798172; font-size: 11px; line-height: 1.8; margin: 12px 0; }
  .mobile-scene-model-dialog .dialog-footer > .button { min-width: 0; min-height: 46px; justify-content: center; font-size: 13px; }
}
</style>
