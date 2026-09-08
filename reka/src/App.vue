<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Database, Download, ImageUp, Pencil, Plus, RotateCcw, Trash2 } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import AppSidebar from '@/components/AppSidebar.vue'
import CocModuleLibrary from '@/components/CocModuleLibrary.vue'
import AuthDialog from '@/components/AuthDialog.vue'
import DirectChatStage from '@/components/DirectChatStage.vue'
import GroupChatStage from '@/components/GroupChatStage.vue'
import TrpgCompletionStage from '@/components/TrpgCompletionStage.vue'
import ModelApiManagerDialog from '@/components/ModelApiManagerDialog.vue'
import TrpgCharacterBindingDialog from '@/components/TrpgCharacterBindingDialog.vue'
import TrpgToolsDialog from '@/components/TrpgToolsDialog.vue'
import DicePlayerDialog from '@/dice/components/DicePlayerDialog.vue'
import WorldHome from '@/components/WorldHome.vue'
import WorldLibrary from '@/components/WorldLibrary.vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import NoticeToast from '@/components/ui/NoticeToast.vue'
import { api, uploadImage } from '@/api/client'
import type {
  TrpgCompletionReport, CharacterTemplate, DiceRollAggregate, TrpgGameTimePeriod, UserInfo, UserWorld, WorldArchive, WorldArchiveReplaceResult, WorldDetail,
  WorldTemplate, WorldTemplateUsage,
} from '@/api/types'
import { useDirectChat } from '@/composables/useDirectChat'
import { errorMessage, notify } from '@/composables/useNotice'
import { useWorkspace } from '@/composables/useWorkspace'
import { canCreateTrpgRun, hasMissingBindings, toggleParticipantSelection } from '@/components/trpgSetupState'
import type { CharacterCardCreationMethod } from '@/components/trpgSetupState'
import { mergeAutoAdvanceDiceSummaryIds } from '@/components/trpgTurnExperiments'
import { mergeGenerationResponseEvents } from '@/components/generationErrorFormatting'
import {
  DICE_SKIN_OPTIONS,
  createDicePostRollPlaybackPlan,
  createDiceInitialAnimationPlan,
  createIncomingDiceMessagePlaybackRequest,
  createDiceMessagePlaybackRequest,
  findDiceMessageElement,
  isDiceAggregatePending,
  planIncomingDicePlayback,
  resolveDiceSkin,
  shouldOfferDiceContinue,
  shouldOfferDiceContinueOnOpen,
  splitDiceAggregateByRound,
  type DiceSkin,
  type DicePlaybackMode,
  type DicePlaybackRequest,
} from '@/dice/domain/dicePlayback'

interface FavorabilityRow { id: string; threshold?: number; prompt: string }

const workspace = useWorkspace()
const direct = useDirectChat({ world: workspace.selectedWorld, characters: workspace.characters, reloadCharacters: workspace.reloadCharacters })
const authOpen = ref(!workspace.isLoggedIn.value)
const view = ref<'library' | 'modules' | 'world' | 'group' | 'direct'>('library')
const completionTranscript = ref(false)
const completionReport = ref<TrpgCompletionReport | null>(null)
const completionLoading = ref(false)
const completionBusy = ref(false)
const completionError = ref('')
const completionAvailable = computed(() => {
  const conversation = workspace.selectedConversation.value
  return Boolean(conversation?.completionStatus && (conversation.completionStatus !== 'requested'
    || workspace.currentTurn.value?.status === 'completed'))
})
watch(() => workspace.selectedConversationId.value, () => { completionTranscript.value = false; completionReport.value = null; completionError.value = '' })
watch(() => `${workspace.selectedConversationId.value}:${completionAvailable.value}:${workspace.selectedConversation.value?.completionStatus}`, () => {
  if (completionAvailable.value) { completionTranscript.value = false; void loadCompletion() }
})
async function loadCompletion() {
  const id = workspace.selectedConversationId.value
  if (!id || !completionAvailable.value) return
  completionLoading.value = true
  completionError.value = ''
  try {
    const report = await api.completionReport(id)
    if (workspace.selectedConversationId.value === id) completionReport.value = report
  } catch (error) {
    if (workspace.selectedConversationId.value === id) completionError.value = errorMessage(error)
  } finally { if (workspace.selectedConversationId.value === id) completionLoading.value = false }
}
async function retryCompletion() {
  if (completionBusy.value || workspace.loading.sending) return
  completionBusy.value = true; completionError.value = ''
  try {
    await workspace.startTrpgTurn()
    await loadCompletion()
  } finally { completionBusy.value = false }
}
function archiveCompletion() { view.value = 'world' }
async function skipCompletion() {
  if (completionBusy.value || workspace.loading.sending) return
  completionBusy.value = true
  try { await workspace.closeConversation(); view.value = 'world' }
  catch (error) { completionError.value = errorMessage(error) }
  finally { completionBusy.value = false }
}
const completionFailed = computed(() => ['failed', 'blocked'].includes(workspace.currentTurn.value?.status || ''))
const dialogs = reactive({ world: false, template: false, templatePreview: false, templateDelete: false, templateReplaceConfirm: false, conversation: false, trpgBinding: false, character: false, characterTemplate: false, characterEdit: false, settings: false, save: false, worldLoad: false, account: false, password: false, modelApis: false, end: false, deleteConversation: false, trpgTools: false })
const busy = ref(false)
const canRetryGenerationFailure = computed(() => {
  const failure = workspace.generationFailure.value
  const conversation = workspace.selectedConversation.value
  const turn = workspace.currentTurn.value
  return Boolean(failure?.detail.retryable
    && failure.conversationId === conversation?.id
    && conversation?.mode === 'trpg'
    && (turn?.status === 'failed' || turn?.status === 'blocked'))
})
function formatDebugValue(value: unknown) {
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}
const uploading = ref<'world' | 'character' | null>(null)
const templateMode = ref<'create' | 'edit'>('create')
const characterTemplateMode = ref<'create' | 'edit'>('create')
const editingCharacterTemplateId = ref<number | null>(null)
const worldForm = reactive({ worldId: '', name: '', acitvePushStatus: true, dailyCompanionMode: true, favorSystemStatus: 'NORMAL', thinkStatus: true, addSpecialPrompt: false, eotDetectionStatus: false })
const templateForm = reactive<WorldTemplate>({ name: '', author: '', image: '', background: '', visible: true })
const conversationForm = reactive({ title: '', mode: 'chat' as 'chat' | 'trpg', moduleId: '', characterIds: [] as number[] })
const conversationStep = ref<1 | 2>(1)
const conversationPreviewCharacterId = ref<number | null>(null)
const characterChoice = ref('')
const characterPrompt = ref('')
const characterChoicePreview = ref<CharacterTemplate | null>(null)
const characterPreviewLoading = ref(false)
const dicePlayerOpen = ref(false)
const dicePlaybackRequest = ref<DicePlaybackRequest | null>(null)
const diceMessageAggregate = ref<DiceRollAggregate | null>(null)
const queuedDiceAggregates = ref<DiceRollAggregate[]>([])
const diceShowContinue = ref(false)
const trpgAutoAdvance = ref(false)
const trpgDirectionEnabled = ref(false)
const trpgInvestigatorDirection = ref('')
const autoAdvanceDiceSummaryIds = ref<Set<number>>(new Set())
const diceAutoContinue = computed(() => {
  const summaryId = diceMessageAggregate.value?.summary.id
  return trpgAutoAdvance.value
    && summaryId != null
    && autoAdvanceDiceSummaryIds.value.has(summaryId)
})
const trpgToolsCardId = ref<number | null>(null)
const trpgBindingTargetKey = ref<string | null>(null)
const trpgBindingCreationMethod = ref<CharacterCardCreationMethod | null>(null)
const returnToTrpgToolsAfterBinding = ref(false)

function createMessagePlaybackRequest(
  aggregate: DiceRollAggregate,
  mode: DicePlaybackMode,
  autoPlay = false,
  offerContinueAfterComplete = false,
): DicePlaybackRequest {
  const previousId = dicePlaybackRequest.value?.id || 0
  const request = createDiceMessagePlaybackRequest(
    previousId,
    aggregate,
    workspace.userInfo.value?.diceSkin,
  )
  return { ...request, mode, autoPlay, offerContinueAfterComplete }
}

function openDiceMessage(aggregate: DiceRollAggregate) {
  try {
    const pending = isDiceAggregatePending(aggregate)
    diceMessageAggregate.value = aggregate
    queuedDiceAggregates.value = []
    diceShowContinue.value = false
    dicePlaybackRequest.value = createMessagePlaybackRequest(
      aggregate,
      pending ? 'pending' : 'settled',
      false,
      pending,
    )
    dicePlayerOpen.value = true
  } catch (error) {
    notify('无法打开骰子结果', errorMessage(error), 'danger')
  }
}

