<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, ArrowLeft, ChevronRight, Ellipsis, BookCopy, Download, FileUp, ImageUp, LockKeyhole, Plus, Save, Search, Trash2, UnlockKeyhole } from '@lucide/vue'
import { api, uploadImage } from '@/api/client'
import type {
  CharacterCardCreationRules, CocModule, CocModuleArchive, CocModuleClue, CocModuleDetail, CocModuleMaterial,
  CocModuleSavePayload, DraftCharacterCard,
} from '@/api/types'
import { parseModuleCharacterText, validateWeaponDamage } from './cocModuleCharacterImport'
import { automaticDerivedFields, syncAutomaticDerivedValues, type CharacterDerivedField } from './cocModuleCharacterDerived'
import { prioritizeNonBaseSkills } from './cocModuleCharacterSkills'
import { cloneCocModuleData } from './cocModuleData'
import { cocModulePayloadFingerprint, createCocModuleSaveQueue, saveCocModuleIfNeeded } from './cocModuleAutosave'
import { useMobileViewport } from '@/composables/useMobileViewport'
import BaseDialog from '@/components/ui/BaseDialog.vue'

type Tab = 'overview' | 'context' | 'locations' | 'clues' | 'materials' | 'characters'
type ImportedDraft = DraftCharacterCard & { profile: NonNullable<DraftCharacterCard['profile']> }
type CharacterEditorMode = 'choose' | 'parse' | 'edit'
type CharacterEditorTab = 'basics' | 'skills' | 'weapons' | 'background'

const emit = defineEmits<{ changed: []; detailOpenChange: [open: boolean] }>()
const { isMobile } = useMobileViewport()
const mobileView = ref<'list' | 'directory' | 'section'>('list')
const showModuleManagementActions = computed(() => !isMobile.value || mobileView.value !== 'section')
const mobileEntry = ref<number | null>(null)
const moduleScroller = ref<HTMLElement | null>(null)
let mobileListScrollTop = 0
const mobilePageTitle = computed(() => {
  if (mobileView.value === 'directory') return '模组详情'
  const index = mobileEntry.value
  if (index !== null) {
    if (activeTab.value === 'locations') return moduleForm.value?.locations[index]?.name || '编辑地点'
    if (activeTab.value === 'clues') return moduleForm.value?.clues[index]?.title || '编辑线索'
    if (activeTab.value === 'materials') return moduleForm.value?.materials[index]?.title || '编辑素材'
  }
  return sectionLabels[activeTab.value]
})
async function openMobileEntry(index: number) {
  mobileListScrollTop = moduleScroller.value?.scrollTop || 0
  mobileEntry.value = index
  await nextTick()
  if (moduleScroller.value) moduleScroller.value.scrollTop = 0
}
function deleteMobileEntry() {
  const index = mobileEntry.value
  if (index == null || !canFullEdit.value || !moduleForm.value) return
  if (activeTab.value === 'locations') removeAt(moduleForm.value.locations, index)
  else if (activeTab.value === 'clues') removeAt(moduleForm.value.clues, index)
  else if (activeTab.value === 'materials') removeAt(moduleForm.value.materials, index)
}

