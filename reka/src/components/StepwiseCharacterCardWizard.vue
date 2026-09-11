<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  ArrowLeft, BookUser, Check, ChevronDown, Dices, FileCheck2, LoaderCircle, Minus, Plus, RotateCw, Search,
  TriangleAlert,
} from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import './mobile-tools.css'
import { api } from '@/api/client'
import type {
  CharacterCard, CharacterCardCreationDraft, CharacterCardCreationRules,
} from '@/api/types'
import DicePlayerDialog from '@/dice/components/DicePlayerDialog.vue'
import { resolveDiceSkin, type DicePlaybackRequest } from '@/dice/domain/dicePlayback'
import { errorMessage } from '@/composables/useNotice'
import {
  buildAttributeDicePlayback,
  buildBackgroundPromptDicePlayback,
} from '@/components/characterCardCreationDice'
import { prepareStepwiseBackgroundSubmission } from '@/components/trpgSetupState'
import {
  filterStepwiseWeapons,
  MAX_STEPWISE_WEAPONS,
  normalizeStepwiseEra,
  restoreStepwiseWeaponCodes,
  stepwiseWeaponSelectionPayload,
} from '@/components/stepwiseWeaponCatalog'

const draft = defineModel<CharacterCardCreationDraft | null>('draft', { required: true })
const props = defineProps<{
  runId: number
  participantId?: number
  defaultName?: string
  defaultEra?: string
  diceSkin?: string
}>()
const emit = defineEmits<{ complete: [card: CharacterCard]; abandoned: []; busyChange: [busy: boolean] }>()

const { isMobile } = useMobileViewport()
const stepsExpanded = ref(false)
const previewOpen = ref(false)
const backgroundDetail = ref<'connections' | 'memories' | null>(null)
const equipmentPickerOpen = ref(false)
const backgroundDetailOpen = computed({ get: () => backgroundDetail.value !== null, set: (open: boolean) => { if (!open) backgroundDetail.value = null } })
const stepHeading = ref<HTMLElement | null>(null)
const rules = ref<CharacterCardCreationRules | null>(null)
const busy = ref(false)
watch(busy, value => emit('busyChange', value), { flush: 'sync' })
onBeforeUnmount(() => emit('busyChange', false))
const failure = ref('')
const diceOpen = ref(false)
const diceRequest = ref<DicePlaybackRequest | null>(null)
const diceRequestId = ref(0)
const skillSearch = ref('')
const weaponSearch = ref('')
const weaponCategory = ref<'ALL' | 'MELEE' | 'FIREARM' | 'OTHER_RANGED'>('ALL')
const selectedWeaponCodes = ref<string[]>([])
const confirmAbandon = ref(false)

const identity = reactive({
  name: props.defaultName || '', occupation: '', age: 28, sex: '', residence: '', birthplace: '',
})
const agePenalties = reactive<Record<string, number>>({ STR: 0, CON: 0, SIZ: 0, DEX: 0 })
const occupationText = ref('')
const skillInputs = reactive<Record<number, { points: number; specialization: string }>>({})
const backgroundEntries = reactive<Record<string, string>>({})
const keyConnectionCategory = ref('')
const equipment = reactive({
  era: normalizeStepwiseEra(props.defaultEra), equipmentText: '', assetsText: '', spendingLevel: '', cash: '',
})

const STEP_META = [
  { code: 'IDENTITY', label: '填写信息' },
  { code: 'ATTRIBUTES', label: '生成属性' },
  { code: 'OCCUPATION', label: '确认职业' },
  { code: 'SKILLS', label: '分配技能' },
  { code: 'BACKGROUND', label: '填写背景' },
  { code: 'EQUIPMENT', label: '添加装备' },
] as const
const BACKGROUND_META = [
  { code: 'APPEARANCE', label: '形象描述', rollable: false, placeholder: '结合 APP 描述调查员的外貌、衣着和气质。' },
  { code: 'IDEOLOGY', label: '思想与信念', rollable: true, placeholder: '写下调查员真正相信或坚持的事情。' },
  { code: 'SIGNIFICANT_PEOPLE', label: '重要之人', rollable: true, placeholder: '为这个人命名，并说明彼此关系。' },
  { code: 'MEANINGFUL_LOCATIONS', label: '意义非凡之地', rollable: true, placeholder: '为这个地点命名，并说明它为何重要。' },
  { code: 'TREASURED_POSSESSIONS', label: '宝贵之物', rollable: true, placeholder: '描述珍藏的物件以及背后的故事。' },
  { code: 'TRAITS', label: '特质', rollable: true, placeholder: '描述最鲜明的一项性格或习惯。' },
] as const
const WEAPON_CATEGORY_META = [
  { code: 'ALL', label: '全部' },
  { code: 'MELEE', label: '近战' },
  { code: 'FIREARM', label: '枪械' },
  { code: 'OTHER_RANGED', label: '其他远程' },
] as const