function openIncomingDiceMessages(incoming: DiceRollAggregate[]) {
  try {
    autoAdvanceDiceSummaryIds.value = mergeAutoAdvanceDiceSummaryIds(
      autoAdvanceDiceSummaryIds.value,
      incoming,
      trpgAutoAdvance.value,
    )
    const current = dicePlayerOpen.value ? diceMessageAggregate.value : null
    const plan = planIncomingDicePlayback(
      current,
      dicePlayerOpen.value ? queuedDiceAggregates.value : [],
      incoming,
    )
    if (!plan.current) throw new Error('这些骰子消息没有可显示的轮次')
    diceMessageAggregate.value = plan.current
    queuedDiceAggregates.value = plan.queued
    if (current) {
      if (dicePlaybackRequest.value) {
        dicePlaybackRequest.value = {
          ...dicePlaybackRequest.value,
          offerContinueAfterComplete: true,
        }
        diceShowContinue.value = shouldOfferDiceContinueOnOpen(
          dicePlaybackRequest.value,
          current.summary.status,
          isDiceAggregatePending(current),
          plan.queued.length > 0,
        )
      }
      return
    }
    const request = createIncomingDiceMessagePlaybackRequest(
      dicePlaybackRequest.value?.id || 0,
      plan.current,
      workspace.userInfo.value?.diceSkin,
    )
    dicePlaybackRequest.value = request
    diceShowContinue.value = shouldOfferDiceContinueOnOpen(
      request,
      plan.current.summary.status,
      isDiceAggregatePending(plan.current),
      plan.queued.length > 0,
    )
    dicePlayerOpen.value = true
  } catch (error) {
    notify('无法打开骰子结果', errorMessage(error), 'danger')
  }
}

async function locateDiceMessage(messageId: number) {
  dialogs.trpgTools = false
  await nextTick()
  const viewport = workspace.messageScroller.value
  if (!viewport) return
  const target = findDiceMessageElement(
    viewport.querySelectorAll<HTMLElement>('.chat-message'),
    messageId,
  )
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function openTrpgTools() {
  trpgToolsCardId.value = null
  dialogs.trpgTools = true
}

async function openTrpgCharacterCardCreation(targetKey: string, method: CharacterCardCreationMethod) {
  trpgToolsCardId.value = null
  trpgBindingTargetKey.value = targetKey
  trpgBindingCreationMethod.value = method
  returnToTrpgToolsAfterBinding.value = true
  dialogs.trpgTools = false
  await nextTick()
  dialogs.trpgBinding = true
}

async function handleTrpgBindingVisibility(visible: boolean) {
  if (visible || !returnToTrpgToolsAfterBinding.value) return
  returnToTrpgToolsAfterBinding.value = false
  trpgBindingTargetKey.value = null
  trpgBindingCreationMethod.value = null
  await nextTick()
  dialogs.trpgTools = true
}

function openTrpgCharacterCard(cardId: number) {
  trpgToolsCardId.value = cardId
  dialogs.trpgTools = true
}

async function rollDiceMessage() {
  const aggregate = diceMessageAggregate.value
  if (!aggregate) return
  const latestRound = Math.max(1, ...aggregate.results.map((detail) => detail.roundNo || 1))
  const pendingResult = aggregate.results.find((detail) => (
    (detail.roundNo || 1) === latestRound
    && !detail.resolvedAt
    && detail.characterId == null
  ))
  if (!pendingResult) return
  const offerContinueAfterComplete = dicePlaybackRequest.value?.offerContinueAfterComplete === true
  try {
    const progress = await api.rollDiceResult(pendingResult.id)
    const refreshed = await workspace.refreshDiceRoll(aggregate.summary.id)
    const plan = createDicePostRollPlaybackPlan(refreshed, progress.rolledResult.id)
    const queuedOtherMessages = queuedDiceAggregates.value.filter(
      (queued) => queued.summary.id !== aggregate.summary.id,
    )
    diceMessageAggregate.value = plan.playbackAggregate
    queuedDiceAggregates.value = plan.queuedAggregate
      ? [...splitDiceAggregateByRound(plan.queuedAggregate), ...queuedOtherMessages]
      : queuedOtherMessages
    const playbackRequest = createMessagePlaybackRequest(
      plan.playbackAggregate,
      'play',
      true,
      offerContinueAfterComplete,
    )
    dicePlaybackRequest.value = {
      ...playbackRequest,
      initialAnimation: createDiceInitialAnimationPlan(playbackRequest, plan.rolledGroupIndex),
    }
  } catch (error) {
    notify('投掷失败', errorMessage(error), 'danger')
    dicePlaybackRequest.value = createMessagePlaybackRequest(
      aggregate,
      'pending',
      false,
      offerContinueAfterComplete,
    )
  }
}

function completeDiceMessageRoll() {
  const aggregate = diceMessageAggregate.value
  if (!aggregate) return
  diceShowContinue.value = shouldOfferDiceContinue(
    dicePlaybackRequest.value,
    aggregate.summary.status,
    isDiceAggregatePending(aggregate),
    queuedDiceAggregates.value.length > 0,
  )
}

async function continueAfterDice() {
  const completedSummaryId = diceMessageAggregate.value?.summary.id
  if (completedSummaryId != null) {
    const remainingAutoDice = new Set(autoAdvanceDiceSummaryIds.value)
    remainingAutoDice.delete(completedSummaryId)
    autoAdvanceDiceSummaryIds.value = remainingAutoDice
  }
  const [queued, ...remaining] = queuedDiceAggregates.value
  if (queued) {
    queuedDiceAggregates.value = remaining
    diceMessageAggregate.value = queued
    const pending = isDiceAggregatePending(queued)
    const request = createMessagePlaybackRequest(
      queued,
      pending ? 'pending' : 'play',
      !pending,
      true,
    )
    dicePlaybackRequest.value = request
    diceShowContinue.value = shouldOfferDiceContinueOnOpen(
      request,
      queued.summary.status,
      pending,
      remaining.length > 0,
    )
    return
  }
  dicePlayerOpen.value = false
  diceShowContinue.value = false
  await workspace.startTrpgTurn()
}
function cancelDiceAutoAdvance() {
  trpgAutoAdvance.value = false
  autoAdvanceDiceSummaryIds.value = new Set()
}
async function startTrpgTurnWithExperiments(direction?: string) {
  const startedBetweenTurns = workspace.currentTurn.value == null
    || workspace.currentTurn.value.status === 'completed'
  const succeeded = await workspace.startTrpgTurn(direction)
  if (!succeeded) {
    trpgAutoAdvance.value = false
    return
  }
  if (startedBetweenTurns) {
    trpgDirectionEnabled.value = false
    trpgInvestigatorDirection.value = ''
  }
}
watch(() => workspace.selectedConversationId.value, () => {
  trpgAutoAdvance.value = false
  trpgDirectionEnabled.value = false
  trpgInvestigatorDirection.value = ''
  autoAdvanceDiceSummaryIds.value = new Set()
})
const characterPickerOpen = ref(false)
const characterPickerPhase = ref<'closed' | 'moving' | 'expanded'>('closed')
const characterTemplateForm = reactive<CharacterTemplate>({ name: '', image: '', background: '', personality: '', cocPlayStyle: '', initFavor: 0, favorability: {} })
const favorabilityRows = ref<FavorabilityRow[]>([])
const selectedCharacterId = ref<number | null>(null)
const characterEditForm = reactive({ prompt: '', favor: 0 })
const settingsForm = reactive({ name: '', acitvePushStatus: false, favorSystemStatus: 'NORMAL', eotDetectionStatus: true })
const detailForm = reactive<WorldDetail>({ about: '', details: '' })
const settingsTab = ref<'general' | 'lore' | 'data'>('general')
const detailComposerOpen = ref(false)
const detailComposerPhase = ref<'closed' | 'moving' | 'expanded'>('closed')
const templateReturnToSettings = ref(false)
const saveRemark = ref('')
const accountForm = reactive({ username: '', email: '', birthday: '', diceSkin: 'classic' as DiceSkin })
const passwordForm = reactive({ email: '', newPassword: '', confirmPassword: '', code: '' })
const selectedTemplatePreview = ref<WorldTemplate | null>(null)
const selectedTemplateUsage = ref<WorldTemplateUsage | null>(null)
const pendingTemplateReplacement = ref<WorldArchive | null>(null)
const templateReplacementReport = ref<WorldArchiveReplaceResult | null>(null)

const availableTemplates = computed(() => workspace.characterTemplates.value.filter((template) => template.id && !workspace.characters.value.some((character) => character.characterId === template.id)))
const selectedConversationFormModule = computed(() => workspace.modules.value.find((item) => item.id === Number(conversationForm.moduleId)) || null)
const selectedConversationModule = computed(() => workspace.modules.value.find((item) => item.id === workspace.selectedConversation.value?.moduleId) || null)
const conversationPreviewCharacter = computed(() => workspace.characters.value.find((item) => item.characterId === conversationPreviewCharacterId.value) || null)
const selectedCharacter = computed(() => workspace.characters.value.find((item) => item.characterId === selectedCharacterId.value) || null)
const templateDialogTitle = computed(() => templateMode.value === 'edit' ? '修改世界模板' : '创建世界模板')
const templateDialogDescription = computed(() => templateMode.value === 'edit'
  ? '修改原创模板的封面、背景和公开状态。'
  : '创建可复用的世界模板；公开后其他用户可以发现并使用。')
const characterTemplateDialogTitle = computed(() => characterTemplateMode.value === 'edit' ? '修改角色模板' : '创建角色模板')
const settingsDialogClass = computed(() => settingsTab.value === 'lore' && detailComposerPhase.value !== 'closed'
  ? `settings-dialog settings-dialog-${detailComposerPhase.value}`
  : 'settings-dialog')
const characterDialogClass = computed(() => characterPickerPhase.value === 'closed'
  ? 'character-dialog'
  : `character-dialog character-dialog-${characterPickerPhase.value}`)
const ownsSelectedTemplate = computed(() => Boolean(
  selectedTemplatePreview.value?.authorId && selectedTemplatePreview.value.authorId === workspace.session.id,
))

let characterPickerTransition = 0

watch(() => workspace.isLoggedIn.value, (loggedIn) => { authOpen.value = !loggedIn; if (!loggedIn) { direct.close(); view.value = 'library' } })
watch(() => worldForm.thinkStatus, (thinking) => { if (thinking) worldForm.eotDetectionStatus = false; else worldForm.addSpecialPrompt = false })
watch(settingsTab, (tab) => { if (tab !== 'lore') resetDetailComposer() })
watch(
  () => workspace.incomingDiceRolls.value.length,
  (count) => {
    if (!count) return
    openIncomingDiceMessages(workspace.incomingDiceRolls.value.splice(0, count))
  },
  { flush: 'post' },
)
watch(() => dialogs.template, async (open, wasOpen) => {
  if (open || !wasOpen || !templateReturnToSettings.value) return
  templateReturnToSettings.value = false
  await nextTick()
  openSettings()
})

async function run(action: () => Promise<unknown>, close?: keyof typeof dialogs) {
  busy.value = true
  try { await action(); if (close) dialogs[close] = false; return true }
  catch (error) { notify('操作失败', errorMessage(error), 'danger'); return false }
  finally { busy.value = false }
}
function openConversationDelete() {
  dialogs.end = false
  dialogs.deleteConversation = true
}
async function permanentlyDeleteConversation() {
  const deleted = await run(workspace.deleteConversation, 'deleteConversation')
  if (deleted) view.value = 'world'
}
async function authenticate(payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }) { await run(async () => { await workspace.authenticate(payload); authOpen.value = false }) }
function logout() { direct.close(); workspace.logout() }
function home() { direct.close(); workspace.selectedWorldId.value = null; workspace.selectedConversationId.value = null; view.value = 'library' }
function openModuleLibrary() { direct.close(); workspace.selectedConversationId.value = null; view.value = 'modules' }
async function selectWorld(id: number) { direct.close(); await workspace.selectWorld(id); view.value = 'world' }
async function selectConversation(id: number) {
  direct.close()
  dialogs.trpgBinding = false
  trpgBindingTargetKey.value = null
  trpgBindingCreationMethod.value = null
  returnToTrpgToolsAfterBinding.value = false
  const loadingConversation = workspace.selectConversation(id)
  view.value = 'group'
  await loadingConversation
  const conversation = workspace.selectedConversation.value
  if (conversation?.mode === 'trpg' && conversation.status === 'active' && !conversation.completionStatus) {
    try {
      const cards = await api.investigatorCards(conversation.id)
      if (hasMissingBindings(workspace.participantIds.value, cards)) dialogs.trpgBinding = true
    } catch (error) {
      notify('人物卡状态读取失败', errorMessage(error), 'danger')
    }
  }
}
async function openDirectChat(id: number) {
  const loadingConversation = direct.selectCharacter(id)
  view.value = 'direct'
  await loadingConversation
}
function closeDirectChat() { direct.close(); view.value = 'world' }
async function restoreTrpg() { const id = workspace.selectedConversationId.value; if (id) await workspace.selectConversation(id) }
async function correctGameTime(dayNo: number, period: TrpgGameTimePeriod) {
  await run(() => workspace.correctGameTime(dayNo, period))
}

