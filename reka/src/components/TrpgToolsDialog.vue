<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, ArrowDown, ArrowRight, ArrowUp, BookUser, Check, ChevronDown, ChevronUp, ClipboardCheck, Dices, LoaderCircle, LocateFixed, MessageSquareText, RefreshCw, RotateCcw, Save, Search, Sparkles, UserRound, X } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import WeaponRiskNotice from '@/components/WeaponRiskNotice.vue'
import CharacterCardExport from '@/components/CharacterCardExport.vue'
import { api } from '@/api/client'
import type {
  Character, CharacterCard, CharacterCardCreationDraft, CocModule, ContextWindowOverview, ContextWindowUsage, Conversation, DiceRollAggregate,
  GroupActorControlMode, GroupActorRuntime, GroupActorRuntimeSavePayload, GroupMessage, InvestigatorCardSummary,
  ModelApi, TrpgRollbackOverview, TrpgRollbackResult, TrpgSave,
} from '@/api/types'
import { errorMessage, notify } from '@/composables/useNotice'
import {
  DICE_HISTORY_CATEGORY_OPTIONS, filterDiceHistoryEntries, formatDiceHistoryTime,
  hydrateDiceMessage, listDiceHistoryEntriesNewestFirst,
} from '@/dice/domain/dicePlayback'
import type { DiceHistoryCategory, DiceHistoryResultKind } from '@/dice/domain/dicePlayback'
import {
  buildFetchedRollbackMessagePreview, buildSkillDisplayItems, buildToolCharacterTargets, buildToolRecoveryTimeline, buildToolRollbackActions, formatCheckRate, formatRollbackPreviewMessage, loadToolCharacterDrafts, nextSkillGroup, preferredToolCharacterTargetKey, resolveRollbackMessagePreview, resolveToolRestoreInvestigators, resolveWeaponCheckValue, restoreInvestigatorCondition, shouldShowWeaponRisk, toolDialogContentClass,
  useToolRestoreConfirmation,
} from '@/components/trpgToolsState'
import type { ToolRecoveryTimelineItem, ToolRestoreAction, ToolRollbackMessagePreview, ToolSkillSortDirection, ToolSkillSortMode } from '@/components/trpgToolsState'
import { buildCharacterCardCreationMethods } from '@/components/trpgSetupState'
import type { CharacterCardCreationMethod } from '@/components/trpgSetupState'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  conversation: Conversation
  module: CocModule | null
  username: string
  characters: Character[]
  participantIds: number[]
  messages: GroupMessage[]
  actorRuntimes: GroupActorRuntime[]
  modelApis: ModelApi[]
  saveActorRuntime: (payload: GroupActorRuntimeSavePayload) => Promise<GroupActorRuntime | undefined>
  hasOlderMessages: boolean
  loadingOlderMessages: boolean
  requestedCardId?: number | null
}>()
const emit = defineEmits<{
  restored: []
  openDice: [aggregate: DiceRollAggregate]
  locateDice: [messageId: number]
  loadEarlier: []
  createCharacterCard: [targetKey: string, method: CharacterCardCreationMethod]
}>()

const busy = ref(false)
const contextOverview = ref<ContextWindowOverview | null>(null)
const save = ref<TrpgSave | null>(null)
const rollbackOverview = ref<TrpgRollbackOverview | null>(null)
const saveRemark = ref('')
const cards = ref<InvestigatorCardSummary[]>([])
const cardDrafts = ref<Record<string, CharacterCardCreationDraft>>({})
const selectedKey = ref('player')
const selectedToolTab = ref('status')
const selectedSheetTab = ref('skills')
const selectedProfileTab = ref('background')
const selectedSkillGroup = ref<string | null>(null)
const skillPanelExpanded = ref(false)
const skillSearchQuery = ref('')
const skillSortMode = ref<ToolSkillSortMode>('default')
const skillSortDirection = ref<ToolSkillSortDirection>('asc')
const diceSearchQuery = ref('')
const diceCategoryFilter = ref<DiceHistoryCategory | ''>('')
const diceResultFilter = ref<DiceHistoryResultKind | ''>('')
const restoreConfirmation = useToolRestoreConfirmation(open, selectedToolTab)
const rollbackMessagePreview = ref<ToolRollbackMessagePreview | null>(null)
const rollbackPreviewLoading = ref(false)
const rollbackPreviewFailed = ref(false)
const saveEditorOpen = ref(false)
const card = ref<CharacterCard | null>(null)
const expandedRuntimeKey = ref<string | null>(null)
const runtimeControlMode = ref<GroupActorControlMode>('MODEL')
const runtimeModelApiId = ref('')
const runtimeSavingKey = ref<string | null>(null)
let rollbackPreviewRequestId = 0

