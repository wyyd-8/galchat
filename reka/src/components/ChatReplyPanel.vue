<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { ArrowDown, ArrowUp, Check, ChevronDown, CircleAlert, GripVertical, ListOrdered, LoaderCircle, Plus, Save, Search, Settings2, Trash2, UsersRound, X } from '@lucide/vue'
import type { Character, GroupActorRuntime, GroupActorRuntimeSavePayload, GroupMessage, ModelApi, ReplyPlanItem } from '@/api/types'
import { replyPlanActorName } from './replyPlanState'
import { replyActorPhase, type ReplyActorPhase, type ReplyTurnState } from './replyTurnStatus'

const props = defineProps<{
  items: ReplyPlanItem[]
  characters: Character[]
  username: string
  availableCharacters: Character[]
  actorRuntimes: GroupActorRuntime[]
  modelApis: ModelApi[]
  messages: GroupMessage[]
  turnState: ReplyTurnState | null
  canEdit: boolean
  locked: boolean
  sending: boolean
  loading: boolean
  closed: boolean
  dirty: boolean
}>()
const emit = defineEmits<{
  save: []
  move: [from: number, to: number]
  remove: [index: number]
  add: [actorId: number]
  saveModel: [payload: GroupActorRuntimeSavePayload]
}>()
const editing = ref(false)
const pickerOpen = ref(false)
const actorSearch = ref('')
const pickerId = useId()
const addButton = ref<HTMLButtonElement | null>(null)
const saveButton = ref<HTMLButtonElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const newestActorId = ref<number | null>(null)
const addAnnouncement = ref('')
const matchingCharacters = computed(() => props.availableCharacters.filter(actor =>
  actor.characterName.toLocaleLowerCase().includes(actorSearch.value.trim().toLocaleLowerCase())))
const draggedIndex = ref<number | null>(null)
const dropIndex = ref<number | null>(null)
const canReorder = computed(() => editing.value && props.canEdit && !props.locked)
const phaseLabels: Record<ReplyActorPhase, string> = { waiting: '等待中', replying: '回复中', completed: '已回复', failed: '回复失败' }
const turnLabels = { starting: '准备回复', running: '正在依次回复', completed: '本轮回复完成', failed: '本轮回复中断' }
const turnLabel = computed(() => props.closed ? '会话已结束'
  : props.loading ? '正在加载'
  : props.turnState ? turnLabels[props.turnState.phase]
  : props.sending ? '准备回复' : '等待新的消息')
const rows = computed(() => props.items.map(item => {
  const character = props.characters.find(character => item.actorType === 'character' && character.characterId === item.actorId)
  const runtime = props.actorRuntimes.find(runtime => runtime.actorType === item.actorType && runtime.actorId === item.actorId)
  const modelId = runtime?.modelApiAvailable && runtime.modelApiId != null ? String(runtime.modelApiId) : ''
  return {
    item, character, modelId,
    name: replyPlanActorName(item, props.username, character?.characterName),
    modelName: modelId ? props.modelApis.find(model => String(model.id) === modelId)?.name || runtime?.modelApiName || '自定义模型' : '默认模型',
    phase: props.turnState && !props.dirty ? replyActorPhase(props.turnState, item, props.messages) : null,
  }
}))
const completedCount = computed(() => rows.value.filter(row => row.phase === 'completed').length)

watch(() => props.dirty, (dirty, wasDirty) => {
  if (wasDirty && !dirty) editing.value = false
})
function saveReplyOrder() {
  if (!props.canEdit || props.locked || !props.items.length) return
  if (props.dirty) emit('save')
  else editing.value = false
}
function toggleEditing() {
  if (!props.canEdit || props.locked) return
  if (editing.value) saveReplyOrder()
  else editing.value = true
}
function selectModel(item: ReplyPlanItem, event: Event) {
  if (props.locked || item.actorType !== 'character' || item.actorId == null) return
  const value = (event.target as HTMLSelectElement).value
  emit('saveModel', { actorType: 'character', actorId: item.actorId, controlMode: 'MODEL', modelApiId: value ? Number(value) : undefined })
}
watch(() => props.locked || !props.canEdit || !editing.value, inactive => {
  if (inactive) { pickerOpen.value = false; actorSearch.value = ''; addAnnouncement.value = '' }
})
async function openActorPicker() {
  if (!editing.value || !props.canEdit || props.locked || !props.availableCharacters.length) return
  actorSearch.value = ''
  pickerOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}