const state = computed(() => draft.value?.state.stepwise)
const attributes = computed(() => state.value?.attributes)
const currentStep = computed(() => draft.value?.currentStep || 'IDENTITY')
const activeStepIndex = computed(() => Math.max(0, STEP_META.findIndex((step) => step.code === currentStep.value)))
const finalAttributes = computed(() => attributes.value?.finalValues || attributes.value?.raw || {})
const age = computed(() => state.value?.identity.age || identity.age)
const requiredAgePenalty = computed(() => {
  if (age.value < 20) return 5
  if (age.value < 40) return 0
  if (age.value < 50) return 5
  if (age.value < 60) return 10
  if (age.value < 70) return 20
  if (age.value < 80) return 40
  return 80
})
const agePenaltyCodes = computed(() => age.value < 20 ? ['STR', 'SIZ'] : ['STR', 'CON', 'DEX'])
const assignedAgePenalty = computed(() => agePenaltyCodes.value.reduce((sum, code) => sum + agePenalties[code], 0))
const backgroundCount = computed(() => BACKGROUND_META.filter((item) => backgroundEntries[item.code]?.trim()).length)
const backgroundPrompts = computed(() => state.value?.background?.prompts || {})
const rulesBySkillId = computed(() => new Map((rules.value?.skills || []).map((skill) => [skill.skillDefId, skill])))
const skillBudget = computed(() => {
  const values = Object.values(finalAttributes.value)
  if (!values.length) return 0
  return (finalAttributes.value.EDU || 0) * 2 + (finalAttributes.value.INT || 0) * 2 + Math.max(...values) * 2
})
const skillSpent = computed(() => Object.values(skillInputs).reduce((sum, input) => sum + Math.max(0, input.points || 0), 0))
const availableSkills = computed(() => (rules.value?.skills || []).filter((skill) => {
  if (skill.name === '克苏鲁神话' || skill.baseValue == null && !skill.baseFormula) return false
  const query = skillSearch.value.trim().toLocaleLowerCase()
  return !query || (skill.name + ' ' + (skill.category || '') + ' ' + (skill.parentName || '')).toLocaleLowerCase().includes(query)
}))
const eraWeapons = computed(() => filterStepwiseWeapons(rules.value?.weapons || [], equipment.era))
const availableWeapons = computed(() => eraWeapons.value.filter((weapon) => {
  if (weaponCategory.value !== 'ALL' && weapon.kind !== weaponCategory.value) return false
  const query = weaponSearch.value.trim().toLocaleLowerCase()
  return !query || `${weapon.name} ${weapon.skillName} ${weapon.damage}`.toLocaleLowerCase().includes(query)
}))
const canCreate = computed(() => identity.name.trim() && identity.occupation.trim() && identity.sex.trim()
  && identity.residence.trim() && identity.birthplace.trim() && identity.age >= 15 && identity.age <= 90)
const canConfirmSkills = computed(() => skillSpent.value <= skillBudget.value
  && Object.entries(skillInputs).every(([id, input]) => input.points <= 0
    || !rulesBySkillId.value.get(Number(id))?.allowSpecialization || input.specialization.trim()))
const mobileNextLabel = computed(() => !draft.value ? '下一步：生成属性' : currentStep.value === 'ATTRIBUTES' ? (draft.value.nextAction === 'ROLL_ATTRIBUTES' ? '掷骰生成属性' : '确认年龄调整') : currentStep.value === 'OCCUPATION' ? '下一步：分配技能' : currentStep.value === 'SKILLS' ? '下一步：填写背景' : currentStep.value === 'BACKGROUND' ? '下一步：添加装备' : '检查并绑定人物卡')
const mobileNextDisabled = computed(() => busy.value || (!draft.value ? !canCreate.value : currentStep.value === 'ATTRIBUTES' ? draft.value.nextAction === 'RESTART_REQUIRED' || (draft.value.nextAction === 'SUBMIT_AGE_ADJUSTMENT' && assignedAgePenalty.value !== requiredAgePenalty.value) : currentStep.value === 'OCCUPATION' ? !occupationText.value.trim() : currentStep.value === 'SKILLS' ? !canConfirmSkills.value : false))
async function mobileNext() {
  if (!draft.value) return createDraft()
  if (draft.value.status === 'PREVIEW_READY') return completeDraft()
  if (currentStep.value === 'ATTRIBUTES') return draft.value.nextAction === 'ROLL_ATTRIBUTES' ? rollAttributes() : submitAgeAdjustment()
  if (currentStep.value === 'OCCUPATION') return confirmOccupation()
  if (currentStep.value === 'SKILLS') return confirmSkills()
  if (currentStep.value === 'BACKGROUND') return confirmBackground()
  if (currentStep.value === 'EQUIPMENT') return confirmEquipment()
}
function requestId() {
  return crypto.randomUUID?.() || 'step-card-' + Date.now() + '-' + Math.random().toString(36).slice(2)
}

