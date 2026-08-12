import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, clearSession, currentSession, saveSession, streamGroupMessage, streamTrpgTurn, UNAUTHORIZED_EVENT } from '@/api/client'
import type {
  Character, CharacterTemplate, CocModule, Conversation, CurrentTurn, DiceRollAggregate, GroupChatEvent, GroupMessage, ReplyPlan, ReplyPlanItem, TrpgGameTimePeriod,
  UserInfo, UserWorld, WorldDetail, WorldSave, WorldTemplate,
} from '@/api/types'
import { beginReplyTurn, updateReplyTurn, type ReplyTurnState } from '@/components/replyTurnStatus'
import { decodeParticipantIds, encodeParticipantIds, resolveParticipantIds } from '@/components/trpgSetupState'
import { applyGameTimeEvent } from '@/components/gameTimeState'
import { hydrateDiceMessage } from '@/dice/domain/dicePlayback'
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
  const modules = ref<CocModule[]>([])
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
  const currentTurn = ref<CurrentTurn | null>(null)
  const replyTurnState = ref<ReplyTurnState | null>(null)
  const latestDiceRoll = ref<DiceRollAggregate | null>(null)
  const incomingDiceRoll = ref<DiceRollAggregate | null>(null)
  const hasOlderGroupMessages = ref(false)
  const diceRollCache = new Map<number, Promise<DiceRollAggregate>>()

  const isLoggedIn = computed(() => Boolean(session.token && session.id))
  const selectedWorld = computed(() => worlds.value.find((item) => item.id === selectedWorldId.value) || null)
  const canEditSelectedWorld = computed(() => Boolean(selectedWorld.value?.myWorld))
  const selectedConversation = computed(() => conversations.value.find((item) => item.id === selectedConversationId.value) || null)
  const planItems = computed(() => replyPlan.value.groups.flatMap((group) => group.items))
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
  function resetWorkspace() {
    selectedWorldId.value = null; selectedConversationId.value = null; worlds.value = []; characters.value = []
    conversations.value = []; messages.value = []; replyPlan.value = freshPlan(); participantIds.value = []; currentTurn.value = null; replyTurnState.value = null; modules.value = []
    latestDiceRoll.value = null; incomingDiceRoll.value = null; hasOlderGroupMessages.value = false; diceRollCache.clear()
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

  async function refreshDiceRoll(summaryId: number): Promise<DiceRollAggregate> {
    const aggregate = await loadDiceAggregate(summaryId, true)
    messages.value = messages.value.map((message) => {
      if (message.diceRoll?.summary.id !== summaryId) return message
      const rounds = new Set(message.diceRoundNos || [])
      const diceRoll = rounds.size
        ? { ...aggregate, results: aggregate.results.filter((detail) => rounds.has(detail.roundNo || 1)) }
        : aggregate
      return { ...message, diceRoll }
    })
    latestDiceRoll.value = aggregate
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
    selectedConversationId.value = id; loading.chat = true; messages.value = []; hasOlderGroupMessages.value = false; currentTurn.value = null; replyTurnState.value = null; latestDiceRoll.value = null; incomingDiceRoll.value = null; diceRollCache.clear(); Object.keys(reasoning).forEach((key) => delete reasoning[Number(key)])
    try {
      const [conversationDetail, history, plan, turn] = await Promise.all([
        api.conversation(id), api.groupMessages(id), api.replyPlan(id), api.currentTurn(id),
      ])
      conversations.value = conversations.value.map((item) => item.id === id ? { ...item, ...conversationDetail } : item)
      messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
      hasOlderGroupMessages.value = history.length === 50
      replyPlan.value = plan || freshPlan()
      currentTurn.value = turn
      const plannedParticipantIds = [...new Set(replyPlan.value.groups
        .flatMap((group) => group.items
          .filter((item) => item.actorType === 'character')
          .map((item) => item.actorId))
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
      await scrollToBottom('auto')
    } catch (error) { notify('会话加载失败', errorMessage(error), 'danger') }
    finally { loading.chat = false }
  }
  async function closeConversation() {
    if (!selectedConversationId.value || !selectedWorldId.value) return
    await api.closeConversation(selectedConversationId.value); conversations.value = await api.conversations(selectedWorldId.value); notify('会话已关闭并生成总结', '', 'success')
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
    if (!group.items.some((item) => item.actorId === actorId)) group.items.push({ order: group.items.length + 1, actorType: 'character', actorId })
  }

  function findEventMessage(event: GroupChatEvent): GroupMessage | undefined {
    if (event.messageId != null) {
      return messages.value.find((item) => item.id === event.messageId)
    }
    return messages.value.find((item) => item.replyStepId === event.replyStepId)
  }

  function applyEvent(event: GroupChatEvent) {
    const step = event.replyStepId
    if (event.eventType === 'game_time.changed' && selectedConversationId.value) {
      conversations.value = conversations.value.map((conversation) => conversation.id === selectedConversationId.value
        ? applyGameTimeEvent(conversation, event)
        : conversation)
    }
    if (selectedConversation.value?.mode === 'chat') replyTurnState.value = updateReplyTurn(replyTurnState.value, event)
    if (event.eventType === 'dice_roll.created' && event.diceRoll) {
      const aggregate = {
        ...event.diceRoll,
        summary: { ...event.diceRoll.summary, toolName: event.toolName },
      }
      latestDiceRoll.value = aggregate
      incomingDiceRoll.value = aggregate
      diceRollCache.set(aggregate.summary.id, Promise.resolve(aggregate))
      const message = findEventMessage(event)
      if (message) Object.assign(message, {
        messageKind: 'dice_roll',
        content: '',
        diceRoll: aggregate,
        diceRoundNos: [...new Set(aggregate.results.map((detail) => detail.roundNo || 1))],
      })
    }
    if (event.eventType === 'reply.started' && step) {
      const character = characterById(event.speaker?.id)
      const existing = findEventMessage(event)
      if (existing) {
        Object.assign(existing, { id: event.messageId || tempMessageId--, turnId: event.turnId,
          speakerType: eventSpeakerType(event), speakerId: event.speaker?.id, speakerName: event.speaker?.name || character?.characterName,
          messageKind: eventMessageKind(event), content: '', decisionContent: '', sequenceNo: event.sequence || Date.now(), status: 'streaming' })
      } else {
        messages.value.push({ id: event.messageId || tempMessageId--, conversationId: selectedConversationId.value!, turnId: event.turnId, replyStepId: step,
          speakerType: eventSpeakerType(event), speakerId: event.speaker?.id, speakerName: event.speaker?.name || character?.characterName,
          messageKind: eventMessageKind(event), content: '', decisionContent: '', sequenceNo: event.sequence || Date.now(), status: 'streaming' })
      }
      reasoning[step] = ''
    } else if (event.eventType === 'reasoning.delta' && step) {
      reasoning[step] = (reasoning[step] || '') + (event.delta || '')
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
    } else if (event.eventType === 'turn.waiting_input' && event.turnId && event.replyStepId) {
      currentTurn.value = {
        turnId: event.turnId, status: 'waiting_input', stepId: event.replyStepId,
        actionType: event.actionType, itemOrder: event.itemOrder,
        inputType: event.actionType === 'trpg_scene_selection' ? 'selection' : 'message',
        sceneName: event.groupName, waitingForUser: true, sceneOptions: event.sceneOptions || {},
      }
    } else if (event.eventType === 'turn.paused' && event.turnId) {
      currentTurn.value = {
        turnId: event.turnId, status: 'paused',
        inputType: 'continue', waitingForUser: false,
        sceneOptions: {},
      }
    } else if (event.eventType === 'turn.completed') {
      currentTurn.value = null
    } else if (event.eventType === 'reply.failed' && step) {
      const message = findEventMessage(event)
      if (message) { message.status = 'failed'; message.content ||= event.error || '回复生成失败' }
    } else if (event.eventType === 'reply.failed' && event.error) {
      notify('本轮回复中断', event.error, 'danger')
    }
    void scrollToBottom()
  }
  async function syncTrpgState(conversation: Conversation) {
    const [history, plan, turn] = await Promise.all([
      api.groupMessages(conversation.id), api.replyPlan(conversation.id), api.currentTurn(conversation.id),
    ])
    messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo)
    replyPlan.value = plan || freshPlan()
    currentTurn.value = turn
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
      await streamTrpgTurn.continue(conversation.id, crypto.randomUUID?.() || `web-${Date.now()}`, applyEvent)
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('行动轮启动失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function selectSceneOption(optionNo: string) {
    const conversation = selectedConversation.value; const turn = currentTurn.value
    if (!conversation || !turn?.waitingForUser || turn.inputType !== 'selection' || !turn.stepId || loading.sending) return
    loading.sending = true
    try {
      await streamTrpgTurn.selection(conversation.id, turn.turnId, turn.stepId,
        { clientRequestId: crypto.randomUUID?.() || `web-${Date.now()}`, optionNo }, applyEvent)
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
      await streamTrpgTurn.endExploration(conversation.id, turn.turnId, turn.stepId,
        crypto.randomUUID?.() || `web-${Date.now()}`, applyEvent)
      await syncTrpgState(conversation)
    } catch (error) {
      await syncTrpgState(conversation).catch(() => undefined)
      notify('结束探索失败', errorMessage(error), 'danger')
    }
    finally { loading.sending = false; await scrollToBottom() }
  }
  async function retryStep(message: GroupMessage) {
    const conversation = selectedConversation.value
    if (!conversation || conversation.mode !== 'trpg' || !message.turnId || !message.replyStepId || loading.sending) return
    loading.sending = true
    try {
      await streamTrpgTurn.retry(conversation.id, message.turnId, message.replyStepId, applyEvent)
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
    if (conversation.mode === 'trpg') {
      const turn = currentTurn.value
      if (!turn?.waitingForUser || turn.inputType !== 'message' || !turn.stepId) {
        notify('消息发送失败', '当前还没有轮到用户调查员行动', 'danger')
        return
      }
    }
    const originalInput = messageInput.value
    const optimisticId = tempMessageId--
    messageInput.value = ''; loading.sending = true
    if (conversation.mode === 'chat') replyTurnState.value = beginReplyTurn()
    messages.value.push({ id: optimisticId, conversationId: conversation.id, speakerType: 'user', messageKind: 'dialogue', content,
      sequenceNo: Date.now(), status: 'completed', createdAt: new Date().toISOString() })
    await scrollToBottom()
    try {
      const clientRequestId = crypto.randomUUID?.() || `web-${Date.now()}`
      if (conversation.mode === 'trpg') {
        const turn = currentTurn.value
        if (!turn?.stepId) throw new Error('当前行动轮状态已变化，请重试')
        await streamTrpgTurn.message(conversation.id, turn.turnId, turn.stepId, { clientRequestId, content }, applyEvent)
        await syncTrpgState(conversation)
      } else {
        await streamGroupMessage(conversation.id, { clientRequestId, content }, applyEvent)
        await api.finishReplyPlan(conversation.id)
        const [history, plan] = await Promise.all([api.groupMessages(conversation.id), api.replyPlan(conversation.id)])
        messages.value = (await hydrateGroupMessages(history)).sort((a, b) => a.sequenceNo - b.sequenceNo); replyPlan.value = plan
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
  async function scrollToBottom(behavior: ScrollBehavior = 'auto') { await nextTick(); messageScroller.value?.scrollTo({ top: messageScroller.value.scrollHeight, behavior }) }

  window.addEventListener(UNAUTHORIZED_EVENT, logout)
  onMounted(boot)
  return {
    session, loading, userInfo, worlds, templates, modules, selectedWorldId, selectedWorld, characters, characterTemplates, details, worldSave,
    conversations, selectedConversationId, selectedConversation, messages, reasoning, replyPlan, participantIds, messageInput, messageScroller, currentTurn, replyTurnState,
    latestDiceRoll, incomingDiceRoll, hasOlderGroupMessages,
    isLoggedIn, canEditSelectedWorld, planItems, availablePlanCharacters, characterById, authenticate, logout, loadUserInfo, saveUserInfo, changePassword,
    loadWorlds, loadTemplates, loadModules, selectWorld, createWorld, updateWorld, removeWorld, createTemplate, loadEditableWorldTemplate, updateTemplate, addDetail, removeDetail, saveSnapshot, loadSnapshot,
    reloadCharacters, addCharacter, removeCharacter, updateCharacter, createCharacterTemplate, loadEditableCharacterTemplate, updateCharacterTemplate, createConversation, selectConversation, closeConversation,
    loadOlderGroupMessages, withdrawGroupTurn, savePlan, movePlanItem, deletePlanItem, addPlanItem, sendMessage, startTrpgTurn, selectSceneOption, endExploration, retryStep, correctGameTime, refreshDiceRoll,
  }
}