interface RuntimeActorView {
  key: string
  name: string
  image?: string
  runtime: GroupActorRuntime
  usage?: ContextWindowUsage
}

const characterTargets = computed(() => buildToolCharacterTargets(
  props.characters,
  cards.value,
  props.participantIds,
  props.username,
))
const selectedTarget = computed(() => characterTargets.value.find((target) => target.key === selectedKey.value)
  || characterTargets.value[0])
const selectedDraft = computed(() => cardDrafts.value[selectedTarget.value?.key || ''])
const creationMethods = computed(() => buildCharacterCardCreationMethods(selectedTarget.value?.actorType || 'PLAYER'))
const selectedDraftMethod = computed<CharacterCardCreationMethod>(() => selectedDraft.value?.creationMode === 'AUTO_QUICK_START' ? 'AUTO' : 'STEP')
const selectedDraftMethodLabel = computed(() => selectedDraftMethod.value === 'AUTO' ? 'AI 自动生成' : '标准步进建卡')
const selectedDraftProgressLabel = computed(() => {
  if (selectedDraft.value?.status === 'PREVIEW_READY') return '检查并绑定'
  return {
    IDENTITY: '身份资料', ATTRIBUTES: '属性生成', OCCUPATION: '职业选择', SKILLS: '技能分配',
    BACKGROUND: '人物背景', EQUIPMENT: '装备确认', PREVIEW: '检查并绑定',
  }[selectedDraft.value?.currentStep || ''] || '继续完善人物卡'
})
const runtimeActors = computed<RuntimeActorView[]>(() => {
  const kpRuntime = props.actorRuntimes.find((runtime) => runtime.actorType === 'kp') || {
    actorType: 'kp' as const,
    controlMode: 'MODEL' as const,
    modelApiAvailable: true,
  }
  const kp: RuntimeActorView = {
    key: 'kp',
    name: 'KP',
    runtime: kpRuntime,
    usage: contextOverview.value?.kp,
  }
  const investigators = props.actorRuntimes
    .filter((runtime) => runtime.actorType === 'character')
    .map((runtime) => {
      const character = props.characters.find((item) => item.characterId === runtime.actorId)
      const investigator = cards.value.find((item) => item.actorType === 'BOT'
        && item.participantId === runtime.actorId)
      const usage = contextOverview.value?.investigators.find((item) =>
        item.subjectCharacterId === investigator?.cardId)?.usage
      return {
        key: `character:${runtime.actorId}`,
        name: investigator && character && investigator.name !== character.characterName
          ? `${character.characterName} 饰演 ${investigator.name}`
          : investigator?.name || character?.characterName || `角色 #${runtime.actorId}`,
        image: character?.characterImage,
        runtime,
        usage,
      }
    })
  return [kp, ...investigators]
})
const modelRuntimeCount = computed(() => runtimeActors.value.filter((actor) => actor.runtime.controlMode === 'MODEL').length)
const manualRuntimeCount = computed(() => runtimeActors.value.filter((actor) => actor.runtime.controlMode === 'MANUAL').length)
const selectedActorName = computed(() => selectedTarget.value?.name || props.username.trim() || '当前玩家')
const completedCardCount = computed(() => characterTargets.value.filter((target) => target.cardId !== undefined).length)
const dialogContentClass = computed(() => toolDialogContentClass(selectedToolTab.value))
const allDiceHistoryEntries = computed(() => listDiceHistoryEntriesNewestFirst(props.messages))
const diceHistoryEntries = computed(() => filterDiceHistoryEntries(allDiceHistoryEntries.value, {
  query: diceSearchQuery.value,
  category: diceCategoryFilter.value,
  resultKind: diceResultFilter.value,
}))
const diceFiltersActive = computed(() => Boolean(
  diceSearchQuery.value.trim() || diceCategoryFilter.value || diceResultFilter.value,
))
const diceHistoryMatchCopy = computed(() => diceFiltersActive.value
  ? `找到 ${diceHistoryEntries.value.length} 条 · 已检索最近 ${allDiceHistoryEntries.value.length} 条掷骰记录`
  : `已显示 ${diceHistoryEntries.value.length} 条掷骰记录`)