function skillInput(skillDefId: number) {
  return skillInputs[skillDefId] ||= { points: 0, specialization: '' }
}

function skillBase(skill: CharacterCardCreationRules['skills'][number]) {
  if (skill.baseValue != null) return skill.baseValue
  const formula = (skill.baseFormula || '').toUpperCase()
  if (formula.includes('DEX')) return Math.floor((finalAttributes.value.DEX || 0) / 2)
  if (formula.includes('EDU')) return finalAttributes.value.EDU || 0
  return 0
}

function syncFromDraft(value: CharacterCardCreationDraft | null) {
  const next = value?.state.stepwise
  if (!next) return
  Object.assign(identity, {
    name: next.identity.name, occupation: next.identity.occupation, age: next.identity.age,
    sex: next.identity.sex, residence: next.identity.residence, birthplace: next.identity.birthplace,
  })
  occupationText.value = next.occupation?.text || next.identity.occupation
  Object.keys(agePenalties).forEach((code) => { agePenalties[code] = next.attributes?.ageAdjustment?.[code] || 0 })
  if (next.skills) {
    next.skills.items.forEach((item) => {
      skillInputs[item.skillDefId] = { points: item.allocatedPoints, specialization: item.specialization || '' }
    })
  }
  BACKGROUND_META.forEach((item) => { backgroundEntries[item.code] = next.background?.entries?.[item.code] || '' })
  keyConnectionCategory.value = next.background?.keyConnectionCategory || ''
  if (next.equipment) {
    Object.assign(equipment, {
      era: normalizeStepwiseEra(next.equipment.era || props.defaultEra), equipmentText: next.equipment.equipmentText || '',
      assetsText: next.equipment.assetsText || '', spendingLevel: next.equipment.spendingLevel || '',
      cash: next.equipment.cash || '',
    })
    if (rules.value) {
      selectedWeaponCodes.value = restoreStepwiseWeaponCodes(
        filterStepwiseWeapons(rules.value.weapons, equipment.era),
        next.equipment.weapons || [],
      )
    }
  }
}

async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  failure.value = ''
  try {
    await action()
  } catch (error) {
    failure.value = errorMessage(error)
  } finally {
    busy.value = false
  }
}

async function createDraft() {
  draft.value = await api.createStepCharacterCardDraft({
    runId: props.runId,
    ...(props.participantId === undefined ? {} : { participantId: props.participantId }),
    ...identity,
  })
}

async function rollAttributes() {
  if (!draft.value) return
  const next = await api.rollCharacterCardDraftAttributes(draft.value.draftId, {
    requestId: requestId(), expectedVersion: draft.value.version,
  })
  draft.value = next
  if (next.state.stepwise?.attributes) {
    diceRequest.value = buildAttributeDicePlayback(
      next.state.stepwise.attributes,
      resolveDiceSkin(props.diceSkin),
      diceRequestId.value,
    )
    diceRequestId.value = diceRequest.value.id
    diceOpen.value = true
  }
}

function adjustAgePenalty(code: string, delta: number) {
  const current = agePenalties[code] || 0
  const raw = attributes.value?.raw?.[code] || 0
  const next = Math.max(0, Math.min(raw, current + delta))
  if (next > current && assignedAgePenalty.value + (next - current) > requiredAgePenalty.value) return
  agePenalties[code] = next
}

async function submitAgeAdjustment() {
  if (!draft.value || assignedAgePenalty.value !== requiredAgePenalty.value) return
  draft.value = await api.saveCharacterCardDraftAgeAdjustment(draft.value.draftId, {
    strPenalty: agePenalties.STR, conPenalty: agePenalties.CON,
    sizPenalty: agePenalties.SIZ, dexPenalty: agePenalties.DEX,
    expectedVersion: draft.value.version,
  })
}

