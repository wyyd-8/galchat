import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, clearSession, currentSession, saveSession, streamGroupMessage, UNAUTHORIZED_EVENT } from '@/api/client'
import type {
  Character, CharacterTemplate, Conversation, GroupChatEvent, GroupMessage, ReplyPlan, ReplyPlanItem,
  UserInfo, UserWorld, WorldDetail, WorldSave, WorldTemplate,
} from '@/api/types'
import { errorMessage, notify } from './useNotice'

let tempMessageId = -1
const freshPlan = (): ReplyPlan => ({ source: 'USER', groups: [{ key: 'default', name: '群聊', order: 1, items: [] }] })

export function useWorkspace() {
  const session = reactive(currentSession())
  const loading = reactive({ boot: false, worlds: false, workspace: false, chat: false, sending: false })
  const userInfo = ref<UserInfo | null>(null)
  const worlds = ref<UserWorld[]>([])
  const templates = ref<WorldTemplate[]>([])
  const selectedWorldId = ref<number | null>(null)
  const characters = ref<Character[]>([])
  const characterTemplates = ref<CharacterTemplate[]>([])
  const details = ref<WorldDetail[]>([])
  const worldSave = ref<WorldSave | null>(null)
  const conversations = ref<Conversation[]>([])
  const selectedConversationId = ref<number | null>(null)
  const messages = ref<GroupMessage[]>([])
  const reasoning = reactive<Record<number, string>>({})
  const replyPlan = ref<ReplyPlan>(freshPlan())
  const participantIds = ref<number[]>([])
  const messageInput = ref('')
  const messageScroller = ref<HTMLElement | null>(null)

  const isLoggedIn = computed(() => Boolean(session.token && session.id))
  const selectedWorld = computed(() => worlds.value.find((item) => item.id === selectedWorldId.value) || null)
  const canEditSelectedWorld = computed(() => Boolean(selectedWorld.value?.myWorld))
  const selectedConversation = computed(() => conversations.value.find((item) => item.id === selectedConversationId.value) || null)
  const planItems = computed(() => replyPlan.value.groups.flatMap((group) => group.items))
  const availablePlanCharacters = computed(() => characters.value.filter((character) =>
    participantIds.value.includes(character.characterId) && !planItems.value.some((item) => item.actorId === character.characterId)))

  function characterById(id?: number) { return characters.value.find((item) => item.characterId === id) }
  function resetWorkspace() {
    selectedWorldId.value = null; selectedConversationId.value = null; worlds.value = []; characters.value = []
    conversations.value = []; messages.value = []; replyPlan.value = freshPlan(); participantIds.value = []
  }
  function logout() { clearSession(); Object.assign(session, currentSession()); userInfo.value = null; resetWorkspace() }

  async function boot() {
    if (!isLoggedIn.value) return
    loading.boot = true
    try { await Promise.all([loadWorlds(), loadTemplates(), loadUserInfo()]) } catch (error) { notify('无法载入工作区', errorMessage(error), 'danger') }
    finally { loading.boot = false }
  }
  async function authenticate(payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }) {
    const result = payload.mode === 'login' ? await api.login(payload.email, payload.password) : await api.register(payload.email, payload.password, payload.code || '')
    saveSession(result); Object.assign(session, currentSession()); await boot(); notify('欢迎回来', result.username, 'success')
  }
  async function loadUserInfo() { userInfo.value = await api.userInfo() }
  async function saveUserInfo(payload: Partial<UserInfo>) { await api.updateUserInfo(payload); await loadUserInfo(); notify('账号资料已保存', '', 'success') }
  async function changePassword(payload: { email: string; newPassword: string; verificationCode: string }) { await api.updatePassword(payload); notify('密码已更新', '', 'success') }

  async function loadTemplates() { templates.value = await api.worldTemplates() }
  async function loadWorlds() {
    if (!session.id) return
    loading.worlds = true
    try { worlds.value = await api.userWorlds(session.id) } finally { loading.worlds = false }
  }
  async function selectWorld(id: number) {
    selectedWorldId.value = id; selectedConversationId.value = null; messages.value = []; loading.workspace = true
    try {
      const [world, characterResult, conversationResult, saveResult] = await Promise.all([api.userWorld(id), api.characters(id), api.conversations(id), api.worldSave(id)])
      worlds.value = worlds.value.map((item) => item.id === id ? { ...item, ...world } : item)
      characters.value = characterResult; conversations.value = conversationResult; worldSave.value = saveResult
      if (world?.worldId) {
        const [templateResult, detailResult] = await Promise.all([api.characterTemplates(world.worldId), api.worldDetails(world.worldId)])
        characterTemplates.value = templateResult; details.value = detailResult
      }
      const active = conversations.value.find((item) => item.status === 'active') || conversations.value[0]
      if (active) await selectConversation(active.id)
    } catch (error) { notify('世界加载失败', errorMessage(error), 'danger') }
    finally { loading.workspace = false }
  }
  async function createWorld(payload: Partial<UserWorld> & { worldId: number }) { await api.createWorld(payload); await loadWorlds(); notify('世界已创建', '', 'success') }
  async function updateWorld(payload: Partial<UserWorld>) {
    if (!selectedWorldId.value) return
    await api.updateWorld(selectedWorldId.value, payload)
    const updated = await api.userWorld(selectedWorldId.value)
    worlds.value = worlds.value.map((item) => item.id === updated.id ? { ...item, ...updated } : item)
    notify('世界设置已保存', '', 'success')
  }
  async function removeWorld() { if (!selectedWorldId.value) return; await api.deleteWorld(selectedWorldId.value); resetWorkspace(); await loadWorlds(); notify('世界已删除', '', 'success') }
  async function createTemplate(payload: WorldTemplate) { await api.createWorldTemplate(payload); await loadTemplates(); notify('世界模板已创建', '', 'success') }
  async function loadEditableWorldTemplate() {
    if (!selectedWorldId.value || !canEditSelectedWorld.value) throw new Error('只有原创世界可以修改模板')
    return api.myWorldTemplate(selectedWorldId.value)
  }
  async function updateTemplate(payload: WorldTemplate) {
    if (!selectedWorldId.value || !canEditSelectedWorld.value) throw new Error('只有原创世界可以修改模板')
    await api.updateWorldTemplate(selectedWorldId.value, payload); await Promise.all([loadTemplates(), selectWorld(selectedWorldId.value)])
    notify('世界模板已保存', '', 'success')
  }
  async function addDetail(payload: WorldDetail) { if (!selectedWorld.value?.worldId) return; await api.addWorldDetail(selectedWorld.value.worldId, payload); details.value = await api.worldDetails(selectedWorld.value.worldId) }
  async function removeDetail(id: number) { if (!selectedWorld.value?.worldId) return; await api.deleteWorldDetail(selectedWorld.value.worldId, id); details.value = await api.worldDetails(selectedWorld.value.worldId) }
  async function saveSnapshot(remark: string) { if (!selectedWorldId.value) return; worldSave.value = await api.saveWorld(selectedWorldId.value, remark); notify('存档已保存', '', 'success') }
  async function loadSnapshot() { if (!selectedWorldId.value) return; await api.loadWorld(selectedWorldId.value); await selectWorld(selectedWorldId.value); notify('已回到存档时刻', '', 'success') }

  async function reloadCharacters() { if (selectedWorldId.value) characters.value = await api.characters(selectedWorldId.value) }
  async function addCharacter(id: number, prompt = '') {
    if (!selectedWorldId.value) return
    await api.addCharacter(selectedWorldId.value, id)
    await api.updatePrompt(selectedWorldId.value, id, prompt.trim())
    await reloadCharacters()
  }
  async function removeCharacter(id: number) { if (!selectedWorldId.value) return; await api.deleteCharacter(selectedWorldId.value, id); characters.value = await api.characters(selectedWorldId.value) }
  async function updateCharacter(id: number, prompt: string, favor?: number) {
    if (!selectedWorldId.value) return
    const requests: Promise<void>[] = [api.updatePrompt(selectedWorldId.value, id, prompt)]
    if (typeof favor === 'number') requests.push(api.updateFavor(selectedWorldId.value, id, favor))
    await Promise.all(requests)
    characters.value = await api.characters(selectedWorldId.value); notify('角色资料已保存', '', 'success')
  }
  async function createCharacterTemplate(payload: CharacterTemplate) {
    if (!selectedWorld.value?.worldId) return
    await api.createCharacterTemplate(selectedWorld.value.worldId, payload)
    characterTemplates.value = await api.characterTemplates(selectedWorld.value.worldId); notify('角色模板已创建', '', 'success')
  }
  async function loadEditableCharacterTemplate(id: number) {
    if (!selectedWorldId.value || !canEditSelectedWorld.value) throw new Error('只有原创世界可以修改角色模板')
    return api.myCharacterTemplate(selectedWorldId.value, id)
  }
  async function updateCharacterTemplate(id: number, payload: CharacterTemplate) {
    if (!selectedWorldId.value || !selectedWorld.value?.worldId || !canEditSelectedWorld.value) throw new Error('只有原创世界可以修改角色模板')
    await api.updateCharacterTemplate(selectedWorldId.value, id, payload)
    characterTemplates.value = await api.characterTemplates(selectedWorld.value.worldId); await reloadCharacters()
    notify('角色模板已保存', '', 'success')
  }

  async function createConversation(payload: { mode: 'chat' | 'trpg'; title: string; opening?: string; characterIds: number[] }) {
    if (!selectedWorldId.value) return
    const created = await api.createConversation({ ...payload, userWorldId: selectedWorldId.value })
    conversations.value = await api.conversations(selectedWorldId.value); participantIds.value = [...payload.characterIds]
    await selectConversation(created.id); notify('群聊已建立', created.title, 'success')
  }
  async function selectConversation(id: number) {
    selectedConversationId.value = id; loading.chat = true; messages.value = []; Object.keys(reasoning).forEach((key) => delete reasoning[Number(key)])
    try {
      const [history, plan] = await Promise.all([api.groupMessages(id), api.replyPlan(id)])
      messages.value = [...history].sort((a, b) => a.sequenceNo - b.sequenceNo)
      replyPlan.value = plan || freshPlan()
      participantIds.value = [...new Set(replyPlan.value.groups.flatMap((group) => group.items.map((item) => item.actorId)))]
      await scrollToBottom()
    } catch (error) { notify('群聊加载失败', errorMessage(error), 'danger') }
    finally { loading.chat = false }
  }
  async function endConversation(ending?: string) {
    if (!selectedConversationId.value || !selectedWorldId.value) return
    await api.endConversation(selectedConversationId.value, ending); conversations.value = await api.conversations(selectedWorldId.value); notify('群聊已结束', '', 'success')
  }
  async function savePlan() {
    if (!selectedConversationId.value) return
    const groups = replyPlan.value.groups.filter((group) => group.items.length).map((group, groupIndex) => ({
      ...group, order: groupIndex + 1, items: group.items.map((item, index) => ({ ...item, order: index + 1 })),
    }))
    if (!groups.length) throw new Error('回复顺序至少保留一位角色')
    replyPlan.value = await api.saveReplyPlan(selectedConversationId.value, { ...replyPlan.value, source: 'USER', groups })
    notify('回复顺序已保存', '', 'success')
  }
  function movePlanItem(from: number, to: number) {
    const items = replyPlan.value.groups[0]?.items; if (!items || from === to || to < 0 || to >= items.length) return
    const [moved] = items.splice(from, 1); if (moved) items.splice(to, 0, moved)
  }
  function deletePlanItem(index: number) { replyPlan.value.groups[0]?.items.splice(index, 1) }
  function addPlanItem(actorId: number) {
    const group = replyPlan.value.groups[0] || (replyPlan.value.groups[0] = { key: 'default', name: '群聊', order: 1, items: [] })
    if (!group.items.some((item) => item.actorId === actorId)) group.items.push({ order: group.items.length + 1, actorType: 'character', actorId, status: 'pending' })
  }

  function applyEvent(event: GroupChatEvent) {
    const step = event.replyStepId
    if (event.eventType === 'reply.started' && step) {
      const character = characterById(event.speaker?.id)
      messages.value.push({ id: tempMessageId--, conversationId: selectedConversationId.value!, turnId: event.turnId, replyStepId: step,
        speakerType: 'character', speakerId: event.speaker?.id, speakerName: event.speaker?.name || character?.characterName,
        messageKind: 'dialogue', content: '', sequenceNo: event.sequence || Date.now(), status: 'streaming' })
      reasoning[step] = ''
    } else if (event.eventType === 'reasoning.delta' && step) {
      reasoning[step] = (reasoning[step] || '') + (event.delta || '')
    } else if (event.eventType === 'message.delta' && step) {
      const message = messages.value.find((item) => item.replyStepId === step); if (message) message.content += event.delta || ''
    } else if (event.eventType === 'message.completed' && step) {
      const message = messages.value.find((item) => item.replyStepId === step)
      if (message) { if (event.messageId) message.id = event.messageId; message.content = event.content ?? message.content; message.status = 'completed' }
    } else if (event.eventType === 'reply.failed' && step) {
      const message = messages.value.find((item) => item.replyStepId === step)
      if (message) { message.status = 'failed'; message.content ||= event.error || '回复生成失败' }
    } else if (event.eventType === 'reply.failed' && event.error) {
      notify('本轮回复中断', event.error, 'danger')
    }
    void scrollToBottom()
  }
  async function sendMessage() {
    const content = messageInput.value.trim(); const conversation = selectedConversation.value
    if (!content || !conversation || conversation.status !== 'active' || loading.sending) return
    messageInput.value = ''; loading.sending = true
    messages.value.push({ id: tempMessageId--, conversationId: conversation.id, speakerType: 'user', messageKind: 'dialogue', content,
      sequenceNo: Date.now(), status: 'completed', createdAt: new Date().toISOString() })
    await scrollToBottom()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      await streamGroupMessage(conversation.id, { clientRequestId, content }, applyEvent)
      await api.finishReplyPlan(conversation.id)
      const [history, plan] = await Promise.all([api.groupMessages(conversation.id), api.replyPlan(conversation.id)])
      messages.value = [...history].sort((a, b) => a.sequenceNo - b.sequenceNo); replyPlan.value = plan
    } catch (error) { notify('消息发送失败', errorMessage(error), 'danger') }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function scrollToBottom() { await nextTick(); messageScroller.value?.scrollTo({ top: messageScroller.value.scrollHeight, behavior: 'smooth' }) }

  window.addEventListener(UNAUTHORIZED_EVENT, logout)
  onMounted(boot)
  return {
    session, loading, userInfo, worlds, templates, selectedWorldId, selectedWorld, characters, characterTemplates, details, worldSave,
    conversations, selectedConversationId, selectedConversation, messages, reasoning, replyPlan, participantIds, messageInput, messageScroller,
    isLoggedIn, canEditSelectedWorld, planItems, availablePlanCharacters, characterById, authenticate, logout, loadUserInfo, saveUserInfo, changePassword,
    loadWorlds, loadTemplates, selectWorld, createWorld, updateWorld, removeWorld, createTemplate, loadEditableWorldTemplate, updateTemplate, addDetail, removeDetail, saveSnapshot, loadSnapshot,
    reloadCharacters, addCharacter, removeCharacter, updateCharacter, createCharacterTemplate, loadEditableCharacterTemplate, updateCharacterTemplate, createConversation, selectConversation, endConversation,
    savePlan, movePlanItem, deletePlanItem, addPlanItem, sendMessage,
  }
}