const diceHistoryLoadLabel = computed(() => {
  if (props.loadingOlderMessages) return '正在加载…'
  if (!props.hasOlderMessages) return '已显示全部掷骰记录'
  return diceFiltersActive.value ? '加载更早记录并继续筛选' : '查找更早的掷骰记录'
})
const diceHistoryLoadHint = computed(() => props.hasOlderMessages
  ? '将继续查找更早聊天中的掷骰记录'
  : '')
const diceResultOptions: Array<{ value: DiceHistoryResultKind; label: string }> = [
  { value: 'numeric', label: '数值' },
  { value: 'success', label: '成功' },
  { value: 'failure', label: '失败' },
  { value: 'other', label: '其他' },
]
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
const displaySkills = computed(() => buildSkillDisplayItems(
  card.value?.skills || [],
  selectedSkillGroup.value,
  {
    query: skillSearchQuery.value,
    sortMode: skillSortMode.value,
    sortDirection: skillSortDirection.value,
  },
))
const displaySkillCount = computed(() => displaySkills.value.filter((item) => item.kind === 'skill').length)
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
function profileText(value?: string | null) {
  const text = value?.trim()
  return !text || ['（空）', '(空)'].includes(text) ? '暂未填写' : text
}
function shown(value?: string | number | null) { return value == null || value === '' ? '—' : value }
function clearDiceFilters() {
  diceSearchQuery.value = ''
  diceCategoryFilter.value = ''
  diceResultFilter.value = ''
}
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
function runtimeContextPercent(actor: RuntimeActorView): number {
  return Math.max(0, Math.round((actor.usage?.ratio || 0) * 100))
}
function runtimeContextTone(actor: RuntimeActorView): 'safe' | 'warning' | 'danger' {
  const percent = runtimeContextPercent(actor)
  return percent >= 90 ? 'danger' : percent >= 70 ? 'warning' : 'safe'
}
function draftRuntimeModel(): ModelApi | undefined {
  return props.modelApis.find((model) => String(model.id) === runtimeModelApiId.value)
}
function runtimeSummary(actor: RuntimeActorView): string {
  if (actor.runtime.controlMode === 'MANUAL') return '等待输入'
  if (!actor.runtime.modelApiAvailable) return '默认模型'
  return actor.runtime.modelApiName || '默认模型'
}
function runtimeModeLabel(actor: RuntimeActorView): string {
  return actor.runtime.controlMode === 'MANUAL' ? '手动控制' : 'AI 控制'
}
function modelStatusLabel(model?: ModelApi): string {
  if (!model) return ''
  return {
    SUCCESS: '可用', PARTIAL: '部分可用', FAILED: '连接异常', UNTESTED: '未测试',
  }[model.status]
}
function openRuntimeEditor(actor: RuntimeActorView) {
  if (expandedRuntimeKey.value === actor.key) {
    expandedRuntimeKey.value = null
    return
  }
  expandedRuntimeKey.value = actor.key
  runtimeControlMode.value = actor.runtime.actorType === 'kp' ? 'MODEL' : actor.runtime.controlMode
  runtimeModelApiId.value = actor.runtime.modelApiAvailable && actor.runtime.modelApiId != null
    ? String(actor.runtime.modelApiId)
    : ''
}
function runtimeDraftChanged(actor: RuntimeActorView): boolean {
  const currentModelId = actor.runtime.modelApiAvailable && actor.runtime.modelApiId != null
    ? String(actor.runtime.modelApiId)
    : ''
  return runtimeControlMode.value !== actor.runtime.controlMode
    || runtimeModelApiId.value !== currentModelId
}
async function saveRuntime(actor: RuntimeActorView) {
  if (runtimeSavingKey.value) return
  runtimeSavingKey.value = actor.key
  try {
    const saved = await props.saveActorRuntime({
      actorType: actor.runtime.actorType,
      actorId: actor.runtime.actorId,
      controlMode: actor.runtime.actorType === 'kp' ? 'MODEL' : runtimeControlMode.value,
      modelApiId: runtimeModelApiId.value ? Number(runtimeModelApiId.value) : undefined,
    })
    if (saved) expandedRuntimeKey.value = null
  } finally {
    runtimeSavingKey.value = null
  }
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
  cardDrafts.value = await loadToolCharacterDrafts(
    characterTargets.value,
    (participantId) => api.activeCharacterCardDraft(props.conversation.id, participantId),
  )
  await loadCard()
}
async function refreshOverview(requestedCardId: number | null = null) {
  const [usageResult, saveResult, rollbackResult] = await Promise.all([
    api.contextWindow(props.conversation.id),
    api.trpgSave(props.conversation.id),
    api.trpgRollbackStatus(props.conversation.id),
  ])
  contextOverview.value = usageResult
  save.value = saveResult
  rollbackOverview.value = rollbackResult
  saveRemark.value = saveResult?.remark || ''
  await refreshCards(requestedCardId)
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
  skillPanelExpanded.value = false
  await execute(loadCard)
}
function toggleSkillGroup(group: string) {
  selectedSkillGroup.value = nextSkillGroup(selectedSkillGroup.value, group)
}
function requestCharacterCardCreation(method: CharacterCardCreationMethod) {
  const option = creationMethods.value.find((item) => item.id === method)
  if (!option?.enabled || !selectedTarget.value) return
  emit('createCharacterCard', selectedTarget.value.key, method)
}
function resumeCharacterCardDraft() {
  if (!selectedDraft.value || !selectedTarget.value) return
  emit('createCharacterCard', selectedTarget.value.key, selectedDraftMethod.value)
}
watch(open, (visible) => {
  if (!visible) {
    skillPanelExpanded.value = false
    skillSearchQuery.value = ''
    skillSortMode.value = 'default'
    skillSortDirection.value = 'asc'
    selectedSkillGroup.value = null
    expandedRuntimeKey.value = null
    return
  }
  if (props.requestedCardId != null) selectedToolTab.value = 'card'
  void execute(() => refreshOverview(props.requestedCardId ?? null))
})
watch(() => props.conversation.id, () => {
  selectedKey.value = 'player'
  selectedSheetTab.value = 'skills'
  selectedProfileTab.value = 'background'
  selectedSkillGroup.value = null
  skillPanelExpanded.value = false
  skillSearchQuery.value = ''
  skillSortMode.value = 'default'
  skillSortDirection.value = 'asc'
  clearDiceFilters()
  cards.value = []
  cardDrafts.value = {}
  contextOverview.value = null
  expandedRuntimeKey.value = null
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
watch(selectedSheetTab, (tab) => {
  if (tab !== 'skills') skillPanelExpanded.value = false
})
</script>

<template>
  <BaseDialog v-model="open" title="跑团工具" :description="module?.name === conversation.title ? conversation.title : `${conversation.title} · 模组：${module?.name || '未记录'}`" size="lg" :content-class="dialogContentClass">
    <TabsRoot v-model="selectedToolTab" class="tabs trpg-tools">
      <TabsList class="tabs-list">
        <TabsTrigger value="status"><Activity :size="15" />状态</TabsTrigger>
        <TabsTrigger value="save"><Save :size="15" />存档</TabsTrigger>
        <TabsTrigger value="card"><BookUser :size="15" />人物卡</TabsTrigger>
        <TabsTrigger value="dice"><Dices :size="15" />掷骰记录</TabsTrigger>
      </TabsList>

      <TabsContent value="status" class="tabs-content tool-section">
        <section class="trpg-runtime-dashboard">
          <header class="trpg-runtime-heading">
            <span><strong>角色控制方式</strong><small>AI 控制 {{ modelRuntimeCount }} 位 · 手动控制 {{ manualRuntimeCount }} 位</small></span>
            <button class="icon-button bordered" :disabled="busy" title="刷新" aria-label="刷新角色控制方式" @click="execute(refreshOverview)"><RefreshCw :size="15" /></button>
          </header>

          <div class="trpg-runtime-list">
            <template v-for="(actor, index) in runtimeActors" :key="actor.key">
              <div v-if="index === 1" class="trpg-runtime-section-label">调查员</div>
              <article class="trpg-runtime-card" :class="[{ expanded: expandedRuntimeKey === actor.key }, actor.runtime.controlMode.toLowerCase()]">
                <button type="button" class="trpg-runtime-card-summary" :aria-expanded="expandedRuntimeKey === actor.key" @click="openRuntimeEditor(actor)">
                  <span
                    class="trpg-runtime-avatar"
                    :class="{ kp: actor.runtime.actorType === 'kp', placeholder: !actor.image }"
                    :style="actor.image ? { backgroundImage: `url(${actor.image})` } : {}"
                  ><UserRound v-if="!actor.image" :size="16" /></span>
                  <span class="trpg-runtime-identity">
                    <strong>{{ actor.name }}</strong>
                    <small>{{ runtimeSummary(actor) }}</small>
                  </span>
                  <span class="trpg-runtime-mode" :class="actor.runtime.controlMode.toLowerCase()">{{ runtimeModeLabel(actor) }}</span>
                  <ChevronDown :size="16" :class="{ rotated: expandedRuntimeKey === actor.key }" />
                </button>

                <div v-if="actor.usage" class="trpg-runtime-context">
                  <span><small>对话容量占用</small><em>{{ runtimeContextPercent(actor) }}%</em></span>
                  <div class="context-meter" :class="runtimeContextTone(actor)"><i :style="{ width: `${Math.min(runtimeContextPercent(actor), 100)}%` }" /></div>
                </div>
                <p v-if="!actor.runtime.modelApiAvailable" class="trpg-runtime-inline-warning">原模型已删除，现使用默认模型</p>

                <div v-if="expandedRuntimeKey === actor.key" class="trpg-runtime-editor">
                  <div v-if="actor.runtime.actorType === 'character'" class="trpg-runtime-field">
                    <span>控制方式</span>
                    <div class="segmented trpg-runtime-mode-switch">
                      <button type="button" :class="{ active: runtimeControlMode === 'MODEL' }" @click="runtimeControlMode = 'MODEL'">AI 控制</button>
                      <button type="button" :class="{ active: runtimeControlMode === 'MANUAL' }" @click="runtimeControlMode = 'MANUAL'">手动控制</button>
                    </div>
                  </div>
                  <p v-else class="trpg-runtime-kp-note">KP 由 AI 控制</p>

                  <template v-if="runtimeControlMode === 'MODEL'">
                    <label class="trpg-runtime-field">
                      <span>使用模型</span>
                      <select v-model="runtimeModelApiId" class="trpg-runtime-model-select">
                        <option value="">默认模型</option>
                        <option v-for="model in modelApis" :key="model.id" :value="String(model.id)">{{ model.name }}</option>
                      </select>
                    </label>
                    <div v-if="runtimeModelApiId && draftRuntimeModel()" class="trpg-runtime-model-meta">
                      <span :class="`is-${draftRuntimeModel()?.status.toLowerCase()}`"><i />{{ modelStatusLabel(draftRuntimeModel()) }}</span>
                      <em v-if="draftRuntimeModel()?.reasoningOutputStatus === 'DETECTED'">推理</em>
                      <em v-if="Object.keys(draftRuntimeModel()?.requestOverrides || {}).length">{{ Object.keys(draftRuntimeModel()?.requestOverrides || {}).length }} 个额外参数</em>
                    </div>
                    <p v-if="draftRuntimeModel()?.status === 'FAILED'" class="trpg-runtime-warning">当前模型连接异常，发言可能失败。</p>
                  </template>
                  <p v-else class="trpg-runtime-manual-note">轮到该角色时由你输入</p>

                  <div class="trpg-runtime-actions">
                    <button type="button" class="button ghost" :disabled="runtimeSavingKey === actor.key" @click="expandedRuntimeKey = null">取消</button>
                    <button type="button" class="button secondary" :disabled="runtimeSavingKey === actor.key || !runtimeDraftChanged(actor)" @click="saveRuntime(actor)">
                      <LoaderCircle v-if="runtimeSavingKey === actor.key" class="spin" :size="14" />保存
                    </button>
                  </div>
                </div>
              </article>
            </template>
          </div>
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
                <span class="binding-state" :class="{ complete: target.cardId !== undefined, draft: target.cardId === undefined && cardDrafts[target.key] }">
                  <Check v-if="target.cardId !== undefined" :size="13" />
                  <ClipboardCheck v-else-if="cardDrafts[target.key]" :size="13" />
                  {{ target.cardId !== undefined ? '已建立' : cardDrafts[target.key] ? '草稿中' : '待建立' }}
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
                  <small>扮演者：{{ selectedActorName }} · {{ card.character.occupation || '未填写职业' }}</small>
                  <h3>{{ card.character.name }}</h3>
                  <p>{{ shown(card.character.sex) }} · {{ shown(card.character.age) }} 岁 · {{ card.character.era || '时代未填' }}</p>
                  <p v-if="profileText(card.character.birthplace) !== '暂未填写' && card.character.birthplace?.trim() !== '无'">出身地：{{ profileText(card.character.birthplace) }}</p><p v-if="profileText(card.character.residence) !== '暂未填写' && card.character.residence?.trim() !== '无'">居住地：{{ profileText(card.character.residence) }}</p>
                </div>
                <div class="sheet-overview-actions">
                  <CharacterCardExport :card="card" :active="open && selectedToolTab === 'card'" />
                  <div class="sheet-statuses" aria-label="调查员状态">
                    <em v-for="status in characterStatuses" :key="status.label" :class="status.tone">{{ status.label }}</em>
                  </div>
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

              <TabsRoot v-model="selectedSheetTab" class="sheet-detail-tabs" :class="{ 'skill-panel-expanded': skillPanelExpanded }">
                <TabsList v-show="!skillPanelExpanded" class="sheet-primary-tabs">
                  <TabsTrigger value="skills">技能</TabsTrigger>
                  <TabsTrigger value="combat">武器</TabsTrigger>
                  <TabsTrigger value="profile">背景与资产</TabsTrigger>
                </TabsList>

                <TabsContent value="skills" class="sheet-tab-content">
                  <div class="sheet-skill-panel">
                    <header class="sheet-skill-heading">
                      <button
                        type="button"
                        class="sheet-skill-expand-toggle"
                        :aria-label="skillPanelExpanded ? '收起技能面板' : '展开技能面板'"
                        :title="skillPanelExpanded ? '收起技能面板' : '展开技能面板'"
                        @click="skillPanelExpanded = !skillPanelExpanded"
                      >
                        <ChevronDown v-if="skillPanelExpanded" :size="15" />
                        <ChevronUp v-else :size="15" />
                      </button>
                      <strong>技能</strong>
                      <span>成功率 <small>常规 / 困难 / 极难</small></span>
                    </header>
                    <div v-if="skillPanelExpanded" class="sheet-skill-toolbar">
                      <label class="sheet-skill-search">
                        <Search :size="14" />
                        <input
                          v-model="skillSearchQuery"
                          class="sheet-skill-search-input"
                          type="search"
                          aria-label="检索技能"
                          placeholder="检索技能名称"
                          autocomplete="off"
                        />
                        <button
                          v-if="skillSearchQuery"
                          type="button"
                          class="sheet-skill-clear"
                          aria-label="清除技能检索"
                          @click="skillSearchQuery = ''"
                        ><X :size="13" /></button>
                      </label>
                      <label class="sheet-skill-sort">
                        <select v-model="skillSortMode" class="sheet-skill-sort-select" aria-label="技能排序方式">
                          <option value="default">默认顺序</option>
                          <option value="value">成功率</option>
                          <option value="category">大类</option>
                        </select>
                      </label>
                      <button
                        type="button"
                        class="sheet-skill-direction-toggle"
                        :aria-label="skillSortDirection === 'asc' ? '切换为倒序' : '切换为正序'"
                        @click="skillSortDirection = skillSortDirection === 'asc' ? 'desc' : 'asc'"
                      >
                        <ArrowUp v-if="skillSortDirection === 'asc'" :size="13" />
                        <ArrowDown v-else :size="13" />
                        {{ skillSortDirection === 'asc' ? '正序' : '倒序' }}
                      </button>
                      <small class="sheet-skill-result-count">{{ displaySkillCount }} 项</small>
                    </div>
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
                      <div v-else class="sheet-table-empty">{{ skillSearchQuery ? '没有匹配的技能' : '暂无技能' }}</div>
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
                        <section><strong>形象描述</strong><p>{{ profileText(card.profile?.appearance) }}</p></section>
                        <section><strong>思想与信念</strong><p>{{ profileText(card.profile?.ideology) }}</p></section>
                        <section><strong>特质</strong><p>{{ profileText(card.profile?.traits) }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="connections" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>重要之人</strong><p>{{ profileText(card.profile?.significantPeople) }}</p></section>
                        <section><strong>意义非凡之地</strong><p>{{ profileText(card.profile?.meaningfulLocations) }}</p></section>
                        <section><strong>宝贵之物</strong><p>{{ profileText(card.profile?.treasuredPossessions) }}</p></section>
                        <section><strong>关键连接</strong><small>{{ card.profile?.keyConnectionCategory || '未分类' }}</small><p>{{ profileText(card.profile?.keyConnectionText) }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="trauma" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>伤口和疤痕</strong><p>{{ profileText(card.profile?.injuriesAndScars) }}</p></section>
                        <section><strong>恐惧症和狂躁症</strong><p>{{ profileText(card.profile?.phobiasAndManias) }}</p></section>
                      </div>
                    </TabsContent>
                    <TabsContent value="assets" class="sheet-profile-content">
                      <div class="sheet-profile-grid">
                        <section><strong>装备和道具</strong><p>{{ profileText(card.profile?.equipmentText) }}</p></section>
                        <section class="sheet-wealth"><strong>财务状况</strong><dl><div><dt>消费水平</dt><dd>{{ card.profile?.spendingLevel || '—' }}</dd></div><div><dt>现金</dt><dd>{{ card.profile?.cash || '—' }}</dd></div></dl></section>
                        <section><strong>资产</strong><p>{{ profileText(card.profile?.assetsText) }}</p></section>
                        <section><strong>调查员笔记</strong><p>{{ profileText(card.profile?.notes) }}</p></section>
                      </div>
                    </TabsContent>
                  </TabsRoot>
                </TabsContent>
              </TabsRoot>
            </section>
            <section v-else-if="selectedDraft" class="creation-method-picker tools-card-draft-summary">
              <header class="tools-card-draft-heading">
                <span class="tools-card-draft-icon"><ClipboardCheck :size="25" /></span>
                <span><small>人物卡草稿</small><strong>{{ selectedActorName }}的建卡进度已保存</strong><p>可以从上次停下的位置继续，不会重新生成或覆盖已经填写的内容。</p></span>
                <em>草稿中</em>
              </header>
              <div class="tools-card-draft-progress">
                <span><small>建卡方式</small><strong>{{ selectedDraftMethodLabel }}</strong></span>
                <ArrowRight :size="18" />
                <span><small>当前进度</small><strong>{{ selectedDraftProgressLabel }}</strong></span>
              </div>
              <div class="tools-card-draft-note">
                <strong>{{ selectedDraft.status === 'PREVIEW_READY' ? '人物卡已生成，等待确认绑定' : '草稿已与建卡工作台同步' }}</strong>
                <p>{{ selectedDraft.status === 'PREVIEW_READY' ? '继续后可以检查完整人物卡，并确认绑定到当前调查员。' : '继续后会直接回到当前步骤；如需重新开始，可以在建卡工作台中放弃这份草稿。' }}</p>
              </div>
              <div class="tools-card-draft-actions">
                <button type="button" class="button primary tools-card-draft-resume" @click="resumeCharacterCardDraft"><ArrowRight :size="15" />继续建卡</button>
              </div>
            </section>
            <section v-else class="creation-method-picker tools-card-creation-picker">
              <header class="creation-method-heading">
                <span><small>建立人物卡</small><strong>为{{ selectedActorName }}选择建卡方式</strong><p>建卡完成后会自动返回人物卡列表，并在这里显示完整人物卡。</p></span>
                <em>待建立</em>
              </header>
              <div class="creation-method-grid">
                <button type="button" class="creation-method-card method-step" @click="requestCharacterCardCreation('STEP')">
                  <span class="creation-method-icon"><Dices :size="23" /></span>
                  <span class="creation-method-copy"><strong>标准步进建卡</strong><p>亲自掷骰并逐步完成属性、技能和人物背景。</p></span>
                  <span class="creation-method-action">开始标准建卡</span>
                </button>
                <button type="button" class="creation-method-card method-auto" :class="{ disabled: !creationMethods.find((item) => item.id === 'AUTO')?.enabled }" :disabled="!creationMethods.find((item) => item.id === 'AUTO')?.enabled" @click="requestCharacterCardCreation('AUTO')">
                  <span class="creation-method-icon"><Sparkles :size="23" /></span>
                  <span class="creation-method-copy"><strong>AI 自动生成</strong><p>参考当前角色与模组，生成后检查并确认绑定。</p></span>
                  <span class="creation-method-action">立即自动生成</span>
                </button>
                <button type="button" class="creation-method-card method-import" @click="requestCharacterCardCreation('IMPORT')">
                  <span class="creation-method-icon"><ClipboardCheck :size="23" /></span>
                  <span class="creation-method-copy"><strong>导入人物卡</strong><p>粘贴已有 CoC 人物卡文本，检查后完成绑定。</p></span>
                  <span class="creation-method-action">打开文本导入</span>
                </button>
              </div>
              <p v-if="selectedTarget?.actorType === 'PLAYER'" class="creation-method-footnote">玩家调查员支持步进建卡或导入；AI 自动生成仅用于已有角色设定的 AI 调查员。</p>
            </section>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="dice" class="tabs-content tool-section">
        <div class="dice-history-panel">
          <div class="dice-history-toolbar">
            <label class="dice-history-search-wrap">
              <span>名称</span>
              <span class="dice-history-search-control">
                <Search :size="14" />
                <input
                  v-model="diceSearchQuery"
                  type="search"
                  class="dice-history-search"
                  aria-label="按名称筛选掷骰记录"
                  placeholder="搜索掷骰名称"
                />
              </span>
            </label>
            <label class="dice-history-select-field">
              <span>掷骰类别</span>
              <select v-model="diceCategoryFilter" class="dice-history-category-filter" aria-label="按掷骰类别筛选">
                <option value="">全部类别</option>
                <option v-for="category in DICE_HISTORY_CATEGORY_OPTIONS" :key="category" :value="category">{{ category }}</option>
              </select>
            </label>
            <label class="dice-history-select-field">
              <span>检定结果</span>
              <select v-model="diceResultFilter" class="dice-history-result-filter" aria-label="按检定结果筛选">
                <option value="">全部结果</option>
                <option v-for="option in diceResultOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
            </label>
          </div>
          <div class="dice-history-filter-meta">
            <span>{{ diceHistoryMatchCopy }}</span>
            <button v-if="diceFiltersActive" type="button" class="dice-history-clear" @click="clearDiceFilters">清除筛选</button>
          </div>

          <div class="dice-history-scroll">
            <div v-if="diceHistoryEntries.length" class="dice-history-list">
              <article
                v-for="entry in diceHistoryEntries"
                :key="`${entry.messageId}:${entry.aggregate.results[0]?.roundNo || 1}`"
                class="dice-history-item dice-history-record"
                :class="[`is-${entry.tone}`, `is-result-${entry.resultKind}`]"
              >
                <button type="button" class="dice-history-record-open" @click="emit('openDice', entry.aggregate)">
                  <span class="dice-history-record-main">
                    <strong>{{ entry.title }}</strong>
                    <small>
                      <em class="dice-history-category">{{ entry.category }}</em>
                      <time v-if="formatDiceHistoryTime(entry.occurredAt)">{{ formatDiceHistoryTime(entry.occurredAt) }}</time>
                    </small>
                  </span>
                  <span class="dice-history-outcome"><i />{{ entry.statusLabel }}</span>
                </button>
                <button
                  type="button"
                  class="dice-history-locate"
                  aria-label="定位到聊天记录"
                  title="定位到聊天记录"
                  @click="emit('locateDice', entry.messageId)"
                >
                  <LocateFixed :size="15" />
                </button>
              </article>
            </div>
            <div v-else class="dice-history-empty">
              <Dices :size="26" />
              <strong>{{ allDiceHistoryEntries.length ? '没有符合条件的掷骰记录' : '当前聊天还没有掷骰记录' }}</strong>
              <p>{{ allDiceHistoryEntries.length ? '可以调整筛选，或继续查找更早的掷骰记录。' : '跑团中产生的掷骰会自动出现在这里。' }}</p>
            </div>
            <footer class="dice-history-load-zone">
              <button
                type="button"
                class="dice-history-load-more"
                :disabled="loadingOlderMessages || !hasOlderMessages"
                @click="emit('loadEarlier')"
              >
                <LoaderCircle v-if="loadingOlderMessages" class="spin" :size="15" />
                <ArrowDown v-else :size="15" />
                {{ diceHistoryLoadLabel }}
              </button>
              <p v-if="diceHistoryLoadHint">{{ diceHistoryLoadHint }}</p>
            </footer>
          </div>
        </div>
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
