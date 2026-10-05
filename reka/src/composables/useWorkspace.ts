import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, clearSession, currentSession, saveSession, streamGroupGeneration, streamGroupMessage, streamGroupRetry, streamManualGroupMessage, streamTrpgTurn, UNAUTHORIZED_EVENT } from '@/api/client'
import type {
  Character, CharacterTemplate, CocModule, Conversation, CurrentTurn, DiceRollAggregate, GenerationFailureState, GroupActorRuntime, GroupActorRuntimeSavePayload, GroupChatEvent, GroupMessage, InvestigatorCardSummary, ModelApi, ReplyPlan, ReplyPlanItem, TrpgCombatParticipantOverview, TrpgComposerIntent, TrpgGameTimePeriod,
  UserInfo, UserWorld, WorldDetail, WorldSave, WorldTemplate,
} from '@/api/types'
import { beginReplyTurn, updateReplyTurn, type ReplyTurnState } from '@/components/replyTurnStatus'
import { activeReplyPlan } from '@/components/replyPlanState'
import { resetConversationScrollFollowing, scrollConversationToLatest } from '@/components/reasoningScroll'
import { decodeParticipantIds, encodeParticipantIds, resolveParticipantIds } from '@/components/trpgSetupState'
import { applyGameTimeEvent } from '@/components/gameTimeState'
import { applyCurrentTurnEvent } from '@/components/trpgExecutionState'
import { hydrateDiceMessage } from '@/dice/domain/dicePlayback'
import { clearChatReadingPositions } from '@/components/chatReadingPosition'
import { useScopedChatDraft } from '@/components/chatInputState'
import { errorMessage, notify, refreshAfterSave } from './useNotice'
import { createCharacterData } from './characterData'
import { followGeneration, isGenerationAbort, GenerationStartRejected } from '@/streaming/generationConnection'

let tempMessageId = -1
const freshPlan = (): ReplyPlan => ({ source: 'USER', displayName: '群聊', items: [] })