function openNewWorld() {
  Object.assign(worldForm, { worldId: '', name: '', acitvePushStatus: true, dailyCompanionMode: true, favorSystemStatus: 'NORMAL', thinkStatus: true, addSpecialPrompt: false, eotDetectionStatus: false })
  dialogs.world = true
}
async function openTemplatePreview(id: number) {
  await run(async () => {
    selectedTemplatePreview.value = await api.worldTemplate(id)
    selectedTemplateUsage.value = ownsSelectedTemplate.value ? await api.worldTemplateUsage(id) : null
    dialogs.templatePreview = true
  })
}
function createFromPreview() {
  const id = selectedTemplatePreview.value?.id; if (!id) return
  openNewWorld(); worldForm.worldId = String(id); dialogs.templatePreview = false
}
function openNewConversation() {
  Object.assign(conversationForm, { title: '', mode: 'chat', moduleId: '', characterIds: [] })
  conversationStep.value = 1
  conversationPreviewCharacterId.value = null
  dialogs.conversation = true
}
function setConversationMode(mode: 'chat' | 'trpg') {
  conversationForm.mode = mode
  conversationStep.value = 1
  conversationPreviewCharacterId.value = null
  conversationForm.characterIds = []
}
function toggleConversationParticipant(characterId: number) {
  const next = toggleParticipantSelection(
    conversationForm.characterIds,
    conversationPreviewCharacterId.value,
    characterId,
  )
  conversationForm.characterIds = next.selectedIds
  conversationPreviewCharacterId.value = next.previewId
}
async function createNormalConversation() {
  const success = await run(() => workspace.createConversation({
    title: conversationForm.title,
    mode: 'chat',
    characterIds: conversationForm.characterIds,
  }))
  if (!success) return
  dialogs.conversation = false
  view.value = 'group'
}
async function createTrpgConversation() {
  const success = await run(() => workspace.createConversation({
    title: conversationForm.title,
    mode: 'trpg',
    moduleId: Number(conversationForm.moduleId),
    characterIds: conversationForm.characterIds,
  }))
  if (!success) return
  dialogs.conversation = false
  trpgBindingTargetKey.value = null
  trpgBindingCreationMethod.value = null
  returnToTrpgToolsAfterBinding.value = false
  dialogs.trpgBinding = true
}
function completeTrpgBinding() {
  dialogs.trpgBinding = false
  view.value = 'group'
}
function clearCharacterPicker() {
  characterChoice.value = ''
  characterPrompt.value = ''
  characterChoicePreview.value = null
  characterPreviewLoading.value = false
}
function resetCharacterPicker() {
  characterPickerTransition += 1
  characterPickerOpen.value = false
  characterPickerPhase.value = 'closed'
  clearCharacterPicker()
}
function openAddCharacter() { resetCharacterPicker(); dialogs.character = true }
async function expandCharacterPicker() {
  if (characterPickerPhase.value !== 'closed') return
  const transition = ++characterPickerTransition
  characterPickerPhase.value = 'moving'
  await new Promise((resolve) => setTimeout(resolve, 180))
  if (transition !== characterPickerTransition || !dialogs.character || !characterChoice.value) return
  characterPickerOpen.value = true
  characterPickerPhase.value = 'expanded'
}
async function collapseCharacterPicker() {
  const transition = ++characterPickerTransition
  characterPickerOpen.value = false
  characterPreviewLoading.value = false
  if (characterPickerPhase.value === 'closed') { clearCharacterPicker(); return }
  characterPickerPhase.value = 'moving'
  await new Promise((resolve) => setTimeout(resolve, 180))
  if (transition !== characterPickerTransition) return
  characterPickerPhase.value = 'closed'
  clearCharacterPicker()
}
async function toggleCharacterChoice(template: CharacterTemplate) {
  if (!template.id) return
  const id = String(template.id)
  if (characterChoice.value === id) { await collapseCharacterPicker(); return }

  characterChoice.value = id
  characterPrompt.value = ''
  characterChoicePreview.value = template
  if (characterPickerPhase.value === 'closed') void expandCharacterPicker()
  if (!workspace.canEditSelectedWorld.value) return

  characterPreviewLoading.value = true
  try {
    const detail = await workspace.loadEditableCharacterTemplate(template.id)
    if (characterChoice.value === id) characterChoicePreview.value = { ...template, ...detail }
  } catch (error) {
    if (characterChoice.value === id) notify('角色资料加载失败', errorMessage(error), 'danger')
  } finally {
    if (characterChoice.value === id) characterPreviewLoading.value = false
  }
}
function openCharacter(id: number) {
  const item = workspace.characters.value.find((character) => character.characterId === id); if (!item) return
  selectedCharacterId.value = id; characterEditForm.prompt = item.userInfoPrompt || ''; characterEditForm.favor = item.favorValue || 0; dialogs.characterEdit = true
}
function openSettings() {
  const world = workspace.selectedWorld.value; if (!world) return
  Object.assign(settingsForm, { name: world.name, acitvePushStatus: world.acitvePushStatus ?? true, favorSystemStatus: world.favorSystemStatus || 'NORMAL', eotDetectionStatus: world.eotDetectionStatus !== false })
  settingsTab.value = 'general'
  resetDetailComposer()
  dialogs.settings = true
}
function resetTemplateForm() { Object.assign(templateForm, { name: '', author: workspace.session.username || '', image: '', background: '', visible: true }) }
function openCreateTemplate() { templateReturnToSettings.value = false; templateMode.value = 'create'; resetTemplateForm(); dialogs.template = true }
async function openEditTemplate() {
  await run(async () => { templateMode.value = 'edit'; Object.assign(templateForm, await workspace.loadEditableWorldTemplate()); templateReturnToSettings.value = true; dialogs.settings = false; dialogs.template = true })
}
function resetCharacterTemplateForm() {
  Object.assign(characterTemplateForm, { name: '', image: '', background: '', personality: '', cocPlayStyle: '', initFavor: 0, favorability: {} }); favorabilityRows.value = []
}
function fillCharacterTemplateForm(template: CharacterTemplate) {
  resetCharacterTemplateForm()
  Object.assign(characterTemplateForm, template, { cocPlayStyle: template.cocPlayStyle || '' })
  favorabilityRows.value = Object.entries(template.favorability || {}).map(([threshold, prompt]) => ({ id: `${threshold}-${crypto.randomUUID?.() || Date.now()}`, threshold: Number(threshold), prompt }))
}
function openCreateCharacterTemplate() { characterTemplateMode.value = 'create'; editingCharacterTemplateId.value = null; resetCharacterTemplateForm(); dialogs.character = false; dialogs.characterTemplate = true }
async function openEditCharacterTemplate(id: number) {
  await run(async () => { characterTemplateMode.value = 'edit'; editingCharacterTemplateId.value = id; fillCharacterTemplateForm(await workspace.loadEditableCharacterTemplate(id)); dialogs.characterTemplate = true; dialogs.characterEdit = false })
}
function addFavorabilityRow() { favorabilityRows.value.push({ id: crypto.randomUUID?.() || String(Date.now() + Math.random()), threshold: undefined, prompt: '' }) }
function removeFavorabilityRow(id: string) { favorabilityRows.value = favorabilityRows.value.filter((item) => item.id !== id) }
function characterTemplatePayload(): CharacterTemplate {
  const favorability: Record<string, string> = {}
  favorabilityRows.value.forEach((row) => { if (typeof row.threshold === 'number' && row.prompt.trim()) favorability[String(row.threshold)] = row.prompt.trim() })
  return { ...characterTemplateForm, name: characterTemplateForm.name.trim(), image: characterTemplateForm.image?.trim(), background: characterTemplateForm.background?.trim(), personality: characterTemplateForm.personality?.trim(), cocPlayStyle: characterTemplateForm.cocPlayStyle?.trim() || undefined, favorability: Object.keys(favorability).length ? favorability : undefined }
}
async function saveCharacterTemplate() {
  const payload = characterTemplatePayload()
  if (characterTemplateMode.value === 'edit' && editingCharacterTemplateId.value) await workspace.updateCharacterTemplate(editingCharacterTemplateId.value, payload)
  else await workspace.createCharacterTemplate(payload)
}
async function saveSelectedCharacter() {
  const id = selectedCharacterId.value; if (!id) return
  await workspace.updateCharacter(id, characterEditForm.prompt, workspace.canEditSelectedWorld.value ? characterEditForm.favor : undefined)
}
async function removeSelectedCharacter() { const id = selectedCharacterId.value; if (id) await workspace.removeCharacter(id) }
async function removeDetail(id?: number) { if (id) await workspace.removeDetail(id) }
function clearDetailForm() {
  detailForm.about = ''
  detailForm.details = ''
}
function resetDetailComposer() {
  detailComposerOpen.value = false
  detailComposerPhase.value = 'closed'
  clearDetailForm()
}
async function openDetailComposer() {
  if (detailComposerPhase.value !== 'closed') return
  detailComposerPhase.value = 'moving'
  await new Promise((resolve) => setTimeout(resolve, 180))
  if (!dialogs.settings || settingsTab.value !== 'lore') { resetDetailComposer(); return }
  detailComposerOpen.value = true
  detailComposerPhase.value = 'expanded'
}
async function cancelDetailComposer() {
  detailComposerOpen.value = false
  clearDetailForm()
  if (detailComposerPhase.value === 'closed') return
  detailComposerPhase.value = 'moving'
  await new Promise((resolve) => setTimeout(resolve, 180))
  if (!detailComposerOpen.value) detailComposerPhase.value = 'closed'
}
async function addWorldDetail() {
  const success = await run(() => workspace.addDetail({ ...detailForm }))
  if (success) await cancelDetailComposer()
}
async function saveTemplate() {
  const payload = { ...templateForm, name: templateForm.name.trim(), background: templateForm.background?.trim() }
  if (templateMode.value === 'edit') await workspace.updateTemplate(payload); else await workspace.createTemplate(payload)
}
async function handleImage(event: Event, target: 'world' | 'character') {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''; if (!file) return
  uploading.value = target
  try { const url = await uploadImage(file); if (target === 'world') templateForm.image = url; else characterTemplateForm.image = url; notify('图片已上传', file.name, 'success') }
  catch (error) { notify('图片上传失败', errorMessage(error), 'danger') }
  finally { uploading.value = null }
}
async function importWorld(file: File) { await run(async () => { const archive = JSON.parse(await file.text()) as WorldArchive; const result = await api.importWorld(archive); await workspace.loadTemplates(); notify('模板导入完成', `${result.name} · ${result.characterCount} 位角色`, 'success') }) }
async function finishTemplateReplacement(result: WorldArchiveReplaceResult) {
  await workspace.loadTemplates()
  selectedTemplatePreview.value = await api.worldTemplate(result.worldId)
  selectedTemplateUsage.value = await api.worldTemplateUsage(result.worldId)
  notify('世界模板已替换', `${result.matchedCharacterCount} 位角色已更新，${result.addedCharacterCount} 位角色已新增`, 'success')
}
async function replaceTemplateFromFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !selectedTemplatePreview.value?.id || !ownsSelectedTemplate.value) return
  await run(async () => {
    const archive = JSON.parse(await file.text()) as WorldArchive
    const result = await api.replaceWorldTemplate(selectedTemplatePreview.value!.id!, archive)
    if (result.confirmationRequired) {
      pendingTemplateReplacement.value = archive
      templateReplacementReport.value = result
      dialogs.templatePreview = false
      dialogs.templateReplaceConfirm = true
      return
    }
    await finishTemplateReplacement(result)
  })
}
async function confirmTemplateReplacement() {
  const id = selectedTemplatePreview.value?.id
  const archive = pendingTemplateReplacement.value
  if (!id || !archive) return
  const success = await run(async () => {
    const result = await api.replaceWorldTemplate(id, archive, true)
    await finishTemplateReplacement(result)
  })
  if (success) {
    dialogs.templateReplaceConfirm = false
    dialogs.templatePreview = true
    pendingTemplateReplacement.value = null
    templateReplacementReport.value = null
  }
}
function cancelTemplateReplacement() {
  dialogs.templateReplaceConfirm = false
  dialogs.templatePreview = true
  pendingTemplateReplacement.value = null
  templateReplacementReport.value = null
}
function openTemplateDeleteConfirmation() {
  dialogs.templatePreview = false
  dialogs.templateDelete = true
}
function cancelTemplateDelete() {
  dialogs.templateDelete = false
  dialogs.templatePreview = true
}
async function deleteSelectedTemplate() {
  const id = selectedTemplatePreview.value?.id
  if (!id || !selectedTemplateUsage.value?.deletable) return
  const success = await run(async () => {
    await api.deleteWorldTemplate(id)
    await workspace.loadTemplates()
    notify('世界模板已删除', selectedTemplatePreview.value?.name || '', 'success')
  })
  if (success) {
    dialogs.templateDelete = false
    dialogs.templatePreview = false
    selectedTemplatePreview.value = null
    selectedTemplateUsage.value = null
  }
}
async function exportWorld() {
  const id = workspace.selectedWorldId.value; if (!id) return
  await run(async () => { const text = await api.exportWorld(id); const blob = new Blob([text], { type: 'application/json' }); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = `${workspace.selectedWorld.value?.name || 'galchat-world'}.json`; link.click(); URL.revokeObjectURL(link.href) })
}
async function openAccount() { await run(async () => { await workspace.loadUserInfo(); const info = workspace.userInfo.value; Object.assign(accountForm, { username: info?.username || '', email: info?.email || '', birthday: info?.birthday || '', diceSkin: resolveDiceSkin(info?.diceSkin) }); dialogs.account = true }) }
async function openPassword() { await run(async () => { await workspace.loadUserInfo(); Object.assign(passwordForm, { email: workspace.userInfo.value?.email || '', newPassword: '', confirmPassword: '', code: '' }); dialogs.password = true }) }
async function sendPasswordCode() { await run(async () => { await api.sendPasswordCode(passwordForm.email); notify('验证码已发送', '', 'success') }) }
async function changePassword() {
  if (passwordForm.newPassword !== passwordForm.confirmPassword) throw new Error('两次输入的新密码不一致')
  await workspace.changePassword({ email: passwordForm.email, newPassword: passwordForm.newPassword, verificationCode: passwordForm.code })
}
</script>

