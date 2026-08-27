<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, BookUser, Check, Dices, FlaskConical, LoaderCircle, LocateFixed, MessageSquareText, RefreshCw, RotateCcw, Save, UserRound } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import WeaponRiskNotice from '@/components/WeaponRiskNotice.vue'
import DiceDebugPanel from '@/dice/components/DiceDebugPanel.vue'
import DiceRollMessage from '@/dice/components/DiceRollMessage.vue'
import { api } from '@/api/client'
import type {
  Character, CharacterCard, CocModule, ContextWindowUsage, Conversation, DiceRollAggregate, GroupMessage,
  InvestigatorCardSummary, TrpgRollbackOverview, TrpgRollbackResult, TrpgSave,
} from '@/api/types'
import { errorMessage, notify } from '@/composables/useNotice'
import { hydrateDiceMessage, listDiceHistoryEntriesNewestFirst } from '@/dice/domain/dicePlayback'
import {
  buildFetchedRollbackMessagePreview, buildSkillDisplayItems, buildToolCharacterTargets, buildToolRecoveryTimeline, buildToolRollbackActions, formatCheckRate, formatKpPromptUpdatedAt, formatRollbackPreviewMessage, nextSkillGroup, preferredToolCharacterTargetKey, resolveRollbackMessagePreview, resolveToolRestoreInvestigators, resolveWeaponCheckValue, restoreInvestigatorCondition, shouldShowWeaponRisk, toolDialogContentClass,
  useToolRestoreConfirmation,
} from '@/components/trpgToolsState'
import type { ToolRecoveryTimelineItem, ToolRestoreAction, ToolRollbackMessagePreview } from '@/components/trpgToolsState'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  conversation: Conversation
  module: CocModule | null
  username: string
  characters: Character[]
  participantIds: number[]
  messages: GroupMessage[]
  requestedCardId?: number | null
}>()
const emit = defineEmits<{
  restored: []
  openDice: [aggregate: DiceRollAggregate]
  debugDice: [aggregate: DiceRollAggregate]
  locateDice: [messageId: number]
}>()

const busy = ref(false)
const contextUsage = ref<ContextWindowUsage | null>(null)
const save = ref<TrpgSave | null>(null)
const rollbackOverview = ref<TrpgRollbackOverview | null>(null)
const saveRemark = ref('')
const cards = ref<InvestigatorCardSummary[]>([])
const selectedKey = ref('player')
const selectedToolTab = ref('status')
const selectedSheetTab = ref('skills')
const selectedProfileTab = ref('background')
const selectedSkillGroup = ref<string | null>(null)
const restoreConfirmation = useToolRestoreConfirmation(open, selectedToolTab)
const rollbackMessagePreview = ref<ToolRollbackMessagePreview | null>(null)
const rollbackPreviewLoading = ref(false)
const rollbackPreviewFailed = ref(false)
const saveEditorOpen = ref(false)
const card = ref<CharacterCard | null>(null)
const cardText = ref('')
let rollbackPreviewRequestId = 0

const characterTargets = computed(() => buildToolCharacterTargets(
  props.characters,
  cards.value,
  props.participantIds,
  props.username,
))
const selectedTarget = computed(() => characterTargets.value.find((target) => target.key === selectedKey.value)
  || characterTargets.value[0])
const selectedParticipantId = computed(() => selectedTarget.value?.participantId)
const contextPercent = computed(() => Math.max(0, Math.round((contextUsage.value?.ratio || 0) * 100)))
const contextTone = computed(() => contextPercent.value >= 90 ? 'danger' : contextPercent.value >= 70 ? 'warning' : 'safe')
const selectedActorName = computed(() => selectedTarget.value?.name || props.username.trim() || '当前玩家')
const completedCardCount = computed(() => characterTargets.value.filter((target) => target.cardId !== undefined).length)
const dialogContentClass = computed(() => toolDialogContentClass(selectedToolTab.value))
const diceHistoryEntries = computed(() => listDiceHistoryEntriesNewestFirst(props.messages))
const rollbackActions = computed(() => buildToolRollbackActions(rollbackOverview.value))
const recoveryTimeline = computed(() => buildToolRecoveryTimeline(save.value, rollbackOverview.value))
const pendingRollback = computed(() => rollbackActions.value.find(
  (action) => action.key === restoreConfirmation.action.value,
))
const pendingRestoreInvestigators = computed(() => resolveToolRestoreInvestigators(
  restoreConfirmation.action.value,
  save.value,
  rollbackActions.value,
))
const confirmationOpen = computed({
  get: () => restoreConfirmation.action.value !== null,
  set: (visible: boolean) => { if (!visible) restoreConfirmation.clear() },
})
const confirmationTitle = computed(() => {
  if (restoreConfirmation.stage.value === 'delete-manual-save') return '确认删除当前存档'
  if (restoreConfirmation.action.value === 'load') return '确认读取跑团存档'
  return `确认${pendingRollback.value?.title || '自动回退'}`
})
const confirmationContentClass = computed(() => restoreConfirmation.stage.value === 'primary'
  ? 'rollback-confirmation-dialog'
  : '')
