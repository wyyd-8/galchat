import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, clearSession, currentSession, saveSession, streamGroupGeneration, streamGroupMessage, streamManualGroupMessage, streamTrpgTurn, UNAUTHORIZED_EVENT } from '@/api/client'
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
import { errorMessage, notify } from './useNotice'

let tempMessageId = -1
const freshPlan = (): ReplyPlan => ({ source: 'USER', displayName: '群聊', items: [] })

export function useWorkspace() {
  const session = reactive(currentSession())
  const loading = reactive({ boot: false, worlds: false, workspace: false, chat: false, sending: false })
  const userInfo = ref<UserInfo | null>(null)
  const worlds = ref<UserWorld[]>([])
  const templates = ref<WorldTemplate[]>([])
  const selectedWorldId = ref<number | null>(null)
  const characters = ref<Character[]>([])
  const characterTemplates = ref<CharacterTemplate[]>([])
  const modules = ref<CocModule[]>([])
  const details = ref<WorldDetail[]>([])
  const worldSave = ref<WorldSave | null>(null)
  const conversations = ref<Conversation[]>([])
  const selectedConversationId = ref<number | null>(null)
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
  const combatOverview = ref<TrpgCombatParticipantOverview[]>([])
  const investigatorCards = ref<InvestigatorCardSummary[]>([])
  const replyTurnState = ref<ReplyTurnState | null>(null)
  const generationFailure = ref<GenerationFailureState | null>(null)
  const generationFailureOpen = ref(false)
  const latestDiceRoll = ref<DiceRollAggregate | null>(null)
  const incomingDiceRolls = ref<DiceRollAggregate[]>([])
  const hasOlderGroupMessages = ref(false)
  const diceRollCache = new Map<number, Promise<DiceRollAggregate>>()
  let catchingUpGenerationId: string | null = null
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
    selectedWorldId.value = null; selectedConversationId.value = null; worlds.value = []; characters.value = []
    conversations.value = []; messages.value = []; replyPlans.value = []; replyPlan.value = freshPlan(); participantIds.value = []; currentTurn.value = null; actorRuntimes.value = []; modelApis.value = []; combatOverview.value = []; investigatorCards.value = []; replyTurnState.value = null; modules.value = []
    latestDiceRoll.value = null; incomingDiceRolls.value = []; hasOlderGroupMessages.value = false; diceRollCache.clear()
  }

  function setReplyPlans(plans: ReplyPlan[]) {
    replyPlans.value = plans
    replyPlan.value = activeReplyPlan(plans) || freshPlan()
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
    const aggregate = await loadDiceAggregate(summaryId, true)
    const diceRoundNos = [...new Set(aggregate.results.map((detail) => detail.roundNo || 1))]
    messages.value = messages.value.map((message) => {
      if (message.diceRoll?.summary.id !== summaryId) return message
      return { ...message, diceRoll: aggregate, diceRoundNos }
    })
    latestDiceRoll.value = aggregate
    if (selectedConversation.value?.mode === 'trpg') {
      const [overview, cards] = await Promise.all([
        loadCombatOverview(selectedConversation.value.id),
        loadInvestigatorCards(selectedConversation.value.id),
      ])
      combatOverview.value = overview
      investigatorCards.value = cards
    }
    return aggregate
  }
  function logout() { clearSession(); Object.assign(session, currentSession()); userInfo.value = null; resetWorkspace() }

  async function boot() {
    if (!isLoggedIn.value) return
    loading.boot = true
    try { await Promise.all([loadWorlds(), loadTemplates(), loadModules(), loadUserInfo()]) } catch (error) { notify('无法载入工作区', errorMessage(error), 'danger') }
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
  async function loadModules() { modules.value = await api.cocModules() }
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
  async function removeWorld() { if (!selectedWorldId.value) return; await api.deleteWorld(selectedWorldId.value); resetWorkspace(); await Promise.all([loadWorlds(), loadModules()]); notify('当前世界已删除', '', 'success') }
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

  async function createConversation(payload: { mode: 'chat' | 'trpg'; title: string; moduleId?: number; characterIds: number[] }) {
    if (!selectedWorldId.value) return
    const created = await api.createConversation({ ...payload, userWorldId: selectedWorldId.value })
    if (payload.mode === 'trpg') {
      localStorage.setItem(`galchat:trpg-participants:${created.id}`, encodeParticipantIds(payload.characterIds))
    }
    conversations.value = await api.conversations(selectedWorldId.value); participantIds.value = [...payload.characterIds]
    await selectConversation(created.id); notify(payload.mode === 'trpg' ? 'CoC 跑团已建立' : '普通群聊已建立', created.title, 'success')
    return created
  }
  async function selectConversation(id: number) {
    selectedConversationId.value = id; loading.chat = true; messages.value = []; hasOlderGroupMessages.value = false; currentTurn.value = null; actorRuntimes.value = []; modelApis.value = []; combatOverview.value = []; investigatorCards.value = []; replyTurnState.value = null; latestDiceRoll.value = null; incomingDiceRolls.value = []; diceRollCache.clear(); Object.keys(reasoning).forEach((key) => delete reasoning[Number(key)])
    try {
      const [conversationDetail, history, plans, turn, runtimes, models, overview, cards] = await Promise.all([
        api.conversation(id), api.groupMessages(id), api.replyPlan(id), api.currentTurn(id), api.actorRuntimes(id), api.modelApis(), loadCombatOverview(id), loadInvestigatorCards(id),
      ])
      conversations.value = conversations.value.map((item) => item.id === id ? { ...item, ...conversationDetail } : item)
      messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
      hasOlderGroupMessages.value = history.length === 50
      setReplyPlans(plans)
      currentTurn.value = turn
      actorRuntimes.value = runtimes
      modelApis.value = models
      combatOverview.value = overview
      investigatorCards.value = cards
      const plannedParticipantIds = [...new Set(replyPlan.value.items
        .filter((item) => item.actorType === 'character')
        .map((item) => item.actorId)
        .filter((id): id is number => typeof id === 'number'))]
      if (conversationDetail.mode === 'trpg') {
        const storageKey = `galchat:trpg-participants:${conversationDetail.id}`
        const rememberedParticipantIds = decodeParticipantIds(localStorage.getItem(storageKey))
        participantIds.value = resolveParticipantIds(
          conversationDetail.characterIds,
          rememberedParticipantIds,
          plannedParticipantIds,
        )
        if (conversationDetail.characterIds !== undefined || (!rememberedParticipantIds.length && plannedParticipantIds.length)) {
          localStorage.setItem(storageKey, encodeParticipantIds(participantIds.value))
        }
      } else {
        participantIds.value = plannedParticipantIds
      }
      await scrollToBottom(true)
    } catch (error) { notify('会话加载失败', errorMessage(error), 'danger') }
    finally { loading.chat = false }
    if (selectedConversationId.value === id && storedGeneration(id)) {
      void resumeGeneration(id)
    }
  }
  async function closeConversation() {
    if (!selectedConversationId.value || !selectedWorldId.value) return
    await api.closeConversation(selectedConversationId.value); conversations.value = await api.conversations(selectedWorldId.value); notify('会话已关闭并生成总结', '', 'success')
  }
  async function deleteConversation() {
    if (!selectedConversationId.value || !selectedWorldId.value) return
    const conversationId = selectedConversationId.value
    await api.deleteConversation(conversationId)
    if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem(generationStorageKey(conversationId))
    localStorage.removeItem(`galchat:trpg-participants:${conversationId}`)
    conversations.value = await api.conversations(selectedWorldId.value)
    selectedConversationId.value = null
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
    notify('会话已永久删除', '', 'success')
  }
  async function loadOlderGroupMessages() {
    if (!selectedConversationId.value || !hasOlderGroupMessages.value || loading.chat) return
    const conversationId = selectedConversationId.value
    const beforeId = messages.value.filter((item) => item.id > 0).reduce((minimum, item) => Math.min(minimum, item.id), Number.POSITIVE_INFINITY)
    if (!Number.isFinite(beforeId)) return
    const viewport = messageScroller.value
    const previousHeight = viewport?.scrollHeight ?? 0
    loading.chat = true
    try {
      const older = await api.groupMessages(conversationId, beforeId, 50)
      if (conversationId !== selectedConversationId.value) return
      const previousTop = viewport?.scrollTop ?? 0
      const known = new Set(messages.value.map((item) => item.id))
      const hydrated = await hydrateGroupMessages(older)
      messages.value = [...hydrated.filter((item) => !known.has(item.id)), ...messages.value].sort((a, b) => a.sequenceNo - b.sequenceNo)
      hasOlderGroupMessages.value = older.length === 50
      await nextTick()
      if (viewport && messageScroller.value === viewport) viewport.scrollTop = previousTop + viewport.scrollHeight - previousHeight
    } finally { loading.chat = false }
  }
  async function withdrawGroupTurn() {
    if (!selectedConversationId.value) return
    await api.withdrawGroupTurn(selectedConversationId.value)
    const history = await api.groupMessages(selectedConversationId.value)
    messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
    hasOlderGroupMessages.value = history.length === 50
    replyTurnState.value = null
    notify('已撤回最近一轮群聊', '', 'success')
  }
  async function savePlan() {
    if (!selectedConversationId.value) return
    const items = replyPlan.value.items.map((item, index) => ({ ...item, order: index + 1 }))
    if (!items.length) throw new Error('回复顺序至少保留一位角色')
    const saved = await api.saveReplyPlan(selectedConversationId.value, {
      source: 'USER', executionKey: 'default', displayName: '群聊', items,
    })
    setReplyPlans([saved])
    notify('回复顺序已保存', '', 'success')
  }
  function movePlanItem(from: number, to: number) {
    const items = replyPlan.value.items; if (from === to || to < 0 || to >= items.length) return
    const [moved] = items.splice(from, 1); if (moved) items.splice(to, 0, moved)
  }
  function deletePlanItem(index: number) { replyPlan.value.items.splice(index, 1) }
  function addPlanItem(actorId: number) {
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

  function applyEvent(event: GroupChatEvent) {
    if (event.eventType === 'stream.caught_up') {
      catchingUpGenerationId = null
      void scrollToBottom()
      return
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
      diceRollCache.set(aggregate.summary.id, Promise.resolve(aggregate))
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
    connect: (onEvent: (event: GroupChatEvent) => void) => Promise<void>,
    catchingUp = false,
  ) {
    if (!catchingUp) {
      generationFailure.value = null
      generationFailureOpen.value = false
    }
    rememberGeneration(conversationId, clientRequestId)
    if (catchingUp) catchingUpGenerationId = clientRequestId
    let failed = false
    let terminal = false
    try {
      await connect((event) => {
        if (event.eventType === 'generation.failed') failed = true
        if (event.eventType === 'generation.failed'
          || event.eventType === 'turn.completed'
          || event.eventType === 'turn.waiting_input'
          || event.eventType === 'turn.paused') terminal = true
        if (selectedConversationId.value === conversationId) applyEvent(event)
      })
      if (terminal) forgetGeneration(conversationId, clientRequestId)
      return { failed, terminal }
    } finally {
      if (catchingUpGenerationId === clientRequestId) catchingUpGenerationId = null
    }
  }
  async function resumeGeneration(conversationId: number) {
    const clientRequestId = storedGeneration(conversationId)
    if (!clientRequestId) return
    loading.sending = true
    try {
      const outcome = await consumeGeneration(
        conversationId,
        clientRequestId,
        (onEvent) => streamGroupGeneration.resume(
          conversationId, clientRequestId, onEvent),
        true,
      )
      const conversation = selectedConversation.value
      if (outcome.failed && conversation?.id === conversationId
        && conversation.mode === 'trpg') {
        await syncTrpgState(conversation)
      }
    } catch {
      // The replay cache is intentionally best-effort; persisted history
      // loaded by selectConversation remains the fallback after expiry/restart.
      const conversation = selectedConversation.value
      if (conversation?.id === conversationId
        && conversation.mode === 'trpg') {
        await syncTrpgState(conversation).catch(() => undefined)
      }
    } finally {
      loading.sending = false
      await scrollToBottom()
    }
  }
  async function syncTrpgState(conversation: Conversation) {
    const [history, plans, turn, overview, cards] = await Promise.all([
      api.groupMessages(conversation.id), api.replyPlan(conversation.id), api.currentTurn(conversation.id), loadCombatOverview(conversation.id), loadInvestigatorCards(conversation.id),
    ])
    messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
    setReplyPlans(plans)
    currentTurn.value = turn
    combatOverview.value = overview
    investigatorCards.value = cards
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
  async function startTrpgTurn() {
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'trpg' || conversation.status !== 'active' || loading.sending) return
    loading.sending = true
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent) => streamTrpgTurn.continue(
          conversation.id, clientRequestId, onEvent),
      )
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('行动轮启动失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function retryGenerationFailure() {
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    if (!generationFailure.value
      || generationFailure.value.conversationId !== conversation?.id
      || conversation.mode !== 'trpg'
      || (turn?.status !== 'failed' && turn?.status !== 'blocked')) return
    generationFailureOpen.value = false
    await startTrpgTurn()
  }
  async function selectSceneOption(optionNo: string) {
    const conversation = selectedConversation.value; const turn = currentTurn.value
    if (!conversation || !turn?.waitingForUser || turn.inputType !== 'selection' || !turn.stepId || loading.sending) return
    loading.sending = true
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent) => streamTrpgTurn.selection(
          conversation.id, turnId, stepId,
          { clientRequestId, optionNo }, onEvent),
      )
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('地点选择失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function endExploration() {
    const conversation = selectedConversation.value; const turn = currentTurn.value
    if (!conversation || !turn?.waitingForUser || turn.inputType !== 'message' || !turn.stepId || loading.sending) return
    loading.sending = true
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent) => streamTrpgTurn.endExploration(
          conversation.id, turnId, stepId,
          clientRequestId, onEvent),
      )
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('结束探索失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }

  async function askKp() {
    const conversation = selectedConversation.value
    const turn = currentTurn.value
    const question = inquiryInput.value.trim()
    if (!conversation || conversation.mode !== 'trpg'
      || conversation.status !== 'active' || !question
      || !turn?.waitingForUser || !turn.canAskKp
      || turn.inputType !== 'message' || !turn.stepId
      || loading.sending) return
    loading.sending = true
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      const { turnId, stepId } = turn
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent) => streamTrpgTurn.inquiry(
          conversation.id, turnId, stepId,
          { clientRequestId, question }, onEvent),
      )
      await syncTrpgState(conversation)
      if (inquiryInput.value.trim() === question) inquiryInput.value = ''
      composerIntent.value = 'action'
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('询问 KP 失败', errorMessage(error), 'danger')
    } finally {
      loading.sending = false
      await scrollToBottom()
    }
  }
  async function retryStep(message: GroupMessage) {
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'trpg' || !message.turnId || !message.replyStepId || loading.sending) return
    loading.sending = true
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      await consumeGeneration(
        conversation.id,
        clientRequestId,
        (onEvent) => streamTrpgTurn.retry(
          conversation.id, message.turnId!, message.replyStepId!,
          clientRequestId, onEvent),
      )
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('角色行动重试失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function sendMessage() {
    const content = messageInput.value.trim(); const conversation = selectedConversation.value
    if (!content || !conversation || conversation.status !== 'active' || loading.sending) return
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
    const originalInput = messageInput.value
    const optimisticId = tempMessageId--
    messageInput.value = ''; loading.sending = true
    if (conversation.mode === 'chat' && !manualChat) replyTurnState.value = beginReplyTurn()
    messages.value.push({ id: optimisticId, conversationId: conversation.id, speakerType: manualCharacter ? 'character' : 'user', speakerId: manualCharacter ? waitingStep?.actorId : undefined, speakerName: manualCharacter ? characterById(waitingStep?.actorId)?.characterName : undefined, messageKind: 'dialogue', content,
      sequenceNo: Date.now(), status: 'completed', createdAt: new Date().toISOString() })
    await scrollToBottom()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      if (conversation.mode === 'trpg') {
        const turn = currentTurn.value
        if (!turn?.stepId) throw new Error('当前行动轮状态已变化，请重试')
        const { turnId, stepId } = turn
        await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent) => streamTrpgTurn.message(
            conversation.id, turnId, stepId,
            { clientRequestId, content }, onEvent),
        )
        await syncTrpgState(conversation)
      } else if (manualChat) {
        const turn = currentTurn.value
        if (!turn?.stepId) throw new Error('当前人工接管步骤已变化，请重试')
        await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent) => streamManualGroupMessage(
            conversation.id, turn.turnId, turn.stepId!,
            { clientRequestId, content }, onEvent,
          ),
        )
        const [history, plans, nextTurn] = await Promise.all([
          api.groupMessages(conversation.id), api.replyPlan(conversation.id), api.currentTurn(conversation.id),
        ])
        messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
        setReplyPlans(plans)
        currentTurn.value = nextTurn
      } else {
        await consumeGeneration(
          conversation.id,
          clientRequestId,
          (onEvent) => streamGroupMessage(
            conversation.id, { clientRequestId, content }, onEvent),
        )
        const [history, plans, nextTurn] = await Promise.all([api.groupMessages(conversation.id), api.replyPlan(conversation.id), api.currentTurn(conversation.id)])
        messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo); setReplyPlans(plans); currentTurn.value = nextTurn
      }
    } catch (error) {
      if (conversation.mode === 'trpg') {
        await syncTrpgState(conversation).catch(() => undefined)
      } else {
        messages.value = messages.value.filter((message) => message.id !== optimisticId)
        if (!messageInput.value) messageInput.value = originalInput
        replyTurnState.value = updateReplyTurn(replyTurnState.value, {
          eventType: 'reply.failed', turnId: replyTurnState.value?.turnId, error: errorMessage(error),
        })
      }
      notify('消息发送失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }

  async function saveActorRuntime(payload: GroupActorRuntimeSavePayload) {
    const conversation = selectedConversation.value
    if (!conversation) return
    try {
      const saved = await api.saveActorRuntime(conversation.id, payload)
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
    session, loading, userInfo, worlds, templates, modules, selectedWorldId, selectedWorld, characters, characterTemplates, details, worldSave,
    conversations, selectedConversationId, selectedConversation, messages, reasoning, replyPlans, replyPlan, participantIds, messageInput, inquiryInput, composerIntent, messageScroller, currentTurn, actorRuntimes, modelApis, combatOverview, investigatorCards, replyTurnState,
    latestDiceRoll, incomingDiceRolls, hasOlderGroupMessages, generationFailure, generationFailureOpen,
    isLoggedIn, canEditSelectedWorld, planItems, availablePlanCharacters, characterById, authenticate, logout, loadUserInfo, saveUserInfo, changePassword,
    loadWorlds, loadTemplates, loadModules, selectWorld, createWorld, updateWorld, removeWorld, createTemplate, loadEditableWorldTemplate, updateTemplate, addDetail, removeDetail, saveSnapshot, loadSnapshot,
    reloadCharacters, addCharacter, removeCharacter, updateCharacter, createCharacterTemplate, loadEditableCharacterTemplate, updateCharacterTemplate, createConversation, selectConversation, closeConversation, deleteConversation,
    loadOlderGroupMessages, withdrawGroupTurn, savePlan, movePlanItem, deletePlanItem, addPlanItem, sendMessage, saveActorRuntime, askKp, startTrpgTurn, retryGenerationFailure, selectSceneOption, endExploration, retryStep, correctGameTime, refreshDiceRoll,
  }
}
