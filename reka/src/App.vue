<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Download, ImageUp, Pencil, Plus, RotateCcw, Trash2 } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import AppSidebar from '@/components/AppSidebar.vue'
import AuthDialog from '@/components/AuthDialog.vue'
import DirectChatStage from '@/components/DirectChatStage.vue'
import GroupChatStage from '@/components/GroupChatStage.vue'
import TrpgToolsDialog from '@/components/TrpgToolsDialog.vue'
import WorldHome from '@/components/WorldHome.vue'
import WorldLibrary from '@/components/WorldLibrary.vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import NoticeToast from '@/components/ui/NoticeToast.vue'
import { api, uploadImage } from '@/api/client'
import type { CharacterTemplate, UserInfo, UserWorld, WorldArchive, WorldDetail, WorldTemplate } from '@/api/types'
import { useDirectChat } from '@/composables/useDirectChat'
import { errorMessage, notify } from '@/composables/useNotice'
import { useWorkspace } from '@/composables/useWorkspace'

interface FavorabilityRow { id: string; threshold?: number; prompt: string }

const workspace = useWorkspace()
const direct = useDirectChat({ world: workspace.selectedWorld, characters: workspace.characters, reloadCharacters: workspace.reloadCharacters })
const authOpen = ref(!workspace.isLoggedIn.value)
const view = ref<'library' | 'world' | 'group' | 'direct'>('library')
const dialogs = reactive({ world: false, template: false, templatePreview: false, conversation: false, character: false, characterTemplate: false, characterEdit: false, settings: false, save: false, worldLoad: false, account: false, password: false, end: false, trpgTools: false })
const busy = ref(false)
const uploading = ref<'world' | 'character' | null>(null)
const templateMode = ref<'create' | 'edit'>('create')
const characterTemplateMode = ref<'create' | 'edit'>('create')
const editingCharacterTemplateId = ref<number | null>(null)
const worldForm = reactive({ worldId: '', name: '', acitvePushStatus: true, dailyCompanionMode: true, favorSystemStatus: 'NORMAL', thinkStatus: true, addSpecialPrompt: false, eotDetectionStatus: false })
const templateForm = reactive<WorldTemplate>({ name: '', author: '', image: '', background: '', visible: true })
const conversationForm = reactive({ title: '', mode: 'chat' as 'chat' | 'trpg', moduleId: '', characterIds: [] as number[] })
const characterChoice = ref('')
const characterPrompt = ref('')
const characterTemplateForm = reactive<CharacterTemplate>({ name: '', image: '', background: '', personality: '', cocPlayStyle: '', initFavor: 0, favorability: {} })
const favorabilityRows = ref<FavorabilityRow[]>([])
const selectedCharacterId = ref<number | null>(null)
const characterEditForm = reactive({ prompt: '', favor: 0 })
const settingsForm = reactive({ name: '', acitvePushStatus: false, favorSystemStatus: 'NORMAL', eotDetectionStatus: true })
const detailForm = reactive<WorldDetail>({ about: '', details: '' })
const saveRemark = ref('')
const accountForm = reactive({ username: '', email: '', birthday: '', diceSkin: '' })
const passwordForm = reactive({ email: '', newPassword: '', confirmPassword: '', code: '' })
const selectedTemplatePreview = ref<WorldTemplate | null>(null)

const availableTemplates = computed(() => workspace.characterTemplates.value.filter((template) => template.id && !workspace.characters.value.some((character) => character.characterId === template.id)))
const selectedConversationFormModule = computed(() => workspace.modules.value.find((item) => item.id === Number(conversationForm.moduleId)) || null)
const selectedConversationModule = computed(() => workspace.modules.value.find((item) => item.id === workspace.selectedConversation.value?.moduleId) || null)
const selectedCharacter = computed(() => workspace.characters.value.find((item) => item.characterId === selectedCharacterId.value) || null)
const templateDialogTitle = computed(() => templateMode.value === 'edit' ? '修改世界模板' : '创建世界模板')
const characterTemplateDialogTitle = computed(() => characterTemplateMode.value === 'edit' ? '修改角色模板' : '创建角色模板')