const confirmationTargetTime = computed(() => restoreConfirmation.action.value === 'load'
  ? save.value?.savedAt
  : pendingRollback.value?.point.savedAt)
const characterAttributes = computed(() => card.value ? [
  { code: 'STR', label: '力量', value: card.value.character.str },
  { code: 'CON', label: '体质', value: card.value.character.con },
  { code: 'SIZ', label: '体型', value: card.value.character.siz },
  { code: 'DEX', label: '敏捷', value: card.value.character.dex },
  { code: 'APP', label: '外貌', value: card.value.character.app },
  { code: 'INT', label: '智力', value: card.value.character.intValue },
  { code: 'POW', label: '意志', value: card.value.character.pow },
  { code: 'EDU', label: '教育', value: card.value.character.edu },
] : [])
const displaySkills = computed(() => buildSkillDisplayItems(card.value?.skills || [], selectedSkillGroup.value))
const dodgeValue = computed(() => {
  const skill = card.value?.skills.find((item) => item.displayName.trim() === '闪避')
  return skill?.value ?? (card.value ? Math.floor(card.value.character.dex / 2) : undefined)
})
const derivedStats = computed(() => card.value ? [
  { label: '伤害加值', value: card.value.character.damageBonus },
  { label: '体格', value: card.value.character.build },
  { label: '移动力', value: card.value.character.mov },
  { label: '闪避', value: dodgeValue.value == null ? undefined : `${dodgeValue.value}%` },
  { label: '护甲', value: card.value.character.armor },
] : [])
const characterStatuses = computed(() => {
  const character = card.value?.character
  if (!character) return []
  const statuses: Array<{ label: string, tone: 'safe' | 'warning' | 'danger' }> = []
  if (character.dead) statuses.push({ label: '死亡', tone: 'danger' })
  else {
    if (character.dying) statuses.push({ label: '濒死', tone: 'danger' })
    if (character.unconscious) statuses.push({ label: '昏迷', tone: 'danger' })
    if (character.majorWound) statuses.push({ label: '重伤', tone: 'warning' })
    if (character.temporaryInsanity) statuses.push({ label: '临时性疯狂', tone: 'warning' })
  }
  return statuses.length ? statuses : [{ label: '状态稳定', tone: 'safe' as const }]
})