async function closeActorPicker() {
  pickerOpen.value = false
  actorSearch.value = ''
  await nextTick()
  if (addButton.value?.disabled) saveButton.value?.focus()
  else addButton.value?.focus()
}
function addActor(actor: Character) {
  if (!editing.value || !props.canEdit || props.locked
    || !props.availableCharacters.some(candidate => candidate.characterId === actor.characterId)
    || props.items.some(item => item.actorType === 'character' && item.actorId === actor.characterId)) return
  newestActorId.value = actor.characterId
  emit('add', actor.characterId)
  addAnnouncement.value = `${actor.characterName}已加入列表末尾，保存后生效。`
  void closeActorPicker()
}
function startDrag(index: number, event: DragEvent) {
  if (!canReorder.value) { event.preventDefault(); return }
  draggedIndex.value = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(index))
  }
}
function endDrag() { draggedIndex.value = null; dropIndex.value = null }
function dragOver(index: number, event: DragEvent) {
  if (!canReorder.value || draggedIndex.value === null) return
  event.preventDefault()
  dropIndex.value = index
}
function drop(index: number) {
  if (canReorder.value && draggedIndex.value !== null && draggedIndex.value !== index) emit('move', draggedIndex.value, index)
  endDrag()
}
</script>

<template>
  <aside class="reply-panel chat-reply-panel" aria-label="群聊回复设置">
    <div class="chat-reply-scroll">
      <header class="chat-reply-heading">
        <span class="chat-reply-heading-icon"><UsersRound :size="19" /></span>
        <div><h2>回复角色</h2><p>{{ items.length }} 位角色参与回复</p></div>
      </header>

      <section class="chat-reply-overview" :class="{ failed: turnState?.phase === 'failed' }" aria-label="本轮回复状态">
        <div class="chat-reply-overview-line" role="status">
          <LoaderCircle v-if="sending || loading" class="spin" :size="14" />
          <CircleAlert v-else-if="turnState?.phase === 'failed'" :size="14" />
          <Check v-else-if="turnState?.phase === 'completed'" :size="14" />
          <span v-else class="chat-reply-idle-dot" />
          <strong>{{ turnLabel }}</strong>
          <small v-if="turnState && !dirty && items.length && !closed && !loading">{{ completedCount }} / {{ items.length }}</small>
        </div>
        <div v-if="turnState && !dirty && items.length && !closed && !loading" class="chat-reply-progress" role="progressbar" aria-label="本轮回复进度" :aria-valuenow="completedCount" :aria-valuemax="items.length" :aria-valuemin="0"><span :style="{ width: `${completedCount / items.length * 100}%` }" /></div>
        <p v-if="turnState?.error" role="alert">{{ turnState.error }}</p>
      </section>

      <section class="chat-reply-order" aria-label="回复顺序">
        <header class="chat-reply-section-heading">
          <h3><ListOrdered :size="15" />回复顺序</h3>
          <button v-if="canEdit" class="chat-reply-edit" :disabled="locked || (editing && !items.length)" :aria-pressed="editing" @click="toggleEditing"><Settings2 v-if="!editing" :size="13" />{{ editing ? '完成调整' : '调整顺序' }}</button>
        </header>
        <p class="chat-reply-hint">{{ editing ? '拖动手柄或使用箭头调整，修改后保存。' : '从上到下依次回复，点击模型名称可切换。' }}</p>
        <ol class="chat-reply-roster">
          <li v-for="(row, index) in rows" :key="`${row.item.actorType}:${row.item.actorId}:${row.item.subjectCharacterId}`" class="chat-reply-member" :class="[row.phase, { dragging: draggedIndex === index, 'drop-target': dropIndex === index && draggedIndex !== index, 'just-added': row.item.actorId === newestActorId }]" @animationend="newestActorId = null" @dragover="dragOver(index, $event)" @drop.prevent="drop(index)">
            <div class="chat-reply-member-main">
              <button v-if="editing && canEdit" class="chat-reply-drag" :draggable="canReorder" :disabled="locked" :aria-label="`拖动${row.name}调整顺序`" title="拖动调整顺序，也可使用下方箭头" @dragstart="startDrag(index, $event)" @dragend="endDrag"><GripVertical :size="15" /></button>
              <span v-else class="chat-reply-number">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="chat-reply-avatar" :style="row.character?.characterImage ? { backgroundImage: `url(${row.character.characterImage})` } : {}">{{ row.character?.characterImage ? '' : row.name.slice(0, 1) }}</span>
              <div class="chat-reply-member-copy"><strong :title="row.name">{{ row.name }}</strong><span v-if="row.phase" class="chat-reply-phase"><i />{{ phaseLabels[row.phase] }}</span><span v-else>第 {{ index + 1 }} 位回复</span></div>
              <Check v-if="row.phase === 'completed'" class="chat-reply-done" :size="14" />
              <LoaderCircle v-else-if="row.phase === 'replying'" class="chat-reply-done spin" :size="14" />
            </div>
            <details v-if="row.item.actorType === 'character'" class="chat-reply-model">
              <summary :aria-label="`设置${row.name}的回复模型`"><span :title="row.modelName">{{ row.modelName }}</span><ChevronDown :size="12" /></summary>
              <label><span>回复模型</span><select :value="row.modelId" :aria-label="`选择${row.name}的回复模型`" :disabled="locked" @change="selectModel(row.item, $event)"><option value="">默认模型</option><option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option></select><small>选择后立即保存</small></label>
            </details>
            <div v-if="editing && canEdit" class="chat-reply-member-actions">
              <button :disabled="locked || index === 0" :aria-label="`上移${row.name}`" title="上移" @click="emit('move', index, index - 1)"><ArrowUp :size="14" /></button>
              <button :disabled="locked || index === items.length - 1" :aria-label="`下移${row.name}`" title="下移" @click="emit('move', index, index + 1)"><ArrowDown :size="14" /></button>
              <button class="chat-reply-remove" :disabled="locked" :aria-label="`移除${row.name}`" @click="emit('remove', index)"><Trash2 :size="13" />移除</button>
            </div>
          </li>
        </ol>
        <div v-if="!items.length" class="chat-reply-empty"><UsersRound :size="24" /><strong>{{ editing ? '添加第一位回复角色' : '暂无回复角色' }}</strong><p v-if="canEdit">{{ editing ? '从下方选择角色，开始这段对话。' : '点击「调整顺序」添加回复角色。' }}</p></div>
        <div v-if="editing && canEdit" class="chat-reply-add">
          <button v-if="!pickerOpen" ref="addButton" class="chat-reply-add-trigger" aria-label="添加回复角色" :aria-controls="pickerId" :aria-expanded="false" :disabled="locked || !availableCharacters.length" @click="openActorPicker"><Plus v-if="availableCharacters.length" :size="16" /><Check v-else :size="15" />{{ availableCharacters.length ? '添加回复角色' : '所有可用角色均已加入' }}</button>
          <section v-else :id="pickerId" class="chat-reply-picker" aria-label="选择回复角色" @keydown.esc.stop.prevent="closeActorPicker">
            <header><strong>选择回复角色</strong><button class="icon-button subtle" aria-label="关闭角色选择" @click="closeActorPicker"><X :size="15" /></button></header>
            <label class="chat-reply-search"><Search :size="14" /><input ref="searchInput" v-model="actorSearch" type="search" aria-label="搜索可添加角色" placeholder="搜索角色名字" :disabled="locked" /></label>
            <div class="chat-reply-candidates">
              <button v-for="actor in matchingCharacters" :key="actor.characterId" class="chat-reply-candidate" :aria-label="`添加${actor.characterName}`" :disabled="locked" @click="addActor(actor)"><span class="chat-reply-avatar" :style="actor.characterImage ? { backgroundImage: `url(${actor.characterImage})` } : {}">{{ actor.characterImage ? '' : actor.characterName.slice(0, 1) }}</span><span>{{ actor.characterName }}</span><Plus :size="14" /></button>
              <p v-if="!matchingCharacters.length" class="chat-reply-no-match" role="status">{{ availableCharacters.length ? '没有找到匹配的角色' : '所有可用角色均已加入' }}</p>
            </div>
            <p class="chat-reply-picker-note">点击加入列表，保存后生效。</p>
          </section>
        </div>
        <span class="chat-reply-announcement" role="status" aria-live="polite">{{ addAnnouncement }}</span>
      </section>
      <p class="chat-reply-explanation">每位角色都能看到本轮前面角色的回复，让对话自然延续。</p>
    </div>
    <footer v-if="editing && canEdit" class="chat-reply-footer" :class="{ dirty }">
      <template v-if="dirty"><p><span class="chat-reply-unsaved-dot" />有未保存的修改</p><button ref="saveButton" class="button primary" :disabled="locked || !items.length" @click="saveReplyOrder"><Save :size="15" />保存回复顺序</button><small v-if="!items.length">至少保留一位回复角色</small></template>
      <template v-else><p><Check :size="14" />{{ closed ? '会话已结束' : loading ? '正在加载回复顺序' : '后续聊天沿用此顺序' }}</p><small>{{ closed ? '回复设置仅供查看' : sending ? '本轮回复中，设置暂不可修改' : '调整角色和顺序后，记得保存' }}</small></template>
    </footer>
  </aside>
