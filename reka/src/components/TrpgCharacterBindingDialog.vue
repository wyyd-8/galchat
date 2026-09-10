<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ArrowDown, ArrowLeft, ArrowUp, BookOpenCheck, BookUser, Check, ChevronDown,
  ChevronUp, CircleCheck, ClipboardCheck, Dices, Fingerprint, LoaderCircle,
  RefreshCw, ScanText, Search, Sparkles, Trash2, TriangleAlert, UserRound, X,
} from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import './mobile-tools.css'
import { useMobileViewport } from '@/composables/useMobileViewport'
import CharacterCardImportGuide from './CharacterCardImportGuide.vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import MobileInvestigatorSheet from '@/components/MobileInvestigatorSheet.vue'
import DicePlayerDialog from '@/dice/components/DicePlayerDialog.vue'
import StepwiseCharacterCardWizard from '@/components/StepwiseCharacterCardWizard.vue'
import WeaponRiskNotice from '@/components/WeaponRiskNotice.vue'
import { api } from '@/api/client'
import type { Character, CharacterCard, CharacterCardCreationDraft, CocModule, Conversation, InvestigatorCardSummary } from '@/api/types'
import type { DicePlaybackRequest } from '@/dice/domain/dicePlayback'
import {
  buildAutoCharacterCardDicePlayback,
  buildImportedCharacterLuckDicePlayback,
} from '@/components/characterCardCreationDice'
import { analyzeCharacterCardImport } from '@/components/characterCardImportPreview'
import {
  buildBindingTargets, buildCharacterCardCreationMethods, loadBindingTargetContent,
  type CharacterCardCreationMethod,
} from '@/components/trpgSetupState'
import {
  buildSkillDisplayItems, formatCheckRate, nextSkillGroup, resolveWeaponCheckValue,
  shouldShowWeaponRisk,
} from '@/components/trpgToolsState'
import type { ToolSkillSortDirection, ToolSkillSortMode } from '@/components/trpgToolsState'
import { errorMessage, notify } from '@/composables/useNotice'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  conversation: Conversation | null
  module?: CocModule | null
  characters: Character[]
  participantIds: number[]
  diceSkin?: string
  requestedTargetKey?: string | null
  requestedCreationMethod?: CharacterCardCreationMethod | null
}>()
const emit = defineEmits<{ complete: [] }>()

const { isMobile } = useMobileViewport()
const importGuideOpen = ref(false)
const mobileImportTab = ref('text')
const mobileDetailOpen = ref(false)
const mobileDetailPane = ref<HTMLElement | null>(null)
let mobileQueueTrigger: HTMLElement | null = null
let mobileQueueScroll = 0
const importDrafts = new Map<string, string>()
function rememberImportDraft() {
  if (selectedCreationMethod.value === 'IMPORT') importDrafts.set(selectedKey.value, cardText.value)
}
async function backToBindingQueue() {
  rememberImportDraft()
  mobileDetailOpen.value = false
  await nextTick()
  mobileQueueTrigger?.focus({ preventScroll: true })
  const viewport = mobileQueueTrigger?.closest('.dialog-body') || mobileDetailPane.value?.closest('.dialog-body')
  viewport?.scrollTo({ top: mobileQueueScroll })
}
const busy = ref(false)
const wizardBusy = ref(false)
const cards = ref<InvestigatorCardSummary[]>([])
const selectedKey = ref('player')
const card = ref<CharacterCard | null>(null)
const draft = ref<CharacterCardCreationDraft | null>(null)
const cardText = ref('')
const confirmDelete = ref(false)
const selectedCreationMethod = ref<CharacterCardCreationMethod | null>(null)
const diceOpen = ref(false)
const diceRequest = ref<DicePlaybackRequest | null>(null)
const diceRequestId = ref(0)
const autoReviewSection = ref<'OVERVIEW' | 'SKILLS' | 'BACKGROUND'>('OVERVIEW')
const selectedSheetTab = ref('skills')
const selectedProfileTab = ref('background')
const selectedSkillGroup = ref<string | null>(null)
const skillPanelExpanded = ref(false)
const skillSearchQuery = ref('')
const skillSortMode = ref<ToolSkillSortMode>('default')
const skillSortDirection = ref<ToolSkillSortDirection>('asc')

const targets = computed(() => buildBindingTargets(props.participantIds, cards.value))
const selectedTarget = computed(() => targets.value.find((target) => target.key === selectedKey.value) || targets.value[0])
const selectedCharacter = computed(() => props.characters.find((item) => item.characterId === selectedTarget.value?.participantId))
const selectedName = computed(() => selectedTarget.value?.actorType === 'PLAYER'
  ? '玩家调查员'
  : selectedCharacter.value?.characterName || `角色 #${selectedTarget.value?.participantId}`)
const completedCount = computed(() => targets.value.filter((target) => target.boundCardId !== undefined).length)
const mobileBindingTitle = computed(() => !mobileDetailOpen.value ? '绑定人物卡' : card.value ? '人物卡' : autoDraft.value && displayCard.value ? '审阅人物卡' : selectedCreationMethod.value === 'STEP' || draft.value?.creationMode === 'STEP_STANDARD' ? '步进建卡' : selectedCreationMethod.value === 'IMPORT' ? '导入人物卡' : selectedCreationMethod.value === 'AUTO' ? '自动建卡' : '建立人物卡')
const mobileWizardActive = computed(() => isMobile.value && mobileDetailOpen.value && (selectedCreationMethod.value === 'STEP' || draft.value?.creationMode === 'STEP_STANDARD'))
async function backMobileBinding() {
  if (busy.value || wizardBusy.value) return
  if (selectedCreationMethod.value && !draft.value && !card.value) { rememberImportDraft(); selectedCreationMethod.value = null; mobileImportTab.value = 'text'; return }
  await backToBindingQueue()
}
const complete = computed(() => targets.value.length > 0 && completedCount.value === targets.value.length)
const displayCard = computed(() => card.value || draft.value?.state.preview || null)
const creationMethods = computed(() => buildCharacterCardCreationMethods(selectedTarget.value?.actorType || 'PLAYER'))
const importPreview = computed(() => analyzeCharacterCardImport(cardText.value))
const autoDraft = computed(() => draft.value?.creationMode === 'AUTO_QUICK_START' ? draft.value : null)
const creationMethodCopy = {
  STEP: { title: '标准步进建卡', eyebrow: '推荐 · 8–12 分钟', description: '亲自生成属性、分配技能，并逐项完成调查员背景。', action: '开始标准建卡' },
  AUTO: { title: 'AI 自动生成', eyebrow: '快速 · 约 30 秒', description: '依据角色模板与当前模组，自动整理职业、技能和人物经历。', action: '立即自动生成' },
  IMPORT: { title: '导入人物卡', eyebrow: '已有卡 · 约 1 分钟', description: '粘贴已有的人物卡文本，检查必填内容后直接绑定。', action: '打开文本导入' },
} as const
const importAttributeItems = [
  { code: 'STR', label: '力量' }, { code: 'CON', label: '体质' },
  { code: 'SIZ', label: '体型' }, { code: 'DEX', label: '敏捷' },
  { code: 'APP', label: '外貌' }, { code: 'INT', label: '智力' },
  { code: 'POW', label: '意志' }, { code: 'EDU', label: '教育' },
] as const
const importPlaceholder = `周宁，记者，女，27岁
出身上海，现居阿卡姆
时代: 1920s
STR 50 CON 55 SIZ 60 DEX 55
APP 50 INT 60 POW 55 EDU 60
—————————技能—————————
侦查 60%`
const importRequiredIssueCount = computed(() => (importPreview.value.identity ? 0 : 1)
  + (importPreview.value.missingAttributes.length ? 1 : 0)
  + importPreview.value.warnings.length)