function time(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '暂无记录' }
function shown(value?: string | number | null) { return value == null || value === '' ? '—' : value }
function resourceStyle(current?: number, max?: number) {
  const ratio = current == null || !max ? 0 : Math.max(0, Math.min(100, Math.round(current / max * 100)))
  return { '--resource-ratio': `${ratio}%` }
}
function ammo(remaining?: number, capacity?: number) {
  if (remaining != null && capacity != null) return `${remaining} / ${capacity}`
  return shown(remaining ?? capacity)
}
function previewSpeaker(message: GroupMessage) {
  if (message.speakerType === 'user') return '你'
  if (message.speakerType === 'narrator') return '叙事'
  if (message.speakerType === 'kp') return message.speakerName || 'KP'
  return message.speakerName || '角色'
}
async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await action() }
  catch (error) { notify('跑团工具操作失败', errorMessage(error), 'danger') }
  finally { busy.value = false }
}
async function loadCard() {
  card.value = null
  card.value = selectedTarget.value?.cardId
    ? await api.characterCardById(selectedTarget.value.cardId)
    : null
}
async function refreshCards(requestedCardId: number | null = null) {
  cards.value = await api.investigatorCards(props.conversation.id)
  selectedKey.value = preferredToolCharacterTargetKey(
    characterTargets.value,
    requestedCardId,
    selectedKey.value,
  )
  await loadCard()
}
async function refreshOverview(requestedCardId: number | null = null) {
  const [usageResult, saveResult, rollbackResult] = await Promise.all([
    api.contextWindow(props.conversation.id),
    api.trpgSave(props.conversation.id),
    api.trpgRollbackStatus(props.conversation.id),
  ])
  contextUsage.value = usageResult
  save.value = saveResult
  rollbackOverview.value = rollbackResult
  saveRemark.value = saveResult?.remark || ''
  await refreshCards(requestedCardId)
}
async function createCard() {
  if (!cardText.value.trim()) throw new Error('请先粘贴人物卡文本')
  const createdCard = await api.createCharacterCard({ runId: props.conversation.id, participantId: selectedParticipantId.value, characterText: cardText.value.trim() })
  cardText.value = ''; await refreshCards(); notify('人物卡已导入', createdCard.character.name, 'success')
}
async function saveSnapshot() {
  save.value = await api.saveTrpg(props.conversation.id, saveRemark.value)
  rollbackOverview.value = await api.trpgRollbackStatus(props.conversation.id)
  saveEditorOpen.value = false
  restoreConfirmation.clear()
  notify('跑团存档已保存', '', 'success')
}
function requestLoad() {
  if (!save.value) return
  restoreConfirmation.request('load')
  void prepareRestorePreview('load', save.value.messageBoundaryId)
}
function requestRollback(action: ToolRestoreAction) {
  const requested = rollbackActions.value.find((item) => item.key === action)
  if (!requested?.point.available) return
  restoreConfirmation.request(action, requested.point.willDeleteManualSave)
  void prepareRestorePreview(requested.key, requested.point.messageBoundaryId)
}
function requestRecovery(recovery: ToolRecoveryTimelineItem) {
  if (!recovery.available) return
  if (recovery.key === 'load') requestLoad()
  else requestRollback(recovery.key)
}
async function prepareRestorePreview(action: ToolRestoreAction, boundaryId?: number) {
  const requestId = ++rollbackPreviewRequestId
  rollbackMessagePreview.value = null
  rollbackPreviewFailed.value = false
  if (boundaryId == null) {
    rollbackPreviewFailed.value = true
    return
  }
  rollbackPreviewLoading.value = true
  try {
    const preview = await resolveRollbackMessagePreview(
      props.messages,
      boundaryId,
      async (beforeId, size) => {
        const history = await api.groupMessages(
          props.conversation.id,
          beforeId,
          size,
        )
        return Promise.all(history.map(async (message) => {
          try {
            return await hydrateDiceMessage(message, async (summaryId) => {
              const [summary, results] = await Promise.all([
                api.diceSummary(summaryId),
                api.diceResults(summaryId),
              ])
              return { summary, results, semanticResult: summary.totalResult }
            })
          } catch {
            return message
          }
        }))
      },
    )
    if (requestId === rollbackPreviewRequestId
      && restoreConfirmation.action.value === action) {
      rollbackMessagePreview.value = preview
    }
  } catch {
    if (requestId === rollbackPreviewRequestId
      && restoreConfirmation.action.value === action) {
      rollbackMessagePreview.value = buildFetchedRollbackMessagePreview(
        [],
        boundaryId,
      )
      rollbackPreviewFailed.value = true
    }
  } finally {
    if (requestId === rollbackPreviewRequestId) {
      rollbackPreviewLoading.value = false
    }
  }
}
async function executeRollback(action: Exclude<ToolRestoreAction, 'load'>): Promise<TrpgRollbackResult> {
  if (action === 'turn') return api.rollbackTrpgTurn(props.conversation.id)
  if (action === 'scene') return api.rollbackTrpgScene(props.conversation.id)
  return api.rollbackTrpgInitial(props.conversation.id)
}
async function confirmRestore() {
  if (!restoreConfirmation.advance()) return
  const action = restoreConfirmation.action.value
  if (!action) return
  if (action === 'load') {
    await api.loadTrpg(props.conversation.id)
    restoreConfirmation.clear()
    await refreshOverview()
    emit('restored')
    notify('跑团存档已读取', '存档点之后的进度已回滚', 'success')
    return
  }
  const result = await executeRollback(action)
  const restoredTitle = pendingRollback.value?.title || '自动回退'
  restoreConfirmation.clear()
  await refreshOverview()
  emit('restored')
  notify(`${restoredTitle}完成`, result.manualSaveDeleted ? '当前手动存档已同时删除' : '可以从回退点重新继续跑团', 'success')
}
async function selectTarget(key: string) {
  if (busy.value || selectedKey.value === key) return
  selectedKey.value = key
  selectedSheetTab.value = 'skills'
  selectedProfileTab.value = 'background'
  selectedSkillGroup.value = null
  cardText.value = ''
  await execute(loadCard)
}
function toggleSkillGroup(group: string) {
  selectedSkillGroup.value = nextSkillGroup(selectedSkillGroup.value, group)
}
watch(open, (visible) => {
  if (!visible) return
  if (props.requestedCardId != null) selectedToolTab.value = 'card'
  void execute(() => refreshOverview(props.requestedCardId ?? null))
})
watch(() => props.conversation.id, () => {
  selectedKey.value = 'player'
  selectedSheetTab.value = 'skills'
  selectedProfileTab.value = 'background'
  selectedSkillGroup.value = null
  cards.value = []
  card.value = null
  rollbackOverview.value = null
  saveEditorOpen.value = false
  restoreConfirmation.clear()
})
watch(() => restoreConfirmation.action.value, (action) => {
  if (action !== null) return
  rollbackPreviewRequestId += 1
  rollbackMessagePreview.value = null
  rollbackPreviewLoading.value = false
  rollbackPreviewFailed.value = false
})
</script>