async function confirmOccupation() {
  if (!draft.value || !occupationText.value.trim()) return
  draft.value = await api.saveCharacterCardDraftOccupation(draft.value.draftId, {
    occupation: occupationText.value.trim(), confirmed: true, expectedVersion: draft.value.version,
  })
}

async function confirmSkills() {
  if (!draft.value || !canConfirmSkills.value) return
  const allocations = Object.entries(skillInputs)
    .filter(([, input]) => input.points > 0)
    .map(([skillDefId, input]) => ({
      skillDefId: Number(skillDefId),
      ...(input.specialization.trim() ? { specialization: input.specialization.trim() } : {}),
      allocatedPoints: input.points,
    }))
  draft.value = await api.saveCharacterCardDraftSkills(draft.value.draftId, {
    allocations, confirmed: true, expectedVersion: draft.value.version,
  })
}

async function rollBackground(category: string) {
  if (!draft.value) return
  const next = await api.rollCharacterCardDraftBackground(draft.value.draftId, category, {
    requestId: requestId(), expectedVersion: draft.value.version,
  })
  draft.value = next
  const prompt = next.state.stepwise?.background?.prompts?.[category]
  if (prompt) {
    diceRequest.value = buildBackgroundPromptDicePlayback(prompt, props.diceSkin, diceRequestId.value)
    diceRequestId.value = diceRequest.value.id
    diceOpen.value = true
  }
}

async function confirmBackground() {
  if (!draft.value) return
  const background = prepareStepwiseBackgroundSubmission(
    BACKGROUND_META.map((item) => item.code),
    backgroundEntries,
    keyConnectionCategory.value,
  )
  draft.value = await api.saveCharacterCardDraftBackground(draft.value.draftId, {
    ...background,
    confirmed: true, expectedVersion: draft.value.version,
  })
}

async function confirmEquipment() {
  if (!draft.value) return
  const completedDraft = await api.saveCharacterCardDraftEquipment(draft.value.draftId, {
    era: equipment.era, equipmentText: equipment.equipmentText, assetsText: equipment.assetsText,
    spendingLevel: equipment.spendingLevel, cash: equipment.cash,
    weapons: stepwiseWeaponSelectionPayload(selectedWeaponCodes.value),
    confirmed: true, expectedVersion: draft.value.version,
  })
  draft.value = completedDraft
  await completeDraft(completedDraft)
}

async function completeDraft(completedDraft = draft.value) {
  if (!completedDraft) return
  const card = await api.completeCharacterCardDraft(completedDraft.draftId, {
    requestId: requestId(), expectedVersion: completedDraft.version,
  })
  draft.value = null
  emit('complete', card)
}

async function abandonDraft() {
  if (!draft.value) return
  if (!confirmAbandon.value) {
    confirmAbandon.value = true
    return
  }
  await api.abandonCharacterCardDraft(draft.value.draftId, draft.value.version)
  draft.value = null
  confirmAbandon.value = false
  emit('abandoned')
}

watch(currentStep, async () => {
  stepsExpanded.value = false
  if (!isMobile.value) return
  await nextTick()
  stepHeading.value?.closest('.dialog-body')?.scrollTo({ top: 0 })
  stepHeading.value?.focus({ preventScroll: true })
})
watch(draft, syncFromDraft, { immediate: true })
watch(() => equipment.era, () => {
  const availableCodes = new Set(eraWeapons.value.map((weapon) => weapon.code))
  selectedWeaponCodes.value = selectedWeaponCodes.value.filter((code) => availableCodes.has(code))
})
onMounted(() => execute(async () => {
  rules.value = await api.characterCardCreationRules()
  syncFromDraft(draft.value)
  if (draft.value?.status === 'PREVIEW_READY') await completeDraft(draft.value)
}))
</script>