export function useWorkspace() {
  const session = reactive(currentSession())
  const readRevisions = { userInfo: 0, templates: 0, modules: 0, worlds: 0, models: 0 }
  let sessionRevision = 0
  function captureSession() {
    const revision = sessionRevision
    const { id, token } = session
    return () => revision === sessionRevision && id === session.id && token === session.token
  }
  const withdrawingConversations = reactive(new Set<number>())
  const savingPlanConversations = reactive(new Set<number>())
  const savingActorRuntimes = reactive(new Set<string>())
  const settingsSaves = new Map<number, { pending: number; succeeded: boolean; ready: Promise<boolean>; finish: (success: boolean) => void }>()
  const loading = reactive({ boot: false, worlds: false, workspace: false, chat: false, sending: false,
    get withdrawing() { return selectedConversationId.value != null && withdrawingConversations.has(selectedConversationId.value) } })
  const userInfo = ref<UserInfo | null>(null)
  const worlds = ref<UserWorld[]>([])
  const templates = ref<WorldTemplate[]>([])
  const selectedWorldId = ref<number | null>(null)
  const loadedWorldId = ref<number | null>(null)
  const worldReady = computed(() => selectedWorldId.value != null && loadedWorldId.value === selectedWorldId.value)
  const characters = ref<Character[]>([])
  const characterTemplates = ref<CharacterTemplate[]>([])
  const modules = ref<CocModule[]>([])
  const details = ref<WorldDetail[]>([])
  const worldSave = ref<WorldSave | null>(null)
  const conversations = ref<Conversation[]>([])
  const selectedConversationId = ref<number | null>(null)
  const conversationReady = ref(false)
  const savingReplyPlan = computed(() => selectedConversationId.value != null && savingPlanConversations.has(selectedConversationId.value))
  const savingActorKeys = computed(() => {
    const prefix = `${selectedConversationId.value}:`
    return [...savingActorRuntimes].filter(key => key.startsWith(prefix)).map(key => key.slice(prefix.length))
  })
  const messages = ref<GroupMessage[]>([])
  const reasoning = reactive<Record<number, string>>({})
  const replyPlans = ref<ReplyPlan[]>([])
  const replyPlan = ref<ReplyPlan>(freshPlan())
  const participantIds = ref<number[]>([])
  const messageInput = ref('')
  const messageScroller = ref<HTMLElement | null>(null)
  const currentTurn = ref<CurrentTurn | null>(null)
  const actorRuntimes = ref<GroupActorRuntime[]>([])
  const modelApis = ref<ModelApi[]>([])
  const inquiryInput = ref('')
  const composerIntent = ref<TrpgComposerIntent>('action')
  const chatDrafts = useScopedChatDraft(computed(() => selectedWorldId.value != null && selectedConversationId.value != null ? `world:${selectedWorldId.value}:group:${selectedConversationId.value}` : null), { action: messageInput, inquiry: inquiryInput, intent: composerIntent })
  const combatOverview = ref<TrpgCombatParticipantOverview[]>([])
  const investigatorCards = ref<InvestigatorCardSummary[]>([])
  const replyTurnState = ref<ReplyTurnState | null>(null)
  const generationFailure = ref<GenerationFailureState | null>(null)
  const generationFailureOpen = ref(false)
  const generationFailed = ref(false)
  const latestDiceRoll = ref<DiceRollAggregate | null>(null)
  const incomingDiceRolls = ref<DiceRollAggregate[]>([])
  const hasOlderGroupMessages = ref(false)
  const diceRollCache = new Map<number, Promise<DiceRollAggregate>>()
  let catchingUpGenerationId: string | null = null
  let generationConnection: { conversationId: number; requestId: string; controller: AbortController } | null = null
  let conversationRevision = 0
  let conversationLoad: { revision: number; ready: Promise<boolean> } | null = null
  let generationOperation: ReturnType<typeof beginGenerationOperation> | null = null
  let worldRevision = 0
  const characterData = createCharacterData({ worldId: selectedWorldId, characters, templates: characterTemplates,
    sessionKey: () => `${session.id ?? ''}:${session.token ?? ''}` })
  const reloadCharacters = characterData.refresh
  const saveCharacterModel = characterData.saveModel
  function disconnectGeneration() {
    generationConnection?.controller.abort()
    generationConnection = null
    catchingUpGenerationId = null
    loading.sending = false
  }
  onUnmounted(disconnectGeneration)
  let acceptedPlanRefreshTurnId: number | null = null

  const isLoggedIn = computed(() => Boolean(session.token && session.id))
  const selectedWorld = computed(() => worlds.value.find((item) => item.id === selectedWorldId.value) || null)
  const canEditSelectedWorld = computed(() => Boolean(selectedWorld.value?.myWorld))
  const selectedConversation = computed(() => conversations.value.find((item) => item.id === selectedConversationId.value) || null)
  const planItems = computed(() => replyPlan.value.items)
  const availablePlanCharacters = computed(() => characters.value.filter((character) =>
    participantIds.value.includes(character.characterId) && !planItems.value.some((item) => item.actorId === character.characterId)))

  function characterById(id?: number) { return characters.value.find((item) => item.characterId === id) }
  function eventSpeakerType(event: GroupChatEvent): GroupMessage['speakerType'] {
    if (event.speaker?.type === 'user' || event.speaker?.type === 'kp' || event.speaker?.type === 'narrator') return event.speaker.type
    return 'character'
  }
  function eventMessageKind(event: GroupChatEvent): GroupMessage['messageKind'] {
    if (event.messageKind === 'narration' || event.messageKind === 'system_event'
      || event.messageKind === 'dice_roll' || event.messageKind === 'material') return event.messageKind
    return 'dialogue'
  }
  function generationStorageKey(conversationId: number) {
    return `galchat:generation:${conversationId}`
  }
  function storedGeneration(conversationId: number) {
    return typeof sessionStorage === 'undefined'
      ? null
      : sessionStorage.getItem(generationStorageKey(conversationId))
  }
  function rememberGeneration(conversationId: number, clientRequestId: string) {
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(generationStorageKey(conversationId), clientRequestId)
    }
  }
  function forgetGeneration(conversationId: number, clientRequestId: string) {
    if (typeof sessionStorage === 'undefined') return
    const key = generationStorageKey(conversationId)
    if (sessionStorage.getItem(key) === clientRequestId) sessionStorage.removeItem(key)
  }
  function resetWorkspace() {
    loadedWorldId.value = null
    conversationReady.value = false
    characterData.clearSession()
    worldRevision++
    conversationRevision++; disconnectGeneration()
    selectedWorldId.value = null; selectedConversationId.value = null; worlds.value = []; characters.value = []
    details.value = []; worldSave.value = null
    conversations.value = []; messages.value = []; replyPlans.value = []; replyPlan.value = freshPlan(); participantIds.value = []; currentTurn.value = null; actorRuntimes.value = []; modelApis.value = []; combatOverview.value = []; investigatorCards.value = []; replyTurnState.value = null; modules.value = []
    latestDiceRoll.value = null; incomingDiceRolls.value = []; hasOlderGroupMessages.value = false; diceRollCache.clear()
    chatDrafts.clear()
    clearChatReadingPositions()
  }

  function setReplyPlans(plans: ReplyPlan[]) {
    replyPlans.value = plans
    replyPlan.value = activeReplyPlan(plans) || freshPlan()
  }

  function setConversationParticipants(conversation: Conversation) {
    const planned = [...new Set(replyPlan.value.items
      .filter(item => item.actorType === 'character')
      .map(item => item.actorId)
      .filter((id): id is number => typeof id === 'number'))]
    if (conversation.mode === 'trpg') {
      const key = `galchat:trpg-participants:${conversation.id}`
      const remembered = decodeParticipantIds(localStorage.getItem(key))
      participantIds.value = resolveParticipantIds(conversation.characterIds, remembered, planned)
      if (conversation.characterIds !== undefined || (!remembered.length && planned.length)) {
        localStorage.setItem(key, encodeParticipantIds(participantIds.value))
      }
    } else participantIds.value = conversation.characterIds ?? planned
  }

  function refreshPlanForAcceptedTurn(conversationId: number, turnId: number) {
    if (acceptedPlanRefreshTurnId === turnId) return
    acceptedPlanRefreshTurnId = turnId
    void api.replyPlan(conversationId).then((plans) => {
      if (selectedConversationId.value !== conversationId
        || currentTurn.value?.turnId !== turnId) return
      setReplyPlans(plans)
    }).catch(() => {
      if (acceptedPlanRefreshTurnId === turnId) acceptedPlanRefreshTurnId = null
    })
  }

  function loadDiceAggregate(summaryId: number, refresh = false): Promise<DiceRollAggregate> {
    if (refresh) diceRollCache.delete(summaryId)
    const cached = diceRollCache.get(summaryId)
    if (cached) return cached
    const request = Promise.all([api.diceSummary(summaryId), api.diceResults(summaryId)])
      .then(([summary, results]) => ({
        summary,
        results,
        semanticResult: summary.totalResult,
      }))
      .catch((error) => {
        diceRollCache.delete(summaryId)
        throw error
      })
    diceRollCache.set(summaryId, request)
    return request
  }

  async function hydrateGroupMessages(source: GroupMessage[]): Promise<GroupMessage[]> {
    return Promise.all(source.map((message) => hydrateDiceMessage(message, loadDiceAggregate)))
  }

  async function loadCombatOverview(
    conversationId: number,
  ): Promise<TrpgCombatParticipantOverview[]> {
    try {
      return await api.combatOverview(conversationId)
    } catch {
      return []
    }
  }

  async function loadInvestigatorCards(
    conversationId: number,
  ): Promise<InvestigatorCardSummary[]> {
    try {
      return await api.investigatorCards(conversationId)
    } catch {
      return []
    }
  }

  async function refreshDiceRoll(summaryId: number): Promise<DiceRollAggregate> {
    const revision = conversationRevision
    const selectedId = selectedConversationId.value
    const currentMessages = messages.value.filter(message => message.diceRoll?.summary.id === summaryId)
    const conversationId = currentMessages[0]?.conversationId
    const isCurrent = () => revision === conversationRevision && selectedId === selectedConversationId.value
    const loadSavedMessages = async () => {
      const remaining = new Set(currentMessages.map(message => message.id))
      const saved = new Map<number, GroupMessage>()
      let beforeId: number | undefined
      while (conversationId != null && remaining.size && isCurrent()) {
        const page = await api.groupMessages(conversationId, beforeId)
        for (const message of page) {
          if (remaining.delete(message.id)) saved.set(message.id, message)
        }
        if (!remaining.size || page.length < 50) break
        const next = Math.min(...page.map(message => message.id))
        if (beforeId != null && next >= beforeId) break
        beforeId = next
      }
      if (isCurrent() && remaining.size) throw new Error('找不到对应的掷骰消息，请重新加载会话')
      return saved
    }
    const [aggregate, saved] = await Promise.all([
      loadDiceAggregate(summaryId, true),
      loadSavedMessages(),
    ])
    const hydrated = new Map(await Promise.all([...saved.values()].map(async message => {
      const refreshed = await hydrateDiceMessage(message, async id => {
        if (id !== summaryId) throw new Error('掷骰消息所属骰组已变更，请重新加载会话')
        return aggregate
      })
      return [message.id, refreshed] as const
    })))
    if (!isCurrent()) return aggregate
    // Only refresh existing dice cards. History reads must not replay stream events
    // or replace unrelated messages that are still being generated.
    messages.value = messages.value.map(message => {
      const refreshed = hydrated.get(message.id)
      if (!refreshed || message.diceRoll?.summary.id !== summaryId) return message
      return { ...message, diceRoll: refreshed.diceRoll, diceRoundNos: refreshed.diceRoundNos }
    })
    latestDiceRoll.value = aggregate
    if (selectedConversation.value?.mode === 'trpg') {
      const [overview, cards] = await Promise.all([
        loadCombatOverview(selectedConversation.value.id),
        loadInvestigatorCards(selectedConversation.value.id),
      ])
      if (isCurrent()) {
        combatOverview.value = overview
        investigatorCards.value = cards
      }
    }
    return aggregate
  }
  function logout() {
    sessionRevision++
    clearSession(); Object.assign(session, currentSession()); userInfo.value = null; templates.value = []
    loading.boot = false; loading.worlds = false
    resetWorkspace()
  }

  async function boot() {
    if (!isLoggedIn.value) return
    const current = captureSession()
    loading.boot = true
    try { await Promise.all([loadWorlds(), loadTemplates(), loadModules(), loadUserInfo()]) } catch (error) { if (current()) notify('无法载入工作区', errorMessage(error), 'danger') }
    finally { if (current()) loading.boot = false }
  }
  async function authenticate(payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }) {
    const result = payload.mode === 'login' ? await api.login(payload.email, payload.password) : await api.register(payload.email, payload.password, payload.code || '')
    sessionRevision++
    saveSession(result); Object.assign(session, currentSession())
    const current = captureSession()
    await boot()
    if (!current()) return false
    notify('欢迎回来', session.username, 'success')
    return true
  }
  async function loadUserInfo() {
    const sessionCurrent = captureSession()
    const revision = ++readRevisions.userInfo
    const current = () => sessionCurrent() && revision === readRevisions.userInfo
    const info = await api.userInfo()
    if (!current()) return false
    userInfo.value = info
    if (info) {
      session.username = info.username
      localStorage.setItem('galchat.username', info.username)
    }
    return true
  }
  async function saveUserInfo(payload: Partial<UserInfo>) {
    const current = captureSession()
    await api.updateUserInfo(payload)
    if (!current() || !await loadUserInfo() || !current()) return false
    notify('账号资料已保存', '', 'success')
    return true
  }
  async function changePassword(payload: { email: string; newPassword: string; verificationCode: string }) { await api.updatePassword(payload); notify('密码已更新', '', 'success') }

  async function loadModelApis() {
    const current = captureSession()
    const revision = ++readRevisions.models
    const rows = await api.modelApis()
    if (current() && revision === readRevisions.models) modelApis.value = rows
  }
  async function loadTemplates() {
    const sessionCurrent = captureSession()
    const revision = ++readRevisions.templates
    const current = () => sessionCurrent() && revision === readRevisions.templates
    const rows = await api.worldTemplates()
    if (current()) templates.value = rows
  }
  async function loadModules() {
    const sessionCurrent = captureSession()
    const revision = ++readRevisions.modules
    const current = () => sessionCurrent() && revision === readRevisions.modules
    const rows = await api.cocModules()
    if (current()) modules.value = rows
  }
  async function loadWorlds() {
    if (!session.id) return
    const sessionCurrent = captureSession()
    const revision = ++readRevisions.worlds
    const current = () => sessionCurrent() && revision === readRevisions.worlds
    loading.worlds = true
    try {
      const rows = await api.userWorlds(session.id)
      if (current()) worlds.value = rows
    } finally { if (current()) loading.worlds = false }
  }
  async function selectWorld(id: number) {
    loadedWorldId.value = null
    worldSave.value = null; conversations.value = []; details.value = []
    conversationReady.value = false
    characterData.reset()
    const revision = ++worldRevision
    const chatRevision = ++conversationRevision
    const isCurrentWorld = () => revision === worldRevision && selectedWorldId.value === id
    const isCurrentLoad = () => isCurrentWorld() && conversationRevision === chatRevision
    disconnectGeneration()
    selectedWorldId.value = id; selectedConversationId.value = null; messages.value = []; loading.workspace = true
    const characterScope = characterData.capture()!
    try {
      const [world, , conversationResult, saveResult] = await Promise.all([api.userWorld(id), reloadCharacters(characterScope), api.conversations(id), api.worldSave(id)])
      if (!isCurrentLoad()) return false
      worlds.value = worlds.value.map((item) => item.id === id ? { ...item, ...world } : item)
      conversations.value = conversationResult; worldSave.value = saveResult
      if (world?.worldId) {
        const [, detailResult] = await Promise.all([characterData.refreshTemplates(characterScope, world.worldId), world.myWorld ? api.worldDetails(world.worldId) : Promise.resolve([])])
        if (!isCurrentLoad()) return false
        details.value = detailResult
      }
      loadedWorldId.value = id
      const active = conversations.value.find((item) => item.status === 'active') || conversations.value[0]
      if (active) {
        const loadingConversation = selectConversation(active.id)
        const selectedRevision = conversationRevision
        await loadingConversation
        return isCurrentWorld() && selectedRevision === conversationRevision
      }
      return true
    } catch (error) {
      if (isCurrentLoad()) notify('世界加载失败', errorMessage(error), 'danger')
      return false
    }
    finally { if (isCurrentWorld()) loading.workspace = false }
  }
  // Mutations retain their original target and may update only the page that started them.
  function captureWorldMutation() {
    const scope = characterData.capture()
    if (!scope) return null
    const revision = conversationRevision
    const conversationId = selectedConversationId.value
    return { ...scope, templateWorldId: selectedWorld.value?.worldId,
      current: () => scope.current() && conversationRevision === revision
        && selectedConversationId.value === conversationId }
  }
  async function createWorld(payload: Partial<UserWorld> & { worldId: number }) {
    const current = captureSession()
    await api.createWorld(payload)
    if (current()) await refreshAfterSave('世界已创建', loadWorlds, current)
  }
  async function updateWorld(payload: Partial<UserWorld>) {
    const scope = captureWorldMutation()
    if (!scope) return false
    await api.updateWorld(scope.worldId, payload)
    if (!scope.current()) return false
    const updated = await api.userWorld(scope.worldId)
    if (!scope.current()) return false
    worlds.value = worlds.value.map((item) => item.id === updated.id ? { ...item, ...updated } : item)
    notify('世界设置已保存', '', 'success')
    return true
  }
  async function removeWorld() {
    const scope = captureWorldMutation()
    if (!scope) return false
    await api.deleteWorld(scope.worldId)
    if (!scope.sameSession()) return false
    // The deletion is real even when its page is no longer selected.
    worlds.value = worlds.value.filter(item => item.id !== scope.worldId)
    await Promise.all([loadWorlds(), loadModules()])
    if (!scope.sameSession()) return false
    const current = scope.current()
    if (current) {
      const remainingWorlds = worlds.value, remainingModules = modules.value
      resetWorkspace()
      worlds.value = remainingWorlds
      modules.value = remainingModules
    }
    if (current) notify('当前世界已删除', '', 'success')
    return current
  }
  async function createTemplate(payload: WorldTemplate) {
    const current = captureSession()
    await api.createWorldTemplate(payload)
    if (current()) await refreshAfterSave('世界模板已创建', loadTemplates, current)
  }
  async function loadEditableWorldTemplate() {
    const scope = characterData.capture()
    if (!scope || !canEditSelectedWorld.value) throw new Error('只有世界模板的作者可以修改模板')
    const template = await api.myWorldTemplate(scope.worldId)
    return scope.current() ? template : undefined
  }
  async function updateTemplate(payload: WorldTemplate, expectedWorldId = selectedWorldId.value) {
    if (expectedWorldId !== selectedWorldId.value) return false
    const scope = captureWorldMutation()
    if (!scope || !canEditSelectedWorld.value) throw new Error('只有世界模板的作者可以修改模板')
    await api.updateWorldTemplate(scope.worldId, payload)
    if (!scope.current()) return false
    await loadTemplates()
    if (!scope.current()) return false
    if (!await selectWorld(scope.worldId)) return false
    notify('世界模板已保存', '', 'success')
    return true
  }
  async function addDetail(payload: WorldDetail) {
    const scope = captureWorldMutation()
    if (!scope?.templateWorldId) return false
    await api.addWorldDetail(scope.templateWorldId, payload)
    if (!scope.current()) return false
    await refreshAfterSave('世界设定已添加', async () => {
      const updated = await api.worldDetails(scope.templateWorldId!)
      if (scope.current()) details.value = updated
    }, scope.current)
    return scope.current()
  }
  async function removeDetail(id: number) {
    const scope = captureWorldMutation()
    if (!scope?.templateWorldId) return false
    await api.deleteWorldDetail(scope.templateWorldId, id)
    if (!scope.current()) return false
    const updated = await api.worldDetails(scope.templateWorldId)
    if (!scope.current()) return false
    details.value = updated
    return true
  }
  async function saveSnapshot(remark: string) {
    if (!worldReady.value || loading.workspace) return false
    const scope = captureWorldMutation()
    if (!scope) return false
    const saved = await api.saveWorld(scope.worldId, remark)
    if (!scope.current()) return false
    worldSave.value = saved
    notify('存档已保存', '', 'success')
    return true
  }
  async function loadSnapshot() {
    if (!worldReady.value || loading.workspace || worldSave.value?.userWorldId !== selectedWorldId.value) return false
    const scope = captureWorldMutation()
    if (!scope) return false
    await api.loadWorld(scope.worldId)
    if (!scope.current() || !await selectWorld(scope.worldId)) return false
    notify('已回到存档时刻', '', 'success')
    return true
  }

  async function addCharacter(id: number, prompt = '') {
    const scope = characterData.capture()
    if (!scope) return
    await characterData.change(scope, id, async () => {
      await api.addCharacter(scope.worldId, id)
      if (scope.sameSession()) await api.updatePrompt(scope.worldId, id, prompt.trim())
    })
  }
  async function removeCharacter(id: number) {
    const scope = characterData.capture()
    if (scope) await characterData.change(scope, id, () => api.deleteCharacter(scope.worldId, id))
  }
  async function updateCharacterFavor(id: number, favor: number) {
    const scope = characterData.capture()
    if (!scope || !canEditSelectedWorld.value) throw new Error('只有世界模板的作者可以手动调整好感度')
    if (!Number.isInteger(favor) || favor < 0 || favor > 100) throw new Error('好感度必须是 0–100 之间的整数')
    await characterData.change(scope, id, () => api.updateFavor(scope.worldId, id, favor))
    if (scope.current()) notify('好感度已保存', '', 'success')
  }
  async function updateCharacterSettings(worldId: number, id: number, settings: { userInfoPrompt: string; modelApiId?: number }) {
    const scope = characterData.capture(worldId)
    if (!scope) return false
    const modelChanged = characters.value.find(item => item.userWorldId === worldId && item.characterId === id)?.modelApiId !== settings.modelApiId
    await characterData.change(scope, id, async () => {
      await api.updatePrompt(worldId, id, settings.userInfoPrompt.trim())
      if (modelChanged && scope.sameSession()) await api.updateCharacterModel(worldId, id, settings.modelApiId)
    })
    return scope.current()
  }
  async function createCharacterTemplate(payload: CharacterTemplate) {
    const scope = characterData.capture(); const templateWorldId = selectedWorld.value?.worldId
    if (!scope || !templateWorldId) return
    await api.createCharacterTemplate(templateWorldId, payload)
    if (scope.current()) await refreshAfterSave('角色模板已创建', () => characterData.refreshTemplates(scope, templateWorldId), scope.current)
  }
  async function loadEditableCharacterTemplate(id: number) {
    const scope = characterData.capture()
    if (!scope || !canEditSelectedWorld.value) throw new Error('只有世界模板的作者可以修改角色模板')
    const template = await api.myCharacterTemplate(scope.worldId, id)
    return scope.current() ? template : undefined
  }
  async function updateCharacterTemplate(id: number, payload: CharacterTemplate) {
    const scope = characterData.capture(); const templateWorldId = selectedWorld.value?.worldId
    if (!scope || !templateWorldId || !canEditSelectedWorld.value) throw new Error('只有世界模板的作者可以修改角色模板')
    await api.updateCharacterTemplate(scope.worldId, id, payload)
    await Promise.all([characterData.refreshTemplates(scope, templateWorldId), reloadCharacters(scope)])
    if (scope.current()) notify('角色模板已保存', '', 'success')
  }

  async function createConversation(payload: { mode: 'chat' | 'trpg'; title: string; moduleId?: number; characterIds: number[] }) {
    const scope = captureWorldMutation()
    if (!scope) return
    const characterIds = [...payload.characterIds]
    const created = await api.createConversation({ ...payload, characterIds, userWorldId: scope.worldId })
    if (!scope.sameSession()) return
    if (payload.mode === 'trpg') {
      localStorage.setItem(`galchat:trpg-participants:${created.id}`, encodeParticipantIds(characterIds))
    }
    if (!scope.current()) return
    conversations.value = [...conversations.value.filter(item => item.id !== created.id), created]
    let refreshError: unknown
    try {
      const updated = await api.conversations(scope.worldId)
      if (!scope.current()) return
      conversations.value = updated.some(item => item.id === created.id) ? updated : [...updated, created]
    } catch (error) { refreshError = error }
    if (!scope.current()) return
    participantIds.value = characterIds
    const selecting = selectConversation(created.id)
    const revision = conversationRevision
    await selecting
    if (!scope.sameSession() || selectedWorldId.value !== scope.worldId
      || selectedConversationId.value !== created.id || conversationRevision !== revision) return
    notify(payload.mode === 'trpg' ? 'CoC 跑团已建立' : '普通群聊已建立', refreshError ? `已创建，列表刷新失败，请重新打开世界刷新。${errorMessage(refreshError)}` : created.title, refreshError ? 'neutral' : 'success')
    return created
  }
  async function selectConversation(id: number) {
    conversationReady.value = false
    setReplyPlans([])
    participantIds.value = []
    const revision = ++conversationRevision
    let finishLoad!: (ready: boolean) => void
    conversationLoad = { revision, ready: new Promise<boolean>(resolve => { finishLoad = resolve }) }
    let loaded = false
    disconnectGeneration()
    const worldId = selectedWorldId.value
    selectedConversationId.value = id; loading.chat = true; messages.value = []; hasOlderGroupMessages.value = false; currentTurn.value = null; actorRuntimes.value = []; modelApis.value = []; combatOverview.value = []; investigatorCards.value = []; replyTurnState.value = null; latestDiceRoll.value = null; incomingDiceRolls.value = []; diceRollCache.clear(); Object.keys(reasoning).forEach((key) => delete reasoning[Number(key)])
    try {
      const [conversationDetail, history, plans, turn, runtimes, , overview, cards] = await Promise.all([
        api.conversation(id), api.groupMessages(id), api.replyPlan(id), api.currentTurn(id), api.actorRuntimes(id), loadModelApis(), loadCombatOverview(id), loadInvestigatorCards(id),
      ])
      if (revision !== conversationRevision || selectedConversationId.value !== id || selectedWorldId.value !== worldId) return
      const hydrated = await hydrateGroupMessages(history)
      if (revision !== conversationRevision || selectedConversationId.value !== id || selectedWorldId.value !== worldId) return
      conversations.value = conversations.value.map((item) => item.id === id ? { ...item, ...conversationDetail } : item)
      messages.value = hydrated.sort((a, b) => a.sequenceNo - b.sequenceNo)
      hasOlderGroupMessages.value = history.length === 50
      setReplyPlans(plans)
      currentTurn.value = turn
      actorRuntimes.value = runtimes
      combatOverview.value = overview
      investigatorCards.value = cards
      setConversationParticipants(conversationDetail)
      await scrollToBottom(true)
      loaded = revision === conversationRevision && selectedConversationId.value === id && selectedWorldId.value === worldId
      if (loaded) conversationReady.value = true
    } catch (error) { notify('会话加载失败', errorMessage(error), 'danger') }
    finally {
      if (revision === conversationRevision && selectedConversationId.value === id && selectedWorldId.value === worldId) {
        loading.chat = false
        if (loaded) conversationLoad = null
      }
      finishLoad(loaded)
    }
    if (revision === conversationRevision && selectedConversationId.value === id && storedGeneration(id)) {
      void resumeGeneration(id)
    }
    return loaded
  }
  async function closeConversation() {
    const scope = captureWorldMutation()
    const conversationId = selectedConversationId.value
    if (!scope || !conversationId) return false
    await api.closeConversation(conversationId)
    if (!scope.current()) return false
    const updated = await api.conversations(scope.worldId)
    if (!scope.current()) return false
    conversations.value = updated
    notify('会话已关闭', '', 'success')
    return true
  }
  async function deleteConversation() {
    if (!selectedConversationId.value || !selectedWorldId.value) return false
    const conversationId = selectedConversationId.value
    const worldId = selectedWorldId.value
    const revision = conversationRevision
    await api.deleteConversation(conversationId)
    if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem(generationStorageKey(conversationId))
    localStorage.removeItem(`galchat:trpg-participants:${conversationId}`)
    if (selectedWorldId.value === worldId) {
      conversations.value = conversations.value.filter(item => item.id !== conversationId)
    }
    notify('会话已永久删除', '', 'success')
    if (selectedWorldId.value !== worldId || selectedConversationId.value !== conversationId
      || conversationRevision !== revision) {
      chatDrafts.forget(`world:${worldId}:group:${conversationId}`)
      return false
    }
    conversationRevision++
    disconnectGeneration()
    conversationReady.value = false
    selectedConversationId.value = null
    chatDrafts.forget(`world:${worldId}:group:${conversationId}`)
    loading.chat = false
    messages.value = []
    Object.keys(reasoning).forEach((key) => delete reasoning[Number(key)])
    replyPlans.value = []
    replyPlan.value = freshPlan()
    participantIds.value = []
    messageInput.value = ''
    inquiryInput.value = ''
    composerIntent.value = 'action'
    currentTurn.value = null
    actorRuntimes.value = []
    modelApis.value = []
    combatOverview.value = []
    investigatorCards.value = []
    replyTurnState.value = null
    generationFailure.value = null
    generationFailureOpen.value = false
    latestDiceRoll.value = null
    incomingDiceRolls.value = []
    hasOlderGroupMessages.value = false
    diceRollCache.clear()
    catchingUpGenerationId = null
    acceptedPlanRefreshTurnId = null
    return true
  }
  async function loadOlderGroupMessages() {
    if (!selectedConversationId.value || !hasOlderGroupMessages.value || loading.chat) return
    const conversationId = selectedConversationId.value
    const revision = conversationRevision
    const worldId = selectedWorldId.value
    const isCurrent = () => revision === conversationRevision
      && conversationId === selectedConversationId.value && worldId === selectedWorldId.value
    const beforeId = messages.value.filter((item) => item.id > 0).reduce((minimum, item) => Math.min(minimum, item.id), Number.POSITIVE_INFINITY)
    if (!Number.isFinite(beforeId)) return
    const viewport = messageScroller.value
    const previousHeight = viewport?.scrollHeight ?? 0
    loading.chat = true
    try {
      const older = await api.groupMessages(conversationId, beforeId, 50)
      if (!isCurrent()) return
      const previousTop = viewport?.scrollTop ?? 0
      const hydrated = await hydrateGroupMessages(older)
      if (!isCurrent()) return
      // Replay may insert messages while dice details are loading. Keep those live versions.
      const known = new Set(messages.value.map((item) => item.id))
      messages.value = [...hydrated.filter((item) => !known.has(item.id)), ...messages.value].sort((a, b) => a.sequenceNo - b.sequenceNo)
      hasOlderGroupMessages.value = older.length === 50
      await nextTick()
      if (isCurrent() && viewport && messageScroller.value === viewport) viewport.scrollTop = previousTop + viewport.scrollHeight - previousHeight
    } finally { if (isCurrent()) loading.chat = false }
  }
  async function withdrawGroupTurn(expectedTurnId?: number) {
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'chat' || loading.sending || loading.withdrawing) return
    const target = expectedTurnId ?? currentTurn.value?.turnId
      ?? [...messages.value].reverse().find(message => message.id > 0 && message.turnId != null)?.turnId
    if (target == null || target <= 0) throw new Error('暂无可撤回的轮次，请重新加载会话记录')
    const conversationId = conversation.id
    const userId = session.id
    const revision = conversationRevision
    const characterScope = characterData.capture(conversation.userWorldId)
    const isCurrent = () => selectedConversationId.value === conversationId && conversationRevision === revision
      && session.id === userId
    withdrawingConversations.add(conversationId)
    try {
      const withdrawn = await api.withdrawGroupTurn(conversationId, target)
      if (session.id === userId && withdrawn?.speakerType === 'user') {
        chatDrafts.restoreIfEmpty(`world:${conversation.userWorldId}:group:${conversationId}`, { action: withdrawn.content })
      }
      const refreshes = await Promise.allSettled([
        (async () => {
          const [history, turn] = await Promise.all([api.groupMessages(conversationId), api.currentTurn(conversationId)])
          const hydrated = await hydrateGroupMessages(history)
          if (!isCurrent()) return
          messages.value = hydrated.sort((a, b) => a.sequenceNo - b.sequenceNo)
          hasOlderGroupMessages.value = history.length === 50
          replyTurnState.value = null
          currentTurn.value = turn
          generationFailureOpen.value = false
          generationFailure.value = null
        })(),
        (async () => {
          const detail = await api.conversation(conversationId)
          if (!characterScope?.current()) return
          conversations.value = conversations.value.map(item => item.id === conversationId ? { ...item, ...detail } : item)
        })(),
        characterScope ? reloadCharacters(characterScope) : Promise.resolve(),
      ])
      if (!isCurrent()) return
      const failure = refreshes.find(result => result.status === 'rejected')
      if (failure?.status === 'rejected') notify('群聊已撤回，数据刷新失败', errorMessage(failure.reason), 'danger')
      else notify('已撤回最近一轮群聊', '', 'success')
    } finally { withdrawingConversations.delete(conversationId) }
  }
  function beginSettingsSave(conversationId: number) {
    let batch = settingsSaves.get(conversationId)
    if (!batch) {
      let finish!: (success: boolean) => void
      const ready = new Promise<boolean>(resolve => { finish = resolve })
      batch = { pending: 0, succeeded: true, ready, finish }
      settingsSaves.set(conversationId, batch)
    }
    const saving = batch
    saving.pending++
    return (success: boolean) => {
      saving.succeeded &&= success
      if (--saving.pending === 0) {
        settingsSaves.delete(conversationId)
        saving.finish(saving.succeeded)
      }
    }
  }
  async function savePlan() {
    if (!conversationReady.value || loading.chat || loading.sending || loading.withdrawing || savingReplyPlan.value) return false
    const conversationId = selectedConversationId.value
    if (!conversationId) return
    const revision = conversationRevision
    const worldId = selectedWorldId.value
    const items = replyPlan.value.items.map((item, index) => ({ ...item, order: index + 1 }))
    if (!items.length) throw new Error('回复顺序至少保留一位角色')
    savingPlanConversations.add(conversationId)
    const finishSave = beginSettingsSave(conversationId)
    let succeeded = false
    try {
      const saved = await api.saveReplyPlan(conversationId, {
        source: 'USER', executionKey: 'default', displayName: '群聊', items,
      })
      succeeded = true
      if (revision !== conversationRevision || selectedConversationId.value !== conversationId || selectedWorldId.value !== worldId) return
      setReplyPlans([saved])
      notify('回复顺序已保存', '', 'success')
    } finally {
      savingPlanConversations.delete(conversationId)
      finishSave(succeeded)
    }
  }
  function movePlanItem(from: number, to: number) {
    if (savingReplyPlan.value) return
    const items = replyPlan.value.items; if (from === to || to < 0 || to >= items.length) return
    const [moved] = items.splice(from, 1); if (moved) items.splice(to, 0, moved)
  }
  function deletePlanItem(index: number) { if (!savingReplyPlan.value) replyPlan.value.items.splice(index, 1) }
  function addPlanItem(actorId: number) {
    if (savingReplyPlan.value) return
    if (!replyPlan.value.items.some((item) => item.actorId === actorId)) replyPlan.value.items.push({ order: replyPlan.value.items.length + 1, actorType: 'character', actorId })
  }

  function findEventMessage(event: GroupChatEvent): GroupMessage | undefined {
    if (event.messageId != null) {
      return messages.value.find((item) => item.id === event.messageId)
    }
    return messages.value.find((item) => item.replyStepId === event.replyStepId)
  }

  function findReplyStartMessage(event: GroupChatEvent): GroupMessage | undefined {
    const exact = event.messageId == null
      ? undefined
      : messages.value.find((item) => item.id === event.messageId)
    return exact || messages.value.find((item) => (
      item.replyStepId === event.replyStepId && item.status === 'failed'
    ))
  }

  function applyEvent(event: GroupChatEvent, optimisticMessageId?: number) {
    if (event.eventType === 'stream.caught_up') {
      catchingUpGenerationId = null
      void scrollToBottom()
      return
    }
    if (event.eventType === 'turn.accepted' && event.messageId != null && optimisticMessageId != null) {
      // The confirmation belongs to this submission, so reconcile by its local ID,
      // never by content or actor (both can legitimately repeat in later steps).
      const optimistic = messages.value.find(message => message.id === optimisticMessageId)
      if (optimistic) Object.assign(optimistic, {
        id: event.messageId,
        turnId: event.turnId,
        replyStepId: event.replyStepId,
        sequenceNo: event.sequence ?? optimistic.sequenceNo,
      })
    }
    const step = event.replyStepId
    if (event.eventType === 'game_time.changed' && selectedConversationId.value) {
      conversations.value = conversations.value.map((conversation) => conversation.id === selectedConversationId.value
        ? applyGameTimeEvent(conversation, event)
        : conversation)
    }
    currentTurn.value = applyCurrentTurnEvent(
      currentTurn.value, event, replyPlan.value,
    )
    if (selectedConversation.value?.mode === 'trpg') {
      if (event.eventType === 'turn.accepted' && event.turnId != null
        && selectedConversationId.value != null) {
        refreshPlanForAcceptedTurn(selectedConversationId.value, event.turnId)
      }
    }
    if (selectedConversation.value?.mode === 'chat') replyTurnState.value = updateReplyTurn(replyTurnState.value, event)
    if (event.eventType === 'dice_roll.created' && event.diceRoll) {
      const aggregate = {
        ...event.diceRoll,
        summary: { ...event.diceRoll.summary, toolName: event.toolName },
      }
      latestDiceRoll.value = aggregate
      if (!catchingUpGenerationId) {
        incomingDiceRolls.value.push(aggregate)
      }
      // Stream events contain only the newly created rounds. History needs the
      // full aggregate, including earlier rounds under the same summary ID.
      diceRollCache.delete(aggregate.summary.id)
      const message = findEventMessage(event)
      if (message) Object.assign(message, {
        messageKind: 'dice_roll',
        content: '',
        diceRoll: aggregate,
        diceRoundNos: [...new Set(aggregate.results.map((detail) => detail.roundNo || 1))],
      })
    }
    if (event.eventType === 'material.created' && event.content) {
      const materialMessage = event.messageId == null
        ? undefined
        : messages.value.find((item) => item.id === event.messageId)
      const value: GroupMessage = {
        id: event.messageId || tempMessageId--,
        conversationId: selectedConversationId.value!,
        turnId: event.turnId,
        replyStepId: step,
        speakerType: eventSpeakerType(event),
        speakerId: event.speaker?.id,
        speakerName: event.speaker?.name,
        messageKind: 'material',
        content: event.content,
        sequenceNo: event.sequence || Date.now(),
        status: 'completed',
      }
      if (materialMessage) Object.assign(materialMessage, value)
      else messages.value.push(value)
    }
    if (event.eventType === 'reply.started' && step) {
      const character = characterById(event.speaker?.id)
      const existing = findReplyStartMessage(event)
      const messageId = event.messageId || tempMessageId--
      if (existing) {
        const previousMessageId = existing.id
        Object.assign(existing, { id: messageId, turnId: event.turnId,
          speakerType: eventSpeakerType(event), speakerId: event.speaker?.id, speakerName: event.speaker?.name || character?.characterName,
          messageKind: eventMessageKind(event), content: '', decisionContent: '', sequenceNo: event.sequence || Date.now(), status: 'streaming' })
        if (previousMessageId !== messageId) delete reasoning[previousMessageId]
      } else {
        messages.value.push({ id: messageId, conversationId: selectedConversationId.value!, turnId: event.turnId, replyStepId: step,
          speakerType: eventSpeakerType(event), speakerId: event.speaker?.id, speakerName: event.speaker?.name || character?.characterName,
          messageKind: eventMessageKind(event), content: '', decisionContent: '', sequenceNo: event.sequence || Date.now(), status: 'streaming' })
      }
      reasoning[messageId] = ''
    } else if (event.eventType === 'reasoning.delta' && step) {
      const message = findEventMessage(event)
      if (message) reasoning[message.id] = (reasoning[message.id] || '') + (event.delta || '')
    } else if (event.eventType === 'decision.delta' && step) {
      const message = findEventMessage(event)
      if (message) message.decisionContent = (message.decisionContent || '') + (event.delta || '')
    } else if (event.eventType === 'decision.completed' && step) {
      const message = findEventMessage(event)
      if (message) message.decisionContent = event.content ?? message.decisionContent
    } else if (event.eventType === 'message.delta' && step) {
      const message = findEventMessage(event); if (message) message.content += event.delta || ''
    } else if (event.eventType === 'message.completed' && step) {
      let message = findEventMessage(event)
      if (!message) {
        message = { id: event.messageId || tempMessageId--, conversationId: selectedConversationId.value!, turnId: event.turnId, replyStepId: step,
          speakerType: eventSpeakerType(event), speakerId: event.speaker?.id, speakerName: event.speaker?.name,
          messageKind: eventMessageKind(event), content: '', sequenceNo: event.sequence || Date.now(), status: 'completed' }
        messages.value.push(message)
      }
      if (message) { if (event.messageId) message.id = event.messageId; message.content = event.content ?? message.content; message.status = 'completed' }
    } else if ((event.eventType === 'reply.failed'
      || event.eventType === 'generation.failed') && step) {
      const message = findEventMessage(event)
      if (message) message.status = 'failed'
    } else if (event.eventType === 'reply.failed' && event.error) {
      notify('本轮回复中断', event.error, 'danger')
    }
    if (event.eventType === 'generation.failed'
      && event.errorDetail && !catchingUpGenerationId
      && selectedConversationId.value != null) {
      generationFailure.value = {
        conversationId: selectedConversationId.value,
        turnId: event.turnId,
        replyStepId: event.replyStepId,
        messageId: event.messageId,
        message: event.error || event.errorDetail.message || '生成失败',
        detail: event.errorDetail,
      }
      generationFailureOpen.value = true
    }
    if (!catchingUpGenerationId) void scrollToBottom()
  }
  async function consumeGeneration(
    conversationId: number,
    clientRequestId: string,
    connect: (onEvent: (event: GroupChatEvent) => void, signal: AbortSignal) => Promise<void>,
    catchingUp = false,
    optimisticMessageId?: number,
  ) {
    if (selectedConversationId.value !== conversationId) throw new DOMException('会话已切换', 'AbortError')
    const conversation = selectedConversation.value
    const characterScope = conversation?.mode === 'chat' ? characterData.capture(conversation.userWorldId) : null
    const revision = conversationRevision
    disconnectGeneration()
    const connection = { conversationId, requestId: clientRequestId, controller: new AbortController() }
    generationConnection = connection
    loading.sending = true
    generationFailed.value = false
    if (!catchingUp) {
      generationFailure.value = null
      generationFailureOpen.value = false
    }
    rememberGeneration(conversationId, clientRequestId)
    if (catchingUp) catchingUpGenerationId = clientRequestId
    let failed = false
    let terminal = false
    let completed = false
    let accepted = false
    let resumed = catchingUp
    let rejectedBeforeAcceptance = false
    try {
      await followGeneration<GroupChatEvent>({
        signal: connection.controller.signal,
        initial: catchingUp ? undefined : connect,
        resume: (receive, after, signal) => streamGroupGeneration.resume(conversationId, clientRequestId, receive, after, signal),
        sequence: event => event.eventSequence,
        terminal: event => ['generation.failed', 'generation.completed', 'turn.completed', 'turn.waiting_input', 'turn.paused'].includes(event.eventType),
        onResume: () => { catchingUpGenerationId = clientRequestId; resumed = true },
        receive: (event) => {
          // Failure traces carry the accepted turn ID even when a model fails.
          accepted ||= event.eventType === 'turn.accepted' || event.turnId != null
          if (event.eventType === 'generation.failed') {
            failed = true
            if (selectedConversationId.value === conversationId) generationFailed.value = true
            // A reconnect may only return a completion marker without turn metadata.
            // Its absence is evidence of rejection only on the original stream.
            rejectedBeforeAcceptance ||= !accepted && !resumed
          }
          if (event.eventType === 'generation.completed') completed = true
          if (['generation.failed', 'generation.completed', 'turn.completed', 'turn.waiting_input', 'turn.paused'].includes(event.eventType)) terminal = true
          if (selectedConversationId.value === conversationId) applyEvent(event, optimisticMessageId)
        },
      })
      if (terminal) forgetGeneration(conversationId, clientRequestId)
      return { failed, terminal, completed, rejectedBeforeAcceptance: rejectedBeforeAcceptance && !accepted }
    } catch (error) {
      if (!isGenerationAbort(error) && selectedConversationId.value === conversationId && conversationRevision === revision) {
        generationFailed.value = true
      }
      if (error instanceof GenerationStartRejected) forgetGeneration(conversationId, clientRequestId)
      throw error
    } finally {
      if (generationConnection === connection) {
        generationConnection = null
        catchingUpGenerationId = null
      }
      // Completed steps can update favor and user notes even if a later reply failed.
      // Keep this independent of history synchronization and send error recovery.
      if (terminal && characterScope?.current()) {
        try { await reloadCharacters(characterScope) }
        catch (error) {
          if (characterScope.current() && selectedConversationId.value === conversationId && conversationRevision === revision) {
            notify('角色资料刷新失败', errorMessage(error), 'danger')
          }
        }
      }
    }
  }
  function beginGenerationOperation() {
    let finish!: (ready: boolean) => void
    const operation = {
      revision: conversationRevision,
      syncing: false,
      synced: false,
      ready: new Promise<boolean>(resolve => { finish = resolve }),
      finish: (ready: boolean) => finish(ready),
    }
    generationOperation = operation
    loading.sending = true
    return operation
  }
  async function finishGenerationOperation(operation: ReturnType<typeof beginGenerationOperation>) {
    // The stream may be closed while history is still loading. Only its owning
    // operation can release the send lock, including after leaving and reopening.
    const current = generationOperation === operation && operation.revision === conversationRevision
    if (generationOperation === operation) generationOperation = null
    if (current) loading.sending = false
    operation.finish(current && operation.synced)
    if (current) await scrollToBottom()
  }
  function pendingConversationHistory(revision: number) {
    // Replay recovery supersedes a failed initial load while its history is syncing.
    if (generationOperation?.revision === revision && generationOperation.syncing) return generationOperation.ready
    if (conversationLoad?.revision === revision) return conversationLoad.ready
    return null
  }
  function pendingGenerationReadiness(revision: number): Promise<boolean> | null {
    const conversationId = selectedConversationId.value
    const history = pendingConversationHistory(revision)
    const settings = conversationId == null ? undefined : settingsSaves.get(conversationId)?.ready
    if (!history && !settings) return null
    return (async () => {
      let pendingSettings = settings
      let pendingHistory = history
      do {
        const [historyReady, settingsReady] = await Promise.all([pendingHistory ?? true, pendingSettings ?? true])
        if (conversationRevision !== revision || selectedConversationId.value !== conversationId) return false
        if (!settingsReady) {
          generationFailed.value = true
          notify('已取消本次生成', '设置保存失败，请保存成功后重试。', 'danger')
          return false
        }
        if (!historyReady) return false
        // Include saves started while this submission was waiting.
        pendingSettings = conversationId == null ? undefined : settingsSaves.get(conversationId)?.ready
        pendingHistory = pendingConversationHistory(revision)
      } while (pendingSettings || pendingHistory)
      return true
    })()
  }
  async function resumeGeneration(conversationId: number) {
    const clientRequestId = storedGeneration(conversationId)
    if (!clientRequestId) return
    const operation = beginGenerationOperation()
    try {
      const result = await consumeGeneration(
        conversationId,
        clientRequestId,
        (onEvent, signal) => streamGroupGeneration.resume(
          conversationId, clientRequestId, onEvent, 0, signal),
        true,
      )
      const conversation = selectedConversation.value
      if (conversation?.id === conversationId
        && (conversation.mode === 'trpg' || result.completed || result.failed)) {
        await syncConversationState(conversation, operation)
      }
    } catch (error) {
      if (isGenerationAbort(error)) return
      // The replay cache is intentionally best-effort; persisted history
      // loaded by selectConversation remains the fallback after expiry/restart.
      const conversation = selectedConversation.value
      if (conversation?.id === conversationId
        && conversation.mode === 'trpg') {
        await syncConversationState(conversation, operation).catch(() => undefined)
      }
    } finally {
      await finishGenerationOperation(operation)
    }
  }
  async function syncConversationState(conversation: Conversation, operation: ReturnType<typeof beginGenerationOperation>) {
    const revision = operation.revision
    if (revision !== conversationRevision || selectedConversationId.value !== conversation.id) return
    operation.syncing = true
    operation.synced = false
    const worldId = selectedWorldId.value
    const recoveringInitialLoad = conversationLoad?.revision === revision
    const [detail, history, plans, turn, overview, cards, runtimes] = await Promise.all([
      api.conversation(conversation.id), api.groupMessages(conversation.id), api.replyPlan(conversation.id), api.currentTurn(conversation.id),
      conversation.mode === 'trpg' ? loadCombatOverview(conversation.id) : [],
      conversation.mode === 'trpg' ? loadInvestigatorCards(conversation.id) : [],
      recoveringInitialLoad ? api.actorRuntimes(conversation.id) : [],
      recoveringInitialLoad ? loadModelApis() : undefined,
    ])
    if (revision !== conversationRevision || selectedConversationId.value !== conversation.id || selectedWorldId.value !== worldId) return
    const hydrated = await hydrateGroupMessages(history)
    if (revision !== conversationRevision || selectedConversationId.value !== conversation.id || selectedWorldId.value !== worldId) return
    conversations.value = conversations.value.map((item) => item.id === conversation.id ? { ...item, ...detail } : item)
    // Replace the authoritative latest page, retaining only persisted older pages.
    // Read after hydration so concurrent pagination is preserved as well.
    const boundary = Math.min(...history.map(item => item.id))
    const older = history.length === 50 ? messages.value.filter(item => item.id > 0 && item.id < boundary) : []
    messages.value = [...older, ...hydrated].sort((a, b) => a.sequenceNo - b.sequenceNo)
    if (!older.length) hasOlderGroupMessages.value = history.length === 50
    setReplyPlans(plans)
    currentTurn.value = turn
    combatOverview.value = overview
    investigatorCards.value = cards
    if (recoveringInitialLoad) {
      setConversationParticipants(detail)
      actorRuntimes.value = runtimes
    }
    conversationReady.value = true
    operation.synced = true
    if (conversationLoad?.revision === revision) conversationLoad = null
  }
  async function correctGameTime(dayNo: number, period: TrpgGameTimePeriod) {
    const conversation = selectedConversation.value
    if (!conversation?.gameTime) throw new Error('当前时间尚未由 KP 初始化')
    const gameTime = await api.updateGameTime(conversation.id, {
      dayNo, period, revision: conversation.gameTime.revision,
    })
    conversations.value = conversations.value.map((item) => item.id === conversation.id
      ? { ...item, gameTime }
      : item)
    notify('游戏时间已校正', gameTime.displayText, 'success')
  }
  async function startTrpgTurn(investigatorDirection?: string): Promise<boolean> {
    const revision = conversationRevision
    const history = pendingGenerationReadiness(revision)
    if (history) {
      if (!await history || conversationRevision !== revision) return false
    }
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'trpg' || conversation.status !== 'active' || loading.sending) return false
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const result = await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent, signal) => streamTrpgTurn.continue(
          conversation.id, clientRequestId, onEvent,
          investigatorDirection?.trim() || undefined, signal),
      )
      await syncConversationState(conversation, operation)
      return !result.failed
    } catch (error) {
      if (isGenerationAbort(error)) return false
      if (selectedConversationId.value === conversation.id && conversationRevision === revision) generationFailed.value = true
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('行动轮启动失败', errorMessage(error), 'danger')
      return false
    }
    finally { await finishGenerationOperation(operation) }
  }
  async function retryGroupTurn() {
    const readiness = pendingGenerationReadiness(conversationRevision)
    if (readiness && !await readiness) return false
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    if (!conversation || conversation.mode !== 'chat' || turn?.status !== 'failed' || loading.sending || loading.withdrawing) return
    generationFailureOpen.value = false
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      await consumeGeneration(conversation.id, clientRequestId,
        (onEvent, signal) => streamGroupRetry(conversation.id, turn.turnId, clientRequestId, onEvent, signal))
      const failure = generationFailure.value
      if (failure?.conversationId === conversation.id && failure.detail.code === 'GROUP_CHECKPOINT_UNAVAILABLE') {
        failure.turnId ??= turn.turnId
      }
      await syncConversationState(conversation, operation)
    } catch (error) {
      if (isGenerationAbort(error)) return false
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('群聊回复重试失败', errorMessage(error), 'danger')
    } finally { await finishGenerationOperation(operation) }
  }
  async function withdrawGenerationFailure() {
    const failure = generationFailure.value
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    if (failure?.detail.code !== 'GROUP_CHECKPOINT_UNAVAILABLE'
      || failure.conversationId !== conversation?.id || conversation?.mode !== 'chat'
      || !turn || loading.sending) return
    await withdrawGroupTurn(failure.turnId ?? turn.turnId)
  }
  async function retryGenerationFailure() {
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    if (!generationFailure.value?.detail.retryable
      || generationFailure.value.conversationId !== conversation?.id
      || (generationFailure.value.turnId != null && generationFailure.value.turnId !== turn?.turnId)
      || (turn?.status !== 'failed' && turn?.status !== 'blocked')) return
    if (conversation.mode === 'chat') return retryGroupTurn()
    generationFailureOpen.value = false
    await startTrpgTurn()
  }
  async function selectSceneOption(optionNo: string) {
    const readiness = pendingGenerationReadiness(conversationRevision)
    if (readiness && !await readiness) return false
    const conversation = selectedConversation.value; const turn = currentTurn.value
    if (!conversation || !turn?.waitingForUser || turn.inputType !== 'selection' || !turn.stepId || loading.sending) return
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent, signal) => streamTrpgTurn.selection(
          conversation.id, turnId, stepId,
          { clientRequestId, optionNo }, onEvent, signal),
      )
      await syncConversationState(conversation, operation)
    } catch (error) {
      if (isGenerationAbort(error)) return false
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('地点选择失败', errorMessage(error), 'danger')
    }
    finally { await finishGenerationOperation(operation) }
  }
  async function endExploration() {
    const readiness = pendingGenerationReadiness(conversationRevision)
    if (readiness && !await readiness) return false
    const conversation = selectedConversation.value; const turn = currentTurn.value
    if (!conversation || !turn?.waitingForUser || turn.inputType !== 'message' || !turn.stepId || loading.sending) return
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent, signal) => streamTrpgTurn.endExploration(
          conversation.id, turnId, stepId,
          clientRequestId, onEvent, signal),
      )
      await syncConversationState(conversation, operation)
    } catch (error) {
      if (isGenerationAbort(error)) return false
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('结束探索失败', errorMessage(error), 'danger')
    }
    finally { await finishGenerationOperation(operation) }
  }

  async function askKp() {
    const revision = conversationRevision
    const originalQuestion = inquiryInput.value
    const readiness = pendingGenerationReadiness(revision)
    if (readiness && (!await readiness || inquiryInput.value !== originalQuestion)) return false
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    const question = inquiryInput.value.trim()
    if (!conversation || conversation.mode !== 'trpg'
      || conversation.status !== 'active' || !question
      || !turn?.waitingForUser || !turn.canAskKp
      || turn.inputType !== 'message' || !turn.stepId
      || loading.sending) return
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      const result = await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent, signal) => streamTrpgTurn.inquiry(
          conversation.id, turnId, stepId,
          { clientRequestId, question }, onEvent, signal),
      )
      await syncConversationState(conversation, operation)
      if (!result.failed && selectedConversationId.value === conversation.id && conversationRevision === revision) {
        if (inquiryInput.value.trim() === question) inquiryInput.value = ''
        composerIntent.value = 'action'
      }
    } catch (error) {
      if (isGenerationAbort(error)) return false
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('询问 KP 失败', errorMessage(error), 'danger')
    } finally {
      await finishGenerationOperation(operation)
    }
  }
  async function retryStep(message: GroupMessage) {
    const readiness = pendingGenerationReadiness(conversationRevision)
    if (readiness && !await readiness) return false
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'trpg' || !message.turnId || !message.replyStepId || loading.sending) return
    const operation = beginGenerationOperation()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent, signal) => streamTrpgTurn.retry(
          conversation.id, message.turnId!, message.replyStepId!,
          clientRequestId, onEvent, signal),
      )
      await syncConversationState(conversation, operation)
    } catch (error) {
      if (isGenerationAbort(error)) return false
      await syncConversationState(conversation, operation).catch(() => undefined)
      notify('角色行动重试失败', errorMessage(error), 'danger')
    }
    finally { await finishGenerationOperation(operation) }
  }
  async function sendMessage() {
    const revision = conversationRevision
    const originalInput = messageInput.value
    const history = pendingGenerationReadiness(revision)
    if (history) {
      if (!await history || conversationRevision !== revision
        || messageInput.value !== originalInput) return
    }
    const content = messageInput.value.trim(); const conversation = selectedConversation.value
    if (!content || !conversation || conversation.status !== 'active' || loading.sending || loading.withdrawing) return
    const waitingStep = currentTurn.value?.steps.find((step) => step.stepId === currentTurn.value?.stepId)
    const manualCharacter = currentTurn.value?.waitingForUser === true
      && waitingStep?.actorType === 'character'
    const manualChat = conversation.mode === 'chat'
      && manualCharacter
    if (conversation.mode === 'trpg') {
      const turn = currentTurn.value
      const acceptsMessage = turn?.inputType === 'message' || turn?.inputType === 'clarification'
      if (!turn?.waitingForUser || !acceptsMessage || !turn.stepId) {
        notify('消息发送失败', '当前还没有轮到用户调查员行动', 'danger')
        return
      }
    }
    const userId = session.id
    const restoreInput = () => {
      if (session.id === userId) chatDrafts.restoreIfEmpty(`world:${conversation.userWorldId}:group:${conversation.id}`, { action: originalInput })
    }
    const optimisticId = tempMessageId--
    messageInput.value = ''; const operation = beginGenerationOperation()
    if (conversation.mode === 'chat' && !manualChat) replyTurnState.value = beginReplyTurn()
    messages.value.push({ id: optimisticId, conversationId: conversation.id, speakerType: manualCharacter ? 'character' : 'user', speakerId: manualCharacter ? waitingStep?.actorId : undefined, speakerName: manualCharacter ? characterById(waitingStep?.actorId)?.characterName : undefined, messageKind: 'dialogue', content,
      sequenceNo: Date.now(), status: 'completed', createdAt: new Date().toISOString() })
    await scrollToBottom()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      let result: Awaited<ReturnType<typeof consumeGeneration>>
      if (conversation.mode === 'trpg') {
        const turn = currentTurn.value
        if (!turn?.stepId) throw new Error('当前行动轮状态已变化，请重试')
        const { turnId, stepId } = turn
        result = await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent, signal) => streamTrpgTurn.message(
            conversation.id, turnId, stepId,
            { clientRequestId, content }, onEvent, signal),
          false, optimisticId,
        )
      } else if (manualChat) {
        const turn = currentTurn.value
        if (!turn?.stepId) throw new Error('当前人工接管步骤已变化，请重试')
        result = await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent, signal) => streamManualGroupMessage(
            conversation.id, turn.turnId, turn.stepId!,
            { clientRequestId, content }, onEvent, signal,
          ),
          false, optimisticId,
        )
      } else {
        result = await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent, signal) => streamGroupMessage(
            conversation.id, { clientRequestId, content }, onEvent, signal),
          false, optimisticId,
        )
      }
      if (result.rejectedBeforeAcceptance) {
        restoreInput()
        if (selectedConversationId.value === conversation.id && conversationRevision === revision) {
          messages.value = messages.value.filter(message => message.id !== optimisticId)
        }
      }
      try {
        await syncConversationState(conversation, operation)
      } catch (error) {
        if (selectedConversationId.value === conversation.id && conversationRevision === revision) {
          notify('会话状态同步失败', errorMessage(error), 'danger')
        }
      }
    } catch (error) {
      if (isGenerationAbort(error)) return false
      restoreInput()
      if (conversation.mode === 'trpg') {
        await syncConversationState(conversation, operation).catch(() => undefined)
      } else if (selectedConversationId.value === conversation.id && conversationRevision === revision) {
        messages.value = messages.value.filter((message) => message.id !== optimisticId)
        replyTurnState.value = updateReplyTurn(replyTurnState.value, {
          eventType: 'reply.failed', turnId: replyTurnState.value?.turnId, error: errorMessage(error),
        })
      }
      notify('消息发送失败', errorMessage(error), 'danger')
    }
    finally { await finishGenerationOperation(operation) }
  }

  async function saveActorRuntime(payload: GroupActorRuntimeSavePayload) {
    if (!conversationReady.value || loading.chat) return
    const conversation = selectedConversation.value
    if (!conversation) return
    const savingKey = `${conversation.id}:${payload.actorType}:${payload.actorId ?? ''}`
    if (savingActorRuntimes.has(savingKey)) return
    savingActorRuntimes.add(savingKey)
    const finishSave = beginSettingsSave(conversation.id)
    let succeeded = false
    const revision = conversationRevision
    const worldId = selectedWorldId.value
    try {
      const saved = await api.saveActorRuntime(conversation.id, payload)
      succeeded = true
      if (revision !== conversationRevision || selectedConversationId.value !== conversation.id || selectedWorldId.value !== worldId) return
      const key = `${saved.actorType}:${saved.actorId ?? ''}`
      const exists = actorRuntimes.value.some((value) => `${value.actorType}:${value.actorId ?? ''}` === key)
      actorRuntimes.value = exists
        ? actorRuntimes.value.map((value) => `${value.actorType}:${value.actorId ?? ''}` === key ? saved : value)
        : [...actorRuntimes.value, saved]
      notify('发言方式已保存', saved.controlMode === 'MANUAL' ? '轮到该角色时会等待人工输入。' : '后续步骤会使用所选模型。', 'success')
      return saved
    } catch (error) {
      notify('发言方式保存失败', errorMessage(error), 'danger')
      return undefined
    } finally {
      savingActorRuntimes.delete(savingKey)
      finishSave(succeeded)
    }
  }
  async function scrollToBottom(force = false) {
    await nextTick()
    const viewport = messageScroller.value
    if (!viewport) return
    if (force) resetConversationScrollFollowing(viewport)
    scrollConversationToLatest(viewport)
  }

  window.addEventListener(UNAUTHORIZED_EVENT, logout)
  onMounted(boot)
  return {
    session, loading, userInfo, worlds, templates, modules, selectedWorldId, selectedWorld, worldReady, characters, characterTemplates, details, worldSave,
    loadModelApis, conversations, selectedConversationId, selectedConversation, conversationReady, savingReplyPlan, savingActorKeys, messages, reasoning, replyPlans, replyPlan, participantIds, messageInput, inquiryInput, composerIntent, messageScroller, currentTurn, actorRuntimes, modelApis, combatOverview, investigatorCards, replyTurnState, generationFailed,
    latestDiceRoll, incomingDiceRolls, hasOlderGroupMessages, generationFailure, generationFailureOpen, loadDiceAggregate,
    isLoggedIn, canEditSelectedWorld, planItems, availablePlanCharacters, characterById, authenticate, logout, loadUserInfo, saveUserInfo, changePassword,
    loadWorlds, loadTemplates, loadModules, selectWorld, createWorld, updateWorld, removeWorld, createTemplate, loadEditableWorldTemplate, updateTemplate, addDetail, removeDetail, saveSnapshot, loadSnapshot,
    reloadCharacters, saveCharacterModel, updateCharacterSettings, addCharacter, removeCharacter, updateCharacterFavor, createCharacterTemplate, loadEditableCharacterTemplate, updateCharacterTemplate, createConversation, selectConversation, closeConversation, deleteConversation,
    loadOlderGroupMessages, withdrawGroupTurn, savePlan, movePlanItem, deletePlanItem, addPlanItem, sendMessage, saveActorRuntime, askKp, startTrpgTurn, retryGroupTurn, retryGenerationFailure, withdrawGenerationFailure, selectSceneOption, endExploration, retryStep, correctGameTime, refreshDiceRoll,
  }
}