<template>
  <BaseDialog v-model="open" title="跑团工具" :description="`${conversation.title} · ${module?.name || `模组 #${conversation.moduleId || '未记录'}`}`" size="lg" :content-class="dialogContentClass">
    <TabsRoot v-model="selectedToolTab" class="tabs trpg-tools">
      <TabsList class="tabs-list">
        <TabsTrigger value="status"><Activity :size="15" />状态</TabsTrigger>
        <TabsTrigger value="save"><Save :size="15" />存档</TabsTrigger>
        <TabsTrigger value="card"><BookUser :size="15" />人物卡</TabsTrigger>
        <TabsTrigger value="dice"><Dices :size="15" />骰子</TabsTrigger>
        <TabsTrigger value="dice-debug"><FlaskConical :size="15" />骰子调试</TabsTrigger>
      </TabsList>

      <TabsContent value="status" class="tabs-content tool-section">
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>最近一次 KP 提示词长度</strong><small>{{ contextUsage ? `${contextUsage.characterCount.toLocaleString()} / ${contextUsage.softLimit.toLocaleString()} 字符` : '尚无记录' }}</small></span><button class="icon-button bordered" :disabled="busy" title="刷新" @click="execute(refreshOverview)"><RefreshCw :size="15" /></button></div>
          <div class="context-meter" :class="contextTone"><i :style="{ width: `${Math.min(contextPercent, 100)}%` }" /></div><small>{{ contextPercent }}% · {{ contextUsage ? `更新于 ${formatKpPromptUpdatedAt(contextUsage.updatedAt)}` : '模型执行一次跑团行动后显示' }}</small>
        </section>
      </TabsContent>

      <TabsContent value="save" class="tabs-content tool-section">
        <section class="progress-archive">
          <header class="progress-archive-heading">
            <span class="progress-archive-emblem"><Save :size="18" /></span>
            <div>
              <small>跑团进度档案</small>
              <h3>选择一个恢复点</h3>
              <p>手动存档与自动回退点会按时间排列。</p>
            </div>
            <button
              v-if="save"
              class="button secondary archive-save-toggle"
              :disabled="busy"
              @click="saveEditorOpen = !saveEditorOpen"
            ><Save :size="15" />{{ saveEditorOpen ? '收起编辑' : '覆盖存档' }}</button>
          </header>

          <div v-if="!save || saveEditorOpen" class="archive-save-editor">
            <span><strong>{{ save ? '覆盖手动存档' : '创建手动存档' }}</strong><small>记录当前进度，方便稍后回到此刻。</small></span>
            <label class="field"><span>存档备注</span><textarea v-model.trim="saveRemark" rows="2" maxlength="200" placeholder="记录当前场景、线索或风险…" /></label>
            <button class="button secondary" :disabled="busy" @click="execute(saveSnapshot)"><Save :size="15" />{{ save ? '确认覆盖' : '创建存档' }}</button>
          </div>

          <div class="recovery-timeline">
            <article class="recovery-timeline-current">
              <i class="recovery-timeline-node"><span /></i>
              <div><small>此刻</small><strong>当前进度</strong><p>你正在这里继续跑团</p></div>
            </article>
            <article
              v-for="recovery in recoveryTimeline"
              :key="recovery.key"
              class="recovery-timeline-entry"
              :class="[recovery.kind, { unavailable: !recovery.available }]"
            >
              <i class="recovery-timeline-node"><Save v-if="recovery.kind === 'manual'" :size="11" /><RotateCcw v-else :size="11" /></i>
              <div class="recovery-record-card">
                <header>
                  <span><em>{{ recovery.kind === 'manual' ? '手动记录' : '自动回退' }}</em><small>{{ recovery.available ? time(recovery.savedAt) : '尚未形成' }}</small></span>
                  <button
                    class="button ghost recovery-action"
                    :disabled="busy || !recovery.available"
                    @click="requestRecovery(recovery)"
                  >{{ recovery.available ? recovery.key === 'load' ? '预览并读档' : '预览并回退' : '不可用' }}</button>
                </header>
                <strong>{{ recovery.title }}</strong>
                <p>{{ recovery.remark || recovery.description }}</p>
                <small v-if="recovery.willDeleteManualSave" class="recovery-delete-warning">回退到此处将同时删除当前手动存档</small>
              </div>
            </article>
          </div>
        </section>
      </TabsContent>

      <TabsContent value="card" class="tabs-content tool-section">
        <div class="trpg-binding-layout trpg-tools-card-layout">
          <section class="trpg-binding-list-pane">
            <header class="settings-section-heading">
              <span><strong>调查员</strong><small>选择左侧人物，在右侧查看人物卡详情</small></span>
              <em>{{ completedCardCount }}/{{ characterTargets.length }} 已建立</em>
            </header>
            <div class="character-choice-list trpg-binding-targets" role="radiogroup" aria-label="人物卡角色">
              <button
                v-for="target in characterTargets"
                :key="target.key"
                type="button"
                class="choice-row"
                :class="{ active: selectedKey === target.key }"
                role="radio"
                :aria-checked="selectedKey === target.key"
                @click="selectTarget(target.key)"
              >
                <span
                  class="character-avatar small"
                  :class="{ 'player-avatar': target.actorType === 'PLAYER' }"
                  :style="target.image ? { backgroundImage: `url(${target.image})` } : {}"
                >
                  <UserRound v-if="target.actorType === 'PLAYER'" :size="17" />
                  <template v-else>{{ target.image ? '' : target.name.slice(0, 1) }}</template>
                </span>
                <span><strong>{{ target.name }}</strong><small>{{ target.actorType === 'PLAYER' ? '由当前登录用户控制' : 'AI 控制' }}</small></span>
                <span class="binding-state" :class="{ complete: target.cardId !== undefined }">
                  <Check v-if="target.cardId !== undefined" :size="13" />{{ target.cardId !== undefined ? '已建立' : '待建立' }}
                </span>
              </button>
            </div>
          </section>

          <aside class="trpg-binding-card-pane">
            <div v-if="busy && !card" class="binding-empty"><LoaderCircle class="spin" :size="24" /><strong>正在读取人物卡…</strong></div>
            <section v-else-if="card" class="character-sheet binding-sheet trpg-character-sheet">
              <header class="sheet-overview">
                <span
                  class="sheet-portrait"
                  :class="{ placeholder: !card.character.image }"
                  :style="card.character.image ? { backgroundImage: `url(${card.character.image})` } : {}"
                ><UserRound v-if="!card.character.image" :size="24" /></span>
                <div class="sheet-identity">
                  <small>{{ selectedActorName }} · {{ card.character.occupation || '未填写职业' }}</small>
                  <h3>{{ card.character.name }}</h3>
                  <p>{{ shown(card.character.sex) }} · {{ shown(card.character.age) }} 岁 · {{ card.character.era || '时代未填' }}</p>
                  <p>{{ card.character.birthplace || '出身地未填' }} · {{ card.character.residence || '居住地未填' }}</p>
                </div>
                <div class="sheet-statuses" aria-label="调查员状态">
                  <em v-for="status in characterStatuses" :key="status.label" :class="status.tone">{{ status.label }}</em>
                </div>
              </header>

              <div class="sheet-resource-grid">
                <span class="sheet-resource hp" :style="resourceStyle(card.character.hpCurrent, card.character.hpMax)">
                  <small>生命 HP</small><strong>{{ shown(card.character.hpCurrent) }} / {{ shown(card.character.hpMax) }}</strong><i />
                </span>
                <span class="sheet-resource san" :style="resourceStyle(card.character.sanCurrent, card.character.sanMax)">
                  <small>理智 SAN</small><strong>{{ shown(card.character.sanCurrent) }} / {{ shown(card.character.sanMax) }}</strong><i />
                </span>
                <span class="sheet-resource mp" :style="resourceStyle(card.character.mpCurrent, card.character.mpMax)">
                  <small>魔法 MP</small><strong>{{ shown(card.character.mpCurrent) }} / {{ shown(card.character.mpMax) }}</strong><i />
                </span>
                <span class="sheet-resource luck" :style="resourceStyle(card.character.luckCurrent, 100)">
                  <small>幸运 LUCK</small><strong>{{ shown(card.character.luckCurrent) }}</strong><i />
                </span>
              </div>

              <div class="sheet-attribute-grid" aria-label="调查员属性">
                <span v-for="attribute in characterAttributes" :key="attribute.code">
                  <span class="sheet-attribute-label"><small>{{ attribute.label }}</small><b>{{ attribute.code }}</b></span>
                  <strong>{{ attribute.value }}</strong>
                </span>
              </div>
              <div class="sheet-derived-grid">
                <span v-for="stat in derivedStats" :key="stat.label"><small>{{ stat.label }}</small><strong>{{ shown(stat.value) }}</strong></span>
              </div>

              <TabsRoot v-model="selectedSheetTab" class="sheet-detail-tabs">
                <TabsList class="sheet-primary-tabs">
                  <TabsTrigger value="skills">技能</TabsTrigger>
                  <TabsTrigger value="combat">战斗与装备</TabsTrigger>
                  <TabsTrigger value="profile">背景与资产</TabsTrigger>
                </TabsList>

                <TabsContent value="skills" class="sheet-tab-content">
                  <div class="sheet-skill-panel">
                    <header class="sheet-skill-heading">
                      <strong>技能</strong>
                      <span>成功率 <small>常规 / 困难 / 极难</small></span>
                    </header>
                    <div class="sheet-skill-scroll">
                      <div v-if="displaySkills.length" class="sheet-skill-grid" role="list">
                        <div v-for="item in displaySkills" :key="item.key" class="sheet-skill-item" :class="{ category: item.kind === 'category' }" role="listitem">
                          <button
                            v-if="item.kind === 'category'"
                            type="button"
                            class="sheet-skill-category-button"
                            :aria-pressed="selectedSkillGroup === item.displayName"
                            @click="toggleSkillGroup(item.displayName)"
                          >
                            <span><strong>类别：{{ item.displayName }}</strong><small>{{ selectedSkillGroup === item.displayName ? '返回全部技能' : '查看大类技能' }}</small></span>
                          </button>
                          <span v-else>
                            <strong>{{ item.displayName }}</strong>
                            <small v-if="item.category">{{ item.category }}</small>
                          </span>
                          <code v-if="item.kind === 'skill'" class="check-rate">{{ formatCheckRate(item.value) }}</code>
                        </div>
                      </div>
                      <div v-else class="sheet-table-empty">暂无技能</div>
                    </div>
                  </div>
                </TabsContent>

                <TabsContent value="combat" class="sheet-tab-content">
                  <div class="sheet-table-scroll">
                      <table class="sheet-data-table weapon-data-table">
                        <thead><tr><th>武器</th><th>成功率</th><th>伤害</th><th>射程</th><th>次数</th><th>弹药</th><th>故障值</th></tr></thead>
                        <tbody>
                          <tr v-for="weapon in card.weapons" :key="weapon.id" :class="{ broken: weapon.isBroken }">
                            <td>
                              <span class="weapon-name-line">
                                <strong>{{ weapon.name }}</strong>
                                <WeaponRiskNotice v-if="shouldShowWeaponRisk(weapon)" :weapon="weapon" />
                              </span>
                              <small v-if="weapon.notes">{{ weapon.notes }}</small><em v-if="weapon.isBroken">已损坏</em>
                            </td>
                            <td class="check-rate">{{ formatCheckRate(resolveWeaponCheckValue(weapon, card.skills)) }}</td>
                            <td>{{ shown(weapon.damage) }}</td>
                            <td>{{ shown(weapon.range) }}</td>
                            <td>{{ shown(weapon.attacksPerRound) }}</td>
                            <td>{{ ammo(weapon.remainingAmmo, weapon.ammoCapacity) }}</td>
                            <td>{{ shown(weapon.malfunction) }}</td>
                          </tr>
                          <tr v-if="!card.weapons.length"><td colspan="7" class="sheet-table-empty">暂无武器</td></tr>
                        </tbody>
                      </table>
                  </div>
                  <section class="sheet-equipment-summary">
                    <strong>随身装备</strong><p>{{ card.profile?.equipmentText || '无额外装备' }}</p>
                  </section>
                </TabsContent>

                <TabsContent value="profile" class="sheet-tab-content profile-tab-content">
                  <TabsRoot v-model="selectedProfileTab" class="sheet-profile-tabs">
                    <TabsList class="sheet-secondary-tabs">
                      <TabsTrigger value="background">人物背景</TabsTrigger>
                      <TabsTrigger value="connections">重要联系</TabsTrigger>
                      <TabsTrigger value="trauma">创伤记录</TabsTrigger>
                      <TabsTrigger value="assets">资产与笔记</TabsTrigger>
                    </TabsList>
                    <TabsContent value="background" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>形象描述</strong><p>{{ card.profile?.appearance || '未记录' }}</p></section>
                        <section><strong>思想与信念</strong><p>{{ card.profile?.ideology || '未记录' }}</p></section>
                        <section><strong>特质</strong><p>{{ card.profile?.traits || '未记录' }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="connections" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>重要之人</strong><p>{{ card.profile?.significantPeople || '未记录' }}</p></section>
                        <section><strong>意义非凡之地</strong><p>{{ card.profile?.meaningfulLocations || '未记录' }}</p></section>
                        <section><strong>宝贵之物</strong><p>{{ card.profile?.treasuredPossessions || '未记录' }}</p></section>
                        <section><strong>关键连接</strong><small>{{ card.profile?.keyConnectionCategory || '未分类' }}</small><p>{{ card.profile?.keyConnectionText || '未记录' }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="trauma" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>伤口和疤痕</strong><p>{{ card.profile?.injuriesAndScars || '未记录' }}</p></section>
                        <section><strong>恐惧症和狂躁症</strong><p>{{ card.profile?.phobiasAndManias || '未记录' }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="assets" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>装备和道具</strong><p>{{ card.profile?.equipmentText || '未记录' }}</p></section>
                        <section class="sheet-wealth"><strong>财务状况</strong><dl><div><dt>消费水平</dt><dd>{{ card.profile?.spendingLevel || '—' }}</dd></div><div><dt>现金</dt><dd>{{ card.profile?.cash || '—' }}</dd></div></dl></section>
                        <section><strong>资产</strong><p>{{ card.profile?.assetsText || '未记录' }}</p></section>
                        <section><strong>调查员笔记</strong><p>{{ card.profile?.notes || '未记录' }}</p></section>
                      </div>
                    </TabsContent>
                  </TabsRoot>
                </TabsContent>
              </TabsRoot>
            </section>
            <section v-else class="tool-card import-card binding-import-card"><BookUser :size="25" /><strong>{{ selectedActorName }}尚未建立人物卡</strong><p>首行必须是“姓名, 职业, 性别, 年龄岁”，并包含 STR、CON、SIZ、DEX、APP、INT、POW、EDU 八项属性；年龄范围为 15–90。</p><label class="field"><span>人物卡文本</span><textarea v-model="cardText" rows="10" placeholder="调查员, 记者, 女, 27岁\n时代: 1920s\nSTR 50 CON 55 SIZ 60 DEX 65 APP 60 INT 70 POW 55 EDU 70\n——技能——\n侦查 60%" /></label><button class="button primary" :disabled="!cardText.trim() || busy" @click="execute(createCard)">导入人物卡</button></section>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="dice" class="tabs-content tool-section">
        <div v-if="diceHistoryEntries.length" class="dice-history-list">
          <div
            v-for="entry in diceHistoryEntries"
            :key="`${entry.messageId}:${entry.aggregate.results[0]?.roundNo || 1}`"
            class="dice-history-item"
          >
            <DiceRollMessage
              :aggregate="entry.aggregate"
              :show-icon="false"
              @open="emit('openDice', $event)"
            />
            <button
              type="button"
              class="dice-history-locate"
              aria-label="定位到聊天记录"
              title="定位到聊天记录"
              @click="emit('locateDice', entry.messageId)"
            >
              <LocateFixed :size="15" />
            </button>
          </div>
        </div>
        <div v-else class="dice-history-empty">
          <Dices :size="26" />
          <strong>当前聊天还没有掷骰记录</strong>
          <p>跑团中产生的掷骰会自动出现在这里。</p>
        </div>
      </TabsContent>

      <TabsContent value="dice-debug" class="tabs-content tool-section">
        <DiceDebugPanel @play="(aggregate) => emit('debugDice', aggregate)" />
      </TabsContent>

    </TabsRoot>
    <div v-if="busy" class="dialog-busy"><LoaderCircle class="spin" :size="17" />正在处理…</div>
  </BaseDialog>

  <BaseDialog v-model="confirmationOpen" :title="confirmationTitle" :description="`目标时间：${time(confirmationTargetTime)}`" size="sm" :layer="'foreground'" :content-class="confirmationContentClass">
    <div v-if="restoreConfirmation.stage.value === 'delete-manual-save'" class="restore-confirmation-copy">
      <strong>确认删除当前存档</strong>
      <p>该自动回退点早于当前手动存档。继续回退将同时删除当前跑团存档，且不可恢复。</p>
    </div>
    <div v-else class="rollback-confirmation-layout">
      <section
        class="rollback-chat-preview"
        aria-label="恢复位置聊天预览"
      >
        <header class="rollback-preview-header">
          <span><MessageSquareText :size="14" /><strong>聊天记录预览</strong></span>
          <small>仅显示恢复点附近</small>
        </header>
        <div v-if="rollbackPreviewLoading" class="rollback-preview-loading">
          <LoaderCircle class="spin" :size="16" />正在读取恢复位置…
        </div>
        <template v-else-if="rollbackMessagePreview">
          <div class="rollback-preview-viewport">
            <div class="rollback-chat-zone retained">
              <div class="rollback-zone-caption"><i />{{ restoreConfirmation.action.value === 'load' ? '读档后保留' : '回退后保留' }}</div>
              <article
                v-for="message in rollbackMessagePreview.retained"
                :key="`retained-${message.id}`"
                class="rollback-preview-message"
                :class="message.speakerType"
              >
                <span v-if="message.speakerType !== 'user'" class="rollback-preview-avatar">{{ previewSpeaker(message).slice(0, 1) }}</span>
                <div>
                  <strong>{{ previewSpeaker(message) }}</strong>
                  <p><span>{{ formatRollbackPreviewMessage(message) }}</span></p>
                </div>
              </article>
              <p v-if="!rollbackMessagePreview.retained.length" class="rollback-preview-empty">此前没有聊天消息</p>
            </div>
            <div class="rollback-chat-boundary"><span><RotateCcw :size="10" />{{ restoreConfirmation.action.value === 'load' ? '将读取到这里' : '将回退到这里' }}</span></div>
            <div class="rollback-chat-zone deleted">
              <div class="rollback-zone-caption"><i />此后内容将删除</div>
              <article
                v-for="message in rollbackMessagePreview.deleted"
                :key="`deleted-${message.id}`"
                class="rollback-preview-message"
                :class="message.speakerType"
              >
                <span v-if="message.speakerType !== 'user'" class="rollback-preview-avatar">{{ previewSpeaker(message).slice(0, 1) }}</span>
                <div>
                  <strong>{{ previewSpeaker(message) }}</strong>
                  <p><span>{{ formatRollbackPreviewMessage(message) }}</span></p>
                </div>
              </article>
              <div v-if="rollbackMessagePreview.deletedMessagesOmitted" class="rollback-preview-omitted" aria-label="后续删除消息已省略">...</div>
              <p v-else-if="!rollbackMessagePreview.deleted.length" class="rollback-preview-empty">当前没有聊天消息会被删除</p>
            </div>
          </div>
          <p v-if="rollbackPreviewFailed" class="rollback-preview-warning">未能读取边界附近的消息，仅显示恢复范围。</p>
        </template>
        <p v-else class="rollback-preview-warning">当前恢复点没有可用的聊天边界。</p>
      </section>
      <aside class="rollback-confirmation-sidebar">
        <section class="restore-confirmation-copy rollback-confirmation-summary">
          <span><RotateCcw :size="16" /></span>
          <div>
            <strong>{{ restoreConfirmation.action.value === 'load' ? '读取跑团存档' : pendingRollback?.title }}</strong>
            <p v-if="restoreConfirmation.action.value === 'load'">请确认聊天记录中的读档位置。存档点之后的行动轮、消息、骰子、人物状态以及更晚的自动回退点将被删除。</p>
            <p v-else>请确认聊天记录中的回退位置。该位置之后的行动轮、消息、骰子和人物状态将被删除。</p>
          </div>
        </section>
        <section class="rollback-investigator-panel">
          <header><span><UserRound :size="14" /><strong>调查员状态</strong></span><small>恢复后</small></header>
          <div v-if="pendingRestoreInvestigators.length" class="rollback-investigator-list">
            <article v-for="item in pendingRestoreInvestigators" :key="item.characterId">
              <span class="rollback-investigator-avatar">{{ item.name.slice(0, 1) }}</span>
              <div>
                <header><strong>{{ item.name }}</strong><em v-if="restoreInvestigatorCondition(item)">{{ restoreInvestigatorCondition(item) }}</em></header>
                <dl>
                  <div><dt>HP</dt><dd>{{ shown(item.hpCurrent) }}/{{ shown(item.hpMax) }}</dd></div>
                  <div><dt>SAN</dt><dd>{{ shown(item.sanCurrent) }}/{{ shown(item.sanMax) }}</dd></div>
                  <div><dt>MP</dt><dd>{{ shown(item.mpCurrent) }}/{{ shown(item.mpMax) }}</dd></div>
                </dl>
              </div>
            </article>
          </div>
          <p v-else class="rollback-investigator-empty">该恢复点没有调查员状态记录</p>
        </section>
      </aside>
    </div>
    <template #footer>
      <button class="button ghost" :disabled="busy" @click="restoreConfirmation.clear()">取消</button>
      <button class="button danger" :disabled="busy" @click="execute(confirmRestore)">
        <LoaderCircle v-if="busy" class="spin" :size="16" />
        {{ restoreConfirmation.stage.value === 'delete-manual-save' ? '删除存档并回退' : restoreConfirmation.action.value === 'load' ? '确认读取存档' : '确认回退' }}
      </button>
    </template>
  </BaseDialog>
</template>