<template>
  <section class="stepwise-character-card-wizard">
    <div v-if="isMobile" class="mobile-wizard-progress"><div>第 {{ activeStepIndex + 1 }} 步 / 6 <span>{{ STEP_META[activeStepIndex]?.label }}</span></div><nav aria-label="建卡进度"><i v-for="(_, index) in STEP_META" :key="index" :class="{ current: index === activeStepIndex, later: index > activeStepIndex }" /></nav></div>
    <nav v-show="!isMobile" class="creation-dossier-tabs" aria-label="建卡进度">
      <span
        v-for="(step, index) in STEP_META"
        :key="step.code"
        :class="{ active: index === activeStepIndex, complete: index < activeStepIndex }"
      >
        <i>{{ index < activeStepIndex ? '✓' : index + 1 }}</i>{{ step.label }}
      </span>
    </nav>

    <div class="creation-workbench">
      <main class="creation-step-panel">
        <header v-if="!isMobile" class="creation-step-heading">
          <span><small>第 {{ activeStepIndex + 1 }} 步，共 6 步</small><h3 ref="stepHeading" tabindex="-1">{{ STEP_META[activeStepIndex]?.label }}</h3></span>
          <em v-if="draft" aria-live="polite">{{ busy ? '正在保存…' : failure ? '保存失败，请重试' : '已保存步骤可恢复' }}</em>
        </header>

        <form v-if="!draft" class="creation-identity-form" @submit.prevent="execute(createDraft)">
          <p v-if="!isMobile" class="creation-step-intro">先填写调查员的基本信息。年龄会决定后续属性调整与教育成长次数。</p>
          <label class="field field-wide"><span>调查员姓名</span><input v-model.trim="identity.name" maxlength="255" /></label>
          <label class="field mobile-occupation"><span>职业</span><input v-model.trim="identity.occupation" maxlength="255" placeholder="记者、乡村医生、退役水手…" /></label>
          <label class="field mobile-age"><span>年龄</span><input v-model.number="identity.age" type="number" min="15" max="90" /></label>
          <label class="field mobile-sex"><span>性别</span><input v-model.trim="identity.sex" maxlength="255" /></label>
          <label class="field"><span>住地</span><input v-model.trim="identity.residence" maxlength="255" /></label>
          <label class="field"><span>出生地</span><input v-model.trim="identity.birthplace" maxlength="255" /></label>
          <p v-if="!canCreate" class="creation-action-hint">请填写完整基本信息后继续；年龄须在 15–90 岁之间。</p>
          <div class="creation-primary-action"><button class="button primary" :disabled="!canCreate || busy"><Dices :size="15" />保存信息并生成属性</button></div>
        </form>

        <section v-else-if="currentStep === 'ATTRIBUTES'" class="creation-attributes-step">
          <div v-if="isMobile && attributes" class="mobile-wizard-attributes" aria-label="基础属性"><div v-for="[code, name] in [['STR', '力量'], ['CON', '体质'], ['SIZ', '体型'], ['DEX', '敏捷'], ['APP', '外貌'], ['INT', '智力'], ['POW', '意志'], ['EDU', '教育']]" :key="code"><small>{{ name }}</small><strong>{{ finalAttributes[code] ?? '—' }}</strong></div></div>
          <template v-if="draft.nextAction === 'ROLL_ATTRIBUTES'">
            <p class="creation-step-intro">一次掷出八项属性和幸运值，骰子会分三轮展示。掷骰结果会自动保存在当前建卡进度中。</p>
            <div class="attribute-formula-grid">
              <span v-for="rule in rules?.attributes" :key="rule.code"><b>{{ rule.code }}</b><small>{{ rule.formula }}</small></span>
              <span><b>幸运</b><small>3D6 × 5</small></span>
            </div>
            <div class="creation-roll-callout"><Dices :size="34" /><span><strong>准备生成全部属性</strong><small>属性不能单项重投；如需重来，应放弃当前草稿。</small></span><button class="button primary" :disabled="busy" @click="execute(rollAttributes)">掷出全部属性</button></div>
          </template>
          <template v-else-if="draft.nextAction === 'SUBMIT_AGE_ADJUSTMENT'">
            <div class="age-adjustment-heading"><span><strong>分配年龄物理减值</strong><small>每次只能增减 5 点，合计必须准确。</small></span><em>还需分配 {{ requiredAgePenalty - assignedAgePenalty }} 点</em></div>
            <div class="age-penalty-grid">
              <article v-for="code in agePenaltyCodes" :key="code">
                <span><small>{{ code }} 原值</small><strong>{{ attributes?.raw?.[code] }}</strong></span>
                <div><button type="button" aria-label="减少减值" :disabled="agePenalties[code] <= 0" @click="adjustAgePenalty(code, -5)"><Minus :size="15" /></button><b>−{{ agePenalties[code] }}</b><button type="button" aria-label="增加减值" @click="adjustAgePenalty(code, 5)"><Plus :size="15" /></button></div>
                <em>调整后 {{ (attributes?.raw?.[code] || 0) - agePenalties[code] }}</em>
              </article>
            </div>
            <div class="creation-primary-action"><button class="button primary" :disabled="assignedAgePenalty !== requiredAgePenalty || busy" @click="execute(submitAgeAdjustment)">确认年龄调整</button></div>
          </template>
          <div v-else-if="draft.nextAction === 'RESTART_REQUIRED'" class="creation-warning-card"><strong>这组属性无法承受年龄减值</strong><p>当前草稿不能继续，请放弃后重新开始完整属性生成。</p></div>
        </section>

        <section v-else-if="currentStep === 'OCCUPATION'" class="creation-occupation-step">
          <p class="creation-step-intro">职业用于描述调查员的经历。这里填写的职业名称不会改变技能点总数，也不会限制可选技能。</p>
          <label class="field"><span>职业名称</span><input v-model.trim="occupationText" maxlength="255" /></label>
          <div class="dossier-note"><strong>填写示例</strong><p>可以填写“记者”“乡村医生”，也可以写更具体的自由职业。</p></div>
          <div class="creation-primary-action"><button class="button primary" :disabled="!occupationText.trim() || busy" @click="execute(confirmOccupation)">确认职业并继续</button></div>
        </section>

        <section v-else-if="currentStep === 'SKILLS'" class="creation-skills-step">
          <div class="skill-budget-strip"><span><small>总技能点</small><strong>{{ skillBudget }}</strong></span><span><small>已投入</small><strong>{{ skillSpent }}</strong></span><span :class="{ over: skillSpent > skillBudget }"><small>剩余</small><strong>{{ skillBudget - skillSpent }}</strong></span></div>
          <label class="creation-skill-search"><Search :size="15" /><input v-model.trim="skillSearch" placeholder="搜索技能或类别" /></label>
          <div class="creation-skill-list">
            <article v-for="skill in availableSkills" :key="skill.skillDefId">
              <span><strong>{{ skill.name }}</strong><small>{{ skill.category || '通用技能' }} · 基础 {{ skillBase(skill) }}%</small></span>
              <input v-if="skill.allowSpecialization" v-model.trim="skillInput(skill.skillDefId).specialization" class="skill-specialization" placeholder="填写专攻" />
              <label><small>投入</small><input v-model.number="skillInput(skill.skillDefId).points" type="number" min="0" :max="99 - skillBase(skill)" /></label>
              <b>{{ Math.min(99, skillBase(skill) + (skillInputs[skill.skillDefId]?.points || 0)) }}%</b>
            </article>
          </div>
          <p v-if="skillSpent > skillBudget" class="creation-error" role="alert">已超出 {{ skillSpent - skillBudget }} 点，请减少投入后继续。</p>
          <div class="creation-primary-action"><button class="button primary" :disabled="!canConfirmSkills || busy" @click="execute(confirmSkills)">确认技能分配</button></div>
        </section>

        <section v-else-if="currentStep === 'BACKGROUND'" class="creation-background-step">
          <div class="background-progress"><span>已填写 {{ backgroundCount }}/6 项</span><small>所有项目均可留空；未选择关键连接时，系统会自动指定。</small></div>
          <article v-for="item in BACKGROUND_META.filter(item => !isMobile || ['APPEARANCE', 'IDEOLOGY'].includes(item.code))" :key="item.code" class="background-dossier-card">
            <header><span><strong>{{ item.label }}</strong></span><button v-if="item.rollable" class="button ghost" :disabled="busy" @click="execute(() => rollBackground(item.code))"><RotateCw :size="13" />{{ backgroundPrompts[item.code] ? '重新掷提示' : '掷骰提示' }}</button></header>
            <div v-if="backgroundPrompts[item.code]" class="background-prompt"><Dices :size="14" /><span><small>随机提示 · {{ backgroundPrompts[item.code].rolls.join(' / ') }}</small><p>{{ backgroundPrompts[item.code].prompts.join('；') }}</p></span></div>
            <textarea v-model="backgroundEntries[item.code]" rows="3" maxlength="1000" :placeholder="item.placeholder" />
            <label v-if="item.code !== 'APPEARANCE' && backgroundEntries[item.code]?.trim()" class="key-connection-choice"><input v-model="keyConnectionCategory" type="radio" :value="item.code" />设为关键连接</label>
          </article>
          <template v-if="isMobile"><button type="button" class="mobile-wizard-row" @click="backgroundDetail = 'connections'"><span><strong>重要之人 / 意义非凡之地</strong><small>填写联系并标记关键联系</small></span><ChevronDown :size="18" /></button><button type="button" class="mobile-wizard-row" @click="backgroundDetail = 'memories'"><span><strong>宝贵之物 / 特质</strong><small>整理人物的随身记忆</small></span><ChevronDown :size="18" /></button></template>
          <div class="creation-primary-action"><button class="button primary" :disabled="busy" @click="execute(confirmBackground)">确认背景并继续</button></div>
        </section>

        <section v-else-if="currentStep === 'EQUIPMENT' && draft.status !== 'PREVIEW_READY'" class="creation-equipment-step">
          <div class="creation-form-grid">
            <label class="field field-wide"><span>时代</span><select v-model="equipment.era" :disabled="Boolean(props.defaultEra)"><option v-for="era in rules?.eras || ['1920S', 'MODERN']" :key="era" :value="era">{{ era === '1920S' ? '1920年代' : era === 'MODERN' ? '现代' : era }}</option></select><small v-if="props.defaultEra">由当前模组决定</small></label>
            <label class="field"><span>消费水平</span><input v-model.trim="equipment.spendingLevel" placeholder="例如：每天 10 美元" /></label>
            <label class="field"><span>现金</span><input v-model.trim="equipment.cash" /></label>
            <label class="field field-wide"><span>随身装备</span><textarea v-model="equipment.equipmentText" rows="4" /></label>
            <label class="field field-wide"><span>资产</span><textarea v-model="equipment.assetsText" rows="3" /></label>
          </div>
          <div class="weapon-editor-heading"><span><strong>携带的武器</strong><small>从当前时代的常用武器中选择，至多 3 件；伤害、射程和弹药等数据会自动带入。</small></span><em>已选择 {{ selectedWeaponCodes.length }}/{{ MAX_STEPWISE_WEAPONS }} 件</em></div>
          <button v-if="isMobile" type="button" class="mobile-wizard-row" @click="equipmentPickerOpen = true"><span><strong>选择武器</strong><small>已选择 {{ selectedWeaponCodes.length }} / {{ MAX_STEPWISE_WEAPONS }} 件 · 按现有武器目录校验</small></span><ChevronDown :size="18" /></button>
          <component :is="isMobile ? BaseDialog : 'div'" v-bind="isMobile ? { modelValue: equipmentPickerOpen, title: '选择武器', mobilePresentation: 'page', layer: 'foreground', contentClass: 'mobile-equipment-picker' } : {}" @update:model-value="equipmentPickerOpen = $event">
          <div class="weapon-selector-toolbar">
            <nav aria-label="武器分类">
              <button v-for="category in WEAPON_CATEGORY_META" :key="category.code" type="button" :class="{ active: weaponCategory === category.code }" @click="weaponCategory = category.code">{{ category.label }}</button>
            </nav>
            <label><Search :size="14" /><input v-model.trim="weaponSearch" placeholder="搜索名称、技能或伤害" /></label>
          </div>
          <div class="weapon-option-grid">
            <label v-for="weapon in availableWeapons" :key="weapon.code" class="weapon-option-card" :class="{ selected: selectedWeaponCodes.includes(weapon.code), unavailable: selectedWeaponCodes.length >= MAX_STEPWISE_WEAPONS && !selectedWeaponCodes.includes(weapon.code) }">
              <input v-model="selectedWeaponCodes" type="checkbox" :value="weapon.code" :disabled="selectedWeaponCodes.length >= MAX_STEPWISE_WEAPONS && !selectedWeaponCodes.includes(weapon.code)" />
              <span class="weapon-option-check"><Check :size="13" /></span>
              <span class="weapon-option-title"><strong>{{ weapon.name }}</strong><small>{{ weapon.skillName }}</small></span>
              <span class="weapon-option-stats"><b>{{ weapon.damage }}</b><small>{{ weapon.range }} · 每轮 {{ weapon.attacksPerRound }} 次<span v-if="weapon.ammoCapacity != null"> · 弹量 {{ weapon.ammoCapacity }}</span></small></span>
              <span v-if="weapon.riskTags.length" class="weapon-option-risks">{{ weapon.riskTags.join(' · ') }}</span>
            </label>
            <p v-if="!availableWeapons.length" class="weapon-option-empty">当前筛选条件下没有可选武器。</p>
          </div>
          <template v-if="isMobile" #footer><button class="button primary" @click="equipmentPickerOpen = false">确认选择 · {{ selectedWeaponCodes.length }} 件</button></template>
          </component>
          <div class="creation-primary-action"><button class="button primary" :disabled="busy" @click="execute(confirmEquipment)"><FileCheck2 :size="15" />保存装备并完成绑定</button></div>
        </section>

        <section v-else-if="draft.status === 'PREVIEW_READY'" class="creation-binding-retry">
          <LoaderCircle v-if="busy" class="spin" :size="28" />
          <TriangleAlert v-else :size="28" />
          <strong>{{ busy ? '正在绑定人物卡' : '人物卡尚未完成绑定' }}</strong>
          <p>{{ busy ? '第六步已保存，正在完成最后的绑定。' : '上次自动绑定没有完成，可以直接重试。' }}</p>
          <button v-if="!busy" class="button primary" @click="execute(() => completeDraft(draft))">重试绑定</button>
        </section>

        <button v-if="isMobile" type="button" class="button secondary mobile-wizard-preview" @click="previewOpen = true">预览完整人物卡</button>
        <p v-if="failure" class="creation-error" role="alert">{{ failure }}</p>
        <div v-if="busy" class="creation-busy" role="status"><LoaderCircle class="spin" :size="15" />正在保存当前步骤…</div>
      </main>

      <component :is="isMobile ? BaseDialog : 'div'"
        v-bind="isMobile ? { modelValue: previewOpen, title: '人物卡预览', description: '查看当前建卡内容，返回后继续编辑。', mobilePresentation: 'page', layer: 'foreground', contentClass: 'mobile-creation-preview' } : { class: 'creation-preview-container' }"
        @update:model-value="previewOpen = $event">
      <aside class="creation-live-sheet">
        <header><small>人物卡预览</small><strong>{{ state?.identity.name || identity.name || '未命名调查员' }}</strong><span>{{ state?.identity.occupation || identity.occupation || '职业待定' }} · {{ age }} 岁</span></header>
        <div class="creation-live-attributes">
          <span v-for="code in ['STR', 'CON', 'SIZ', 'DEX', 'APP', 'INT', 'POW', 'EDU']" :key="code"><small>{{ code }}</small><b>{{ finalAttributes[code] ?? '—' }}</b></span>
        </div>
        <div class="creation-live-vitals"><span>HP <b>{{ attributes?.derived?.hp ?? '—' }}</b></span><span>SAN <b>{{ attributes?.derived?.san ?? '—' }}</b></span><span>MP <b>{{ attributes?.derived?.mp ?? '—' }}</b></span><span>幸运 <b>{{ attributes?.luck ?? '—' }}</b></span></div>
        <section><strong>技能分配</strong><p v-if="state?.skills?.items.length">{{ state.skills.items.filter((item) => item.allocatedPoints > 0).map((item) => item.displayName + ' ' + item.finalValue + '%').join(' · ') || '未投入额外技能点' }}</p><p v-else>尚未填写</p></section>
        <section><strong>背景完成度</strong><p>{{ backgroundCount }}/6 项<span v-if="keyConnectionCategory"> · 已选择关键连接</span></p></section>
        <section><strong>装备摘要</strong><p>{{ equipment.equipmentText || '尚未填写' }}</p></section>
        <button v-if="draft" class="creation-abandon" :class="{ confirm: confirmAbandon }" :disabled="busy" @click="execute(abandonDraft)"><ArrowLeft :size="13" />{{ confirmAbandon ? '再次点击确认放弃草稿' : '放弃并重新选择建卡方式' }}</button>
      </aside>
      </component>
    </div>
    <footer v-if="isMobile" class="mobile-wizard-footer"><button class="button primary" :disabled="mobileNextDisabled" @click="execute(mobileNext)"><LoaderCircle v-if="busy" class="spin" :size="15" />{{ mobileNextLabel }}</button></footer>
  </section>
  <BaseDialog v-if="isMobile" v-model="backgroundDetailOpen" :title="backgroundDetail === 'connections' ? '重要联系' : '宝贵之物与特质'" mobile-presentation="page" layer="foreground" content-class="mobile-background-detail">
    <article v-for="item in BACKGROUND_META.filter(item => (backgroundDetail === 'connections' ? ['SIGNIFICANT_PEOPLE', 'MEANINGFUL_LOCATIONS'] : ['TREASURED_POSSESSIONS', 'TRAITS']).includes(item.code))" :key="item.code" class="background-dossier-card">
      <header><strong>{{ item.label }}</strong><button class="button ghost" :disabled="busy" @click="execute(() => rollBackground(item.code))"><Dices :size="14" />掷骰提示</button></header>
      <p v-if="backgroundPrompts[item.code]" class="mobile-tools-notice">{{ backgroundPrompts[item.code].prompts.join('；') }}</p>
      <label class="field"><span>{{ item.label }}</span><textarea v-model="backgroundEntries[item.code]" rows="5" maxlength="1000" :placeholder="item.placeholder" /></label>
      <label v-if="backgroundEntries[item.code]?.trim()" class="key-connection-choice"><input v-model="keyConnectionCategory" type="radio" :value="item.code" />设为关键连接</label>
    </article>
    <template #footer><button class="button primary" @click="backgroundDetail = null">完成</button></template>
  </BaseDialog>

  <DicePlayerDialog v-model="diceOpen" :request="diceRequest" />
</template>