const moduleActionsOpen = ref(false)
const contextField = ref('truthBackground')
const featuredModule = computed(() => ownedModules.value[0] || null)
const mobileModuleRows = computed(() => [...ownedModules.value.slice(featuredModule.value ? 1 : 0), ...defaultModules.value])
function moduleSectionDescription(tab: Tab) {
  const form = moduleForm.value
  if (!form) return ''
  return { overview: '名称、简介、可见性', context: '真相、流程与特殊规则', locations: `${form.locations.length} 个调查地点`, clues: `${form.clues.length} 条 · 含关键线索`, materials: `${form.materials.length} 份图片资料`, characters: `${form.characters.length} 位登场人物` }[tab]
}
function moduleSectionCount(tab: Tab) {
  return tab === 'overview' || tab === 'context' ? undefined : moduleForm.value?.[tab].length
}
function moduleSummary(module: CocModule) { return module.introduction.replace(/(^|\n)\s*[#>]+\s*/g, ' ').replace(/[*_`]/g, '').trim() }

const leaveOpen = ref(false)
const pendingDestination = ref<'list' | 'directory'>('list')
const sectionLabels = { overview: '基本资料', context: '主持人设定', locations: '地点', clues: '线索', materials: '素材', characters: '模组角色卡' }
watch([isMobile, mobileView], () => emit('detailOpenChange', isMobile.value && mobileView.value !== 'list'), { immediate: true })
onUnmounted(() => emit('detailOpenChange', false))
async function mobileBack() {
  if (mobileEntry.value !== null) { mobileEntry.value = null; await nextTick(); if (moduleScroller.value) moduleScroller.value.scrollTop = mobileListScrollTop; return }
  const destination = mobileView.value === 'section' && !creating.value ? 'directory' : 'list'
  if (hasUnsavedChanges.value && (canFullEdit.value || canRestrictedEdit.value)) {
    if (creating.value || canRestrictedEdit.value || !await persistCurrentModule()) {
      pendingDestination.value = destination
      leaveOpen.value = true
      return
    }
  }
  mobileView.value = destination
}
function discardAndLeave() {
  if (savedPayload.value && !creating.value) editing.value = cloneCocModuleData(savedPayload.value)
  else { editing.value = null; selected.value = null; creating.value = false }
  mobileView.value = pendingDestination.value
  leaveOpen.value = false
}
async function openMobileSection(tab: Tab) {
  switchTab(tab)
  mobileEntry.value = null
  mobileView.value = 'section'
  await nextTick()
  document.querySelector<HTMLElement>('.mobile-module-heading')?.focus({ preventScroll: true })
}


const ownedModules = ref<CocModule[]>([])
const defaultModules = ref<CocModule[]>([])
const selected = ref<CocModuleDetail | null>(null)
const editing = ref<CocModuleSavePayload | null>(null)
const activeTab = ref<Tab>('overview')
const loading = ref(true)
const busy = ref(false)
const message = ref('')
const creating = ref(false)
const unlockConfirm = ref(false)
const rules = ref<CharacterCardCreationRules | null>(null)
const importingText = ref('')
const importedCard = ref<ImportedDraft | null>(null)
const unresolvedWeaponLines = ref<string[]>([])
const uploadingMaterial = ref<number | null>(null)
const uploadingCover = ref(false)
const weaponError = ref('')
const characterDialogOpen = ref(false)
const characterEditorMode = ref<CharacterEditorMode>('choose')
const mobileParserTab = ref<'source' | 'result'>('source')
const characterEditorTab = ref<CharacterEditorTab>('basics')
const editingCharacterIndex = ref<number | null>(null)
const characterSkillSearch = ref('')
const characterSkillSpecializations = reactive<Record<number, string>>({})
const automaticCharacterDerivedFields = ref<Set<CharacterDerivedField>>(new Set())
const weaponForm = reactive({ name: '', skillName: '斗殴', damage: '', range: '', attacksPerRound: '', ammoCapacity: '', remainingAmmo: '', malfunction: '', canImpale: false, notes: '' })
const savedPayload = ref<CocModuleSavePayload | null>(null)
const savedFingerprint = ref('')
const failedFingerprint = ref('')
const pendingSaveCount = ref(0)
const saveQueue = createCocModuleSaveQueue(async (moduleId, payload) => {
  await api.updateCocModule(moduleId, payload)
})

const isDefault = computed(() => selected.value?.module.ownerUserId == null && !creating.value)
const isLocked = computed(() => selected.value?.module.editLocked === true && !isDefault.value)
const canFullEdit = computed(() => creating.value || (!isDefault.value && !isLocked.value))
const canRestrictedEdit = computed(() => !isDefault.value && isLocked.value)
const moduleForm = computed(() => editing.value)
const selectedId = computed(() => selected.value?.module.id || 0)
const characterDialogTitle = computed(() => editingCharacterIndex.value == null ? '新建模组角色卡' : (importedCard.value?.character.name || '模组角色卡'))
const characterDialogDescription = computed(() => editingCharacterIndex.value == null
  ? '手动录入数值，或从现有文本中提取人物资料。'
  : canFullEdit.value ? '修改人物资料、技能、武器与背景。' : '当前模组不可编辑，你可以查看完整人物资料。')
const characterDialogContentClass = computed(() => `module-character-editor-dialog character-dialog-${characterEditorMode.value === 'choose' ? 'choice' : 'workspace'}`)
const firearmBinding = computed(() => weaponForm.skillName.startsWith('射击:'))
const skillNames = computed(() => rules.value?.skills.map((skill) => skill.name) || [])
const currentFingerprint = computed(() => editing.value ? cocModulePayloadFingerprint(cleanPayload(editing.value)) : '')
const hasUnsavedChanges = computed(() => Boolean(currentFingerprint.value) && currentFingerprint.value !== savedFingerprint.value)
const isSaving = computed(() => pendingSaveCount.value > 0)
const saveStatus = computed(() => {
  if (isSaving.value) return { kind: 'saving', text: '正在保存…' }
  if (hasUnsavedChanges.value && failedFingerprint.value === currentFingerprint.value) return { kind: 'error', text: '保存失败，修改仍保留' }
  if (hasUnsavedChanges.value) return { kind: 'dirty', text: '有未保存修改' }
  return { kind: 'saved', text: '已保存' }
})
const availableCharacterSkills = computed(() => {
  const query = characterSkillSearch.value.trim().toLocaleLowerCase()
  const matching = (rules.value?.skills || []).filter((skill) => {
    if (skill.name === '克苏鲁神话' || skill.baseValue == null && !skill.baseFormula) return false
    return !query || `${skill.name} ${skill.category || ''} ${skill.parentName || ''}`.toLocaleLowerCase().includes(query)
  })
  return prioritizeNonBaseSkills(matching, characterSkillValue, characterSkillBaseValue)
})
const missingAttributes = computed(() => {
  const character = importedCard.value?.character
  if (!character) return []
  return ([['str', 'STR'], ['con', 'CON'], ['siz', 'SIZ'], ['dex', 'DEX'], ['app', 'APP'], ['intValue', 'INT'], ['pow', 'POW'], ['edu', 'EDU']] as const)
    .filter(([field]) => !Number(character[field]))
    .map(([, code]) => code)
})
const weaponBindings = computed(() => {
  const names = skillNames.value.filter((name) => name === '斗殴' || name.startsWith('格斗:') || name.startsWith('射击:'))
  return Array.from(new Set(['斗殴', '射击:手枪', '射击:步枪/霰弹枪', ...names]))
})

watch(
  () => {
    const character = importedCard.value?.character
    return character ? [character.str, character.con, character.siz, character.dex, character.pow] : null
  },
  () => {
    if (importedCard.value) syncAutomaticDerivedValues(importedCard.value.character, automaticCharacterDerivedFields.value)
  },
  { flush: 'sync' },
)

onMounted(async () => {
  await Promise.all([loadModules(), loadRules()])
})

async function loadRules() {
  try { rules.value = await api.characterCardCreationRules() } catch { rules.value = null }
}

async function loadModules(preferredId?: number) {
  loading.value = true
  try {
    const [mine, visible] = await Promise.all([api.myCocModules(), api.cocModules()])
    ownedModules.value = mine
    defaultModules.value = visible.filter((item) => item.ownerUserId == null)
    const id = preferredId || selected.value?.module.id || mine[0]?.id || defaultModules.value[0]?.id
    if (id) await openModule(id, Boolean(preferredId))
    else { selected.value = null; editing.value = null }
  } catch (error) { showError(error) }
  finally { loading.value = false }
}

async function openModule(id: number, reveal = true) {
  busy.value = true
  try {
    const detail = await api.manageCocModule(id)
    const payload = detailToPayload(detail)
    selected.value = detail
    editing.value = payload
    savedPayload.value = cloneCocModuleData(payload)
    savedFingerprint.value = cocModulePayloadFingerprint(cleanPayload(payload))
    failedFingerprint.value = ''
    creating.value = false
    unlockConfirm.value = false
    activeTab.value = 'overview'
    mobileEntry.value = null
    if (reveal) mobileView.value = 'directory'
    characterDialogOpen.value = false
    clearCharacterImport()
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

function newModule() {
  mobileView.value = 'section'
  mobileEntry.value = null
  creating.value = true
  selected.value = {
    module: { id: 0, name: '未命名模组', introduction: '', visible: true, ownerUserId: -1, editLocked: false },
    context: {}, locations: [], clues: [], materials: [], characters: [],
  }
  editing.value = emptyPayload()
  savedFingerprint.value = ''
  failedFingerprint.value = ''
  activeTab.value = 'overview'
  unlockConfirm.value = false
  characterDialogOpen.value = false
  clearCharacterImport()
}

async function saveModule() {
  const form = editing.value
  if (!form || !canFullEdit.value) return
  if (!creating.value) {
    await persistCurrentModule('模组已保存')
    return
  }
  if (!form.name.trim() || !form.introduction.trim()) return showMessage('请填写模组名称和简介')
  busy.value = true
  try {
    const saved = await api.createCocModule(cleanPayload(form))
    showMessage('模组已创建，可以继续编辑其他内容')
    emit('changed')
    await loadModules(saved.id)
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

async function persistCurrentModule(successMessage?: string): Promise<boolean> {
  const form = editing.value
  const moduleId = selectedId.value
  if (!form || !moduleId || !canFullEdit.value || creating.value) return false

  const payload = cleanPayload(form)
  const fingerprint = cocModulePayloadFingerprint(payload)
  pendingSaveCount.value += 1
  try {
    const result = await saveCocModuleIfNeeded(saveQueue, moduleId, payload, savedFingerprint.value)
    if (result.status === 'invalid') {
      showMessage(`尚未保存：${result.message}`)
      return false
    }
    if (result.status === 'error') {
      if (selectedId.value === moduleId) failedFingerprint.value = fingerprint
      showMessage(`保存失败：${result.message}`)
      return false
    }
    if (result.status === 'saved' && selectedId.value === moduleId) {
      savedPayload.value = cloneCocModuleData(payload)
      savedFingerprint.value = result.fingerprint
      failedFingerprint.value = ''
      syncModuleSummary(payload)
      emit('changed')
    }
    if (successMessage) showMessage(result.status === 'unchanged' ? '没有需要保存的修改' : successMessage)
    return true
  } finally {
    pendingSaveCount.value -= 1
  }
}

function syncModuleSummary(payload: CocModuleSavePayload) {
  if (selected.value) {
    Object.assign(selected.value.module, {
      name: payload.name, author: payload.author, era: payload.era, introduction: payload.introduction,
      investigatorCreation: payload.investigatorCreation, coverUrl: payload.coverUrl, playerCount: payload.playerCount,
      estimatedDuration: payload.estimatedDuration, visible: payload.visible,
    })
  }
  const summary = ownedModules.value.find((item) => item.id === selectedId.value)
  if (summary) Object.assign(summary, selected.value?.module)
}

function switchTab(tab: Tab) {
  if (tab === activeTab.value) return
  void persistCurrentModule()
  activeTab.value = tab
}

async function deleteModule() {
  if (!selectedId.value || isDefault.value || !confirm(`确定删除“${selected.value?.module.name}”吗？此操作不能撤销。`)) return
  busy.value = true
  try { await api.deleteCocModule(selectedId.value); showMessage('模组已删除'); emit('changed'); selected.value = null; await loadModules() }
  catch (error) { showError(error) }
  finally { busy.value = false }
}

async function exportModule() {
  if (!selectedId.value) return
  busy.value = true
  try {
    const blob = await api.exportCocModule(selectedId.value)
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `${selected.value?.module.name || 'galchat-coc-module'}.json`
    link.click()
    URL.revokeObjectURL(link.href)
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

async function importModule(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const archive = JSON.parse(await file.text()) as CocModuleArchive
    const imported = await api.importCocModule(archive)
    showMessage(`已导入“${imported.name}”`)
    emit('changed')
    await loadModules(imported.id)
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

async function unlockModule() {
  if (!selectedId.value || !isLocked.value) return
  busy.value = true
  try { await api.unlockCocModule(selectedId.value); showMessage('模组已解锁，相关跑团存档已按规则处理'); emit('changed'); await loadModules(selectedId.value) }
  catch (error) { showError(error) }
  finally { busy.value = false; unlockConfirm.value = false }
}

async function saveLockedLocation(index: number) {
  const location = editing.value?.locations[index]
  if (!location?.id || !selectedId.value) return
  busy.value = true
  try { const content = location.content; await api.updateCocModuleLocationContent(selectedId.value, location.id, content); const baseline = savedPayload.value?.locations.find(item => item.id === location.id); if (baseline) { baseline.content = content; savedFingerprint.value = cocModulePayloadFingerprint(cleanPayload(savedPayload.value!)) } showMessage(`地点“${location.name}”已更新`) }
  catch (error) { showError(error) }
  finally { busy.value = false }
}

async function saveLockedClue(index: number) {
  const clue = editing.value?.clues[index]
  if (!clue?.id || !selectedId.value) return
  busy.value = true
  try { const content = clue.content; await api.updateCocModuleClueContent(selectedId.value, clue.id, content); const baseline = savedPayload.value?.clues.find(item => item.id === clue.id); if (baseline) { baseline.content = content; savedFingerprint.value = cocModulePayloadFingerprint(cleanPayload(savedPayload.value!)) } showMessage(`线索“${clue.title}”已更新`) }
  catch (error) { showError(error) }
  finally { busy.value = false }
}

async function addLockedClue() {
  if (!editing.value || !selectedId.value) return
  const clue = editing.value.clues.at(-1)
  if (!clue || clue.id || !clue.title.trim() || !clue.content.trim()) return showMessage('请先填写新线索的标题和正文')
  busy.value = true
  try { await api.addCocModuleClue(selectedId.value, clue); showMessage('新线索已添加'); await openModule(selectedId.value, false); activeTab.value = 'clues'; mobileEntry.value = null }
  catch (error) { showError(error) }
  finally { busy.value = false }
}

function addLocation() { editing.value?.locations.push({ name: '', summary: '', content: '' }) }
function addClue() { editing.value?.clues.push({ title: '', content: '', important: false }) }
function addMaterial() { editing.value?.materials.push({ title: '', description: '', imageUrl: '' }) }
function removeAt<T>(list: T[], index: number) { list.splice(index, 1); mobileEntry.value = null }
function removeMaterialImage(index: number) {
  const material = editing.value?.materials[index]
  if (material) material.imageUrl = ''
}

async function uploadMaterialImage(event: Event, index: number) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const material = editing.value?.materials[index]
  if (!file || !material) return
  uploadingMaterial.value = index
  try { material.imageUrl = await uploadImage(file); showMessage('素材图片已上传') }
  catch (error) { showError(error) }
  finally { uploadingMaterial.value = null }
}

async function uploadCoverImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const form = editing.value
  if (!file || !form) return
  uploadingCover.value = true
  try { form.coverUrl = await uploadImage(file); showMessage('模组封面已上传') }
  catch (error) { showError(error) }
  finally { uploadingCover.value = false }
}

function emptyCharacterDraft(): ImportedDraft {
  return {
    character: {
      actorType: 'BOT', name: '', str: 0, con: 0, siz: 0, dex: 0, app: 0, intValue: 0, pow: 0, edu: 0,
      damageBonus: undefined, build: undefined, mov: undefined, hpMax: undefined, hpCurrent: undefined,
      sanMax: undefined, sanCurrent: undefined, mpMax: undefined, mpCurrent: undefined,
    },
    skills: [], weapons: [], profile: {},
  }
}

function openNewCharacterDialog() {
  clearCharacterImport()
  editingCharacterIndex.value = null
  characterEditorMode.value = 'choose'
  characterEditorTab.value = 'basics'
  characterDialogOpen.value = true
}

function chooseCharacterEntry(mode: 'manual' | 'parse') {
  clearCharacterImport()
  mobileParserTab.value = 'source'
  if (mode === 'manual') {
    importedCard.value = emptyCharacterDraft()
    automaticCharacterDerivedFields.value = automaticDerivedFields(importedCard.value.character)
    characterEditorMode.value = 'edit'
    characterEditorTab.value = 'basics'
  } else {
    characterEditorMode.value = 'parse'
  }
}

function openCharacterEditor(index: number) {
  const card = editing.value?.characters[index]
  if (!card) return
  clearCharacterImport()
  importedCard.value = cloneCocModuleData({ ...card, profile: card.profile || {} }) as ImportedDraft
  automaticCharacterDerivedFields.value = automaticDerivedFields(importedCard.value.character)
  editingCharacterIndex.value = index
  characterEditorMode.value = 'edit'
  characterEditorTab.value = 'basics'
  characterDialogOpen.value = true
}

function closeCharacterDialog() {
  characterDialogOpen.value = false
  clearCharacterImport()
}

function continueParsedCharacter() {
  if (!importedCard.value) return
  characterEditorMode.value = 'edit'
  characterEditorTab.value = 'basics'
}

function parseCharacter() {
  const result = parseModuleCharacterText(importingText.value, skillNames.value)
  unresolvedWeaponLines.value = result.unresolvedLines
  importedCard.value = {
    character: {
      actorType: 'BOT', name: String(result.character.name || ''),
      str: Number(result.character.str || 0), con: Number(result.character.con || 0), siz: Number(result.character.siz || 0), dex: Number(result.character.dex || 0),
      app: Number(result.character.app || 0), intValue: Number(result.character.intValue || 0), pow: Number(result.character.pow || 0), edu: Number(result.character.edu || 0),
      damageBonus: String(result.character.damageBonus ?? ''), build: Number(result.character.build || 0), mov: Number(result.character.mov || 0),
      hpMax: Number(result.character.hpMax || 0), hpCurrent: Number(result.character.hpCurrent || 0), sanMax: Number(result.character.sanMax || 0), sanCurrent: Number(result.character.sanCurrent || 0),
      mpMax: Number(result.character.mpMax || 0), mpCurrent: Number(result.character.mpCurrent || 0),
    },
    skills: result.skills.map((skill) => ({ ...skill })), weapons: [], profile: {},
  }
  automaticCharacterDerivedFields.value = automaticDerivedFields(importedCard.value.character)
}

function markCharacterDerivedManual(field: CharacterDerivedField) {
  automaticCharacterDerivedFields.value.delete(field)
}

function addWeapon() {
  if (!importedCard.value) return
  weaponError.value = validateWeaponDamage(weaponForm.damage, weaponForm.skillName) || ''
  if (!weaponForm.name.trim()) weaponError.value = '请填写武器名称'
  if (firearmBinding.value && (!weaponForm.range || !weaponForm.attacksPerRound || !weaponForm.ammoCapacity || !weaponForm.malfunction)) {
    weaponError.value = '枪械还需填写射程、每轮攻击、弹容量和故障值'
  }
  if (weaponError.value) return
  importedCard.value.weapons.push({
    name: weaponForm.name.trim(), skillName: weaponForm.skillName, damage: weaponForm.damage.trim(),
    ...(firearmBinding.value ? {
      range: weaponForm.range.trim(), attacksPerRound: weaponForm.attacksPerRound.trim(), ammoCapacity: Number(weaponForm.ammoCapacity),
      remainingAmmo: Number(weaponForm.remainingAmmo || weaponForm.ammoCapacity), malfunction: weaponForm.malfunction.trim(), canImpale: weaponForm.canImpale,
    } : {}),
    notes: weaponForm.notes.trim(),
  })
  Object.assign(weaponForm, { name: '', damage: '', range: '', attacksPerRound: '', ammoCapacity: '', remainingAmmo: '', malfunction: '', canImpale: false, notes: '' })
}

function characterSkillBaseValue(skill: CharacterCardCreationRules['skills'][number]) {
  if (skill.baseValue != null) return skill.baseValue
  const formula = (skill.baseFormula || '').toUpperCase()
  if (formula === 'DEX/2') return Math.floor((importedCard.value?.character.dex || 0) / 2)
  if (formula === 'EDU') return importedCard.value?.character.edu || 0
  return 0
}

function characterSkillEntry(skill: CharacterCardCreationRules['skills'][number]) {
  const skills = importedCard.value?.skills || []
  const exact = skills.find((item) => item.displayName === skill.name)
  if (exact || !skill.allowSpecialization) return exact
  const prefix = `${skill.name}:`
  return skills.find((item) => item.displayName.startsWith(prefix) && !skillNames.value.includes(item.displayName))
}

function characterSkillSpecialization(skill: CharacterCardCreationRules['skills'][number]) {
  if (!skill.allowSpecialization) return ''
  const cached = characterSkillSpecializations[skill.skillDefId]
  if (cached != null) return cached
  const entry = characterSkillEntry(skill)
  return entry?.specialization || entry?.displayName.slice(skill.name.length + 1) || ''
}

function characterSkillValue(skill: CharacterCardCreationRules['skills'][number]) {
  return characterSkillEntry(skill)?.value ?? characterSkillBaseValue(skill)
}

function setCharacterSkillSpecialization(skill: CharacterCardCreationRules['skills'][number], value: string) {
  characterSkillSpecializations[skill.skillDefId] = value
  const entry = characterSkillEntry(skill)
  if (!entry) return
  entry.specialization = value.trim()
  entry.displayName = value.trim() ? `${skill.name}:${value.trim()}` : skill.name
}

function setCharacterSkillValue(skill: CharacterCardCreationRules['skills'][number], rawValue: string) {
  if (!importedCard.value) return
  const entry = characterSkillEntry(skill)
  if (rawValue === '') {
    if (entry) removeAt(importedCard.value.skills, importedCard.value.skills.indexOf(entry))
    return
  }
  const value = Math.max(0, Math.min(100, Number(rawValue)))
  if (!Number.isFinite(value)) return
  const specialization = characterSkillSpecialization(skill).trim()
  if (skill.allowSpecialization && !specialization) return
  if (!skill.allowSpecialization && value === characterSkillBaseValue(skill)) {
    if (entry) removeAt(importedCard.value.skills, importedCard.value.skills.indexOf(entry))
    return
  }
  const displayName = specialization ? `${skill.name}:${specialization}` : skill.name
  if (entry) {
    Object.assign(entry, { displayName, category: skill.category, specialization, baseValue: characterSkillBaseValue(skill), value })
  } else {
    importedCard.value.skills.push({ displayName, category: skill.category, specialization, baseValue: characterSkillBaseValue(skill), value, isCustom: false })
  }
}

async function confirmImportedCharacter() {
  if (!editing.value || !importedCard.value || !canFullEdit.value) return
  if (!importedCard.value.character.name.trim()) return showMessage('请填写人物名称')
  if (missingAttributes.value.length) return showMessage(`仍缺少属性：${missingAttributes.value.join('、')}`)
  const saved = cloneCocModuleData(importedCard.value)
  saved.character.hpCurrent = saved.character.hpMax
  saved.character.sanCurrent = saved.character.sanMax
  saved.character.mpCurrent = saved.character.mpMax
  const creatingCharacter = editingCharacterIndex.value == null
  if (creatingCharacter) {
    editing.value.characters.push(saved)
    editingCharacterIndex.value = editing.value.characters.length - 1
  } else {
    editing.value.characters.splice(editingCharacterIndex.value!, 1, saved)
  }
  if (await persistCurrentModule(creatingCharacter ? '模组角色卡已创建并保存' : '模组角色卡已更新并保存')) closeCharacterDialog()
}

function clearCharacterImport() {
  importingText.value = ''
  importedCard.value = null
  unresolvedWeaponLines.value = []
  weaponError.value = ''
  characterSkillSearch.value = ''
  automaticCharacterDerivedFields.value = new Set()
  Object.keys(characterSkillSpecializations).forEach((key) => delete characterSkillSpecializations[Number(key)])
  Object.assign(weaponForm, { name: '', skillName: '斗殴', damage: '', range: '', attacksPerRound: '', ammoCapacity: '', remainingAmmo: '', malfunction: '', canImpale: false, notes: '' })
}

function detailToPayload(detail: CocModuleDetail): CocModuleSavePayload {
  return {
    name: detail.module.name, author: detail.module.author || '', era: detail.module.era || '', introduction: detail.module.introduction || '',
    investigatorCreation: detail.module.investigatorCreation || '', coverUrl: detail.module.coverUrl || '', playerCount: detail.module.playerCount || '',
    estimatedDuration: detail.module.estimatedDuration || '', visible: detail.module.visible !== false,
    context: { ...(detail.context || {}) }, locations: structuredClone(detail.locations || []), clues: structuredClone(detail.clues || []),
    materials: structuredClone(detail.materials || []), characters: (detail.characters || []).map((item) => structuredClone(item.cardData)),
  }
}

function emptyPayload(): CocModuleSavePayload {
  return { name: '', author: '', era: '1920s', introduction: '', investigatorCreation: '', coverUrl: '', playerCount: '', estimatedDuration: '', visible: true, context: {}, locations: [], clues: [], materials: [], characters: [] }
}

function cleanPayload(form: CocModuleSavePayload): CocModuleSavePayload {
  return {
    ...cloneCocModuleData(form), name: form.name.trim(), introduction: form.introduction.trim(),
    locations: form.locations.map(({ name, summary, content }) => ({ name: name.trim(), summary: summary.trim(), content: content.trim() })),
    clues: form.clues.map(({ title, content, important }) => ({ title: title.trim(), content: content.trim(), important })),
    materials: form.materials.map(({ title, description, imageUrl }) => ({ title: title.trim(), description: description.trim(), imageUrl: imageUrl.trim() })),
  }
}

function showError(error: unknown) { showMessage(error instanceof Error ? error.message : '操作失败') }
function showMessage(value: string) { message.value = value; window.setTimeout(() => { if (message.value === value) message.value = '' }, 3500) }
</script>

<template>
  <main class="module-library-page" :class="`mobile-module-${mobileView}`">
    <div v-if="message" class="module-toast" role="status">{{ message }}</div>
    <div class="module-workspace">
      <section v-if="isMobile && mobileView === 'list'" class="v1-module-library">
        <header class="v1-module-library-header"><span class="v1-module-brand">✦</span><div><strong>模组库</strong><small>CoC 跑团内容</small></div><button class="icon-button" aria-label="创建模组" :disabled="busy" @click="newModule"><Plus :size="21" /></button></header>
        <div class="v1-module-library-content">
          <div class="v1-module-intro"><span class="eyebrow">SCENARIOS</span><h1>每一个谜团，<br />都有它的开端。</h1></div>
          <button class="button primary v1-module-wide" :disabled="busy" @click="newModule"><Plus :size="16" />创建模组</button>
          <div class="v1-module-sectionline"><h3>模组列表</h3><label class="v1-module-import file-button">导入模组<input type="file" accept="application/json,.json" :disabled="busy" @change="importModule" /></label></div>
          <p v-if="loading" role="status">正在载入模组…</p>
          <article v-if="featuredModule" class="v1-module-featured"><small>CoC · 我的模组{{ featuredModule.editLocked ? ' · 受限编辑' : '' }}</small><h2>{{ featuredModule.name }}</h2><p>{{ moduleSummary(featuredModule) }}</p><div class="v1-module-meta">{{ [featuredModule.era, featuredModule.playerCount, featuredModule.estimatedDuration].filter(Boolean).join(' · ') }}</div><button class="button secondary v1-module-wide" @click="openModule(featuredModule.id)">查看与编辑</button></article>
          <button v-for="item in mobileModuleRows" :key="item.id" class="v1-module-row" @click="openModule(item.id)"><span class="v1-module-avatar" :style="item.coverUrl ? { backgroundImage: `url(${item.coverUrl})` } : undefined"><BookCopy v-if="!item.coverUrl" :size="21" /></span><span><strong>{{ item.name }}</strong><small>{{ item.ownerUserId == null ? '系统提供 · 只读' : item.editLocked ? '我的模组 · 受限编辑' : '我的模组' }}</small></span><ChevronRight :size="16" /></button>
          <p v-if="!loading && !featuredModule && !mobileModuleRows.length" class="v1-module-empty">还没有模组，创建或导入一份模组开始准备故事。</p>
        </div>
      </section>
      <nav v-if="!isMobile" class="module-switcher" aria-label="选择模组">
        <div class="module-switcher-heading">
          <h1>模组库</h1>
          <small>{{ ownedModules.length }} 个自建 · {{ defaultModules.length }} 个默认</small>
        </div>
        <div class="module-switcher-track">
          <p v-if="loading" class="mobile-module-loading" role="status">正在载入模组…</p>
          <button v-if="creating" class="module-switcher-item active draft" type="button">
            <span class="module-switcher-cover"><Plus :size="18" /></span>
            <span><small>正在创建</small><strong>{{ moduleForm?.name || '未命名模组' }}</strong></span>
          </button>
          <span v-if="ownedModules.length" class="module-switcher-group">我的</span>
          <button v-for="item in ownedModules" :key="item.id" class="module-switcher-item" :class="{ active: !creating && selectedId === item.id }" type="button" @click="openModule(item.id)">
            <span class="module-switcher-cover" :style="item.coverUrl ? { backgroundImage: `url(${item.coverUrl})` } : {}"><BookCopy v-if="!item.coverUrl" :size="17" /></span>
            <span><small>{{ item.editLocked ? '受限编辑' : (item.visible ? '可用于跑团' : '已停用') }}</small><strong>{{ item.name }}</strong></span>
            <LockKeyhole v-if="item.editLocked" :size="13" />
          </button>
          <span v-if="defaultModules.length" class="module-switcher-group">默认</span>
          <button v-for="item in defaultModules" :key="item.id" class="module-switcher-item" :class="{ active: !creating && selectedId === item.id }" type="button" @click="openModule(item.id)">
            <span class="module-switcher-cover default"><BookCopy :size="17" /></span>
            <span><small>系统提供 · 只读</small><strong>{{ item.name }}</strong></span>
          </button>
        </div>
        <div class="module-switcher-actions">
          <label class="button secondary file-button"><FileUp :size="15" />导入模组<input type="file" accept="application/json,.json" :disabled="busy" @change="importModule" /></label>
          <button class="button primary" :disabled="busy" @click="newModule"><Plus :size="15" />新建模组</button>
        </div>
      </nav>

      <section class="module-editor-pane">
        <header v-if="isMobile && mobileView !== 'list'" class="mobile-module-header"><button class="icon-button" aria-label="返回上一层" :disabled="busy || isSaving" @click="mobileBack"><ArrowLeft :size="20" /></button><h2 class="mobile-module-heading" tabindex="-1">{{ mobilePageTitle }}</h2><button v-if="mobileView === 'directory'" class="icon-button" aria-label="模组更多选项" @click="moduleActionsOpen = true"><Ellipsis :size="21" /></button><button v-else-if="mobileEntry !== null && canFullEdit && activeTab !== 'materials'" class="icon-button danger-text" aria-label="删除当前条目" :disabled="busy || isSaving" @click="deleteMobileEntry"><Trash2 :size="18" /></button><span v-else-if="canFullEdit && !creating" class="module-save-status" :class="saveStatus.kind" role="status">{{ saveStatus.text }}</span></header>
        <nav v-if="isMobile && mobileView === 'directory'" class="mobile-module-directory-list" aria-label="模组目录">
          <div class="v1-module-intro"><span class="eyebrow">{{ isDefault ? '系统模组 · 只读' : isLocked ? '我的模组 · 受限编辑' : '我的模组 · 可编辑' }}</span><h1>{{ moduleForm?.name || '未命名模组' }}</h1><p v-if="canFullEdit" class="module-save-status" :class="saveStatus.kind" role="status">{{ saveStatus.text }}</p></div>
          <button v-for="(label, tab) in sectionLabels" :key="tab" @click="openMobileSection(tab)"><span><strong>{{ label }}</strong><small>{{ moduleSectionDescription(tab) }}</small></span><span v-if="moduleSectionCount(tab) !== undefined" class="v1-module-count">{{ moduleSectionCount(tab) }}</span><ChevronRight v-else :size="16" /></button>
          <p v-if="!isLocked" class="v1-module-notice">{{ isDefault ? '这份模组由系统提供，内容只读。' : '这份模组由你创建，可维护全部内容。' }}</p>
        </nav>
        <div v-if="loading" class="module-empty">正在载入模组…</div>
        <div v-else-if="!moduleForm" class="module-empty"><BookCopy :size="34" /><strong>还没有可管理的模组</strong><span>新建一个模组，或导入已有 JSON 文件。</span></div>
        <template v-else>
          <nav v-if="showModuleManagementActions || canFullEdit" class="module-tabs">
            <div class="module-tab-identity">
              <span class="module-state" :class="{ locked: isLocked, readonly: isDefault }">{{ creating ? '新模组' : isDefault ? '默认模组 · 只读' : isLocked ? '已被跑团引用 · 受限编辑' : '自建模组 · 完整编辑' }}</span>
              <strong>{{ moduleForm.name || '未命名模组' }}</strong>
            </div>
            <div v-if="!creating" class="module-tab-links">
              <button v-for="tab in ([['overview','概览'],['context','主持人设定'],['locations','地点'],['clues','线索'],['materials','素材'],['characters','模组角色卡']] as const)" :key="tab[0]" :class="{ active: activeTab === tab[0] }" @click="switchTab(tab[0])">{{ tab[1] }}</button>
            </div>
            <div class="module-editor-actions">
              <span v-if="canFullEdit && !creating" class="module-save-status" :class="saveStatus.kind" role="status">{{ saveStatus.text }}</span>
              <button v-if="selectedId && showModuleManagementActions" class="button secondary" :disabled="busy" @click="exportModule"><Download :size="16" />导出</button>
              <button v-if="isLocked && showModuleManagementActions" class="button secondary" :disabled="busy" @click="unlockConfirm = true"><UnlockKeyhole :size="16" />解锁</button>
              <button v-if="canFullEdit" class="button primary" :disabled="busy || isSaving" @click="saveModule"><Save :size="16" />{{ creating ? '创建模组' : isSaving ? '保存中…' : '保存全部' }}</button>
            </div>
          </nav>

          <div v-if="isLocked" class="module-lock-notice"><LockKeyhole :size="17" /><span><strong>当前为受限编辑</strong><small>只能修改地点正文、新增线索或修改线索正文。其他内容保持只读。</small></span></div>
          <div v-if="unlockConfirm" class="module-unlock-warning"><AlertTriangle :size="19" /><div><strong>解锁会影响所有引用此模组的跑团</strong><p>活动跑团会重置到初始状态；已完成跑团除初始状态外的存档会失效。仅绑定角色卡、尚未开始第一次行动轮的跑团不受影响。</p></div><button class="button ghost" @click="unlockConfirm = false">取消</button><button class="button danger" :disabled="busy" @click="unlockModule">确认解锁</button></div>

          <div ref="moduleScroller" class="module-editor-scroll" :class="{ 'mobile-has-entry': mobileEntry !== null }">
            <section v-if="activeTab === 'overview'" class="module-form-grid overview-grid">
              <template v-if="isMobile">
                <label class="field full"><span>模组名称</span><input v-model="moduleForm.name" :disabled="!canFullEdit" /></label>
                <label class="field full"><span>简介</span><textarea v-model="moduleForm.introduction" rows="5" :disabled="!canFullEdit" /></label>
                <label v-if="!isDefault" class="switch-row full"><span><strong>可用于新建跑团</strong><small>关闭后不出现在新建跑团的模组列表中。</small></span><input v-model="moduleForm.visible" type="checkbox" :disabled="!canFullEdit" /></label>
                <details class="v1-module-details full"><summary>更多基本资料</summary><div class="module-form-grid">
                  <label class="field"><span>作者</span><input v-model="moduleForm.author" :disabled="!canFullEdit" /></label>
                  <label class="field"><span>时代</span><input v-if="isDefault" :value="moduleForm.era" disabled /><select v-else v-model="moduleForm.era" :disabled="!canFullEdit"><option value="1920s">1920s</option><option value="现代">现代</option></select></label>
                  <label class="field"><span>玩家人数</span><input v-model="moduleForm.playerCount" :disabled="!canFullEdit" /></label><label class="field"><span>预计时长</span><input v-model="moduleForm.estimatedDuration" :disabled="!canFullEdit" /></label>
                  <div class="field"><span>模组封面</span><img v-if="moduleForm.coverUrl" class="v1-module-cover-preview" :src="moduleForm.coverUrl" alt="模组封面" /><label v-if="canFullEdit" class="button secondary file-button"><ImageUp :size="15" />{{ uploadingCover ? '上传中…' : '上传封面' }}<input type="file" accept="image/*" :disabled="uploadingCover" @change="uploadCoverImage" /></label></div>
                  <label class="field"><span>调查员创建说明</span><textarea v-model="moduleForm.investigatorCreation" rows="5" :disabled="!canFullEdit" /></label>
                </div></details>
              </template>
              <template v-else>
              <header class="editor-section-heading full"><span v-if="creating" class="eyebrow">第 1 步，共 2 步</span><h3>{{ creating ? '填写基本资料' : '基本资料' }}</h3><p>{{ creating ? '先填写名称和简介并创建模组，创建后即可继续补充其他内容。' : '这些信息会展示在新建跑团时的模组选择页面。' }}</p></header>
              <label class="field full"><span>模组名称 *</span><input v-model="moduleForm.name" :disabled="!canFullEdit" /></label>
              <label class="field"><span>作者</span><input v-model="moduleForm.author" :disabled="!canFullEdit" /></label>
              <label class="field"><span>时代</span><input v-if="isDefault" :value="moduleForm.era" disabled /><select v-else v-model="moduleForm.era" :disabled="!canFullEdit"><option value="1920s">1920s</option><option value="现代">现代</option></select></label>
              <label class="field"><span>玩家人数</span><input v-model="moduleForm.playerCount" :disabled="!canFullEdit" placeholder="例如：2–4 人" /></label>
              <label class="field"><span>预计时长</span><input v-model="moduleForm.estimatedDuration" :disabled="!canFullEdit" placeholder="例如：4–6 小时" /></label>
              <div class="field full"><span>模组封面</span><div class="upload-row"><small>{{ moduleForm.coverUrl ? '已上传封面' : '尚未上传封面' }}</small><label v-if="canFullEdit" class="button secondary file-button"><ImageUp :size="15" />{{ uploadingCover ? '上传中…' : '上传封面' }}<input type="file" accept="image/*" :disabled="uploadingCover" @change="uploadCoverImage" /></label></div></div>
              <label class="field full"><span>模组简介 *</span><textarea v-model="moduleForm.introduction" rows="6" :disabled="!canFullEdit" /></label>
              <label class="field full"><span>调查员创建说明</span><textarea v-model="moduleForm.investigatorCreation" rows="5" :disabled="!canFullEdit" /></label>
              <label v-if="!isDefault" class="switch-row full"><span><strong>可用于新建跑团</strong><small>关闭后，该模组仍保留，但不会出现在新建跑团的模组列表中。</small></span><input v-model="moduleForm.visible" type="checkbox" :disabled="!canFullEdit" /></label>
              </template>
            </section>

            <section v-else-if="activeTab === 'context'" class="module-form-grid">
              <label v-if="isMobile" class="field"><span>设定内容</span><select v-model="contextField"><option v-for="field in (['truthBackground','investigatorIntro','timeline','specialRules','keeperGuidance','endingContent','extraContent'] as const)" :key="field" :value="field">{{ ({ truthBackground: '真相与背景', investigatorIntro: '调查员开场', timeline: '时间线', specialRules: '特殊规则', keeperGuidance: '守秘人指引', endingContent: '结局内容', extraContent: '补充内容' })[field] }}</option></select></label>
              <header class="editor-section-heading full"><h3>主持人设定</h3><p>整理只供主持人查看的真相、流程与特殊规则。</p></header>
              <label v-for="field in ([['truthBackground','真相与背景'],['investigatorIntro','调查员开场'],['timeline','时间线'],['specialRules','特殊规则'],['keeperGuidance','守秘人指引'],['endingContent','结局内容'],['extraContent','补充内容']] as const).filter(field => !isMobile || contextField === field[0])" :key="field[0]" class="field full"><span>{{ field[1] }}</span><textarea v-model="moduleForm.context[field[0]]" :rows="isMobile ? 12 : 6" :disabled="!canFullEdit" /></label>
            </section>

            <section v-else-if="activeTab === 'locations'" class="module-collection">
              <header class="editor-section-heading"><h3>地点</h3><p>按调查顺序维护场景摘要与主持正文。</p></header>
              <template v-for="(location, index) in moduleForm.locations" :key="location.id || index"><article v-if="!isMobile || mobileEntry === null || mobileEntry === index" class="module-entry-card" :class="{ 'mobile-entry-selected': mobileEntry === index }">
                <header v-if="!isMobile || mobileEntry === null"><button v-if="isMobile" class="mobile-module-entry-title" @click="openMobileEntry(index)">{{ location.name || `地点 ${index + 1}` }}<ChevronRight :size="17" /></button><strong v-else>地点 {{ index + 1 }}</strong><button v-if="canFullEdit && !isMobile" class="icon-button" title="删除地点" @click="removeAt(moduleForm.locations, index)"><Trash2 :size="15" /></button></header>
                <template v-if="!isMobile || mobileEntry === index">
                <div class="module-form-grid"><label class="field"><span>名称 *</span><input v-model="location.name" :disabled="!canFullEdit" /></label><label class="field"><span>摘要 *</span><input v-model="location.summary" :disabled="!canFullEdit" /></label><label class="field full"><span>地点正文 *</span><textarea v-model="location.content" rows="8" :disabled="isDefault" /></label></div>
                <button v-if="canRestrictedEdit" class="button secondary entry-save" :disabled="busy" @click="saveLockedLocation(index)"><Save :size="15" />保存地点正文</button>
                </template>
              </article></template>
              <button v-if="canFullEdit" class="module-add-card" @click="addLocation(); openMobileEntry(moduleForm.locations.length - 1)"><Plus :size="18" />新增地点</button>
            </section>

            <section v-else-if="activeTab === 'clues'" class="module-collection">
              <header class="editor-section-heading"><h3>线索</h3><p>管理玩家可能获取的信息以及关键线索标记。</p></header>
              <template v-for="(clue, index) in moduleForm.clues" :key="clue.id || `new-${index}`"><article v-if="!isMobile || mobileEntry === null || mobileEntry === index" class="module-entry-card" :class="{ 'mobile-entry-selected': mobileEntry === index }">
                <header v-if="!isMobile || mobileEntry === null"><button v-if="isMobile" class="mobile-module-entry-title" @click="openMobileEntry(index)">{{ clue.title || `线索 ${index + 1}` }}<ChevronRight :size="17" /></button><strong v-else>{{ clue.id ? `线索 ${index + 1}` : '新线索' }}</strong><button v-if="canFullEdit && !isMobile" class="icon-button" title="删除线索" @click="removeAt(moduleForm.clues, index)"><Trash2 :size="15" /></button></header>
                <template v-if="!isMobile || mobileEntry === index">
                <div class="module-form-grid"><label class="field full"><span>标题 *</span><input v-model="clue.title" :disabled="!canFullEdit && Boolean(clue.id)" /></label><label class="field full"><span>线索正文 *</span><textarea v-model="clue.content" rows="7" :disabled="isDefault" /></label><label class="switch-row full"><span><strong>重要线索</strong></span><input v-model="clue.important" type="checkbox" :disabled="!canFullEdit && Boolean(clue.id)" /></label></div>
                <button v-if="canRestrictedEdit" class="button secondary entry-save" :disabled="busy" @click="clue.id ? saveLockedClue(index) : addLockedClue()"><Save :size="15" />{{ clue.id ? '保存线索正文' : '添加线索' }}</button>
                </template>
              </article></template>
              <button v-if="canFullEdit || (canRestrictedEdit && !moduleForm.clues.some((clue) => !clue.id))" class="module-add-card" @click="addClue(); openMobileEntry(moduleForm.clues.length - 1)"><Plus :size="18" />新增线索</button>
            </section>

            <section v-else-if="activeTab === 'materials'" class="module-collection material-grid">
              <header class="editor-section-heading collection-heading"><h3>素材</h3><p>上传可在跑团过程中展示给玩家的图片资料。</p></header>
              <template v-for="(material, index) in moduleForm.materials" :key="material.id || index"><article v-if="!isMobile || mobileEntry === null || mobileEntry === index" class="module-entry-card material-card" :class="{ 'mobile-entry-selected': mobileEntry === index }">
                <header v-if="!isMobile || mobileEntry === null"><button v-if="isMobile" class="mobile-module-entry-title" @click="openMobileEntry(index)">{{ material.title || `素材 ${index + 1}` }}<ChevronRight :size="17" /></button><strong v-else>素材 {{ index + 1 }}</strong><button v-if="canFullEdit && !isMobile" class="icon-button" title="删除素材" @click="removeAt(moduleForm.materials, index)"><Trash2 :size="15" /></button></header>
                <template v-if="!isMobile || mobileEntry === index">
                <label v-if="canFullEdit && !material.imageUrl" class="material-image-frame empty" :class="{ uploading: uploadingMaterial === index }">
                  <ImageUp :size="28" />
                  <strong>{{ uploadingMaterial === index ? '正在上传…' : '上传图片' }}</strong>
                  <small>点击选择素材图片</small>
                  <input type="file" accept="image/*" :disabled="uploadingMaterial !== null" @change="uploadMaterialImage($event, index)" />
                </label>
                <div v-else class="material-image-frame" :class="{ 'has-image': material.imageUrl, empty: !material.imageUrl, uploading: uploadingMaterial === index }">
                  <img v-if="material.imageUrl" :src="material.imageUrl" :alt="material.title || `素材 ${index + 1}`" />
                  <template v-else><ImageUp :size="28" /><strong>暂无图片</strong></template>
                  <div v-if="!isMobile && canFullEdit && material.imageUrl" class="material-image-actions">
                    <label class="material-image-action">
                      <ImageUp :size="16" />{{ uploadingMaterial === index ? '上传中…' : '替换' }}
                      <input type="file" accept="image/*" :disabled="uploadingMaterial !== null" @change="uploadMaterialImage($event, index)" />
                    </label>
                    <button class="material-image-action danger" type="button" :disabled="uploadingMaterial !== null" @click="removeMaterialImage(index)"><Trash2 :size="16" />删除</button>
                  </div>
                </div>
                <div v-if="isMobile && !canFullEdit" class="v1-material-reading"><p>{{ material.description }}</p></div>
                <label v-if="!isMobile || canFullEdit" class="field"><span>标题 *</span><input v-model="material.title" :disabled="!canFullEdit" /></label>
                <label v-if="!isMobile || canFullEdit" class="field"><span>介绍 *</span><textarea v-model="material.description" rows="4" :disabled="!canFullEdit" /></label>
                </template>
              </article></template>
              <button v-if="canFullEdit" class="module-add-card" @click="addMaterial(); openMobileEntry(moduleForm.materials.length - 1)"><Plus :size="18" />新增素材</button>
            </section>

            <section v-else class="module-character-section">
              <header class="editor-section-heading"><h3>模组角色卡</h3><p>查看或维护模组中的人物、怪物和其他登场角色。</p></header>
              <div v-if="moduleForm.characters.length || canFullEdit" class="preset-character-list">
                <article v-for="(card, index) in moduleForm.characters" :key="index"><button class="preset-character-main" type="button" @click="openCharacterEditor(index)"><span class="preset-avatar">{{ card.character.name.slice(0, 1) }}</span><span><strong>{{ card.character.name }}</strong><small>{{ card.skills.length }} 项技能 · {{ card.weapons.length }} 件武器</small></span></button><button v-if="canFullEdit" class="icon-button" aria-label="删除模组角色卡" @click="removeAt(moduleForm.characters, index)"><Trash2 :size="15" /></button></article>
                <button v-if="canFullEdit && !isMobile" class="preset-character-create-card" type="button" @click="openNewCharacterDialog"><Plus :size="18" /><strong>新建模组角色卡</strong></button>
              </div>
              <div v-else class="module-empty small">此模组尚未添加角色卡。</div>
              <div v-if="isMobile && canFullEdit" class="v1-module-character-actions"><button class="button secondary" @click="openNewCharacterDialog(); chooseCharacterEntry('parse')">粘贴导入人物数据</button><button class="button secondary" @click="openNewCharacterDialog(); chooseCharacterEntry('manual')">新建人物 / 技能 / 武器</button></div>

              <BaseDialog v-model="characterDialogOpen" mobile-presentation="page" :title="characterDialogTitle" :description="isMobile ? undefined : characterDialogDescription" :mobile-back="isMobile && characterEditorMode === 'parse' && mobileParserTab === 'result' ? () => mobileParserTab = 'source' : undefined" size="lg" :content-class="characterDialogContentClass">
                <div class="character-editor-content">
                  <section v-if="characterEditorMode === 'choose'" class="character-entry-choice">
                    <button type="button" @click="chooseCharacterEntry('manual')"><span class="character-entry-icon"><Plus :size="22" /></span><strong>手动录入</strong><small>从空白人物卡开始，逐项填写属性、技能、武器与背景。</small></button>
                    <button type="button" @click="chooseCharacterEntry('parse')"><span class="character-entry-icon"><FileUp :size="22" /></span><strong>文本自动解析</strong><small>粘贴已有资料，识别属性与中文技能后再手动完善。</small></button>
                  </section>

                  <section v-else-if="characterEditorMode === 'parse'" class="character-parser-layout">
                    <nav v-if="isMobile" class="v1-parser-tabs" aria-label="导入人物资料"><button :class="{ active: mobileParserTab === 'source' }" @click="mobileParserTab = 'source'">粘贴原文</button><button :class="{ active: mobileParserTab === 'result' }" :disabled="!importedCard" @click="mobileParserTab = 'result'">识别结果</button></nav>
                    <div v-show="!isMobile || mobileParserTab === 'source'" class="character-parser-source">
                      <h3>粘贴人物数据</h3>
                      <p>属性支持中文名和 STR 等缩写；技能按中文名称匹配。武器只标记原文，不会自动建立。</p>
                      <textarea v-model="importingText" :rows="isMobile ? 7 : 18" placeholder="粘贴图片示例中的文字格式…" />
                    </div>
                    <div v-show="!isMobile || mobileParserTab === 'result'" class="character-parser-result">
                      <h3>解析结果</h3>
                      <div v-if="importedCard" class="parse-result-summary">
                        <div class="review-heading"><div><strong>{{ importedCard.character.name || '未识别姓名' }}</strong><small>{{ importedCard.skills.length }} 项技能已匹配</small></div><span v-if="missingAttributes.length" class="validation-badge error">缺少 {{ missingAttributes.join('、') }}</span><span v-else class="validation-badge">核心属性完整</span></div>
                        <div class="parsed-attribute-grid"><span v-for="field in ([['str','STR'],['con','CON'],['siz','SIZ'],['dex','DEX'],['app','APP'],['intValue','INT'],['pow','POW'],['edu','EDU']] as const)" :key="field[0]"><small>{{ field[1] }}</small><strong>{{ importedCard.character[field[0]] || '—' }}</strong></span></div>
                        <div v-if="importedCard.skills.length" class="parsed-skills compact"><strong>已匹配技能</strong><span v-for="skill in importedCard.skills" :key="skill.displayName">{{ skill.displayName }} {{ skill.value }}%</span></div>
                        <div v-if="unresolvedWeaponLines.length" class="unresolved-lines"><strong>待手工建立的武器</strong><code v-for="line in unresolvedWeaponLines" :key="line">{{ line }}</code></div>
                      </div>
                      <div v-else class="parse-result-empty"><FileUp :size="28" /><strong>等待解析</strong><small>解析结果会显示在这里，原文不会直接创建角色。</small></div>
                    </div>
                  </section>

                  <section v-else-if="importedCard" class="character-card-editor">
                    <nav class="character-editor-tabs" aria-label="人物卡编辑分区">
                      <button v-for="tab in ([['basics','基础与属性'],['skills','技能'],['weapons','武器'],['background','背景资料']] as const)" :key="tab[0]" type="button" :class="{ active: characterEditorTab === tab[0] }" @click="characterEditorTab = tab[0]">{{ tab[1] }}</button>
                    </nav>
                    <fieldset :disabled="!canFullEdit">
                      <div v-if="characterEditorTab === 'basics'" class="character-basics-layout">
                        <div class="character-basics-form module-form-grid compact-grid">
                          <label class="field full"><span>姓名 *</span><input v-model="importedCard.character.name" /></label>
                          <div class="attribute-editor-grid full"><label v-for="field in ([['str','STR','力量'],['con','CON','体质'],['siz','SIZ','体型'],['dex','DEX','敏捷'],['app','APP','外貌'],['intValue','INT','智力'],['pow','POW','意志'],['edu','EDU','教育']] as const)" :key="field[0]"><span>{{ field[2] }} <small>{{ field[1] }}</small></span><input v-model.number="importedCard.character[field[0]]" type="number" min="0" /></label></div>
                          <label class="field"><span>HP</span><input v-model.number="importedCard.character.hpMax" type="number" min="0" @input="markCharacterDerivedManual('hpMax')" /></label>
                          <label class="field"><span>SAN</span><input v-model.number="importedCard.character.sanMax" type="number" min="0" @input="markCharacterDerivedManual('sanMax')" /></label>
                          <label class="field"><span>MP</span><input v-model.number="importedCard.character.mpMax" type="number" min="0" @input="markCharacterDerivedManual('mpMax')" /></label>
                          <label class="field"><span>伤害加值 / 体格 / MOV</span><div class="inline-inputs three"><input v-model="importedCard.character.damageBonus" @input="markCharacterDerivedManual('damageBonus')" /><input v-model.number="importedCard.character.build" type="number" @input="markCharacterDerivedManual('build')" /><input v-model.number="importedCard.character.mov" type="number" @input="markCharacterDerivedManual('mov')" /></div></label>
                          <label class="field"><span>护甲</span><input v-model.number="importedCard.character.armor" type="number" min="0" /></label>
                        </div>
                      </div>

                      <div v-else-if="characterEditorTab === 'skills'" class="character-skills-editor">
                        <header><div><strong>技能</strong><small>参考基础值录入最终成功率；未调整的技能保持规则默认值。</small></div><span>{{ importedCard.skills.length }} 项已调整</span></header>
                        <label class="character-skill-search"><Search :size="15" /><input v-model.trim="characterSkillSearch" type="search" placeholder="搜索技能或类别" /></label>
                        <div v-if="availableCharacterSkills.length" class="character-skill-list">
                          <article v-for="skill in availableCharacterSkills" :key="skill.skillDefId">
                            <span><strong>{{ skill.name }}</strong><small>{{ skill.category || '通用技能' }} · 基础 {{ characterSkillBaseValue(skill) }}%</small></span>
                            <input v-if="skill.allowSpecialization" class="skill-specialization" :value="characterSkillSpecialization(skill)" :disabled="!canFullEdit" placeholder="填写专攻" @input="setCharacterSkillSpecialization(skill, ($event.target as HTMLInputElement).value)" />
                            <label><small>成功率</small><input type="number" min="0" max="100" :value="characterSkillValue(skill)" :disabled="!canFullEdit || skill.allowSpecialization && !characterSkillSpecialization(skill)" @input="setCharacterSkillValue(skill, ($event.target as HTMLInputElement).value)" /></label>
                            <b>{{ characterSkillValue(skill) }}%</b>
                          </article>
                        </div>
                        <div v-else class="character-section-empty">{{ characterSkillSearch ? '没有匹配的技能' : '暂无可用技能' }}</div>
                      </div>

                      <div v-else-if="characterEditorTab === 'weapons'" class="character-list-editor">
                        <header><div><strong>武器</strong><small>武器名称手工填写，并选择对应战斗技能。</small></div><span>{{ importedCard.weapons.length }} 件</span></header>
                        <div v-if="importedCard.weapons.length" class="weapon-record-list"><article v-for="(weapon, index) in importedCard.weapons" :key="index"><div><strong>{{ weapon.name }}</strong><small>{{ weapon.skillName }} · {{ weapon.damage }}</small><small v-if="weapon.range">射程 {{ weapon.range }} · 每轮 {{ weapon.attacksPerRound }} · 弹药 {{ weapon.remainingAmmo }}/{{ weapon.ammoCapacity }} · 故障 {{ weapon.malfunction }}</small></div><button v-if="canFullEdit" class="icon-button" type="button" @click="removeAt(importedCard!.weapons, index)"><Trash2 :size="14" /></button></article></div>
                        <div v-else class="character-section-empty">尚未录入武器。</div>
                        <div v-if="canFullEdit" class="weapon-builder">
                          <strong>添加武器</strong>
                          <div class="module-form-grid compact-grid"><label class="field"><span>武器名称 *</span><input v-model="weaponForm.name" /></label><label class="field"><span>绑定战斗字段 *</span><select v-model="weaponForm.skillName"><option v-for="name in weaponBindings" :key="name" :value="name">{{ name === '斗殴' ? '斗殴（含棍棒）' : name }}</option></select></label><label class="field"><span>伤害 *</span><input v-model="weaponForm.damage" placeholder="1D8+DB 或 4D6/2D6/1D6" /></label><label class="field"><span>备注</span><input v-model="weaponForm.notes" /></label><template v-if="firearmBinding"><label class="field"><span>射程 *</span><input v-model="weaponForm.range" /></label><label class="field"><span>每轮攻击 *</span><input v-model="weaponForm.attacksPerRound" /></label><label class="field"><span>弹容量 / 当前弹药 *</span><div class="inline-inputs"><input v-model="weaponForm.ammoCapacity" type="number" /><input v-model="weaponForm.remainingAmmo" type="number" /></div></label><label class="field"><span>故障值 *</span><input v-model="weaponForm.malfunction" /></label><label class="switch-row full"><span><strong>可贯穿</strong></span><input v-model="weaponForm.canImpale" type="checkbox" /></label></template></div>
                          <small v-if="weaponError" class="field-error">{{ weaponError }}</small><button class="button secondary" type="button" @click="addWeapon"><Plus :size="15" />加入武器</button>
                        </div>
                      </div>

                      <div v-else class="character-background-grid module-form-grid compact-grid">
                        <label v-for="field in ([['appearance','外貌描述'],['ideology','思想与信念'],['significantPeople','重要之人'],['meaningfulLocations','意义非凡之地'],['treasuredPossessions','宝贵之物'],['traits','特质'],['keyConnectionText','重要关系'],['injuriesAndScars','伤口与疤痕'],['phobiasAndManias','恐惧症与躁狂症'],['equipmentText','装备'],['assetsText','资产'],['spendingLevel','消费水平'],['cash','现金'],['notes','备注']] as const)" :key="field[0]" class="field"><span>{{ field[1] }}</span><textarea v-model="importedCard.profile[field[0]]" rows="4" /></label>
                      </div>
                    </fieldset>
                  </section>
                </div>
                <template #footer>
                  <template v-if="isMobile && characterEditorMode === 'parse'"><button v-if="mobileParserTab === 'source'" class="button primary" :disabled="!importingText.trim()" @click="parseCharacter(); importedCard && (mobileParserTab = 'result')">{{ importedCard ? '重新解析' : '开始解析' }}</button><button v-else class="button primary" :disabled="!importedCard" @click="continueParsedCharacter">填入人物卡并继续完善</button></template>
                  <template v-else-if="isMobile && characterEditorMode === 'edit'"><button v-if="canFullEdit" class="button primary" :disabled="isSaving" @click="confirmImportedCharacter">{{ isSaving ? '保存中…' : '保存角色' }}</button><button v-else class="button primary" @click="closeCharacterDialog">返回模组</button></template>
                  <template v-else-if="characterEditorMode === 'choose'"><button class="button ghost" type="button" @click="closeCharacterDialog">取消</button></template>
                  <template v-else-if="characterEditorMode === 'parse'"><button class="button ghost" type="button" @click="characterEditorMode = 'choose'">返回</button><span class="dialog-footer-spacer" /><button class="button secondary" type="button" :disabled="!importingText.trim()" @click="parseCharacter">{{ importedCard ? '重新解析' : '开始解析' }}</button><button class="button primary" type="button" :disabled="!importedCard" @click="continueParsedCharacter">填入人物卡并继续完善</button></template>
                  <template v-else><button class="button ghost" type="button" @click="closeCharacterDialog">{{ canFullEdit ? '取消' : '关闭' }}</button><span class="dialog-footer-spacer" /><button v-if="canFullEdit" class="button primary" type="button" :disabled="isSaving" @click="confirmImportedCharacter"><Save :size="15" />{{ isSaving ? '保存中…' : '保存角色' }}</button></template>
                </template>
              </BaseDialog>
            </section>
          </div>
          <footer v-if="isMobile && mobileView === 'section'" class="v1-module-footer"><button v-if="activeTab === 'materials' && mobileEntry !== null && canFullEdit" class="button secondary mobile-material-delete" :disabled="busy || isSaving || uploadingMaterial !== null" @click="deleteMobileEntry"><Trash2 :size="16" />删除素材</button><button v-if="creating" class="button primary" :disabled="busy || isSaving" @click="saveModule">创建模组</button><button v-else class="button primary" :disabled="busy || isSaving" @click="mobileBack">{{ mobileEntry !== null ? `返回${sectionLabels[activeTab]}列表` : '返回模组' }}</button></footer>
          <footer v-if="!isMobile && canFullEdit && !creating" class="module-danger-zone"><span><strong>删除模组</strong><small>仅未锁定的自建模组可以删除。</small></span><button class="button ghost danger-text" :disabled="busy" @click="deleteModule"><Trash2 :size="15" />删除</button></footer>
        </template>
      </section>
    </div>
    <BaseDialog v-model="leaveOpen" title="仍有未保存的修改" description="修改仍保留在当前页面。可以返回继续编辑，或明确放弃这次修改。" layer="foreground"><template #footer><button class="button secondary" @click="leaveOpen = false">继续编辑</button><button class="button danger" @click="discardAndLeave">放弃修改并返回</button></template></BaseDialog>
    <BaseDialog v-model="moduleActionsOpen" title="模组选项" mobile-presentation="sheet">
      <nav class="v1-module-action-list"><button v-if="selectedId" :disabled="busy" @click="exportModule(); moduleActionsOpen = false"><Download :size="18" />导出模组</button><button v-if="isLocked" :disabled="busy" @click="unlockConfirm = true; moduleActionsOpen = false"><UnlockKeyhole :size="18" />解锁模组</button><button v-if="canFullEdit && !creating" class="danger-text" :disabled="busy" @click="moduleActionsOpen = false; deleteModule()"><Trash2 :size="18" />删除模组</button></nav>
      <template #footer><button class="button secondary" @click="moduleActionsOpen = false">取消</button></template>
    </BaseDialog>
  </main>
</template>

<style scoped>
.module-library-page { height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: #f6f3ec; }
.module-editor-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.module-save-status { align-self: center; color: var(--muted); font-size: 9px; white-space: nowrap; }
.module-save-status.saving, .module-save-status.dirty { color: #95642b; }
.module-save-status.error { color: var(--wine); }
.module-save-status.saved { color: var(--pine); }
.module-workspace { min-height: 0; flex: 1; display: flex; flex-direction: column; overflow: hidden; }

.module-switcher { min-height: 94px; padding: 12px clamp(28px, 4vw, 58px); border-bottom: 1px solid #d8d4ca; display: grid; grid-template-columns: 130px minmax(0, 1fr) auto; align-items: center; gap: 18px; background: #eeebe3; }
.module-switcher-heading h1, .module-switcher-heading small { display: block; }
.module-switcher-heading h1 { margin: 3px 0 2px; font-family: var(--font-display); font-size: 22px; letter-spacing: -.035em; }
.module-switcher-heading .eyebrow { font-size: 7px; }
.module-switcher-heading small { color: #88887f; font-size: 8px; white-space: nowrap; }
.module-switcher-track { min-width: 0; padding: 2px 2px 4px; display: flex; align-items: center; gap: 7px; overflow-x: auto; }
.module-switcher-group { min-width: max-content; padding: 0 4px 0 8px; color: #888981; font-size: 8px; font-weight: 750; letter-spacing: .1em; text-transform: uppercase; }
.module-switcher-item { width: 190px; min-width: 190px; min-height: 58px; padding: 7px 10px 7px 7px; border: 1px solid transparent; border-radius: 9px; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 9px; color: #52544f; background: transparent; text-align: left; cursor: pointer; transition: 150ms ease; }
.module-switcher-item:hover { background: rgba(255,255,255,.58); }
.module-switcher-item.active { color: #f9f5ec; background: #294f49; box-shadow: 0 7px 18px rgba(41,79,73,.15); }
.module-switcher-item > span:nth-child(2) { min-width: 0; }
.module-switcher-item strong, .module-switcher-item small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.module-switcher-item strong { margin-top: 3px; font-size: 11px; }
.module-switcher-item small { color: #85877f; font-size: 8px; }
.module-switcher-item.active small { color: rgba(255,255,255,.66); }
.module-switcher-cover { width: 42px; height: 42px; border-radius: 7px; display: grid; place-items: center; color: var(--pine); background: #dfe7e3 center/cover; }
.module-switcher-cover.default { color: #8a673a; background: #eadfc9; }
.module-switcher-item.active .module-switcher-cover { color: #294f49; background-color: #f2eadb; }
.module-switcher-actions { display: flex; align-items: center; gap: 7px; }
.module-switcher-actions .button { min-height: 34px; padding-inline: 12px; font-size: 11px; white-space: nowrap; }

.module-editor-pane { min-width: 0; min-height: 0; flex: 1; display: flex; flex-direction: column; overflow: hidden; background: #fbfaf6; }
.module-state { color: var(--pine); font-size: 9px; font-weight: 750; letter-spacing: .1em; }
.module-state.locked { color: #95642b; }
.module-state.readonly { color: #787a75; }
.module-editor-actions { grid-column: 3; align-self: center; justify-self: end; justify-content: flex-end; }
.module-lock-notice { padding: 10px clamp(32px, 4vw, 58px); display: flex; align-items: center; gap: 10px; color: #825b2a; background: #f3e8d5; }
.module-lock-notice strong, .module-lock-notice small { display: block; }
.module-lock-notice strong { font-size: 11px; }
.module-lock-notice small { margin-top: 2px; font-size: 9px; }
.module-unlock-warning { margin: 16px clamp(32px, 4vw, 58px) 0; padding: 13px 15px; border: 1px solid #e4c7cb; border-radius: 9px; display: grid; grid-template-columns: auto 1fr auto auto; align-items: center; gap: 11px; color: var(--wine); background: #fbf2f3; }
.module-unlock-warning strong { font-size: 11px; }
.module-unlock-warning p { margin: 4px 0 0; color: #84696d; font-size: 9px; line-height: 1.6; }

.module-tabs { min-height: 64px; padding: 0 clamp(32px, 4vw, 58px); border-bottom: 1px solid #dedad0; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: stretch; gap: 28px; background: #fbfaf6; }
.module-tab-identity { min-width: 150px; display: flex; flex-direction: column; justify-content: center; }
.module-tab-identity strong { max-width: 190px; margin-top: 4px; overflow: hidden; font-family: var(--font-display); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.module-tab-links { min-width: 0; display: flex; align-items: flex-end; gap: 23px; overflow-x: auto; }
.module-tab-links button { min-height: 64px; padding: 2px 0 0; position: relative; border: 0; color: #797b74; background: transparent; font-size: 11px; cursor: pointer; white-space: nowrap; }
.module-tab-links button::after { height: 3px; position: absolute; inset: auto 0 0; border-radius: 3px 3px 0 0; content: ''; background: transparent; }
.module-tab-links button.active { color: #273c38; font-weight: 750; }
.module-tab-links button.active::after { background: var(--pine); }
.module-editor-scroll { min-height: 0; flex: 1; padding: 38px clamp(32px, 4vw, 58px) 62px; overflow-y: auto; }
.module-editor-scroll > section { width: min(100%, 1120px); margin: 0 auto; }
.editor-section-heading { margin-bottom: 25px; }
.editor-section-heading.full { grid-column: 1 / -1; }
.editor-section-heading h3 { margin: 7px 0 5px; font-family: var(--font-display); font-size: 23px; letter-spacing: -.025em; }
.editor-section-heading p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.module-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 17px 20px; }
.module-form-grid.compact-grid { gap: 10px; }
.field.full, .switch-row.full { grid-column: 1 / -1; }
.field { display: grid; gap: 7px; }
.field > span { color: #5f625c; font-size: 10px; font-weight: 650; }
.field input, .field textarea, .field select, .character-import-source textarea { width: 100%; border: 1px solid #d8d3c8; border-radius: 8px; color: var(--ink); background: #fffefb; padding: 10px 11px; font-size: 12px; line-height: 1.55; resize: vertical; transition: border-color 140ms ease, background 140ms ease; }
.field input:focus, .field textarea:focus, .field select:focus, .character-import-source textarea:focus { border-color: #8ba09a; background: #fff; }
.field input:disabled, .field textarea:disabled, .field select:disabled { color: #696c66; border-color: #e1ded6; background: #f0eee8; }
.overview-grid .field.full textarea { min-height: 120px; }
.switch-row { min-height: 58px; padding: 10px 13px; border: 1px solid #d8d3c8; border-radius: 9px; display: flex; align-items: center; justify-content: space-between; gap: 18px; background: #fffefb; }
.switch-row strong, .switch-row small { display: block; }
.switch-row strong { font-size: 11px; }
.switch-row small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.switch-row input { accent-color: var(--pine); }

.module-collection { display: grid; gap: 0; }
.module-entry-card { padding: 24px 0 27px; border-top: 1px solid #dedad0; background: transparent; }
.module-entry-card > header { margin-bottom: 16px; display: flex; align-items: center; justify-content: space-between; }
.module-entry-card > header strong { font-family: var(--font-display); font-size: 15px; }
.entry-save { margin: 14px 0 0 auto; }
.module-add-card { min-height: 64px; margin-top: 14px; border: 1px dashed #aaa99f; border-radius: 8px; display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--pine); background: rgba(255,255,255,.28); font-size: 11px; font-weight: 700; cursor: pointer; }
.material-grid { grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 16px; }
.collection-heading { grid-column: 1 / -1; }
.material-card { padding: 13px; border: 1px solid #d8d3c8; border-radius: 10px; display: grid; gap: 11px; background: #fffefb; }
.material-card > header { margin: 0; }
.material-image-frame { min-height: 180px; position: relative; border: 1px solid #d8d3c8; border-radius: 8px; display: grid; place-items: center; overflow: hidden; color: #6f7975; background: #f0f1ed; }
.material-image-frame.empty { border-style: dashed; align-content: center; gap: 7px; background: #fafaf7; }
label.material-image-frame.empty { cursor: pointer; transition: border-color 150ms ease, color 150ms ease, background 150ms ease; }
label.material-image-frame.empty:hover, label.material-image-frame.empty:focus-within { border-color: #789089; color: var(--pine); background: #f1f5f2; }
.material-image-frame.empty strong { font-size: 11px; }
.material-image-frame.empty small { color: #979b96; font-size: 9px; }
.material-image-frame > img { width: 100%; height: 100%; min-height: 180px; position: absolute; inset: 0; object-fit: cover; }
.material-image-frame input { width: 1px; height: 1px; position: absolute; opacity: 0; }
.material-image-actions { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 8px; opacity: 0; background: rgba(23, 35, 32, .64); transition: opacity 150ms ease; }
.material-image-frame:hover .material-image-actions, .material-image-frame:focus-within .material-image-actions, .material-image-frame.uploading .material-image-actions { opacity: 1; }
.material-image-action { min-height: 34px; padding: 0 11px; border: 1px solid rgba(255,255,255,.48); border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; color: #fff; background: rgba(20,31,29,.62); font-size: 10px; font-weight: 700; cursor: pointer; }
button.material-image-action { font-family: inherit; }
.material-image-action:hover { background: rgba(20,31,29,.88); }
.material-image-action.danger:hover { border-color: #efc9cd; background: rgba(113,38,49,.9); }
.material-image-action:disabled { opacity: .58; cursor: wait; }

.module-character-section { display: grid; gap: 21px; }
.preset-character-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(225px, 1fr)); gap: 9px; }
.preset-character-list article { min-height: 61px; padding: 11px; border: 1px solid #d8d3c8; border-radius: 9px; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 9px; background: #fffefb; }
.preset-character-list article:hover, .preset-character-list article:focus-within { border-color: #8ba09a; outline: none; }
.preset-character-main { min-width: 0; padding: 0; border: 0; display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 9px; color: inherit; background: transparent; text-align: left; font-family: inherit; cursor: pointer; }
.preset-character-main:focus-visible { outline: none; }
.preset-character-list strong, .preset-character-list small { display: block; }
.preset-character-list strong { font-size: 11px; }
.preset-character-list small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.preset-avatar { width: 37px; height: 37px; border-radius: 50%; display: grid; place-items: center; color: white; background: var(--pine); }
.preset-character-create-card { min-height: 61px; padding: 11px; border: 1px dashed #aaa99f; border-radius: 9px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--pine); background: rgba(255,255,255,.28); font-family: inherit; cursor: pointer; }
.preset-character-create-card:hover, .preset-character-create-card:focus-visible { border-color: var(--pine); outline: none; background: #f1f5f2; }
.preset-character-create-card strong { font-size: 11px; }
:global(.dialog-content.module-character-editor-dialog) { width: min(calc(100vw - 40px), 1040px); max-height: min(88vh, 820px); }
:global(.dialog-content.module-character-editor-dialog.character-dialog-workspace) { height: min(88vh, 820px); }
:global(.module-character-editor-dialog .dialog-body) { min-height: 0; padding: 0; display: flex; flex: 1; overflow: hidden; }
:global(.module-character-editor-dialog .dialog-footer) { align-items: center; }
.character-editor-content { min-height: 0; width: 100%; display: flex; flex-direction: column; overflow: hidden; }
.character-entry-choice { min-height: 360px; padding: 48px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-content: center; gap: 18px; background: #f7f4ed; }
.character-entry-choice > button { min-height: 190px; padding: 28px; border: 1px solid #d8d3c8; border-radius: 12px; display: flex; flex-direction: column; align-items: flex-start; justify-content: flex-end; color: var(--ink); background: #fffefb; text-align: left; font-family: inherit; cursor: pointer; transition: 150ms ease; }
.character-entry-choice > button:hover, .character-entry-choice > button:focus-visible { border-color: #789089; outline: none; box-shadow: 0 10px 30px rgba(41,79,73,.09); transform: translateY(-2px); }
.character-entry-choice strong { margin: 15px 0 7px; font-family: var(--font-display); font-size: 19px; }
.character-entry-choice small { color: var(--muted); font-size: 10px; line-height: 1.65; }
.character-entry-icon { width: 42px; height: 42px; border-radius: 10px; display: grid; place-items: center; color: var(--pine); background: var(--pine-soft); }
.character-parser-layout { min-height: 0; flex: 1; display: grid; grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr); overflow: hidden; }
.character-parser-source, .character-parser-result { min-height: 0; padding: 26px; overflow-y: auto; }
.character-parser-source { border-right: 1px solid var(--line); background: #f7f4ed; }
.character-parser-source h3, .character-parser-result h3 { margin: 6px 0 7px; font-family: var(--font-display); font-size: 18px; }
.character-parser-source p { margin: 0 0 14px; color: var(--muted); font-size: 10px; line-height: 1.65; }
.character-parser-source textarea { width: 100%; min-height: 310px; margin-bottom: 10px; padding: 13px; border: 1px solid #d8d3c8; border-radius: 9px; resize: vertical; background: #fffefb; font-family: var(--font-mono); font-size: 11px; line-height: 1.65; }
.character-parser-result { background: #fffefb; }
.parse-result-summary { margin-top: 18px; }
.parse-result-summary .review-heading strong, .parse-result-summary .review-heading small { display: block; }
.parse-result-summary .review-heading strong { font-family: var(--font-display); font-size: 16px; }
.parse-result-summary .review-heading small { margin-top: 4px; color: var(--muted); font-size: 9px; }
.parsed-attribute-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.parsed-attribute-grid > span { padding: 10px; border: 1px solid var(--line); border-radius: 8px; background: #f8f6f0; }
.parsed-attribute-grid small, .parsed-attribute-grid strong { display: block; }
.parsed-attribute-grid small { color: var(--muted); font-size: 8px; }
.parsed-attribute-grid strong { margin-top: 3px; font-size: 14px; }
.parse-result-empty { min-height: 290px; display: grid; place-content: center; justify-items: center; gap: 7px; color: #9a9d97; }
.parse-result-empty strong { color: #62655f; font-size: 12px; }
.parse-result-empty small { font-size: 9px; }
.character-card-editor { min-height: 0; flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.character-card-editor fieldset { min-height: 0; margin: 0; padding: 26px; border: 0; flex: 1; overflow-y: auto; }
.character-editor-tabs { min-height: 52px; padding: 0 26px; border-bottom: 1px solid var(--line); display: flex; align-items: stretch; gap: 24px; background: #f7f4ed; }
.character-editor-tabs button { position: relative; border: 0; color: var(--muted); background: transparent; font-family: inherit; font-size: 10px; cursor: pointer; }
.character-editor-tabs button::after { height: 2px; position: absolute; inset: auto 0 0; content: ''; background: transparent; }
.character-editor-tabs button.active { color: var(--pine); font-weight: 750; }
.character-editor-tabs button.active::after { background: var(--pine); }
.character-basics-layout { max-width: 820px; }
.attribute-editor-grid { padding-top: 4px; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; }
.attribute-editor-grid.full { grid-column: 1 / -1; }
.attribute-editor-grid label { padding: 9px; border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; background: #f8f6f0; }
.attribute-editor-grid label > span { font-size: 9px; font-weight: 700; }
.attribute-editor-grid label small { color: var(--muted); font-size: 7px; }
.attribute-editor-grid input { width: 100%; padding: 4px 0; border: 0; border-bottom: 1px solid #cbc7bd; background: transparent; font-size: 14px; }
.character-list-editor > header { margin-bottom: 16px; display: flex; align-items: center; justify-content: space-between; }
.character-list-editor > header strong, .character-list-editor > header small { display: block; }
.character-list-editor > header strong { font-family: var(--font-display); font-size: 17px; }
.character-list-editor > header small { margin-top: 4px; color: var(--muted); font-size: 9px; }
.character-list-editor > header > span { padding: 5px 8px; border-radius: 12px; color: var(--pine); background: var(--pine-soft); font-size: 9px; }
.character-data-rows { border-top: 1px solid var(--line); }
.character-data-rows > div { min-height: 48px; border-bottom: 1px solid var(--line); display: grid; grid-template-columns: minmax(0, 1fr) 100px auto; align-items: center; gap: 10px; }
.character-data-rows strong { font-size: 10px; }
.character-data-rows label { display: flex; align-items: center; gap: 4px; }
.character-data-rows input { width: 72px; padding: 7px; border: 1px solid var(--line); border-radius: 7px; background: #fff; }
.character-skills-editor { min-height: 0; height: 100%; display: flex; flex-direction: column; }
.character-skills-editor > header { margin-bottom: 12px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.character-skills-editor > header strong, .character-skills-editor > header small { display: block; }
.character-skills-editor > header strong { font-family: var(--font-display); font-size: 17px; }
.character-skills-editor > header small { margin-top: 4px; color: var(--muted); font-size: 9px; }
.character-skills-editor > header > span { padding: 5px 8px; border-radius: 12px; color: var(--pine); background: var(--pine-soft); font-size: 9px; white-space: nowrap; }
.character-skill-search { width: min(100%, 520px); height: 42px; min-height: 42px; margin-bottom: 12px; padding: 0 12px; border: 1px solid #d9d4ca; border-radius: 9px; display: flex; flex-shrink: 0; align-items: center; gap: 8px; color: #929289; background: #fff; }
.character-skill-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; font-size: 11px; }
.character-skill-list { min-height: 0; overflow-y: auto; display: grid; gap: 5px; }
.character-skill-list article { min-height: 52px; padding: 7px 9px 7px 11px; border: 1px solid #e1ddd4; border-radius: 8px; display: grid; grid-template-columns: minmax(140px, 1fr) minmax(100px, 150px) 112px 44px; align-items: center; gap: 8px; background: rgba(255,255,255,.62); }
.character-skill-list article > span, .character-skill-list article > span strong, .character-skill-list article > span small { min-width: 0; display: block; }
.character-skill-list article > span strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.character-skill-list article > span small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.character-skill-list input { width: 100%; min-width: 0; height: 31px; padding: 0 8px; border: 1px solid #d9d4ca; border-radius: 6px; background: #fff; font-size: 10px; }
.character-skill-list input:disabled { color: #8d8d86; background: #f1efe9; }
.character-skill-list article > label { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 5px; }
.character-skill-list article > label small { color: var(--muted); font-size: 9px; }
.character-skill-list article > b { color: var(--pine); font-size: 12px; text-align: right; }
.weapon-record-list { display: grid; gap: 7px; }
.weapon-record-list article { padding: 12px; border: 1px solid var(--line); border-radius: 8px; display: flex; align-items: center; justify-content: space-between; background: #f8f6f0; }
.weapon-record-list strong, .weapon-record-list small { display: block; }
.weapon-record-list strong { font-size: 10px; }
.weapon-record-list small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.character-section-empty { min-height: 120px; border: 1px dashed var(--line); border-radius: 9px; display: grid; place-items: center; color: var(--muted); font-size: 10px; }
.character-background-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.dialog-footer-spacer { flex: 1; }
.review-heading { margin-bottom: 15px; display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.validation-badge { padding: 5px 8px; border-radius: 12px; color: var(--pine); background: var(--pine-soft); font-size: 9px; }
.validation-badge.error { color: var(--wine); background: #f4e1e4; }
.inline-inputs { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; }
.inline-inputs.three { grid-template-columns: repeat(3, 1fr); }
.parsed-skills, .unresolved-lines, .weapon-builder { margin-top: 15px; padding-top: 15px; border-top: 1px solid var(--line); }
.parsed-skills > strong, .unresolved-lines > strong, .weapon-builder > strong { margin-bottom: 8px; display: block; font-size: 11px; }
.parsed-skills > span, .weapon-chips > span { margin: 0 5px 5px 0; padding: 5px 7px; border-radius: 12px; display: inline-flex; gap: 5px; background: var(--pine-soft); font-size: 9px; }
.parsed-skills.compact > span { color: var(--pine); }
.parsed-skills button, .weapon-chips button { padding: 0; border: 0; background: transparent; cursor: pointer; }
.parsed-skills > small { color: var(--muted); font-size: 9px; }
.unresolved-lines code { margin-top: 5px; padding: 7px 8px; border-radius: 6px; display: block; color: #735f43; background: #f5ecdd; font-size: 9px; white-space: pre-wrap; }
.weapon-builder > .button { margin-top: 9px; }
.field-error { display: block; color: var(--wine); font-size: 9px; }
.weapon-chips { margin-top: 9px; }
.import-confirm-actions { margin-top: 17px; display: flex; justify-content: flex-end; gap: 8px; }
.module-danger-zone { padding: 12px clamp(32px, 4vw, 58px); border-top: 1px solid #dedad0; display: flex; align-items: center; justify-content: space-between; background: #f4f1ea; }
.module-danger-zone strong, .module-danger-zone small { display: block; }
.module-danger-zone strong { font-size: 10px; }
.module-danger-zone small { margin-top: 2px; color: var(--muted); font-size: 8px; }
.module-empty { min-height: 220px; display: grid; place-content: center; justify-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.module-empty strong { color: var(--ink); font-size: 13px; }
.module-empty.small { min-height: 120px; }
.module-toast { position: fixed; z-index: 50; top: 24px; right: 28px; padding: 10px 14px; border-radius: 8px; color: white; background: #294f49; box-shadow: var(--shadow); font-size: 11px; }

@media(max-width:1050px) {
  .module-library-page { height: auto; min-height: 100vh; overflow: visible; }
  .module-workspace, .module-editor-pane { overflow: visible; }
  .module-tabs { grid-template-columns: auto minmax(0, 1fr); }
  .module-editor-actions { grid-column: 1 / -1; padding: 0 0 12px; justify-self: start; }
  .module-form-grid { grid-template-columns: 1fr; }
  .field.full, .switch-row.full, .editor-section-heading.full { grid-column: auto; }
}
@media(max-width:760px) {
  .module-switcher { padding: 12px 18px; grid-template-columns: 1fr; gap: 8px; }
  .module-switcher-heading { display: flex; align-items: center; justify-content: space-between; }
  .module-switcher-heading .eyebrow { display: none; }
  .module-switcher-heading h1 { margin: 0; }
  .module-switcher-heading small { margin: 0; }
  .module-switcher-actions { justify-content: flex-end; }
  .module-lock-notice { padding-inline: 18px; }
  .module-unlock-warning { margin-inline: 18px; grid-template-columns: auto 1fr; }
  .module-unlock-warning .button { grid-column: span 1; }
  .module-tabs { padding: 10px 18px 0; grid-template-columns: 1fr; gap: 4px; }
  .module-tab-links { gap: 18px; }
  .module-editor-actions { grid-column: 1; grid-row: 1; justify-self: end; padding: 0; }
  .module-tab-identity { padding-right: 150px; }
  .module-editor-scroll { padding: 30px 18px 44px; }
  .module-danger-zone { padding-inline: 18px; }
  .material-grid { grid-template-columns: 1fr; }
  :global(.dialog-content.module-character-editor-dialog) { width: calc(100vw - 20px); max-height: 92vh; }
  :global(.dialog-content.module-character-editor-dialog.character-dialog-workspace) { height: 92vh; }
  .character-entry-choice, .character-parser-layout, .character-basics-layout { grid-template-columns: 1fr; }
  .character-entry-choice { padding: 20px; }
  .character-entry-choice > button { min-height: 140px; }
  .character-parser-layout { overflow-y: auto; }
  .character-parser-source, .character-parser-result { overflow: visible; }
  .character-parser-source { border-right: 0; border-bottom: 1px solid var(--line); }
  .character-skill-list article { grid-template-columns: minmax(0, 1fr) 90px 42px; }
  .character-skill-list .skill-specialization { grid-column: 1 / -1; grid-row: 2; }
  .attribute-editor-grid, .parsed-attribute-grid, .character-background-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 767px) {
  :global(.dialog-content.module-character-editor-dialog.character-dialog-workspace) { height: var(--app-viewport-height, 100dvh); }
  .module-library-page { padding: 0; height: auto; min-height: var(--app-viewport-height, 100dvh); }
  .module-workspace { display: block; height: auto; min-height: 0; }
  .module-switcher { display: flex; flex-direction: column; align-items: stretch; grid-template-columns: minmax(0, 1fr); padding: 26px 16px; width: 100%; height: auto; border: 0; background: var(--paper); }
  .module-switcher-heading { padding: 0 0 20px; }
  .module-switcher-heading h1 { font-size: 24px; }
  .module-switcher-track { display: grid; grid-template-columns: minmax(0, 1fr); padding: 0; overflow: visible; }
  .module-switcher-item { min-width: 0; width: 100%; padding: 16px; min-height: 90px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; }
  .module-switcher-item > span { min-width: 0; }
  .module-switcher-item strong { white-space: normal; overflow-wrap: anywhere; font-size: 14px; }
  .module-switcher-item small { font-size: 12px; }
  .module-switcher-group { margin: 12px 0 4px; font-size: 12px; }
  .module-switcher-actions { display: flex; gap: 8px; padding: 18px 0; }
  .module-switcher-actions > * { flex: 1; }
  .mobile-module-list .module-editor-pane { display: none; }
  .mobile-module-list .module-switcher:has(.module-switcher-track:empty)::after { content: '还没有模组，可以新建或导入。'; display: block; color: var(--muted); padding: 25px 0; }
  .mobile-module-directory .module-switcher, .mobile-module-section .module-switcher { display: none; }
  .module-editor-pane { display: flex; flex-direction: column; height: var(--app-viewport-height, 100dvh); min-height: 0; }
  .mobile-module-header { min-height: 60px; flex-shrink: 0; display: flex; align-items: center; gap: 8px; padding: max(8px, env(safe-area-inset-top)) 12px 8px; border-bottom: 1px solid var(--line); background: var(--paper); }
  .mobile-module-heading { flex: 1; min-width: 0; font-size: 14px; line-height: 1.5; margin: 0; overflow-wrap: anywhere; }
  .mobile-module-header .module-save-status { max-width: 80px; text-align: right; font-size: 10px; }
  .mobile-module-directory-list { padding: 16px; overflow-y: auto; flex: 1; }
  .mobile-module-directory-list > p { color: var(--muted); line-height: 1.85; font-size: 14px; white-space: pre-wrap; overflow-wrap: anywhere; }
  .mobile-module-directory-list > button { width: 100%; min-height: 62px; display: flex; justify-content: space-between; align-items: center; border: 0; border-bottom: 1px solid var(--line); background: none; font-size: 14px; color: var(--pine); text-align: left; }
  .module-tabs { padding: 12px 16px; min-height: 0; flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; position: static; }
  .module-tab-links, .module-tab-identity { display: none; }
  .module-editor-actions { width: 100%; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
  .module-editor-actions .button { flex: 1; }
  .module-editor-actions .module-save-status { display: none; }
  .mobile-module-directory .module-tabs { order: 4; padding-bottom: max(12px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); }
  .mobile-module-directory .module-editor-scroll { display: none; }
  .module-editor-scroll { padding: 16px 16px max(24px, env(safe-area-inset-bottom)); min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
  .module-form-grid, .material-grid, .character-parser-layout, .character-entry-choice, .character-profile-grid { grid-template-columns: minmax(0, 1fr); }
  .module-form-grid .field { min-width: 0; }
  .field input, .field textarea, .field select, .character-parser-source textarea { font-size: 16px; }
  .field > span, .switch-row strong, .switch-row small, .editor-section-heading p { font-size: 12px; }
  .module-lock-notice, .module-unlock-warning { margin: 0 16px; padding: 12px; flex-shrink: 0; flex-wrap: wrap; }
  .module-lock-notice small, .module-unlock-warning p { font-size: 12px; }
  .module-lock-notice { width: 100%; margin: 0; padding: 12px 16px; flex-wrap: nowrap; border-top: 1px solid #e4d4b9; }
  .module-lock-notice > svg { flex-shrink: 0; }
  .module-lock-notice > span { flex: 1; min-width: 0; line-height: 1.5; }
  .mobile-module-section .module-lock-notice { order: 5; padding-bottom: max(12px, env(safe-area-inset-bottom)); }
  .module-entry-card { padding: 10px 0; }
  .module-entry-card > header { margin: 0; gap: 8px; }
  .mobile-module-entry-title { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex: 1; min-width: 0; min-height: 54px; border: 0; background: none; text-align: left; font-size: 14px; color: var(--pine); overflow-wrap: anywhere; }
  .module-entry-card:not(.mobile-entry-selected) > :not(header) { display: none; }
  .mobile-has-entry .module-entry-card:not(.mobile-entry-selected), .mobile-has-entry .module-add-card { display: none; }
  .module-entry-selected > header { margin-bottom: 14px; }
  .module-add-card { min-height: 52px; font-size: 13px; }
  .material-image-actions { opacity: 1; transform: none; }
  .character-editor-tabs { overflow-x: auto; display: flex; }
  .character-editor-tabs button { min-height: 44px; flex-shrink: 0; }
  .module-toast { max-width: calc(100vw - 24px); overflow-wrap: anywhere; }
}

@media (max-width: 767px) {
  .module-library-page { height: 100%; min-height: 0; overflow: hidden; }
  .module-workspace { height: 100%; min-height: 0; overflow: hidden; }
  .v1-module-library { height: 100%; min-height: 0; display: flex; flex-direction: column; }
  .v1-module-library-header { min-height: 64px; flex-shrink: 0; display: flex; align-items: center; gap: 8px; padding: 6px 12px 10px; border-bottom: 1px solid var(--line); }
  .v1-module-library-header > div { flex: 1; min-width: 0; }
  .v1-module-library-header strong { display: block; font-size: 17px; line-height: 1.5; font-weight: 600; }
  .v1-module-library-header small { display: block; color: var(--muted); font-size: 11px; margin-top: 3px; }
  .v1-module-brand { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; background: var(--pine); color: #fff; margin: 0 9px; font-size: 22px; }
  .v1-module-library-content { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 18px 24px; }
  .v1-module-intro { padding: 8px 0 18px; }
  .v1-module-intro .eyebrow { font: 10px/1.7 ui-monospace, monospace; letter-spacing: 1.5px; }
  .v1-module-intro h1 { font-size: 28px; font-weight: 600; letter-spacing: -.6px; line-height: 1.35; margin: 12px 0 10px; overflow-wrap: anywhere; }
  .v1-module-intro p { color: var(--muted); margin: 0; font-size: 13px; line-height: 1.8; }
  .v1-module-wide { width: 100%; margin: 7px 0; }
  .v1-module-sectionline { display: flex; justify-content: space-between; align-items: center; min-height: 30px; margin: 23px 0 12px; }
  .v1-module-sectionline h3 { font-size: 16px; font-weight: 600; margin: 0; }
  .v1-module-import { color: var(--pine); font-size: 12px; min-height: 44px; display: inline-flex; align-items: center; }
  .v1-module-featured { border: 1px solid var(--line); border-radius: 13px; padding: 17px; margin: 12px 0; background: var(--surface); }
  .v1-module-featured > small { color: var(--wine); font-size: 11px; letter-spacing: .5px; }
  .v1-module-featured h2 { font-size: 23px; font-weight: 550; line-height: 1.4; margin: 12px 0; }
  .v1-module-featured p { font-size: 13px; line-height: 1.8; color: var(--muted); margin: 8px 0 12px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .v1-module-meta { font-size: 11px; color: var(--muted); margin: 16px 0; }
  .v1-module-row { width: 100%; min-height: 76px; padding: 15px 0; display: flex; align-items: center; gap: 12px; border: 0; border-bottom: 1px solid var(--line); background: transparent; text-align: left; color: var(--ink); }
  .v1-module-row > span:nth-child(2) { flex: 1; min-width: 0; }
  .v1-module-row strong { display: block; font-size: 15px; font-weight: 550; line-height: 1.5; }
  .v1-module-row small { display: block; color: var(--muted); font-size: 12px; line-height: 1.6; margin-top: 4px; }
  .v1-module-row > svg { color: var(--muted); flex-shrink: 0; }
  .v1-module-avatar { width: 46px; height: 46px; border-radius: 14px; background: var(--pine-soft) center/cover; display: grid; place-items: center; color: var(--pine); flex-shrink: 0; }
  .v1-module-empty { color: var(--muted); font-size: 13px; line-height: 1.8; padding: 24px 0; }
  .mobile-module-header { min-height: 64px; padding: 6px 12px 10px; }
  .mobile-module-heading { font-size: 17px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mobile-module-directory-list { padding: 18px 18px 24px; min-height: 0; }
  .mobile-module-directory-list > button { min-height: 76px; padding: 15px 0; gap: 12px; color: var(--ink); }
  .mobile-module-directory-list > button > span:first-child { flex: 1; min-width: 0; }
  .mobile-module-directory-list strong { display: block; font-size: 15px; font-weight: 550; line-height: 1.5; }
  .mobile-module-directory-list small { display: block; color: var(--muted); font-size: 12px; line-height: 1.6; margin-top: 4px; }
  .v1-module-count { font-size: 11px; color: var(--muted); }
  .mobile-module-directory-list > .v1-module-notice { font-size: 13px; line-height: 1.8; color: #44614f; padding: 14px; border-radius: 11px; background: var(--pine-soft); margin: 16px 0; white-space: normal; }
  .module-tabs { display: none; }
  .module-lock-notice { order: 5; padding: 12px 18px max(12px, env(safe-area-inset-bottom)); margin: 0; width: 100%; }
  .module-lock-notice strong { font-size: 13px; }
  .module-editor-scroll { padding: 18px 18px 24px; }
  .module-editor-scroll .editor-section-heading { display: none; }
  .module-form-grid, .module-collection, .material-grid { display: block; }
  .module-form-grid .field { margin: 17px 0; }
  .module-form-grid > .field:first-child { margin-top: 0; }
  .module-form-grid .field > span { font-size: 13px; font-weight: 550; margin-bottom: 8px; }
  .module-form-grid .field input, .module-form-grid .field textarea, .module-form-grid .field select { padding: 12px; border-radius: 10px; min-height: 48px; font-size: 16px; line-height: 1.6; }
  .module-form-grid .field small { font-size: 12px; line-height: 1.7; }
  .v1-module-details { margin: 15px 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; font-size: 12px; color: var(--muted); }
  .v1-module-details summary { min-height: 22px; }
  .v1-module-cover-preview { display: block; width: 100%; max-height: 180px; object-fit: contain; margin-bottom: 12px; }
  .module-entry-card { border: 0; border-bottom: 1px solid var(--line); border-radius: 0; background: transparent; padding: 0; }
  .mobile-module-entry-title { min-height: 76px; padding: 15px 0; font-size: 15px; color: var(--ink); font-weight: 550; }
  .module-entry-selected { border: 0; }
  .module-entry-selected > header { margin: 0 0 12px; }
  .module-entry-selected .mobile-module-entry-title { min-height: 30px; padding: 0; font-size: 12px; font-weight: 400; color: var(--muted); }
  .module-entry-selected .mobile-module-entry-title > svg { display: none; }
  .module-add-card { width: 100%; min-height: 46px; margin: 16px 0; padding: 10px 16px; border: 1px solid var(--line); border-radius: 11px; font-size: 14px; }
  .v1-module-footer { display: flex; gap: 10px; padding: 12px 18px max(12px, env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--surface); flex-shrink: 0; order: 4; }
  .v1-module-footer > button { flex: 1; }
  .v1-module-action-list > button { width: 100%; min-height: 64px; padding: 15px 0; display: flex; align-items: center; gap: 12px; border: 0; border-bottom: 1px solid var(--line); font-size: 15px; background: transparent; text-align: left; }
  .v1-module-footer:has(.mobile-material-delete) { flex-direction: column; }
  .v1-module-footer:has(.mobile-material-delete) > button { flex: auto; width: 100%; }
  .v1-module-footer .mobile-material-delete { width: 100%; color: var(--wine); border-color: #dec6c8; background: #faf0ef; }
  .v1-material-reading h2 { font-size: 23px; font-weight: 550; line-height: 1.4; margin: 12px 0; }
  .v1-material-reading p { font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
  .material-image-frame.has-image { aspect-ratio: auto; min-height: 0; background: transparent; border: 0; }
  .material-image-frame.has-image img { position: static; height: auto; max-height: none; width: 100%; object-fit: contain; }
  .preset-character-list { display: block; }
  .preset-character-list > article { border: 0; border-bottom: 1px solid var(--line); border-radius: 0; padding: 15px 0; min-height: 76px; background: transparent; }
  .preset-character-main { min-height: 46px; padding: 0; gap: 12px; }
  .preset-character-main strong { font-size: 15px; font-weight: 550; }
  .preset-character-main small { font-size: 12px; line-height: 1.6; margin-top: 4px; }
  .preset-avatar { width: 46px; height: 46px; border-radius: 14px; font-size: 19px; }
  .preset-character-create-card { margin: 16px 0; min-height: 46px; width: 100%; border: 1px solid var(--line); border-radius: 11px; font-size: 14px; }
  .character-editor-tabs { padding: 0 18px; gap: 18px; }
  .character-editor-tabs button { font-size: 13px; }
  .character-card-editor fieldset { padding: 18px; }
  .character-skills-editor { height: auto; display: block; }
  .character-skill-list { overflow: visible; display: block; }
  .character-skill-list article { border: 0; border-bottom: 1px solid var(--line); border-radius: 0; min-height: 76px; padding: 12px 0; background: none; }
  .character-skill-list article > span strong { font-size: 14px; }
  .character-skill-list article > span small { font-size: 11px; line-height: 1.6; }
  .character-skill-list input { font-size: 16px; height: 45px; }
  .character-skill-list article > label small { font-size: 11px; }
  .character-skill-list article > b { font-size: 16px; }
  .character-skills-editor > header small, .character-skills-editor > header > span { font-size: 12px; }
  .character-skill-search input { font-size: 16px; }
  .v1-module-character-actions > button { width: 100%; margin: 7px 0; }
  .character-entry-choice { padding: 18px; gap: 12px; }
  .character-entry-choice > button { min-height: 100px; }
  .character-entry-choice strong { font-size: 16px; }
  .character-entry-choice small { font-size: 12px; line-height: 1.7; }
  .character-parser-layout { display: block; }
  .character-parser-source, .character-parser-result { padding: 18px; }
  .character-parser-source textarea { min-height: 240px; border-radius: 10px; padding: 12px; line-height: 1.6; }
  .character-parser-source h3, .character-parser-result h3 { font-size: 16px; }
  .character-parser-source p { font-size: 12px; line-height: 1.7; }
  .v1-parser-tabs { display: flex; margin: 12px 18px 16px; gap: 3px; border-radius: 10px; padding: 3px; background: #e8e7df; }
  .v1-parser-tabs button { flex: 1; min-height: 40px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); font-size: 13px; }
  .v1-parser-tabs button.active { background: var(--surface-strong); color: var(--pine); }
  .attribute-editor-grid, .parsed-attribute-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .attribute-editor-grid label > span, .parsed-attribute-grid small { font-size: 11px; }
  .attribute-editor-grid label small { display: block; font-size: 10px; }
  .attribute-editor-grid input { min-height: 40px; font-size: 18px; }
  .parsed-attribute-grid strong { font-size: 21px; font-weight: 550; }
  .parsed-skills > strong, .unresolved-lines > strong, .weapon-builder > strong { font-size: 14px; }
  .parsed-skills > span, .validation-badge, .review-heading small { font-size: 11px; line-height: 1.6; }
  .review-heading strong { font-size: 16px; }
  .weapon-record-list article { border: 0; border-bottom: 1px solid var(--line); border-radius: 0; background: transparent; padding: 15px 0; }
  .weapon-record-list strong { font-size: 14px; }
  .weapon-record-list small { font-size: 12px; line-height: 1.6; }
  .module-form-grid .switch-row { padding: 17px 14px; border-bottom: 1px solid var(--line); }
  .module-form-grid .switch-row strong { font-size: 14px; font-weight: 550; }
  .module-form-grid .switch-row small { font-size: 11px; line-height: 1.7; }
  .module-form-grid .switch-row input[type=checkbox] { appearance: none; width: 42px; height: 25px; flex-shrink: 0; position: relative; border: 0; border-radius: 20px; background: #c8cbbf; }
  .module-form-grid .switch-row input[type=checkbox]::after { content: ''; width: 19px; height: 19px; border-radius: 50%; position: absolute; top: 3px; left: 3px; background: var(--surface-strong); }
  .module-form-grid .switch-row input[type=checkbox]:checked { background: var(--pine); }
  .module-form-grid .switch-row input[type=checkbox]:checked::after { left: 20px; }

}
</style>