watch(() => workspace.isLoggedIn.value, (loggedIn) => { authOpen.value = !loggedIn; if (!loggedIn) { direct.close(); view.value = 'library' } })
watch(() => worldForm.thinkStatus, (thinking) => { if (thinking) worldForm.eotDetectionStatus = false; else worldForm.addSpecialPrompt = false })

async function run(action: () => Promise<unknown>, close?: keyof typeof dialogs) {
  busy.value = true
  try { await action(); if (close) dialogs[close] = false; return true }
  catch (error) { notify('操作失败', errorMessage(error), 'danger'); return false }
  finally { busy.value = false }
}
async function authenticate(payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }) { await run(async () => { await workspace.authenticate(payload); authOpen.value = false }) }
function logout() { direct.close(); workspace.logout() }
function home() { direct.close(); workspace.selectedWorldId.value = null; workspace.selectedConversationId.value = null; view.value = 'library' }
async function selectWorld(id: number) { direct.close(); await workspace.selectWorld(id); view.value = 'world' }
async function selectConversation(id: number) { direct.close(); await workspace.selectConversation(id); view.value = 'group' }
async function openDirectChat(id: number) { await direct.selectCharacter(id); view.value = 'direct' }
function closeDirectChat() { direct.close(); view.value = 'world' }
async function restoreTrpg() { const id = workspace.selectedConversationId.value; if (id) await workspace.selectConversation(id) }