<template>
  <div v-if="workspace.isLoggedIn.value" class="app-shell">
    <AppSidebar :session="workspace.session" :worlds="workspace.worlds.value" :characters="workspace.characters.value" :conversations="workspace.conversations.value" :selected-world-id="workspace.selectedWorldId.value" :selected-character-id="view === 'direct' ? direct.selectedCharacter.value?.characterId || null : null" :selected-conversation-id="view === 'group' ? workspace.selectedConversationId.value : null" :module-library-active="view === 'modules'" :loading="workspace.loading.worlds" @home="home" @modules="openModuleLibrary" @select-world="selectWorld" @select-direct="openDirectChat" @select-conversation="selectConversation" @new-world="openNewWorld" @account="openAccount" @password="openPassword" @models="dialogs.modelApis = true" @logout="logout" />
    <div class="app-content">
      <WorldLibrary v-if="view === 'library'" :worlds="workspace.worlds.value" :templates="workspace.templates.value" :loading="workspace.loading.boot" @select="selectWorld" @preview-template="openTemplatePreview" @create-world="openNewWorld" @create-template="openCreateTemplate" @import-world="importWorld" />
      <CocModuleLibrary v-else-if="view === 'modules'" @changed="workspace.loadModules" />
      <WorldHome v-else-if="view === 'world' && workspace.selectedWorld.value" :world="workspace.selectedWorld.value" :characters="workspace.characters.value" :conversations="workspace.conversations.value" :world-save="workspace.worldSave.value" @open-character="openDirectChat" @edit-character="openCharacter" @open-conversation="selectConversation" @new-conversation="openNewConversation" @add-character="openAddCharacter" @save="dialogs.save = true" @load="dialogs.worldLoad = true" @settings="openSettings" />
      <DirectChatStage v-else-if="view === 'direct' && workspace.selectedWorld.value && direct.selectedCharacter.value" v-model:input="direct.input.value" v-model:scroller="direct.scroller.value" :world="workspace.selectedWorld.value" :character="direct.selectedCharacter.value" :messages="direct.messages.value" :model-apis="direct.modelApis.value" :loading="direct.loading" :can-withdraw="direct.canWithdraw.value" :has-older-messages="direct.hasOlderMessages.value" @back="closeDirectChat" @send="direct.send" @withdraw="direct.withdraw" @load-earlier="direct.loadEarlier" @select-model="direct.selectModel" @edit="openCharacter(direct.selectedCharacter.value.characterId)" @focus="direct.focus" @composition="direct.setComposing" />
      <TrpgCompletionStage v-else-if="view === 'group' && completionAvailable && !completionTranscript" :key="workspace.selectedConversationId.value || 0" :report="completionReport" :loading="completionLoading" :busy="completionBusy || workspace.loading.sending" :failed="completionFailed" :skip-disabled="!!workspace.currentTurn.value && !completionFailed && workspace.currentTurn.value.status !== 'completed'" :error="completionError" @retry="retryCompletion" @skip="skipCompletion" @tools="dialogs.trpgTools = true" @reload="loadCompletion" @archive="archiveCompletion" @transcript="completionTranscript = true" @back="view = 'world'" />
      <GroupChatStage v-else-if="view === 'group' && workspace.selectedConversation.value" v-model:input="workspace.messageInput.value" v-model:inquiry-input="workspace.inquiryInput.value" v-model:composer-intent="workspace.composerIntent.value" v-model:scroller="workspace.messageScroller.value" v-model:auto-advance="trpgAutoAdvance" v-model:direction-enabled="trpgDirectionEnabled" v-model:investigator-direction="trpgInvestigatorDirection" :conversation="workspace.selectedConversation.value" :username="workspace.session.username" :messages="workspace.messages.value" :reasoning="workspace.reasoning" :characters="workspace.characters.value" :reply-plan="workspace.replyPlan.value" :reply-plans="workspace.replyPlans.value" :available-characters="workspace.availablePlanCharacters.value" :current-turn="workspace.currentTurn.value" :actor-runtimes="workspace.actorRuntimes.value" :model-apis="workspace.modelApis.value" :combat-overview="workspace.combatOverview.value" :investigator-cards="workspace.investigatorCards.value" :reply-turn-state="workspace.replyTurnState.value" :sending="workspace.loading.sending" :loading="workspace.loading.chat" :has-older-messages="workspace.hasOlderGroupMessages.value" @open-completion="completionTranscript = false; loadCompletion()" @back="view = 'world'" @save-plan="run(workspace.savePlan)" @move-plan-item="workspace.movePlanItem" @delete-plan-item="workspace.deletePlanItem" @add-plan-item="workspace.addPlanItem" @save-actor-runtime="workspace.saveActorRuntime" @load-earlier="workspace.loadOlderGroupMessages" @withdraw="run(workspace.withdrawGroupTurn)" @open-tools="openTrpgTools" @open-character-card="openTrpgCharacterCard" @open-dice="openDiceMessage" @send="workspace.sendMessage" @ask-kp="workspace.askKp" @start-turn="startTrpgTurnWithExperiments" @select-scene="workspace.selectSceneOption" @end-exploration="workspace.endExploration" @correct-time="correctGameTime" @end="dialogs.end = true" />
    </div>
  </div>
  <div v-else class="signed-out"><span class="brand-glyph large">✦</span><h1>GalChat</h1><p>一个安静的角色与群像叙事工作台。</p><button class="button primary" @click="authOpen = true">登录或注册</button></div>

  <AuthDialog v-model="authOpen" @submit="authenticate" />

  <BaseDialog v-model="dialogs.world" title="创建世界" description="从一个模板开始，并设定角色的陪伴方式。" size="lg">
    <div class="form-stack"><label class="field"><span>世界模板</span><select v-model="worldForm.worldId"><option value="">请选择</option><option v-for="item in workspace.templates.value" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="field"><span>世界名称</span><input v-model.trim="worldForm.name" placeholder="留空则使用模板名称" /></label><div class="field"><span>好感变化幅度</span><div class="segmented"><button v-for="item in ['EASY','NORMAL','HARD']" :key="item" :class="{ active: worldForm.favorSystemStatus === item }" @click="worldForm.favorSystemStatus = item">{{ {EASY:'较易提升',NORMAL:'标准',HARD:'较难提升'}[item as 'EASY'] }}</button></div></div><label class="switch-row"><span><strong>主动消息偏好</strong><small>允许角色围绕聊天中提到的事件发送提醒和当日关怀；晚间主动话题不受此开关影响</small></span><input v-model="worldForm.acitvePushStatus" type="checkbox" /></label><label class="switch-row"><span><strong>日常陪伴语气</strong><small>让单聊更侧重现实日常分享与陪伴</small></span><input v-model="worldForm.dailyCompanionMode" type="checkbox" /></label><label class="switch-row"><span><strong>逐步显示思考与回复</strong><small>发送后逐步展示角色思考和回复；关闭后可连续发送消息，等待角色回应</small></span><input v-model="worldForm.thinkStatus" type="checkbox" /></label><label v-if="worldForm.thinkStatus" class="switch-row"><span><strong>以第一人称展示角色思考</strong><small>例如：“我想先听听你的意见。”</small></span><input v-model="worldForm.addSpecialPrompt" type="checkbox" /></label><label v-else class="switch-row"><span><strong>输入结束识别</strong><small>停止输入后判断表达是否完整并自动回复</small></span><input v-model="worldForm.eotDetectionStatus" type="checkbox" /></label></div>
    <template #footer><button class="button ghost" @click="dialogs.world = false">取消</button><button class="button primary" :disabled="!worldForm.worldId || busy" @click="run(() => workspace.createWorld({ ...worldForm, worldId: Number(worldForm.worldId), eotDetectionStatus: worldForm.thinkStatus ? false : worldForm.eotDetectionStatus, addSpecialPrompt: worldForm.thinkStatus && worldForm.addSpecialPrompt }), 'world')">创建</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.template" :title="templateDialogTitle" :description="templateDialogDescription" size="lg">
    <div class="form-grid"><label class="field"><span>模板名称</span><input v-model.trim="templateForm.name" /></label><label class="field"><span>作者</span><input v-model.trim="templateForm.author" /></label><label class="field full"><span>封面</span><div class="upload-row"><small>{{ templateForm.image ? '已上传封面' : '尚未上传封面' }}</small><button v-if="templateForm.image" type="button" class="button ghost" :disabled="uploading !== null" @click="templateForm.image = ''">移除封面</button><label class="button secondary file-button"><ImageUp :size="16" />{{ uploading === 'world' ? '上传中' : '上传封面' }}<input type="file" accept="image/*" :disabled="uploading !== null" @change="handleImage($event, 'world')" /></label></div></label><label class="field full"><span>世界背景</span><textarea v-model.trim="templateForm.background" rows="7" /></label><label class="switch-row full"><span><strong>公开模板</strong><small>其他用户可以发现并使用</small></span><input v-model="templateForm.visible" type="checkbox" /></label></div>
    <template #footer><button class="button ghost" @click="dialogs.template = false">取消</button><button class="button primary" :disabled="!templateForm.name || !templateForm.background || busy" @click="run(saveTemplate, 'template')">{{ templateMode === 'edit' ? '保存模板' : '创建模板' }}</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.templatePreview" :title="selectedTemplatePreview?.name || '世界模板'" :description="selectedTemplatePreview?.author ? `作者：${selectedTemplatePreview.author}` : '匿名创作者'" size="lg">
    <div v-if="selectedTemplatePreview" class="template-preview">
      <div class="template-preview-cover" :style="selectedTemplatePreview.image ? { backgroundImage: `url(${selectedTemplatePreview.image})` } : {}" />
      <div>
        <span class="eyebrow">{{ selectedTemplatePreview.visible === false ? '私有模板' : '公开模板' }}</span>
        <p>{{ selectedTemplatePreview.background || '尚未填写世界背景。' }}</p>
        <small v-if="ownsSelectedTemplate && selectedTemplateUsage && !selectedTemplateUsage.deletable" class="template-usage-note">
          已有 {{ selectedTemplateUsage.associatedWorldCount }} 个世界使用此模板，因此不能删除。可上传新版本，按角色名称匹配并更新。
        </small>
      </div>
    </div>
    <template #footer>
      <div v-if="ownsSelectedTemplate" class="template-owner-actions">
        <button
          class="button ghost danger-text"
          :disabled="!selectedTemplateUsage?.deletable || busy"
          @click="openTemplateDeleteConfirmation"
        >
          <Trash2 :size="16" />删除模板
        </button>
        <label class="button secondary file-button" :class="{ disabled: busy }">
          <RotateCcw :size="16" />上传并替换
          <input type="file" accept="application/json,.json" :disabled="busy" @change="replaceTemplateFromFile" />
        </label>
      </div>
      <div class="dialog-inline-actions">
        <button class="button ghost" @click="dialogs.templatePreview = false">关闭</button>
        <button class="button primary" @click="createFromPreview"><Plus :size="16" />使用此模板</button>
      </div>
    </template>
  </BaseDialog>

  <BaseDialog
    v-model="dialogs.templateDelete"
    title="删除世界模板"
    description="此模板及其世界设定、角色模板都会被永久删除。"
  >
    <div class="destructive-confirmation">
      <strong>确认删除“{{ selectedTemplatePreview?.name }}”？</strong>
      <p>当前模板没有关联世界。删除完成后无法恢复，也不能再使用它创建世界。</p>
    </div>
    <template #footer>
      <button class="button ghost" :disabled="busy" @click="cancelTemplateDelete">取消</button>
      <button class="button danger" :disabled="busy" @click="deleteSelectedTemplate"><Trash2 :size="16" />确认删除</button>
    </template>
  </BaseDialog>

  <BaseDialog
    v-model="dialogs.templateReplaceConfirm"
    title="角色匹配度过低"
    description="尚未替换。请核对下方角色匹配结果后再确认。"
    size="lg"
  >
    <div v-if="templateReplacementReport" class="replacement-report">
      <section class="replacement-rate">
        <span>角色匹配度</span>
        <strong>{{ Math.round(templateReplacementReport.matchRate * 100) }}%</strong>
        <small>低于 50%，请确认上传的确实是这个世界模板的新版本。</small>
      </section>
      <div class="replacement-groups">
        <section><strong>将更新 · {{ templateReplacementReport.matchedCharacterCount }}</strong><p>{{ templateReplacementReport.matchedCharacterNames.join('、') || '无' }}</p></section>
        <section><strong>将新增 · {{ templateReplacementReport.addedCharacterCount }}</strong><p>{{ templateReplacementReport.addedCharacterNames.join('、') || '无' }}</p></section>
        <section><strong>保持不变 · {{ templateReplacementReport.unchangedCharacterCount }}</strong><p>{{ templateReplacementReport.unchangedCharacterNames.join('、') || '无' }}</p></section>
      </div>
      <p class="replacement-warning">继续后会完整替换世界基本信息和世界设定；同名角色的模板资料会更新，未包含在新文件中的角色会保留。</p>
    </div>
    <template #footer>
      <button class="button ghost" :disabled="busy" @click="cancelTemplateReplacement">取消</button>
      <button class="button danger" :disabled="busy" @click="confirmTemplateReplacement"><RotateCcw :size="16" />仍然替换</button>
    </template>
  </BaseDialog>

  <BaseDialog
    v-model="dialogs.conversation"
    :title="conversationForm.mode === 'trpg' ? '建立 CoC 跑团' : '建立会话'"
    :description="conversationForm.mode === 'trpg' ? `第 ${conversationStep} 阶段，共 3 阶段` : '选择参与角色，创建多人对话。'"
    size="lg"
    :content-class="conversationForm.mode === 'trpg' && conversationStep === 2 ? 'trpg-participant-dialog' : ''"
  >
    <div v-if="conversationForm.mode === 'trpg'" class="trpg-setup-steps" aria-label="跑团创建进度">
      <span :class="{ active: conversationStep === 1, complete: conversationStep > 1 }"><b>1</b>跑团信息</span>
      <i />
      <span :class="{ active: conversationStep === 2 }"><b>2</b>选择参与者</span>
      <i />
      <span><b>3</b>分配调查员人物卡</span>
    </div>

    <div v-if="conversationStep === 1" class="form-stack">
      <div class="field"><span>模式</span><div class="segmented"><button :class="{ active: conversationForm.mode === 'chat' }" @click="setConversationMode('chat')">普通群聊</button><button :class="{ active: conversationForm.mode === 'trpg' }" @click="setConversationMode('trpg')">CoC 跑团</button></div></div>
      <label class="field"><span>标题</span><input v-model.trim="conversationForm.title" placeholder="例如：深夜图书馆" /></label>
      <label v-if="conversationForm.mode === 'trpg'" class="field"><span>跑团模组</span><select v-model="conversationForm.moduleId"><option value="">请选择可用模组</option><option v-for="item in workspace.modules.value" :key="item.id" :value="String(item.id)">{{ item.name }}{{ item.era ? ` · ${item.era}` : '' }}</option></select><small v-if="selectedConversationFormModule">{{ selectedConversationFormModule.author || '作者未标注' }} · {{ selectedConversationFormModule.playerCount || '人数未标注' }} · {{ selectedConversationFormModule.estimatedDuration || '时长未标注' }}<br />{{ selectedConversationFormModule.introduction }}</small><small v-else-if="!workspace.modules.value.length">当前没有可选的公开模组。</small></label>
      <div v-if="conversationForm.mode === 'chat'" class="field"><span>参与角色</span><div class="check-grid"><label v-for="item in workspace.characters.value" :key="item.characterId" class="check-card"><input v-model="conversationForm.characterIds" type="checkbox" :value="item.characterId" /><span class="reply-avatar" :style="item.characterImage ? { backgroundImage: `url(${item.characterImage})` } : {}">{{ item.characterImage ? '' : item.characterName.slice(0,1) }}</span><strong>{{ item.characterName }}</strong></label></div></div>
    </div>

    <div v-else class="trpg-participant-picker-layout">
      <section class="trpg-participant-list-pane">
        <header class="settings-section-heading">
          <span><strong>选择 AI 调查员</strong><small>可以不选并以单人团开始，也可以多选 AI 调查员</small></span>
          <em>{{ conversationForm.characterIds.length }} 位已选</em>
        </header>
        <div v-if="workspace.characters.value.length" class="character-choice-list trpg-participant-list" role="group" aria-label="AI 调查员角色">
          <button
            v-for="item in workspace.characters.value"
            :key="item.characterId"
            type="button"
            class="choice-row"
            :class="{ active: conversationForm.characterIds.includes(item.characterId) }"
            role="checkbox"
            :aria-checked="conversationForm.characterIds.includes(item.characterId)"
            @click="toggleConversationParticipant(item.characterId)"
          >
            <span class="character-avatar small" :style="item.characterImage ? { backgroundImage: `url(${item.characterImage})` } : {}">{{ item.characterImage ? '' : item.characterName.slice(0, 1) }}</span>
            <span><strong>{{ item.characterName }}</strong><small>{{ conversationForm.characterIds.includes(item.characterId) ? '已选择，再次点击取消' : '加入本次跑团' }}</small></span>
            <b v-if="conversationForm.characterIds.includes(item.characterId)" class="participant-selected-mark">✓</b>
          </button>
        </div>
        <div v-else class="empty-panel compact"><p>当前世界还没有可选角色。</p></div>
      </section>
      <aside class="trpg-participant-preview-pane">
        <template v-if="conversationPreviewCharacter">
          <div class="character-preview-heading">
            <span class="character-preview-image" :style="conversationPreviewCharacter.characterImage ? { backgroundImage: `url(${conversationPreviewCharacter.characterImage})` } : {}">{{ conversationPreviewCharacter.characterImage ? '' : conversationPreviewCharacter.characterName.slice(0, 1) }}</span>
            <span><small>最近选择</small><strong>{{ conversationPreviewCharacter.characterName }}</strong></span>
          </div>
          <section class="character-background-preview"><strong>当前世界中的角色资料</strong><p>{{ conversationPreviewCharacter.userInfoPrompt || '暂未记录希望角色记住的事。' }}</p></section>
          <div class="participant-preview-note"><strong>下一阶段</strong><span>跑团创建后，需要为该角色绑定一张独立的 AI 调查员人物卡。</span></div>
        </template>
        <div v-else class="binding-empty"><strong>单人团</strong><span>不选择 AI 调查员，将由玩家独自进入本次跑团。</span></div>
      </aside>
    </div>

    <template #footer>
      <button class="button ghost" :disabled="busy" @click="conversationStep === 2 ? (conversationStep = 1) : (dialogs.conversation = false)">{{ conversationStep === 2 ? '上一步' : '取消' }}</button>
      <button v-if="conversationForm.mode === 'chat'" class="button primary" :disabled="!conversationForm.title || !conversationForm.characterIds.length || busy" @click="createNormalConversation">创建并进入</button>
      <button v-else-if="conversationStep === 1" class="button primary" :disabled="!conversationForm.title || !Number(conversationForm.moduleId) || busy" @click="conversationStep = 2">下一步：选择参与者</button>
      <button v-else class="button primary" :disabled="!canCreateTrpgRun(conversationForm.title, Number(conversationForm.moduleId), busy)" @click="createTrpgConversation">创建跑团并绑定人物卡</button>
    </template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.character" title="添加角色" description="选择一个角色模板，将角色加入当前世界。" :content-class="characterDialogClass">
    <div class="character-picker-layout" :class="{ expanded: characterPickerOpen }">
      <section class="character-picker-pane">
        <header class="settings-section-heading">
          <span><strong>选择角色</strong><small>再次点击已选角色可以取消选择</small></span>
          <em>{{ availableTemplates.length }} 位</em>
        </header>
        <div v-if="availableTemplates.length" class="choice-list character-choice-list" role="radiogroup" aria-label="可添加的角色">
          <button
            v-for="item in availableTemplates"
            :key="item.id"
            type="button"
            class="choice-row"
            :class="{ active: characterChoice === String(item.id) }"
            role="radio"
            :aria-checked="characterChoice === String(item.id)"
            @click="toggleCharacterChoice(item)"
          >
            <span class="character-avatar small" :style="item.image ? { backgroundImage: `url(${item.image})` } : {}">{{ item.image ? '' : item.name.slice(0,1) }}</span>
            <span><strong>{{ item.name }}</strong><small v-if="characterChoice === String(item.id)">已选择，再次点击取消</small></span>
          </button>
        </div>
        <div v-else class="empty-panel compact"><p>没有可添加的角色模板。</p></div>
      </section>

      <aside v-if="characterPickerOpen && characterChoicePreview" class="character-preview-pane">
        <div class="character-preview-heading">
          <span class="character-preview-image" :style="characterChoicePreview.image ? { backgroundImage: `url(${characterChoicePreview.image})` } : {}">{{ characterChoicePreview.image ? '' : characterChoicePreview.name.slice(0,1) }}</span>
          <span><small>即将加入</small><strong>{{ characterChoicePreview.name }}</strong></span>
        </div>
        <section v-if="workspace.canEditSelectedWorld.value" class="character-background-preview">
          <strong>角色背景</strong>
          <p v-if="characterPreviewLoading">正在载入角色背景…</p>
          <p v-else>{{ characterChoicePreview.background || '尚未填写角色背景。' }}</p>
        </section>
        <label class="field character-memory-field"><span>希望角色记住的事</span><textarea v-model="characterPrompt" rows="6" placeholder="可以填写你的称呼、偏好或共同经历…" /></label>
      </aside>
    </div>
    <template #footer><button v-if="workspace.canEditSelectedWorld.value && !characterChoice" class="button ghost" @click="openCreateCharacterTemplate">新建角色模板</button><button class="button primary" :disabled="!characterChoice || characterPickerPhase === 'moving' || busy" @click="run(() => workspace.addCharacter(Number(characterChoice), characterPrompt), 'character')">添加</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.characterTemplate" :title="characterTemplateDialogTitle" size="lg">
    <div class="form-grid"><label class="field"><span>角色名</span><input v-model.trim="characterTemplateForm.name" /></label><label class="field"><span>初始好感</span><input v-model.number="characterTemplateForm.initFavor" type="number" min="0" max="100" /></label><label class="field full"><span>角色头像</span><div class="upload-row"><small>{{ characterTemplateForm.image ? '已上传角色头像' : '尚未上传角色头像' }}</small><button v-if="characterTemplateForm.image" type="button" class="button ghost" :disabled="uploading !== null" @click="characterTemplateForm.image = ''">移除头像</button><label class="button secondary file-button"><ImageUp :size="16" />{{ uploading === 'character' ? '上传中' : '上传角色头像' }}<input type="file" accept="image/*" :disabled="uploading !== null" @change="handleImage($event, 'character')" /></label></div></label><label class="field full"><span>背景</span><textarea v-model.trim="characterTemplateForm.background" rows="4" /></label><label class="field full"><span>性格</span><textarea v-model.trim="characterTemplateForm.personality" rows="4" /></label><label class="field full"><span>CoC 跑团偏好</span><textarea v-model.trim="characterTemplateForm.cocPlayStyle" rows="4" placeholder="例如：倾向优先调查无人探索的地点；遇到明显危险时更愿意与同伴结伴。" /></label><div class="field full"><span>好感阶段的角色表现</span><div class="favorability-list"><div v-for="row in favorabilityRows" :key="row.id" class="favorability-row"><input v-model.number="row.threshold" type="number" min="0" max="100" placeholder="好感度" /><input v-model.trim="row.prompt" placeholder="达到该好感度时的角色表现" /><button class="icon-button" title="删除阶段" @click="removeFavorabilityRow(row.id)"><Trash2 :size="15" /></button></div></div><button class="button ghost add-row-button" @click="addFavorabilityRow"><Plus :size="15" />添加阶段</button></div></div>
    <template #footer><button class="button ghost" @click="dialogs.characterTemplate = false">取消</button><button class="button primary" :disabled="!characterTemplateForm.name || busy" @click="run(saveCharacterTemplate, 'characterTemplate')">{{ characterTemplateMode === 'edit' ? '保存模板' : '创建模板' }}</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.characterEdit" :title="selectedCharacter?.characterName ? `${selectedCharacter.characterName} · 角色设置` : '角色设置'" description="这些设置会影响角色在当前世界中的表现。">
    <div class="form-stack"><label class="field"><span>好感度</span><input v-model.number="characterEditForm.favor" type="range" min="0" max="100" :disabled="!workspace.canEditSelectedWorld.value" /><output>{{ characterEditForm.favor }}</output><small v-if="!workspace.canEditSelectedWorld.value">只有世界模板的作者可以手动设置好感度。</small></label><label class="field"><span>希望角色记住的事</span><textarea v-model="characterEditForm.prompt" rows="6" placeholder="可以填写你的称呼、偏好或共同经历…" /></label><button v-if="workspace.canEditSelectedWorld.value && selectedCharacterId" class="button secondary" @click="openEditCharacterTemplate(selectedCharacterId)"><Pencil :size="16" />编辑角色模板</button></div>
    <template #footer><button class="button ghost danger-text" @click="run(removeSelectedCharacter, 'characterEdit')"><Trash2 :size="16" />移出当前世界</button><button class="button primary" :disabled="!selectedCharacterId || busy" @click="run(saveSelectedCharacter, 'characterEdit')">保存</button></template>
  </BaseDialog>

  <BaseDialog
    v-model="dialogs.settings"
    title="世界设置"
    size="lg"
    :content-class="settingsDialogClass"
  >
    <TabsRoot v-model="settingsTab" class="tabs">
      <TabsList class="tabs-list">
        <TabsTrigger value="general">常规</TabsTrigger>
        <TabsTrigger v-if="workspace.canEditSelectedWorld.value" value="lore">世界设定</TabsTrigger>
        <TabsTrigger value="data">数据</TabsTrigger>
      </TabsList>

      <TabsContent value="general" class="tabs-content">
        <div class="form-stack">
          <label class="field"><span>世界名称</span><input v-model.trim="settingsForm.name" /></label>
          <div class="field"><span>好感变化幅度</span><div class="segmented"><button v-for="item in ['EASY','NORMAL','HARD']" :key="item" :class="{ active: settingsForm.favorSystemStatus === item }" @click="settingsForm.favorSystemStatus = item">{{ {EASY:'较易提升',NORMAL:'标准',HARD:'较难提升'}[item as 'EASY'] }}</button></div></div>
          <label class="switch-row"><span><strong>主动消息偏好</strong><small>允许角色围绕聊天中提到的事件发送提醒和当日关怀；晚间主动话题不受此开关影响</small></span><input v-model="settingsForm.acitvePushStatus" type="checkbox" /></label>
          <label v-if="workspace.selectedWorld.value?.thinkStatus === false" class="switch-row"><span><strong>输入结束识别</strong><small>停止输入后判断表达是否完整并自动回复</small></span><input v-model="settingsForm.eotDetectionStatus" type="checkbox" /></label>
        </div>
        <div class="dialog-inline-actions settings-general-actions"><button v-if="workspace.canEditSelectedWorld.value" class="button secondary" @click="openEditTemplate"><Pencil :size="16" />编辑世界模板</button><button class="button primary" @click="run(() => workspace.updateWorld({ ...settingsForm }))">保存设置</button></div>
      </TabsContent>

      <TabsContent v-if="workspace.canEditSelectedWorld.value" value="lore" class="tabs-content lore-tab">
        <div class="lore-layout" :class="{ expanded: detailComposerOpen }">
          <section class="lore-list-pane">
            <header class="settings-section-heading">
              <span><strong>已有设定</strong><small>用于补充这个世界的规则、地点与背景信息</small></span>
              <em>{{ workspace.details.value.length }} 条</em>
            </header>
            <div v-if="workspace.details.value.length" class="detail-list">
              <article v-for="item in workspace.details.value" :key="item.id">
                <span><strong>{{ item.about }}</strong><p>{{ item.details }}</p></span>
                <button class="icon-button" title="删除设定" aria-label="删除设定" @click="run(() => removeDetail(item.id))"><Trash2 :size="15" /></button>
              </article>
            </div>
            <div v-else class="lore-empty"><span>✦</span><strong>还没有世界设定</strong><small>添加规则、地点或背景，让角色更了解这个世界。</small></div>
            <div v-if="!detailComposerOpen" class="lore-pane-actions">
              <button class="button primary" :disabled="detailComposerPhase !== 'closed'" @click="openDetailComposer"><Plus :size="16" />添加设定</button>
            </div>
          </section>

          <aside v-if="detailComposerOpen" class="lore-compose-pane">
            <header class="settings-section-heading">
              <span><strong>添加世界设定</strong><small>主题用于概括，内容用于描述具体信息</small></span>
            </header>
            <div class="form-stack lore-compose-form">
              <label class="field"><span>主题</span><input v-model.trim="detailForm.about" autofocus placeholder="例如：城邦规则" /></label>
              <label class="field"><span>内容</span><textarea v-model.trim="detailForm.details" rows="8" maxlength="2000" placeholder="描述这项设定的具体内容（最多 2000 字）" /></label>
            </div>
            <div class="lore-compose-actions">
              <button class="button ghost" :disabled="busy" @click="cancelDetailComposer">取消</button>
              <button class="button primary" :disabled="!detailForm.about || !detailForm.details || busy" @click="addWorldDetail"><Plus :size="16" />添加</button>
            </div>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="data" class="tabs-content">
        <div class="settings-data">
          <section class="data-world-summary">
            <span class="data-summary-icon"><Database :size="20" /></span>
            <span class="data-summary-copy"><small>当前世界</small><strong>{{ workspace.selectedWorld.value?.name }}</strong><em>{{ workspace.canEditSelectedWorld.value ? '自有模板' : '他人模板' }}</em></span>
            <dl><div><dt>世界设定</dt><dd>{{ workspace.details.value.length }}</dd></div><div><dt>当前角色</dt><dd>{{ workspace.characters.value.length }}</dd></div></dl>
          </section>

          <section class="data-action-card">
            <span class="data-card-icon"><Download :size="18" /></span>
            <span class="data-card-copy"><strong>导出世界模板</strong><small v-if="workspace.canEditSelectedWorld.value">导出模板、世界设定和角色模板；聊天与存档不会包含在内。</small><small v-else>当前世界基于他人的模板创建，源模板不能在这里编辑或导出。</small></span>
            <button v-if="workspace.canEditSelectedWorld.value" class="button secondary" @click="exportWorld">导出 JSON</button>
            <span v-else class="data-status">不可导出</span>
          </section>

          <section class="data-action-card destructive">
            <span class="data-card-icon"><Trash2 :size="18" /></span>
            <span class="data-card-copy"><strong>删除当前世界</strong><small>需要先移出当前世界中的所有角色。删除后，该世界将从你的世界列表中移除。</small></span>
            <button class="button danger" @click="run(workspace.removeWorld, 'settings').then((success) => { if (success) view = 'library' })">删除世界</button>
          </section>
        </div>
      </TabsContent>
    </TabsRoot>
  </BaseDialog>

  <BaseDialog v-model="dialogs.save" :title="workspace.worldSave.value ? '覆盖世界存档' : '创建世界存档'" description="每个世界只保留一个存档；再次保存会覆盖现有存档。"><label class="field"><span>存档备注</span><textarea v-model.trim="saveRemark" rows="4" maxlength="200" placeholder="记录此刻发生了什么（最多 200 字）" /></label><template #footer><button class="button ghost" @click="dialogs.save = false">取消</button><button class="button primary" @click="run(() => workspace.saveSnapshot(saveRemark), 'save')">{{ workspace.worldSave.value ? '确认覆盖' : '创建存档' }}</button></template></BaseDialog>
  <BaseDialog v-model="dialogs.worldLoad" title="确认读取世界存档" description="读档会回滚角色、聊天、好感和世界事件，并删除存档点之后的进度。">
    <div class="restore-summary"><strong>{{ workspace.worldSave.value?.remark || '未填写存档备注' }}</strong><span>{{ workspace.worldSave.value?.savedAt || '未知存档时间' }}</span><p>这项操作不可撤销，请确认当前进度已不再需要。</p></div>
    <template #footer><button class="button ghost" @click="dialogs.worldLoad = false">取消</button><button class="button danger" :disabled="busy" @click="run(workspace.loadSnapshot, 'worldLoad')"><RotateCcw :size="16" />确认读档</button></template>
  </BaseDialog>
  <BaseDialog v-model="dialogs.account" title="账号资料"><div class="form-stack"><label class="field"><span>用户名</span><input v-model.trim="accountForm.username" /></label><label class="field"><span>邮箱（不可在此修改）</span><input v-model="accountForm.email" disabled /></label><label class="field"><span>生日</span><input v-model="accountForm.birthday" type="date" /></label><label class="field"><span>骰子皮肤</span><select v-model="accountForm.diceSkin"><option v-for="option in DICE_SKIN_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select><small>保存后，新打开的掷骰动画会使用这套皮肤。</small></label></div><template #footer><button class="button primary" @click="run(() => workspace.saveUserInfo(accountForm as Partial<UserInfo>), 'account')">保存</button></template></BaseDialog>
  <BaseDialog v-model="dialogs.password" title="修改密码" description="验证码发送到当前账户邮箱，5 分钟内有效。"><div class="form-stack"><label class="field"><span>账户邮箱</span><input v-model="passwordForm.email" disabled /></label><label class="field"><span>6 位邮箱验证码</span><div class="field-inline"><input v-model.trim="passwordForm.code" inputmode="numeric" maxlength="6" /><button class="button secondary" @click="sendPasswordCode">发送验证码</button></div></label><label class="field"><span>新密码</span><input v-model="passwordForm.newPassword" type="password" placeholder="请输入非空新密码" /></label><label class="field"><span>确认新密码</span><input v-model="passwordForm.confirmPassword" type="password" /></label></div><template #footer><button class="button primary" :disabled="!passwordForm.code || !passwordForm.newPassword || !passwordForm.confirmPassword" @click="run(changePassword, 'password')">更新密码</button></template></BaseDialog>
  <ModelApiManagerDialog v-model="dialogs.modelApis" />
  <BaseDialog
    v-model="dialogs.end"
    :title="workspace.selectedConversation.value?.status === 'active' ? (workspace.selectedConversation.value?.mode === 'trpg' ? '结束跑团' : '结束群聊') : '会话操作'"
    :description="workspace.selectedConversation.value?.status === 'active'
      ? '结束后保留现有记录，不再继续行动。直接结束跑团不会生成人物后传与完成报告。'
      : '该会话已经归档；如果记录不再需要，可以永久删除。'"
  >
    <template #footer><button class="button ghost" @click="dialogs.end = false">取消</button><button class="button ghost danger-text" :disabled="busy" @click="openConversationDelete">永久删除</button><button v-if="workspace.selectedConversation.value?.status === 'active'" class="button primary" :disabled="busy" @click="run(workspace.closeConversation, 'end')">{{ workspace.selectedConversation.value?.mode === 'trpg' ? '确认结束跑团' : '确认结束群聊' }}</button></template>
  </BaseDialog>
  <BaseDialog v-model="dialogs.deleteConversation" title="永久删除会话" description="此操作不可撤销。相关聊天记录、跑团人物卡、投骰、战斗与存档数据都会被删除。">
    <div class="destructive-confirmation"><strong>确认删除“{{ workspace.selectedConversation.value?.title || '当前会话' }}”？</strong><p>删除普通群聊不会撤销已经产生的好感度变化，但聊天记录会被清除。</p></div>
    <template #footer><button class="button ghost" :disabled="busy" @click="dialogs.deleteConversation = false">取消</button><button class="button danger" :disabled="busy" @click="permanentlyDeleteConversation"><Trash2 :size="16" />确认永久删除</button></template>
  </BaseDialog>
  <BaseDialog
    v-if="workspace.generationFailure.value"
    v-model="workspace.generationFailureOpen.value"
    :title="workspace.selectedConversation.value?.mode === 'trpg' ? '行动轮执行失败' : '回复生成失败'"
    :description="workspace.generationFailure.value.message"
    size="lg"
    content-class="generation-error-dialog"
  >
    <div class="generation-error-overview">
      <dl>
        <div><dt>错误编号</dt><dd>{{ workspace.generationFailure.value.detail.errorId }}</dd></div>
        <div><dt>错误代码</dt><dd>{{ workspace.generationFailure.value.detail.code }}</dd></div>
        <div><dt>发生时间</dt><dd>{{ workspace.generationFailure.value.detail.occurredAt }}</dd></div>
        <div><dt>操作</dt><dd>{{ workspace.generationFailure.value.detail.operation }}</dd></div>
      </dl>
      <p>详细信息仅保留在当前页面中，刷新后会清除。</p>
    </div>
    <div class="generation-error-details">
      <details open>
        <summary>Request</summary>
        <pre>{{ formatDebugValue(workspace.generationFailure.value.detail.request) }}</pre>
      </details>
      <details>
        <summary>Response</summary>
        <pre>{{ formatDebugValue(mergeGenerationResponseEvents(workspace.generationFailure.value.detail.response)) }}</pre>
      </details>
      <details>
        <summary>Stack trace</summary>
        <pre>{{ formatDebugValue(workspace.generationFailure.value.detail.stack) }}</pre>
      </details>
    </div>
    <template #footer>
      <button class="button ghost" @click="workspace.generationFailureOpen.value = false">关闭</button>
      <button v-if="canRetryGenerationFailure" class="button primary" :disabled="workspace.loading.sending" @click="workspace.retryGenerationFailure">重试此行动轮</button>
    </template>
  </BaseDialog>
  <TrpgCharacterBindingDialog
    v-if="workspace.selectedConversation.value?.mode === 'trpg'"
    v-model="dialogs.trpgBinding"
    :conversation="workspace.selectedConversation.value"
    :module="selectedConversationModule"
    :characters="workspace.characters.value"
    :participant-ids="workspace.participantIds.value"
    :dice-skin="workspace.userInfo.value?.diceSkin"
    :requested-target-key="trpgBindingTargetKey"
    :requested-creation-method="trpgBindingCreationMethod"
    @update:model-value="handleTrpgBindingVisibility"
    @complete="completeTrpgBinding"
  />
  <TrpgToolsDialog v-if="workspace.selectedConversation.value?.mode === 'trpg'" v-model="dialogs.trpgTools" :conversation="workspace.selectedConversation.value" :module="selectedConversationModule" :username="workspace.session.username" :characters="workspace.characters.value" :participant-ids="workspace.participantIds.value" :messages="workspace.messages.value" :actor-runtimes="workspace.actorRuntimes.value" :model-apis="workspace.modelApis.value" :save-actor-runtime="workspace.saveActorRuntime" :has-older-messages="workspace.hasOlderGroupMessages.value" :loading-older-messages="workspace.loading.chat" :requested-card-id="trpgToolsCardId" @restored="restoreTrpg" @open-dice="openDiceMessage" @locate-dice="locateDiceMessage" @load-earlier="workspace.loadOlderGroupMessages" @create-character-card="openTrpgCharacterCardCreation" />
  <DicePlayerDialog v-model="dicePlayerOpen" :request="dicePlaybackRequest" :show-continue="diceShowContinue" :auto-continue="diceAutoContinue" @roll="rollDiceMessage" @complete="completeDiceMessageRoll" @continue="continueAfterDice" @cancel-auto-continue="cancelDiceAutoAdvance" />
  <NoticeToast />
</template>