const importRecognizedAttributeCount = computed(() => importAttributeItems.length - importPreview.value.missingAttributes.length)
const importBackgroundRecognized = computed(() => importPreview.value.checks.some((item) => item.code === 'BACKGROUND' && item.state === 'complete'))
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

function shown(value?: string | number | null) { return value == null || value === '' ? '—' : value }
function resourceStyle(current?: number, max?: number) {
  const ratio = current == null || !max ? 0 : Math.max(0, Math.min(100, Math.round(current / max * 100)))
  return { '--resource-ratio': `${ratio}%` }
}
function ammo(remaining?: number, capacity?: number) {
  if (remaining != null && capacity != null) return `${remaining} / ${capacity}`
  return shown(remaining ?? capacity)
}
function toggleSkillGroup(group: string) {
  selectedSkillGroup.value = nextSkillGroup(selectedSkillGroup.value, group)
}

function characterName(participantId?: number) {
  return props.characters.find((item) => item.characterId === participantId)?.characterName || `角色 #${participantId}`
}

async function loadSelectedCard() {
  importGuideOpen.value = false
  confirmDelete.value = false
  cardText.value = importDrafts.get(selectedKey.value) || ''
  autoReviewSection.value = 'OVERVIEW'
  const content = await loadBindingTargetContent(
    selectedTarget.value,
    (cardId) => api.characterCardById(cardId),
    (participantId) => props.conversation
      ? api.activeCharacterCardDraft(props.conversation.id, participantId)
      : Promise.resolve(null),
  )
  card.value = content.card
  draft.value = content.draft
  selectedCreationMethod.value = content.draft?.creationMode === 'STEP_STANDARD'
    ? 'STEP'
    : content.draft?.creationMode === 'AUTO_QUICK_START' ? 'AUTO'
      : !content.card && importDrafts.has(selectedKey.value) ? 'IMPORT' : null
}