function openNewWorld() {
  Object.assign(worldForm, { worldId: '', name: '', acitvePushStatus: true, dailyCompanionMode: true, favorSystemStatus: 'NORMAL', thinkStatus: true, addSpecialPrompt: false, eotDetectionStatus: false })
  dialogs.world = true
}
async function openTemplatePreview(id: number) {
  await run(async () => { selectedTemplatePreview.value = await api.worldTemplate(id); dialogs.templatePreview = true })
}
function createFromPreview() {
  const id = selectedTemplatePreview.value?.id; if (!id) return
  openNewWorld(); worldForm.worldId = String(id); dialogs.templatePreview = false
}
function openNewConversation() { Object.assign(conversationForm, { title: '', mode: 'chat', moduleId: '', characterIds: [] }); dialogs.conversation = true }
function openAddCharacter() { characterChoice.value = ''; characterPrompt.value = ''; dialogs.character = true }
function openCharacter(id: number) {
  const item = workspace.characters.value.find((character) => character.characterId === id); if (!item) return
  selectedCharacterId.value = id; characterEditForm.prompt = item.userInfoPrompt || ''; characterEditForm.favor = item.favorValue || 0; dialogs.characterEdit = true
}
function openSettings() {
  const world = workspace.selectedWorld.value; if (!world) return
  Object.assign(settingsForm, { name: world.name, acitvePushStatus: world.acitvePushStatus ?? true, favorSystemStatus: world.favorSystemStatus || 'NORMAL', eotDetectionStatus: world.eotDetectionStatus !== false }); dialogs.settings = true
}
function resetTemplateForm() { Object.assign(templateForm, { name: '', author: workspace.session.username || '', image: '', background: '', visible: true }) }
function openCreateTemplate() { templateMode.value = 'create'; resetTemplateForm(); dialogs.template = true }
async function openEditTemplate() {
  await run(async () => { templateMode.value = 'edit'; Object.assign(templateForm, await workspace.loadEditableWorldTemplate()); dialogs.settings = false; dialogs.template = true })
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
async function exportWorld() {
  const id = workspace.selectedWorldId.value; if (!id) return
  await run(async () => { const text = await api.exportWorld(id); const blob = new Blob([text], { type: 'application/json' }); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = `${workspace.selectedWorld.value?.name || 'galchat-world'}.json`; link.click(); URL.revokeObjectURL(link.href) })
}
async function openAccount() { await run(async () => { await workspace.loadUserInfo(); const info = workspace.userInfo.value; Object.assign(accountForm, { username: info?.username || '', email: info?.email || '', birthday: info?.birthday || '', diceSkin: info?.diceSkin || '' }); dialogs.account = true }) }
async function openPassword() { await run(async () => { await workspace.loadUserInfo(); Object.assign(passwordForm, { email: workspace.userInfo.value?.email || '', newPassword: '', confirmPassword: '', code: '' }); dialogs.password = true }) }
async function sendPasswordCode() { await run(async () => { await api.sendPasswordCode(passwordForm.email); notify('验证码已发送', '', 'success') }) }
async function changePassword() {
  if (passwordForm.newPassword !== passwordForm.confirmPassword) throw new Error('两次输入的新密码不一致')
  await workspace.changePassword({ email: passwordForm.email, newPassword: passwordForm.newPassword, verificationCode: passwordForm.code })
}
</script>

<template>
  <div v-if="workspace.isLoggedIn.value" class="app-shell">
    <AppSidebar :session="workspace.session" :worlds="workspace.worlds.value" :conversations="workspace.conversations.value" :selected-world-id="workspace.selectedWorldId.value" :selected-conversation-id="workspace.selectedConversationId.value" :loading="workspace.loading.worlds" @home="home" @select-world="selectWorld" @select-conversation="selectConversation" @new-world="openNewWorld" @new-conversation="openNewConversation" @account="openAccount" @password="openPassword" @logout="logout" />
    <div class="app-content">
      <WorldLibrary v-if="view === 'library'" :worlds="workspace.worlds.value" :templates="workspace.templates.value" :loading="workspace.loading.boot" @select="selectWorld" @preview-template="openTemplatePreview" @create-world="openNewWorld" @create-template="openCreateTemplate" @import-world="importWorld" />
      <WorldHome v-else-if="view === 'world' && workspace.selectedWorld.value" :world="workspace.selectedWorld.value" :characters="workspace.characters.value" :conversations="workspace.conversations.value" :world-save="workspace.worldSave.value" @open-character="openDirectChat" @edit-character="openCharacter" @open-conversation="selectConversation" @new-conversation="openNewConversation" @add-character="openAddCharacter" @save="dialogs.save = true" @load="dialogs.worldLoad = true" @settings="openSettings" />
      <DirectChatStage v-else-if="view === 'direct' && workspace.selectedWorld.value && direct.selectedCharacter.value" v-model:input="direct.input.value" v-model:scroller="direct.scroller.value" :world="workspace.selectedWorld.value" :character="direct.selectedCharacter.value" :messages="direct.messages.value" :loading="direct.loading" :can-withdraw="direct.canWithdraw.value" :has-older-messages="direct.hasOlderMessages.value" @back="closeDirectChat" @send="direct.send" @withdraw="direct.withdraw" @load-earlier="direct.loadEarlier" @edit="openCharacter(direct.selectedCharacter.value.characterId)" @focus="direct.focus" @composition="direct.setComposing" />
      <GroupChatStage v-else-if="view === 'group' && workspace.selectedConversation.value" v-model:input="workspace.messageInput.value" v-model:scroller="workspace.messageScroller.value" :conversation="workspace.selectedConversation.value" :messages="workspace.messages.value" :reasoning="workspace.reasoning" :characters="workspace.characters.value" :reply-plan="workspace.replyPlan.value" :available-characters="workspace.availablePlanCharacters.value" :current-turn="workspace.currentTurn.value" :sending="workspace.loading.sending" :loading="workspace.loading.chat" :has-older-messages="workspace.hasOlderGroupMessages.value" @back="view = 'world'" @save-plan="run(workspace.savePlan)" @advance-plan="run(workspace.advancePlan)" @move-plan-item="workspace.movePlanItem" @delete-plan-item="workspace.deletePlanItem" @add-plan-item="workspace.addPlanItem" @load-earlier="workspace.loadOlderGroupMessages" @withdraw="run(workspace.withdrawGroupTurn)" @open-tools="dialogs.trpgTools = true" @send="workspace.sendMessage" @start-turn="workspace.startTrpgTurn" @select-scene="workspace.selectSceneOption" @end-exploration="workspace.endExploration" @retry="workspace.retryStep" @end="dialogs.end = true" />
    </div>
  </div>
  <div v-else class="signed-out"><span class="brand-glyph large">✦</span><h1>GalChat</h1><p>一个安静的角色与群像叙事工作台。</p><button class="button primary" @click="authOpen = true">登录或注册</button></div>

  <AuthDialog v-model="authOpen" @submit="authenticate" />

  <BaseDialog v-model="dialogs.world" title="创建世界" description="从一个模板开始，并设定角色的陪伴方式。" size="lg">
    <div class="form-stack"><label class="field"><span>世界模板</span><select v-model="worldForm.worldId"><option value="">请选择</option><option v-for="item in workspace.templates.value" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="field"><span>世界名称</span><input v-model.trim="worldForm.name" placeholder="留空则使用模板名称" /></label><div class="field"><span>好感提升难度</span><div class="segmented"><button v-for="item in ['EASY','NORMAL','HARD']" :key="item" :class="{ active: worldForm.favorSystemStatus === item }" @click="worldForm.favorSystemStatus = item">{{ {EASY:'轻松',NORMAL:'标准',HARD:'困难'}[item as 'EASY'] }}</button></div></div><label class="switch-row"><span><strong>主动提醒</strong><small>允许角色主动发送消息</small></span><input v-model="worldForm.acitvePushStatus" type="checkbox" /></label><label class="switch-row"><span><strong>日常陪伴模式</strong><small>启用持续陪伴上下文</small></span><input v-model="worldForm.dailyCompanionMode" type="checkbox" /></label><label class="switch-row"><span><strong>角色思考</strong><small>使用流式思考模式回复</small></span><input v-model="worldForm.thinkStatus" type="checkbox" /></label><label v-if="worldForm.thinkStatus" class="switch-row"><span><strong>中性代词演出</strong><small>将 AI 回复中的“他/她”显示为“ta”</small></span><input v-model="worldForm.addSpecialPrompt" type="checkbox" /></label><label v-else class="switch-row"><span><strong>输入结束识别</strong><small>等待用户完整表达后再回复</small></span><input v-model="worldForm.eotDetectionStatus" type="checkbox" /></label></div>
    <template #footer><button class="button ghost" @click="dialogs.world = false">取消</button><button class="button primary" :disabled="!worldForm.worldId || busy" @click="run(() => workspace.createWorld({ ...worldForm, worldId: Number(worldForm.worldId), eotDetectionStatus: worldForm.thinkStatus ? false : worldForm.eotDetectionStatus, addSpecialPrompt: worldForm.thinkStatus && worldForm.addSpecialPrompt }), 'world')">创建</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.template" :title="templateDialogTitle" description="编辑原创世界的封面、背景与公开状态。" size="lg">
    <div class="form-grid"><label class="field"><span>模板名称</span><input v-model.trim="templateForm.name" /></label><label class="field"><span>作者</span><input v-model.trim="templateForm.author" /></label><label class="field full"><span>封面</span><div class="upload-row"><input v-model.trim="templateForm.image" placeholder="图片 URL" /><label class="button secondary file-button"><ImageUp :size="16" />{{ uploading === 'world' ? '上传中' : '上传图片' }}<input type="file" accept="image/*" :disabled="uploading !== null" @change="handleImage($event, 'world')" /></label></div></label><label class="field full"><span>世界背景</span><textarea v-model.trim="templateForm.background" rows="7" /></label><label class="switch-row full"><span><strong>公开模板</strong><small>其他用户可以发现并使用</small></span><input v-model="templateForm.visible" type="checkbox" /></label></div>
    <template #footer><button class="button ghost" @click="dialogs.template = false">取消</button><button class="button primary" :disabled="!templateForm.name || !templateForm.background || busy" @click="run(saveTemplate, 'template')">{{ templateMode === 'edit' ? '保存模板' : '创建模板' }}</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.templatePreview" :title="selectedTemplatePreview?.name || '世界模板'" :description="selectedTemplatePreview?.author ? `作者：${selectedTemplatePreview.author}` : '匿名创作者'" size="lg">
    <div v-if="selectedTemplatePreview" class="template-preview"><div class="template-preview-cover" :style="selectedTemplatePreview.image ? { backgroundImage: `url(${selectedTemplatePreview.image})` } : {}" /><div><span class="eyebrow">{{ selectedTemplatePreview.visible === false ? 'PRIVATE TEMPLATE' : 'PUBLIC TEMPLATE' }}</span><p>{{ selectedTemplatePreview.background || '尚未填写世界背景。' }}</p></div></div>
    <template #footer><button class="button ghost" @click="dialogs.templatePreview = false">关闭</button><button class="button primary" @click="createFromPreview"><Plus :size="16" />使用此模板</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.conversation" title="建立群聊" description="普通群聊可自由编排回复；跑团会按模组和行动轮推进。" size="lg">
    <div class="form-stack"><div class="field"><span>模式</span><div class="segmented"><button :class="{ active: conversationForm.mode === 'chat' }" @click="conversationForm.mode = 'chat'">普通群聊</button><button :class="{ active: conversationForm.mode === 'trpg' }" @click="conversationForm.mode = 'trpg'">跑团</button></div></div><label class="field"><span>标题</span><input v-model.trim="conversationForm.title" placeholder="例如：深夜图书馆" /></label><label v-if="conversationForm.mode === 'trpg'" class="field"><span>跑团模组</span><select v-model="conversationForm.moduleId"><option value="">请选择可用模组</option><option v-for="item in workspace.modules.value" :key="item.id" :value="String(item.id)">{{ item.name }}{{ item.era ? ` · ${item.era}` : '' }}</option></select><small v-if="selectedConversationFormModule">{{ selectedConversationFormModule.author || '佚名作者' }} · {{ selectedConversationFormModule.playerCount || '人数不限' }} · {{ selectedConversationFormModule.estimatedDuration || '时长未标注' }}<br />{{ selectedConversationFormModule.introduction }}</small><small v-else-if="!workspace.modules.value.length">当前没有可选的公开模组。</small></label><div class="field"><span>参与角色</span><div class="check-grid"><label v-for="item in workspace.characters.value" :key="item.characterId" class="check-card"><input v-model="conversationForm.characterIds" type="checkbox" :value="item.characterId" /><span class="reply-avatar" :style="item.characterImage ? { backgroundImage: `url(${item.characterImage})` } : {}">{{ item.characterImage ? '' : item.characterName.slice(0,1) }}</span><strong>{{ item.characterName }}</strong></label></div></div></div>
    <template #footer><button class="button ghost" @click="dialogs.conversation = false">取消</button><button class="button primary" :disabled="!conversationForm.title || !conversationForm.characterIds.length || (conversationForm.mode === 'trpg' && !Number(conversationForm.moduleId)) || busy" @click="run(() => workspace.createConversation({ title: conversationForm.title, mode: conversationForm.mode, moduleId: conversationForm.mode === 'trpg' ? Number(conversationForm.moduleId) : undefined, characterIds: conversationForm.characterIds }), 'conversation').then((success) => { if (success) view = 'group' })">进入群聊</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.character" title="添加角色" description="选择角色模板，并填写该角色需要记住的用户信息。">
    <div v-if="availableTemplates.length" class="form-stack"><div class="choice-list"><label v-for="item in availableTemplates" :key="item.id" class="choice-row"><input v-model="characterChoice" type="radio" :value="String(item.id)" /><span class="character-avatar small" :style="item.image ? { backgroundImage: `url(${item.image})` } : {}">{{ item.image ? '' : item.name.slice(0,1) }}</span><span><strong>{{ item.name }}</strong><small>{{ item.personality || '未填写性格' }}</small></span></label></div><label class="field"><span>用户信息提示词</span><textarea v-model="characterPrompt" rows="4" placeholder="例如：称呼、偏好、共同经历…" /></label></div><div v-else class="empty-panel compact"><p>没有可添加的角色模板。</p></div>
    <template #footer><button v-if="workspace.canEditSelectedWorld.value" class="button ghost" @click="openCreateCharacterTemplate">新建角色模板</button><button class="button primary" :disabled="!characterChoice || busy" @click="run(() => workspace.addCharacter(Number(characterChoice), characterPrompt), 'character')">添加</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.characterTemplate" :title="characterTemplateDialogTitle" size="lg">
    <div class="form-grid"><label class="field"><span>角色名</span><input v-model.trim="characterTemplateForm.name" /></label><label class="field"><span>初始好感</span><input v-model.number="characterTemplateForm.initFavor" type="number" min="0" max="100" /></label><label class="field full"><span>角色图片</span><div class="upload-row"><input v-model.trim="characterTemplateForm.image" placeholder="图片 URL" /><label class="button secondary file-button"><ImageUp :size="16" />{{ uploading === 'character' ? '上传中' : '上传图片' }}<input type="file" accept="image/*" :disabled="uploading !== null" @change="handleImage($event, 'character')" /></label></div></label><label class="field full"><span>背景</span><textarea v-model.trim="characterTemplateForm.background" rows="4" /></label><label class="field full"><span>性格</span><textarea v-model.trim="characterTemplateForm.personality" rows="4" /></label><label class="field full"><span>COC 跑团偏好</span><textarea v-model.trim="characterTemplateForm.cocPlayStyle" rows="4" placeholder="例如：倾向优先调查无人探索的地点；遇到明显危险时更愿意与同伴结伴。" /></label><div class="field full"><span>好感度阶段提示词</span><div class="favorability-list"><div v-for="row in favorabilityRows" :key="row.id" class="favorability-row"><input v-model.number="row.threshold" type="number" min="0" max="100" placeholder="阈值" /><input v-model.trim="row.prompt" placeholder="达到该好感度时的角色表现" /><button class="icon-button" @click="removeFavorabilityRow(row.id)"><Trash2 :size="15" /></button></div></div><button class="button ghost add-row-button" @click="addFavorabilityRow"><Plus :size="15" />添加阶段</button></div></div>
    <template #footer><button class="button ghost" @click="dialogs.characterTemplate = false">取消</button><button class="button primary" :disabled="!characterTemplateForm.name || busy" @click="run(saveCharacterTemplate, 'characterTemplate')">{{ characterTemplateMode === 'edit' ? '保存模板' : '创建模板' }}</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.characterEdit" :title="selectedCharacter?.characterName || '角色资料'" description="这些设置会影响角色在当前世界中的表现。">
    <div class="form-stack"><label class="field"><span>好感度</span><input v-model.number="characterEditForm.favor" type="range" min="0" max="100" :disabled="!workspace.canEditSelectedWorld.value" /><output>{{ characterEditForm.favor }}</output></label><label class="field"><span>用户信息提示词</span><textarea v-model="characterEditForm.prompt" rows="6" placeholder="角色应该如何理解用户…" /></label><button v-if="workspace.canEditSelectedWorld.value && selectedCharacterId" class="button secondary" @click="openEditCharacterTemplate(selectedCharacterId)"><Pencil :size="16" />编辑角色模板</button></div>
    <template #footer><button v-if="workspace.canEditSelectedWorld.value" class="button ghost danger-text" @click="run(removeSelectedCharacter, 'characterEdit')"><Trash2 :size="16" />移出世界</button><button class="button primary" :disabled="!selectedCharacterId || busy" @click="run(saveSelectedCharacter, 'characterEdit')">保存</button></template>
  </BaseDialog>

  <BaseDialog v-model="dialogs.settings" title="世界设置" size="lg">
    <TabsRoot default-value="general" class="tabs"><TabsList class="tabs-list"><TabsTrigger value="general">常规</TabsTrigger><TabsTrigger v-if="workspace.canEditSelectedWorld.value" value="lore">世界设定</TabsTrigger><TabsTrigger value="data">数据</TabsTrigger></TabsList><TabsContent value="general" class="tabs-content"><div class="form-stack"><label class="field"><span>世界名称</span><input v-model.trim="settingsForm.name" /></label><div class="field"><span>好感提升难度</span><div class="segmented"><button v-for="item in ['EASY','NORMAL','HARD']" :key="item" :class="{ active: settingsForm.favorSystemStatus === item }" @click="settingsForm.favorSystemStatus = item">{{ item }}</button></div></div><label class="switch-row"><span><strong>主动提醒</strong></span><input v-model="settingsForm.acitvePushStatus" type="checkbox" /></label><label class="switch-row"><span><strong>输入结束识别</strong></span><input v-model="settingsForm.eotDetectionStatus" type="checkbox" /></label></div><div class="dialog-inline-actions"><button v-if="workspace.canEditSelectedWorld.value" class="button secondary" @click="openEditTemplate"><Pencil :size="16" />编辑世界模板</button><button class="button primary" @click="run(() => workspace.updateWorld({ ...settingsForm }))">保存设置</button></div></TabsContent><TabsContent v-if="workspace.canEditSelectedWorld.value" value="lore" class="tabs-content"><div class="detail-list"><article v-for="item in workspace.details.value" :key="item.id"><span><strong>{{ item.about }}</strong><p>{{ item.details }}</p></span><button class="icon-button" @click="run(() => removeDetail(item.id))"><Trash2 :size="15" /></button></article></div><div class="form-stack detail-form"><label class="field"><span>主题</span><input v-model.trim="detailForm.about" /></label><label class="field"><span>内容</span><textarea v-model.trim="detailForm.details" rows="3" /></label><button class="button secondary" :disabled="!detailForm.about || !detailForm.details" @click="run(async () => { await workspace.addDetail({ ...detailForm }); detailForm.about = ''; detailForm.details = '' })"><Plus :size="16" />添加设定</button></div></TabsContent><TabsContent value="data" class="tabs-content"><div class="danger-zone"><button v-if="workspace.canEditSelectedWorld.value" class="button secondary" @click="exportWorld"><Download :size="16" />导出世界</button><button v-if="workspace.canEditSelectedWorld.value" class="button danger" @click="run(workspace.removeWorld, 'settings').then(() => view = 'library')"><Trash2 :size="16" />删除世界</button><span v-if="!workspace.canEditSelectedWorld.value">收藏世界不能修改模板或删除原始数据。</span></div></TabsContent></TabsRoot>
  </BaseDialog>

  <BaseDialog v-model="dialogs.save" title="保存世界快照"><label class="field"><span>存档备注</span><textarea v-model.trim="saveRemark" rows="4" placeholder="记录此刻发生了什么" /></label><template #footer><button class="button ghost" @click="dialogs.save = false">取消</button><button class="button primary" @click="run(() => workspace.saveSnapshot(saveRemark), 'save')">保存</button></template></BaseDialog>
  <BaseDialog v-model="dialogs.worldLoad" title="确认读取世界存档" description="读档会回滚角色、聊天、好感和世界事件，并删除存档点之后的进度。">
    <div class="restore-summary"><strong>{{ workspace.worldSave.value?.remark || '未填写存档备注' }}</strong><span>{{ workspace.worldSave.value?.savedAt || '未知存档时间' }}</span><p>这项操作不可撤销，请确认当前进度已不再需要。</p></div>
    <template #footer><button class="button ghost" @click="dialogs.worldLoad = false">取消</button><button class="button danger" :disabled="busy" @click="run(workspace.loadSnapshot, 'worldLoad')"><RotateCcw :size="16" />确认读档</button></template>
  </BaseDialog>
  <BaseDialog v-model="dialogs.account" title="账号资料"><div class="form-stack"><label class="field"><span>用户名</span><input v-model.trim="accountForm.username" /></label><label class="field"><span>邮箱</span><input v-model="accountForm.email" disabled /></label><label class="field"><span>生日</span><input v-model="accountForm.birthday" type="date" /></label><label class="field"><span>骰子皮肤标识</span><input v-model.trim="accountForm.diceSkin" maxlength="50" placeholder="例如 galaxy-blue" /></label></div><template #footer><button class="button primary" @click="run(() => workspace.saveUserInfo(accountForm as Partial<UserInfo>), 'account')">保存</button></template></BaseDialog>
  <BaseDialog v-model="dialogs.password" title="修改密码"><div class="form-stack"><label class="field"><span>邮箱</span><input v-model="passwordForm.email" disabled /></label><label class="field"><span>验证码</span><div class="field-inline"><input v-model.trim="passwordForm.code" /><button class="button secondary" @click="sendPasswordCode">发送验证码</button></div></label><label class="field"><span>新密码</span><input v-model="passwordForm.newPassword" type="password" /></label><label class="field"><span>确认新密码</span><input v-model="passwordForm.confirmPassword" type="password" /></label></div><template #footer><button class="button primary" :disabled="!passwordForm.code || !passwordForm.newPassword || !passwordForm.confirmPassword" @click="run(changePassword, 'password')">更新密码</button></template></BaseDialog>
  <BaseDialog v-model="dialogs.end" title="关闭会话" description="服务端会生成会话总结并将状态设为已关闭，之后不能继续发送消息。"><template #footer><button class="button ghost" @click="dialogs.end = false">取消</button><button class="button danger" :disabled="busy" @click="run(workspace.closeConversation, 'end')">确认关闭</button></template></BaseDialog>
  <TrpgToolsDialog v-if="workspace.selectedConversation.value?.mode === 'trpg'" v-model="dialogs.trpgTools" :conversation="workspace.selectedConversation.value" :module="selectedConversationModule" :characters="workspace.characters.value" :latest-dice-roll="workspace.latestDiceRoll.value" @restored="restoreTrpg" />
  <NoticeToast />
</template>