</template>

<style scoped>
.chat-reply-panel { padding: 0; display: flex; flex-direction: column; background: var(--surface); }
.chat-reply-scroll { min-height: 0; flex: 1; overflow-y: auto; padding: 23px 16px 16px; scrollbar-width: thin; }
.chat-reply-heading { display: flex; align-items: center; gap: 10px; margin-bottom: 21px; }
.chat-reply-heading-icon { width: 37px; height: 37px; display: grid; place-items: center; border: 1px solid #d5dfd9; border-radius: 12px; background: var(--pine-soft); color: var(--pine); }
.chat-reply-heading h2 { margin: 0; font-size: 15px; font-weight: 650; letter-spacing: .03em; }
.chat-reply-heading p { margin: 4px 0 0; color: var(--muted); font-size: 11px; }
.chat-reply-overview { padding: 11px 12px; border: 1px solid #dbe3dd; border-radius: 9px; background: #f0f4f0; color: var(--pine); }
.chat-reply-overview-line { display: flex; align-items: center; gap: 7px; }
.chat-reply-overview-line strong { flex: 1; font-size: 11px; font-weight: 550; }
.chat-reply-overview-line small { font-size: 10px; font-variant-numeric: tabular-nums; }
.chat-reply-idle-dot { width: 6px; height: 6px; margin-inline: 4px; border-radius: 50%; background: #839c8e; }
.chat-reply-progress { height: 3px; margin-top: 9px; border-radius: 5px; background: #dce5df; overflow: hidden; }
.chat-reply-progress > span { height: 100%; display: block; background: #597d6d; transition: width 250ms ease; }
.chat-reply-overview.failed { background: #f8eeed; border-color: #e8d4d2; color: var(--wine); }
.chat-reply-overview p { margin: 8px 0 0; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.chat-reply-order { margin-top: 23px; }
.chat-reply-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.chat-reply-section-heading h3 { margin: 0; display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 650; }
.chat-reply-section-heading h3 svg { color: var(--muted); }
.chat-reply-edit { min-height: 28px; padding: 3px 0 3px 6px; display: flex; align-items: center; gap: 4px; border: 0; background: none; color: var(--pine); font-size: 11px; cursor: pointer; }
.chat-reply-edit:hover { text-decoration: underline; text-underline-offset: 3px; }
.chat-reply-hint { margin: 5px 0 13px; font-size: 10px; line-height: 1.7; color: var(--muted); }
.chat-reply-roster { padding: 0; margin: 0; list-style: none; display: grid; grid-template-columns: minmax(0, 1fr); gap: 8px; }
.chat-reply-member { min-width: 0; padding: 12px 10px 9px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-strong); transition: border-color 150ms, background 150ms; }
.chat-reply-member.replying { border-color: #a2bbb0; background: #f3f8f3; }
.chat-reply-member.failed { border-color: #dcb6b8; }
.chat-reply-member.dragging { opacity: .45; }
.chat-reply-member.drop-target { border-color: var(--pine); box-shadow: 0 -3px 0 var(--pine); }
.chat-reply-member-main { display: flex; gap: 8px; align-items: center; min-width: 0; }
.chat-reply-number, .chat-reply-drag { width: 16px; flex: 0 0 16px; color: #8c8d81; text-align: center; font-family: var(--font-mono); font-size: 10px; }
.chat-reply-drag { height: 30px; border: 0; padding: 0; background: none; cursor: grab; display: grid; place-items: center; }
.chat-reply-drag:active { cursor: grabbing; }
.chat-reply-avatar { width: 34px; height: 34px; flex: 0 0 34px; display: grid; place-items: center; border-radius: 50%; color: var(--pine); background-color: #e3ebe3; background-size: cover; background-position: center; font-size: 13px; }
.chat-reply-member:nth-child(3n + 2) .chat-reply-avatar { color: #866542; background-color: #eee5d6; }
.chat-reply-member:nth-child(3n) .chat-reply-avatar { color: #746285; background-color: #eae4ee; }
.chat-reply-member-copy { flex: 1; min-width: 0; }
.chat-reply-member-copy strong { display: block; font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-reply-member-copy > span { margin-top: 4px; display: flex; align-items: center; gap: 5px; color: var(--muted); font-size: 10px; }
.chat-reply-phase i { width: 4px; height: 4px; border-radius: 50%; background: #b4b6ac; }
.replying .chat-reply-phase { color: var(--pine); }
.replying .chat-reply-phase i { background: #597d6d; }
.failed .chat-reply-phase { color: var(--wine); }
.failed .chat-reply-phase i { background: var(--wine); }
.chat-reply-done { flex-shrink: 0; color: #597d6d; }
.chat-reply-model { margin: 7px 0 0 66px; min-width: 0; }
.chat-reply-model summary { width: fit-content; max-width: 100%; min-height: 22px; display: flex; gap: 5px; align-items: center; cursor: pointer; color: #69756b; font-size: 10px; list-style: none; }
.chat-reply-model summary::-webkit-details-marker { display: none; }
.chat-reply-model summary span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.chat-reply-model summary svg { flex-shrink: 0; }
.chat-reply-model summary:hover { color: var(--pine); }
.chat-reply-model summary:focus-visible { outline: 2px solid var(--pine); outline-offset: 3px; border-radius: 3px; }
.chat-reply-model[open] { margin-left: 24px; }
.chat-reply-model[open] summary { margin-left: 42px; }
.chat-reply-model[open] summary svg { transform: rotate(180deg); }
.chat-reply-model label { display: grid; gap: 6px; padding-top: 9px; color: var(--muted); font-size: 10px; }
.chat-reply-model label small { font-size: 9px; }
.chat-reply-model select { min-width: 0; width: 100%; height: 33px; padding: 0 7px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--ink); font-size: 11px; }
.chat-reply-member-actions { display: flex; gap: 4px; padding-top: 8px; margin-top: 8px; border-top: 1px solid var(--line); }
.chat-reply-member-actions button { min-width: 29px; height: 27px; padding: 0 6px; display: flex; justify-content: center; align-items: center; gap: 4px; border: 0; border-radius: 5px; background: transparent; color: var(--muted); cursor: pointer; font-size: 10px; }
.chat-reply-member-actions button:not(:disabled):hover { background: var(--paper); color: var(--pine); }
.chat-reply-member-actions .chat-reply-remove { margin-left: auto; }
.chat-reply-member-actions .chat-reply-remove:not(:disabled):hover { background: #f8eeed; color: var(--wine); }
.chat-reply-add { margin-top: 15px; }
.chat-reply-add-trigger { width: 100%; min-height: 39px; padding: 9px 6px; display: flex; align-items: center; justify-content: center; gap: 6px; border: 1px dashed var(--line-strong); border-radius: 8px; color: var(--pine); background: transparent; font-size: 11px; cursor: pointer; }
.chat-reply-add-trigger:not(:disabled):hover { background: var(--pine-soft); border-color: var(--pine); }
.chat-reply-picker { padding: 11px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-strong); box-shadow: 0 5px 18px rgba(42, 41, 35, .05); }
.chat-reply-picker > header { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 8px; }
.chat-reply-picker > header strong { font-size: 12px; font-weight: 600; }
.chat-reply-search { position: relative; display: block; color: var(--muted); }
.chat-reply-search > svg { position: absolute; top: 11px; left: 9px; pointer-events: none; }
.chat-reply-search input { width: 100%; min-width: 0; height: 36px; padding: 0 7px 0 29px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); color: var(--ink); font-size: 11px; }
.chat-reply-candidates { margin-top: 8px; display: grid; grid-template-columns: minmax(0, 1fr); gap: 3px; }
.chat-reply-candidate { min-width: 0; width: 100%; display: flex; align-items: center; gap: 8px; padding: 9px 5px; border: 0; border-radius: 7px; background: transparent; text-align: left; cursor: pointer; }
.chat-reply-candidate:not(:disabled):hover { background: var(--pine-soft); }
.chat-reply-candidate > span:nth-child(2) { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 12px; }
.chat-reply-candidate > svg { flex-shrink: 0; color: var(--muted); }
.chat-reply-no-match { margin: 0; padding: 18px 2px; color: var(--muted); font-size: 11px; text-align: center; }
.chat-reply-picker-note { margin: 9px 0 0; padding-top: 9px; border-top: 1px solid var(--line); color: var(--muted); font-size: 10px; }
.chat-reply-announcement { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
.chat-reply-member.just-added { animation: chat-reply-added 1.5s ease-out; }
@keyframes chat-reply-added { 0%, 35% { background: var(--pine-soft); border-color: var(--pine); } 100% { background: var(--surface-strong); border-color: var(--line); } }
.chat-reply-empty { padding: 24px 5px; display: grid; justify-items: center; gap: 10px; color: var(--muted); text-align: center; }
.chat-reply-empty strong { font-size: 12px; font-weight: 500; }
.chat-reply-empty p { margin: 0; font-size: 10px; line-height: 1.6; }
.chat-reply-explanation { margin: 20px 3px 0; color: var(--muted); font-size: 10px; line-height: 1.8; }
.chat-reply-footer { flex-shrink: 0; padding: 15px 16px; border-top: 1px solid var(--line); background: #f3f3ec; }
.chat-reply-footer p { margin: 0; display: flex; align-items: center; justify-content: center; gap: 6px; color: var(--pine); font-size: 11px; }
.chat-reply-footer small { margin-top: 5px; display: block; text-align: center; color: var(--muted); font-size: 10px; line-height: 1.6; }
.chat-reply-footer.dirty { background: #f5f0e5; }
.chat-reply-footer.dirty p { justify-content: flex-start; color: #8b682d; }
.chat-reply-unsaved-dot { width: 5px; height: 5px; border-radius: 50%; background: #b98542; }
.chat-reply-footer .button { width: 100%; margin-top: 10px; font-size: 12px; }
@media (prefers-reduced-motion: reduce) { .chat-reply-progress > span, .chat-reply-member { transition: none; } .spin, .chat-reply-member.just-added { animation: none; } }
</style>