function requestId() {
  return crypto.randomUUID?.() || `card-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function generateCard() {
  if (!props.conversation || !selectedTarget.value?.participantId) return
  const generated = await api.createAutoCharacterCardDraft({
    runId: props.conversation.id,
    participantId: selectedTarget.value.participantId,
    requestId: requestId(),
  })
  draft.value = generated
  autoReviewSection.value = 'OVERVIEW'
  playAutoDice(generated)
}

async function chooseCreationMethod(method: CharacterCardCreationMethod) {
  const option = creationMethods.value.find((item) => item.id === method)
  if (!option?.enabled) return
  selectedCreationMethod.value = method
}

function playAutoDice(nextDraft: CharacterCardCreationDraft) {
  const request = buildAutoCharacterCardDicePlayback({
    buildRolls: nextDraft.state.buildRolls,
  }, props.diceSkin, diceRequestId.value)
  if (!request.result.modules.length) return
  diceRequestId.value = request.id
  diceRequest.value = request
  diceOpen.value = true
}

async function regenerateCard() {
  if (!draft.value) return
  const regenerated = await api.regenerateCharacterCardDraft(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
  draft.value = regenerated
  playAutoDice(regenerated)
}

async function rewriteBackground() {
  if (!draft.value) return
  const rewritten = await api.rewriteCharacterCardBackground(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
  draft.value = rewritten
}

async function abandonAutoDraft() {
  if (!draft.value) {
    selectedCreationMethod.value = null
    return
  }
  await api.abandonCharacterCardDraft(draft.value.draftId, draft.value.version)
  draft.value = null
  selectedCreationMethod.value = null
}

async function confirmGeneratedCard() {
  if (!draft.value) return
  await api.completeCharacterCardDraft(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
  draft.value = null
  await refreshCards()
  notify('人物卡已生成并绑定', selectedName.value, 'success')
}

async function refreshCards(selectFirstMissing = false) {
  if (!props.conversation) return
  cards.value = await api.investigatorCards(props.conversation.id)
  if (selectFirstMissing) {
    const nextTargets = buildBindingTargets(props.participantIds, cards.value)
    const requestedTarget = nextTargets.find((target) => target.key === props.requestedTargetKey
      && target.boundCardId === undefined)
    selectedKey.value = requestedTarget?.key
      || nextTargets.find((target) => target.boundCardId === undefined)?.key
      || 'player'
  }
  await loadSelectedCard()
  if (selectFirstMissing && !card.value && !draft.value && props.requestedCreationMethod) {
    const requestedMethod = creationMethods.value.find((method) => method.id === props.requestedCreationMethod)
    if (requestedMethod?.enabled) selectedCreationMethod.value = requestedMethod.id
  }
}

async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
  } catch (error) {
    notify('人物卡绑定失败', errorMessage(error), 'danger')
  } finally {
    busy.value = false
  }
}

async function selectTarget(key: string) {
  if (busy.value || wizardBusy.value) return
  rememberImportDraft()
  if (isMobile.value) {
    mobileQueueTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    mobileQueueScroll = mobileQueueTrigger?.closest('.dialog-body')?.scrollTop || 0
  }
  mobileDetailOpen.value = true
  if (isMobile.value) {
    await nextTick()
    mobileDetailPane.value?.focus({ preventScroll: true })
    mobileDetailPane.value?.closest('.dialog-body')?.scrollTo({ top: 0 })
    // Returning from the queue to the same actor keeps the editor mounted and untouched.
    if (selectedKey.value === key) return
  }
  card.value = null
  draft.value = null
  selectedCreationMethod.value = null
  selectedKey.value = key
  selectedSheetTab.value = 'skills'
  selectedProfileTab.value = 'background'
  selectedSkillGroup.value = null
  skillPanelExpanded.value = false
  skillSearchQuery.value = ''
  await execute(loadSelectedCard)
}

async function bindCard() {
  if (!props.conversation || !selectedTarget.value || !cardText.value.trim()) return
  const importedCard = await api.createCharacterCard({
    runId: props.conversation.id,
    participantId: selectedTarget.value.participantId,
    characterText: cardText.value.trim(),
  })
  const luckResult = await api.rollCharacterLuck(importedCard.character.id)
  const playback = buildImportedCharacterLuckDicePlayback(
    luckResult,
    importedCard.character.name,
    props.diceSkin,
    diceRequestId.value,
  )
  diceRequestId.value = playback.id
  diceRequest.value = playback
  diceOpen.value = true
  await refreshCards()
  selectedCreationMethod.value = null
  notify('人物卡已绑定并生成幸运', selectedName.value, 'success')
}

async function completeStepwiseCard() {
  draft.value = null
  selectedCreationMethod.value = null
  await refreshCards()
  notify('人物卡已生成并绑定', selectedName.value, 'success')
}

async function removeCard() {
  if (!card.value) return
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  await api.deleteCharacterCard(card.value.character.id)
  await refreshCards()
  notify('人物卡已解除绑定', selectedName.value, 'success')
}

function finish() {
  if (!complete.value) return
  open.value = false
  emit('complete')
}

watch(open, (visible) => {
  if (visible) {
    mobileDetailOpen.value = Boolean(props.requestedTargetKey || props.requestedCreationMethod)
    mobileQueueTrigger = null
    mobileQueueScroll = 0
    void execute(() => refreshCards(true))
  }
  else {
    rememberImportDraft()
    importGuideOpen.value = false
    selectedSkillGroup.value = null
    skillPanelExpanded.value = false
    skillSearchQuery.value = ''
    skillSortMode.value = 'default'
    skillSortDirection.value = 'asc'
  }
}, { immediate: true })
watch(() => props.conversation?.id, () => {
  importDrafts.clear()
  mobileDetailOpen.value = false
  selectedKey.value = 'player'
  cards.value = []
  card.value = null
  draft.value = null
  selectedCreationMethod.value = null
})
</script>

<template>
  <BaseDialog
    v-model="open"
    :title="isMobile ? mobileBindingTitle : '绑定调查员人物卡'"
    :mobile-back="isMobile && mobileDetailOpen ? backMobileBinding : undefined"
    :description="isMobile ? '' : '第三阶段 · 为玩家和每位 AI 调查员准备本次跑团使用的人物卡。'"
    size="lg"
    mobile-presentation="page"
    :content-class="`trpg-binding-dialog trpg-character-creation-dialog ${mobileWizardActive ? 'mobile-binding-workflow' : ''}`"
  >
    <div class="trpg-binding-layout">
      <section v-show="!isMobile || !mobileDetailOpen" class="trpg-binding-list-pane">
        <div v-if="isMobile" class="mobile-binding-intro"><h1>让调查员就位</h1><p>已绑定 {{ completedCount }} / {{ targets.length }} 张人物卡</p></div>
        <header v-if="!isMobile" class="settings-section-heading">
          <span><strong>调查员</strong><small>选择对象，查看或绑定人物卡</small></span>
          <em>{{ completedCount }}/{{ targets.length }} 已绑定</em>
        </header>
        <div class="character-choice-list trpg-binding-targets">
          <button
            type="button"
            class="choice-row"
            :class="{ active: selectedKey === 'player' }"
            @click="selectTarget('player')"
          >
            <span class="character-avatar small player-avatar"><UserRound :size="17" /></span>
            <span><strong>玩家调查员</strong><small>由当前登录用户控制</small></span>
            <span class="binding-state" :class="{ complete: targets[0]?.boundCardId !== undefined }">
              <Check v-if="targets[0]?.boundCardId !== undefined" :size="13" />{{ targets[0]?.boundCardId !== undefined ? '已绑定' : '待绑定' }}
            </span>
          </button>
          <button
            v-for="target in targets.slice(1)"
            :key="target.key"
            type="button"
            class="choice-row"
            :class="{ active: selectedKey === target.key }"
            @click="selectTarget(target.key)"
          >
            <span
              class="character-avatar small"
              :style="characters.find((item) => item.characterId === target.participantId)?.characterImage ? { backgroundImage: `url(${characters.find((item) => item.characterId === target.participantId)?.characterImage})` } : {}"
            >{{ characters.find((item) => item.characterId === target.participantId)?.characterImage ? '' : characterName(target.participantId).slice(0, 1) }}</span>
            <span><strong>{{ characterName(target.participantId) }}</strong><small>AI 调查员</small></span>
            <span class="binding-state" :class="{ complete: target.boundCardId !== undefined }">
              <Check v-if="target.boundCardId !== undefined" :size="13" />{{ target.boundCardId !== undefined ? '已绑定' : '待绑定' }}
            </span>
          </button>
        </div>
        <p v-if="isMobile" class="mobile-tools-notice">你的调查员支持步进建卡或导入；自动建卡仅对 AI 调查员开放。</p>
        <button v-if="isMobile" class="button secondary mobile-binding-later" :disabled="busy || wizardBusy" @click="open = false">稍后处理</button>
        <p v-if="isMobile" class="mobile-binding-help">全部人物卡完成后才能进入跑团。</p>
      </section>

      <aside v-show="!isMobile || mobileDetailOpen" ref="mobileDetailPane" tabindex="-1" aria-label="当前调查员建卡与绑定" class="trpg-binding-card-pane">
        <div v-if="busy && !displayCard" class="binding-empty auto-generation-loading">
          <LoaderCircle class="spin" :size="27" />
          <strong>{{ selectedCreationMethod === 'AUTO' ? '正在生成人物卡…' : selectedCreationMethod === 'IMPORT' ? '正在导入并绑定人物卡…' : '正在读取人物卡…' }}</strong>
          <p>{{ selectedCreationMethod === 'AUTO' ? '正在参考角色设定和模组背景，并按照 CoC 7版规则完成掷骰。生成后请检查人物卡再确认绑定。' : selectedCreationMethod === 'IMPORT' ? '正在检查你粘贴的内容，完成后会直接绑定到当前调查员。' : '正在检查已绑定的人物卡和未完成的建卡进度。' }}</p>
          <div v-if="selectedCreationMethod === 'AUTO'" class="auto-loading-steps"><span class="complete">读取角色设定</span><span class="active">掷骰并生成</span><span>等待你确认</span></div>
        </div>
        <StepwiseCharacterCardWizard
          v-else-if="selectedCreationMethod === 'STEP' || draft?.creationMode === 'STEP_STANDARD'"
          :key="`${conversation?.id}:${selectedKey}`"
          v-model:draft="draft"
          :run-id="conversation!.id"
          :participant-id="selectedTarget?.participantId"
          :default-name="selectedCharacter?.characterName"
          :default-era="module?.era"
          :dice-skin="diceSkin"
          @busy-change="wizardBusy = $event"
          @complete="execute(completeStepwiseCard)"
          @abandoned="selectedCreationMethod = null"
        />
        <section v-else-if="selectedCreationMethod === 'AUTO' && !draft" class="auto-creation-briefing">
          <div class="creation-process-strip">
            <span class="active"><i>1</i>确认方式</span><span><i>2</i>生成与掷骰</span><span><i>3</i>检查并绑定</span>
          </div>
          <div class="auto-briefing-layout">
            <main class="auto-briefing-main">
              <header class="creation-workbench-heading">
                <small>自动建卡</small>
                <h3>为{{ selectedName }}生成人物卡</h3>
                <p>AI 会参考角色设定和当前模组，按照 CoC 7版规则生成一张人物卡。你检查并确认后才会绑定。</p>
              </header>
              <div class="auto-source-grid">
                <article><Fingerprint :size="19" /><span><strong>角色模板</strong><small>姓名倾向、背景与性格</small></span><Check :size="14" /></article>
                <article><BookOpenCheck :size="19" /><span><strong>当前模组</strong><small>时代、地点与调查主题</small></span><Check :size="14" /></article>
                <article><Dices :size="19" /><span><strong>CoC 7版规则</strong><small>生成属性、技能与人物背景</small></span><Check :size="14" /></article>
              </div>
              <div class="auto-dice-disclosure"><Dices :size="22" /><span><strong>骰点会完整展示</strong><small>幸运、教育成长和六类背景骰都会进入 3D 骰盘；数值生成后仍可整卡重试或只改写背景。</small></span></div>
              <div class="creation-workbench-actions"><button class="button ghost" @click="selectedCreationMethod = null"><ArrowLeft :size="14" />返回选择</button><button class="button primary" :disabled="busy" @click="execute(generateCard)"><Sparkles :size="15" />开始自动生成</button></div>
            </main>
            <aside class="auto-briefing-note">
              <span class="dossier-stamp">生成说明</span>
              <small>预计耗时</small><strong>约 30 秒</strong>
              <dl><div><dt>角色设定</dt><dd>会参考</dd></div><div><dt>模组信息</dt><dd>会参考</dd></div><div><dt>所有骰点</dt><dd>会展示</dd></div><div><dt>绑定人物卡</dt><dd>确认后进行</dd></div></dl>
              <p>生成结果不满意时，可以整张重来，也可以只重新生成人物背景。</p>
            </aside>
          </div>
        </section>
        <section v-else-if="autoDraft && displayCard && isMobile" class="mobile-auto-review">
          <p class="mobile-tools-notice">已生成 · 请检查后再绑定</p>
          <MobileInvestigatorSheet :card="displayCard" :actor-name="selectedName" />
          <details class="mobile-review-options"><summary>生成选项</summary><button class="button secondary" :disabled="busy" @click="playAutoDice(autoDraft)">重放骰点</button><button class="button secondary" :disabled="busy" @click="execute(rewriteBackground)">仅重骰并重写背景</button><button class="button secondary" :disabled="busy" @click="execute(regenerateCard)">完整重新生成</button><button class="button ghost" :disabled="busy" @click="execute(abandonAutoDraft)">放弃草稿</button></details>
        </section>
        <section v-else-if="autoDraft && displayCard" class="auto-review-dossier">
          <div class="creation-process-strip">
            <span class="complete"><i>✓</i>确认方式</span><span class="complete"><i>✓</i>生成与掷骰</span><span class="active"><i>3</i>检查并绑定</span>
          </div>
          <div class="auto-review-workbench">
            <main class="auto-review-main">
              <header class="creation-step-heading auto-review-heading">
                <div class="auto-review-heading-copy"><small>自动生成结果</small><h3>检查人物卡</h3></div>
                <div class="auto-review-heading-actions">
                  <button class="button ghost small" :disabled="busy" @click="playAutoDice(autoDraft)"><Dices :size="14" />重放骰点</button>
                  <details class="auto-review-more-actions">
                    <summary class="button ghost small">更多修改</summary>
                    <div>
                      <button class="button ghost small" :disabled="busy" @click="execute(regenerateCard)"><RefreshCw :size="14" />完整重试</button>
                      <button class="button ghost small" :disabled="busy" @click="execute(abandonAutoDraft)"><ArrowLeft :size="14" />放弃草稿</button>
                    </div>
                  </details>
                </div>
              </header>

              <div class="auto-review-identity">
                <span><small>{{ displayCard.character.actorType === 'PLAYER' ? '玩家调查员' : 'AI 调查员' }} · {{ displayCard.character.occupation || '未填写职业' }}</small><h3>{{ displayCard.character.name }}</h3><p>{{ displayCard.character.sex || '—' }} · {{ displayCard.character.age || '—' }} 岁 · {{ displayCard.character.era || '时代未填' }}<template v-if="displayCard.character.residence"> · {{ displayCard.character.residence }}</template></p></span>
                <div class="auto-review-vitals"><span><small>HP</small><strong>{{ displayCard.character.hpCurrent }}/{{ displayCard.character.hpMax }}</strong></span><span><small>SAN</small><strong>{{ displayCard.character.sanCurrent }}/{{ displayCard.character.sanMax }}</strong></span><span><small>MP</small><strong>{{ displayCard.character.mpCurrent }}/{{ displayCard.character.mpMax }}</strong></span></div>
              </div>

              <div class="auto-review-attributes"><span v-for="[name, value] in Object.entries({ STR: displayCard.character.str, CON: displayCard.character.con, SIZ: displayCard.character.siz, DEX: displayCard.character.dex, APP: displayCard.character.app, INT: displayCard.character.intValue, POW: displayCard.character.pow, EDU: displayCard.character.edu })" :key="name"><small>{{ name }}</small><strong>{{ value }}</strong></span></div>

              <nav class="auto-review-category-tabs" aria-label="人物卡内容分类">
                <button type="button" class="auto-review-category-tab" :class="{ active: autoReviewSection === 'OVERVIEW' }" @click="autoReviewSection = 'OVERVIEW'">基础数据</button>
                <button type="button" class="auto-review-category-tab" :class="{ active: autoReviewSection === 'SKILLS' }" @click="autoReviewSection = 'SKILLS'">技能与装备</button>
                <button type="button" class="auto-review-category-tab" :class="{ active: autoReviewSection === 'BACKGROUND' }" @click="autoReviewSection = 'BACKGROUND'">人物背景</button>
              </nav>

              <section v-if="autoReviewSection === 'OVERVIEW'" class="auto-review-section">
                <header><strong>基础数据</strong><small>检查幸运、移动率和伤害相关数值</small></header>
                <div class="auto-review-overview-grid">
                  <span><small>幸运</small><strong>{{ displayCard.character.luckCurrent ?? '—' }}</strong></span>
                  <span><small>移动率</small><strong>{{ displayCard.character.mov ?? '—' }}</strong></span>
                  <span><small>体格</small><strong>{{ displayCard.character.build ?? '—' }}</strong></span>
                  <span><small>伤害加值</small><strong>{{ displayCard.character.damageBonus || '—' }}</strong></span>
                </div>
              </section>

              <section v-else-if="autoReviewSection === 'SKILLS'" class="auto-review-section">
                <header><strong>技能与装备</strong><small>技能按生成结果完整展示</small></header>
                <div class="auto-review-skill-grid"><span v-for="skill in displayCard.skills" :key="skill.id">{{ skill.displayName }}<strong>{{ skill.value }}%</strong></span></div>
                <div class="auto-review-equipment">
                  <strong>武器与随身装备</strong>
                  <p><template v-for="(weapon, index) in displayCard.weapons" :key="weapon.id"><span>{{ weapon.name }}{{ weapon.damage ? ` ${weapon.damage}` : '' }}<WeaponRiskNotice v-if="shouldShowWeaponRisk(weapon)" :weapon="weapon" /></span><span v-if="index < displayCard.weapons.length - 1"> · </span></template><span v-if="!displayCard.weapons.length">无武器</span><br>{{ displayCard.profile?.equipmentText || '无额外装备' }}</p>
                </div>
              </section>

              <section v-else class="auto-review-section">
                <header><strong>人物背景</strong><small>可在右侧单独重写</small></header>
                <div class="auto-review-story-grid">
                  <article><strong>形象与信念</strong><p>{{ displayCard.profile?.appearance || '未填写' }}<br>{{ displayCard.profile?.ideology || '未填写' }}</p></article>
                  <article><strong>重要联系</strong><p>{{ displayCard.profile?.significantPeople || '未填写' }}<br>{{ displayCard.profile?.keyConnectionText || '未填写' }}</p></article>
                  <article><strong>地点与珍宝</strong><p>{{ displayCard.profile?.meaningfulLocations || '未填写' }}<br>{{ displayCard.profile?.treasuredPossessions || '未填写' }}</p></article>
                  <article><strong>特质</strong><p>{{ displayCard.profile?.traits || '未填写' }}</p></article>
                </div>
              </section>
            </main>

            <aside class="creation-live-sheet auto-review-live-sheet">
              <span class="dossier-stamp">待确认</span>
              <header><small>人物卡预览</small><strong>{{ displayCard.character.name }}</strong><span>确认后即可绑定到当前调查员</span></header>
              <dl class="auto-review-facts">
                <div><dt>身份</dt><dd>{{ displayCard.character.occupation || '未填写职业' }}</dd></div>
                <div><dt>年龄</dt><dd>{{ displayCard.character.age || '—' }} 岁</dd></div>
                <div><dt>时代</dt><dd>{{ displayCard.character.era || '未填写' }}</dd></div>
                <div><dt>当前状态</dt><dd>等待确认</dd></div>
              </dl>
              <div class="dossier-note auto-review-note"><strong>绑定说明</strong><p>这里只绑定当前调查员。全部人物卡都绑定后，再点击窗口底部的“完成并进入跑团”。</p></div>
              <div class="auto-review-live-actions">
                <button class="button ghost" :disabled="busy" @click="execute(rewriteBackground)"><Sparkles :size="15" />仅重骰并重写背景</button>
                <button class="button primary auto-review-bind-action" :disabled="busy" @click="execute(confirmGeneratedCard)"><Check :size="15" />确认并绑定此调查员</button>
              </div>
            </aside>
          </div>
        </section>
        <MobileInvestigatorSheet v-else-if="card && isMobile" :card="card" :actor-name="selectedName"><details class="mobile-review-options"><summary>人物卡管理</summary><button class="button" :class="confirmDelete ? 'danger' : 'ghost'" :disabled="busy" @click="execute(removeCard)">{{ confirmDelete ? '确认解除绑定' : '解除并重新绑定' }}</button></details></MobileInvestigatorSheet>
        <section v-else-if="card" class="character-sheet binding-sheet trpg-character-sheet trpg-binding-established-sheet">
          <header class="sheet-overview">
            <span
              class="sheet-portrait"
              :class="{ placeholder: !card.character.image }"
              :style="card.character.image ? { backgroundImage: `url(${card.character.image})` } : {}"
            ><UserRound v-if="!card.character.image" :size="24" /></span>
            <div class="sheet-identity">
              <small>{{ selectedName }} · {{ card.character.occupation || '未填写职业' }}</small>
              <h3>{{ card.character.name }}</h3>
              <p>{{ shown(card.character.sex) }} · {{ shown(card.character.age) }} 岁 · {{ card.character.era || '时代未填' }}</p>
              <p>{{ card.character.birthplace || '出身地未填' }} · {{ card.character.residence || '居住地未填' }}</p>
            </div>
            <div class="sheet-statuses" aria-label="调查员状态">
              <em v-for="status in characterStatuses" :key="status.label" :class="status.tone">{{ status.label }}</em>
            </div>
          </header>

          <div class="sheet-resource-grid">
            <span class="sheet-resource hp" :style="resourceStyle(card.character.hpCurrent, card.character.hpMax)"><small>生命 HP</small><strong>{{ shown(card.character.hpCurrent) }} / {{ shown(card.character.hpMax) }}</strong><i /></span>
            <span class="sheet-resource san" :style="resourceStyle(card.character.sanCurrent, card.character.sanMax)"><small>理智 SAN</small><strong>{{ shown(card.character.sanCurrent) }} / {{ shown(card.character.sanMax) }}</strong><i /></span>
            <span class="sheet-resource mp" :style="resourceStyle(card.character.mpCurrent, card.character.mpMax)"><small>魔法 MP</small><strong>{{ shown(card.character.mpCurrent) }} / {{ shown(card.character.mpMax) }}</strong><i /></span>
            <span class="sheet-resource luck" :style="resourceStyle(card.character.luckCurrent, 100)"><small>幸运 LUCK</small><strong>{{ shown(card.character.luckCurrent) }}</strong><i /></span>
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
                  <button type="button" class="sheet-skill-expand-toggle" :aria-label="skillPanelExpanded ? '收起技能面板' : '展开技能面板'" :title="skillPanelExpanded ? '收起技能面板' : '展开技能面板'" @click="skillPanelExpanded = !skillPanelExpanded">
                    <ChevronDown v-if="skillPanelExpanded" :size="15" /><ChevronUp v-else :size="15" />
                  </button>
                  <strong>技能</strong><span>成功率 <small>常规 / 困难 / 极难</small></span>
                </header>
                <div v-if="isMobile || skillPanelExpanded" class="sheet-skill-toolbar">
                  <label class="sheet-skill-search"><Search :size="14" /><input v-model="skillSearchQuery" class="sheet-skill-search-input" type="search" aria-label="检索技能" placeholder="检索技能名称" autocomplete="off" /><button v-if="skillSearchQuery" type="button" class="sheet-skill-clear" aria-label="清除技能检索" @click="skillSearchQuery = ''"><X :size="13" /></button></label>
                  <label class="sheet-skill-sort"><select v-model="skillSortMode" class="sheet-skill-sort-select" aria-label="技能排序方式"><option value="default">默认顺序</option><option value="value">成功率</option><option value="category">大类</option></select></label>
                  <button type="button" class="sheet-skill-direction-toggle" :aria-label="skillSortDirection === 'asc' ? '切换为倒序' : '切换为正序'" @click="skillSortDirection = skillSortDirection === 'asc' ? 'desc' : 'asc'"><ArrowUp v-if="skillSortDirection === 'asc'" :size="13" /><ArrowDown v-else :size="13" />{{ skillSortDirection === 'asc' ? '正序' : '倒序' }}</button>
                  <small class="sheet-skill-result-count">{{ displaySkillCount }} 项</small>
                </div>
                <div class="sheet-skill-scroll">
                  <div v-if="displaySkills.length" class="sheet-skill-grid" role="list">
                    <div v-for="item in displaySkills" :key="item.key" class="sheet-skill-item" :class="{ category: item.kind === 'category' }" role="listitem">
                      <button v-if="item.kind === 'category'" type="button" class="sheet-skill-category-button" :aria-pressed="selectedSkillGroup === item.displayName" @click="toggleSkillGroup(item.displayName)"><span><strong>类别：{{ item.displayName }}</strong><small>{{ selectedSkillGroup === item.displayName ? '返回全部技能' : '查看大类技能' }}</small></span></button>
                      <span v-else><strong>{{ item.displayName }}</strong><small v-if="item.category">{{ item.category }}</small></span>
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
                      <td><span class="weapon-name-line"><strong>{{ weapon.name }}</strong><WeaponRiskNotice v-if="shouldShowWeaponRisk(weapon)" :weapon="weapon" /></span><small v-if="weapon.notes">{{ weapon.notes }}</small><em v-if="weapon.isBroken">已损坏</em></td>
                      <td data-label="成功率" class="check-rate">{{ formatCheckRate(resolveWeaponCheckValue(weapon, card.skills)) }}</td><td data-label="伤害">{{ shown(weapon.damage) }}</td><td data-label="射程">{{ shown(weapon.range) }}</td><td data-label="次数">{{ shown(weapon.attacksPerRound) }}</td><td data-label="弹药">{{ ammo(weapon.remainingAmmo, weapon.ammoCapacity) }}</td><td data-label="故障值">{{ shown(weapon.malfunction) }}</td>
                    </tr>
                    <tr v-if="!card.weapons.length"><td colspan="7" class="sheet-table-empty">暂无武器</td></tr>
                  </tbody>
                </table>
              </div>
            </TabsContent>

            <TabsContent value="profile" class="sheet-tab-content profile-tab-content">
              <TabsRoot v-model="selectedProfileTab" class="sheet-profile-tabs">
                <TabsList class="sheet-secondary-tabs"><TabsTrigger value="background">人物背景</TabsTrigger><TabsTrigger value="connections">重要联系</TabsTrigger><TabsTrigger value="trauma">创伤记录</TabsTrigger><TabsTrigger value="assets">资产与笔记</TabsTrigger></TabsList>
                <TabsContent value="background" class="sheet-profile-content"><div class="sheet-profile-grid"><section><strong>形象描述</strong><p>{{ card.profile?.appearance || '未记录' }}</p></section><section><strong>思想与信念</strong><p>{{ card.profile?.ideology || '未记录' }}</p></section><section><strong>特质</strong><p>{{ card.profile?.traits || '未记录' }}</p></section></div></TabsContent>
                <TabsContent value="connections" class="sheet-profile-content"><div class="sheet-profile-grid"><section><strong>重要之人</strong><p>{{ card.profile?.significantPeople || '未记录' }}</p></section><section><strong>意义非凡之地</strong><p>{{ card.profile?.meaningfulLocations || '未记录' }}</p></section><section><strong>宝贵之物</strong><p>{{ card.profile?.treasuredPossessions || '未记录' }}</p></section><section><strong>关键连接</strong><small>{{ card.profile?.keyConnectionCategory || '未分类' }}</small><p>{{ card.profile?.keyConnectionText || '未记录' }}</p></section></div></TabsContent>
                <TabsContent value="trauma" class="sheet-profile-content"><div class="sheet-profile-grid"><section><strong>伤口和疤痕</strong><p>{{ card.profile?.injuriesAndScars || '未记录' }}</p></section><section><strong>恐惧症和狂躁症</strong><p>{{ card.profile?.phobiasAndManias || '未记录' }}</p></section></div></TabsContent>
                <TabsContent value="assets" class="sheet-profile-content"><div class="sheet-profile-grid"><section><strong>装备和道具</strong><p>{{ card.profile?.equipmentText || '未记录' }}</p></section><section class="sheet-wealth"><strong>财务状况</strong><dl><div><dt>消费水平</dt><dd>{{ card.profile?.spendingLevel || '—' }}</dd></div><div><dt>现金</dt><dd>{{ card.profile?.cash || '—' }}</dd></div></dl></section><section><strong>资产</strong><p>{{ card.profile?.assetsText || '未记录' }}</p></section><section><strong>调查员笔记</strong><p>{{ card.profile?.notes || '未记录' }}</p></section></div></TabsContent>
              </TabsRoot>
            </TabsContent>
          </TabsRoot>

          <div class="binding-established-actions">
            <button class="button" :class="confirmDelete ? 'danger' : 'ghost'" :disabled="busy" @click="execute(removeCard)"><Trash2 :size="15" />{{ confirmDelete ? '确认解除绑定' : '解除并重新绑定' }}</button>
          </div>
        </section>
        <section v-else-if="selectedCreationMethod === 'IMPORT'" class="binding-import-card creation-import-workbench">
          <div class="creation-process-strip import-process-strip">
            <span class="active"><i>1</i>粘贴人物卡</span><span :class="{ active: cardText.trim(), complete: importPreview.ready }"><i>2</i>检查必填内容</span><span><i>3</i>确认并绑定</span>
          </div>
          <header class="creation-workbench-heading import-workbench-heading"><span><small>导入现成人物卡</small><h3>导入{{ selectedName }}人物卡</h3><p>把已有的人物卡文本粘贴到下方，即时检查已识别内容和需要补充的项目。</p></span><em :class="{ ready: importPreview.ready }">{{ importPreview.ready ? '可以导入' : cardText.trim() ? '需要补充' : '等待粘贴' }}</em></header>
          <nav v-if="isMobile" class="mobile-import-tabs" aria-label="人物卡导入"><button type="button" :aria-pressed="mobileImportTab === 'text'" @click="mobileImportTab = 'text'">粘贴原文</button><button type="button" :aria-pressed="mobileImportTab === 'result'" @click="mobileImportTab = 'result'">识别结果</button></nav>
          <div class="creation-import-layout">
            <main v-show="!isMobile || mobileImportTab === 'text'" class="creation-import-editor">
              <label class="field"><span><ScanText :size="15" />粘贴人物卡文本</span><textarea v-model="cardText" rows="14" spellcheck="false" :placeholder="importPlaceholder" aria-describedby="character-import-format-help" /></label>
              <button v-if="isMobile" class="import-guide-entry" type="button" @click="importGuideOpen = true">
                <BookOpenCheck :size="20" /><span><strong>人物卡模板与导入说明</strong><small>下载模板 · 完成建卡 · 复制文本</small></span><ChevronDown :size="18" />
              </button>
              <div id="character-import-format-help" class="import-format-help"><strong>最低导入要求</strong><span>第一行写明姓名、职业、性别和年龄，并包含 STR、CON、SIZ、DEX、APP、INT、POW、EDU 八项属性。技能与背景可以不填。</span></div>
              <div class="import-editor-meta"><span>{{ cardText.length }} 字符</span><span>{{ cardText.split(/\r?\n/).filter(Boolean).length }} 行</span><span>不会自动修改原文</span></div>
            </main>
            <aside v-show="!isMobile || mobileImportTab === 'result'" class="creation-import-assistant">
              <p v-if="isMobile && !cardText.trim()" class="mobile-tools-notice">请先粘贴人物卡原文，识别结果会显示在这里。</p>
              <CharacterCardImportGuide v-if="!isMobile" />

              <template v-if="cardText.trim()">
                <section class="import-result-status" :class="{ ready: importPreview.ready }" aria-live="polite">
                  <CircleCheck v-if="importPreview.ready" :size="21" />
                  <TriangleAlert v-else :size="21" />
                  <span><strong>{{ importPreview.ready ? '必填内容已识别，可以导入' : `还需处理 ${importRequiredIssueCount} 处必填内容` }}</strong><small>{{ importPreview.ready ? '点击下方按钮后，将按当前内容创建并绑定人物卡。' : '根据下方的字段提示修改原文。' }}</small></span>
                </section>

                <section class="import-required-checks">
                  <header><strong>必填内容</strong><small>需要全部通过</small></header>
                  <article :class="importPreview.identity ? 'complete' : 'missing'">
                    <CircleCheck v-if="importPreview.identity" :size="17" /><TriangleAlert v-else :size="17" />
                    <span><strong>身份信息</strong><small v-if="importPreview.identity">已识别：{{ importPreview.identity.name }} · {{ importPreview.identity.occupation }} · {{ importPreview.identity.sex }} · {{ importPreview.identity.age }} 岁</small><small v-else>没有识别到。请把第一行写成“姓名，职业，性别，年龄岁”。</small></span>
                  </article>
                  <article :class="importPreview.missingAttributes.length ? 'missing' : 'complete'">
                    <CircleCheck v-if="!importPreview.missingAttributes.length" :size="17" /><TriangleAlert v-else :size="17" />
                    <span><strong>八项基础属性</strong><small v-if="!importPreview.missingAttributes.length">八项属性已全部识别。</small><small v-else>已识别 {{ importRecognizedAttributeCount }}/8，还缺 {{ importPreview.missingAttributes.join('、') }}。</small></span>
                  </article>
                </section>

                <section v-if="importPreview.warnings.length" class="import-validation-warnings">
                  <strong>还需要修改</strong>
                  <p v-for="warning in importPreview.warnings" :key="warning"><TriangleAlert :size="15" />{{ warning }}</p>
                </section>

                <section class="import-recognized-preview">
                  <header><strong>属性识别结果</strong><small>对照原文检查</small></header>
                  <div><span v-for="item in importAttributeItems" :key="item.code" :class="{ missing: importPreview.attributes[item.code] == null }"><small>{{ item.label }} {{ item.code }}</small><strong>{{ importPreview.attributes[item.code] ?? '未识别' }}</strong></span></div>
                </section>

                <section class="import-optional-checks">
                  <header><strong>可选内容</strong><small>缺少也可以导入</small></header>
                  <div><span><CircleCheck v-if="importPreview.skillCount" :size="15" /><i v-else>—</i><span><strong>技能</strong><small>{{ importPreview.skillCount ? `已识别 ${importPreview.skillCount} 项` : '暂未识别' }}</small></span></span><span><CircleCheck v-if="importBackgroundRecognized" :size="15" /><i v-else>—</i><span><strong>背景</strong><small>{{ importBackgroundRecognized ? '已识别' : '暂未识别' }}</small></span></span></div>
                </section>
              </template>
            </aside>
          </div>
          <div v-if="!isMobile" class="creation-import-actions"><button class="button ghost" @click="selectedCreationMethod = null"><ArrowLeft :size="14" />返回选择</button><button class="button primary" :disabled="!importPreview.ready || busy" @click="execute(bindCard)"><ClipboardCheck :size="15" />导入并绑定人物卡</button></div>
        </section>
        <section v-else class="creation-method-picker">
          <header class="creation-method-heading"><span><small>选择建卡方式</small><strong>为{{ selectedName }}建立人物卡</strong><p>三种方式最终都会先让你检查内容，确认后才会绑定。</p></span><em>尚未绑定</em></header>
          <div class="creation-method-grid">
            <button
              v-for="option in creationMethods"
              :key="option.id"
              type="button"
              class="creation-method-card"
              :class="['method-' + option.id.toLowerCase(), { disabled: !option.enabled }]"
              :disabled="!option.enabled || busy"
              @click="execute(() => chooseCreationMethod(option.id))"
            >
              <span class="creation-method-icon"><template v-if="isMobile">{{ option.id === 'STEP' ? '01' : option.id === 'AUTO' ? '02' : '03' }}</template><template v-else><Dices v-if="option.id === 'STEP'" :size="24" /><Sparkles v-else-if="option.id === 'AUTO'" :size="24" /><BookUser v-else :size="24" /></template></span>
              <span class="creation-method-copy"><small>{{ creationMethodCopy[option.id].eyebrow }}</small><strong>{{ creationMethodCopy[option.id].title }}</strong><p>{{ creationMethodCopy[option.id].description }}</p></span>
              <span class="creation-method-action">{{ option.enabled ? creationMethodCopy[option.id].action : '仅 AI 调查员可用' }}</span>
            </button>
          </div>
          <p class="creation-method-footnote">选择步进建卡后会自动保存进度；关闭窗口不会丢失已填写内容。</p>
        </section>
      </aside>
    </div>
    <div v-if="busy" class="dialog-busy"><LoaderCircle class="spin" :size="17" />正在处理…</div>
    <template v-if="!mobileWizardActive && (!isMobile || !mobileDetailOpen || selectedCreationMethod === 'IMPORT' || autoDraft || selectedCreationMethod === 'AUTO')" #footer>
      <template v-if="isMobile && mobileDetailOpen">
        <button v-if="selectedCreationMethod === 'IMPORT'" class="button primary" :disabled="!importPreview.ready || busy" @click="execute(bindCard)">导入并绑定人物卡</button>
        <button v-else-if="autoDraft" class="button primary" :disabled="busy" @click="execute(confirmGeneratedCard)">确认并绑定人物卡</button>
        <button v-else-if="selectedCreationMethod === 'AUTO'" class="button primary" :disabled="busy" @click="execute(generateCard)">开始自动生成</button>
      </template>
      <template v-else>
      <span v-if="!isMobile" class="binding-footer-status">{{ complete ? '全部人物卡已绑定' : `仍有 ${targets.length - completedCount} 张人物卡待绑定` }}</span>
      <button v-if="!isMobile" class="button ghost" :disabled="busy || wizardBusy" @click="open = false">稍后处理</button>
      <button class="button primary" :disabled="!complete || busy || wizardBusy" @click="finish">{{ !complete && isMobile ? `仍有 ${targets.length - completedCount} 张待绑定` : '完成并进入跑团' }}</button>
      </template>
    </template>
  </BaseDialog>
  <BaseDialog v-if="isMobile" v-model="importGuideOpen" title="模板与导入说明"
    :description="`返回后继续为${selectedName}导入人物卡，已粘贴的内容会保留。`"
    layer="foreground" mobile-presentation="page" content-class="import-guide-dialog">
    <CharacterCardImportGuide />
    <template #footer><button class="button primary" @click="importGuideOpen = false"><ArrowLeft :size="16" />返回粘贴人物卡</button></template>
  </BaseDialog>
  <DicePlayerDialog v-model="diceOpen" :request="diceRequest" />
</template>

<style>
.import-guide-entry { display: flex; align-items: center; gap: 12px; width: 100%; padding: 14px; margin: 12px 0; border: 1px solid var(--line); border-radius: 10px; background: #fffef9; color: var(--pine); text-align: left; }
.import-guide-entry > span { display: grid; flex: 1; gap: 5px; min-width: 0; }
.import-guide-entry strong { font-size: 14px; }
.import-guide-entry small { color: var(--muted); font-size: 12px; }
@media (max-width: 767px) {
  .mobile-binding-navigation { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
  .mobile-binding-navigation strong { font-size: 14px; overflow-wrap: anywhere; }
  .trpg-character-creation-dialog .trpg-binding-targets { max-height: none; }
  .trpg-character-creation-dialog .trpg-binding-layout { height: auto; min-height: 0; grid-template-columns: minmax(0, 1fr); gap: 18px; }
  .trpg-character-creation-dialog .trpg-binding-card-pane { min-width: 0; overflow: visible; padding: 0; border: 0; }
  .trpg-character-creation-dialog .trpg-binding-list-pane { overflow: visible; }
  .trpg-character-creation-dialog .creation-import-workbench { min-height: 0; padding-bottom: 0; border: 0; border-radius: 0; box-shadow: none; }
  .trpg-character-creation-dialog .creation-import-layout { min-height: 0; grid-template-columns: minmax(0, 1fr); padding: 0; gap: 16px; }
  .trpg-character-creation-dialog .creation-import-editor { padding: 14px; }
  .trpg-character-creation-dialog .creation-import-editor textarea { min-height: 240px; font-size: 16px; line-height: 1.8; }
  .trpg-character-creation-dialog .creation-import-assistant { overflow: visible; padding: 14px; }
  .trpg-character-creation-dialog .creation-import-assistant:empty { display: none; }
  .trpg-character-creation-dialog .import-workbench-heading { flex-wrap: wrap; padding: 18px 0; }
  .trpg-character-creation-dialog .import-workbench-heading h3 { font-size: 18px; overflow-wrap: anywhere; }
  .trpg-character-creation-dialog .import-process-strip { gap: 8px; padding: 12px 0; flex-wrap: wrap; }
  .trpg-character-creation-dialog .creation-import-actions { padding: 16px 0; flex-wrap: wrap; }
  .trpg-character-creation-dialog .creation-import-actions .button { min-height: 48px; }
  .trpg-character-creation-dialog .creation-import-actions .primary { flex: 1; }
  .trpg-character-creation-dialog .creation-method-grid { grid-template-columns: minmax(0, 1fr); }
  .trpg-character-creation-dialog .creation-method-heading { flex-wrap: wrap; }
  .trpg-character-creation-dialog .import-format-help span,
  .trpg-character-creation-dialog .import-required-checks small,
  .trpg-character-creation-dialog .import-result-status small { font-size: 12px; line-height: 1.75; }
  .trpg-character-creation-dialog .import-recognized-preview > div { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
