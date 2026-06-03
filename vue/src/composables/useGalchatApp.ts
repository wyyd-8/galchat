import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import {
  api,
  clearSession,
  createChatSocket,
  currentSession,
  saveSession,
  streamChat,
  UNAUTHORIZED_EVENT,
  uploadImage,
} from '@/api/client'
import type {
  ActiveStory,
  CharacterTemplate,
  ChatHistory,
  ChatMessagePayload,
  StoryDetail,
  StoryListItem,
  UserCharacter,
  UserWorld,
  UserWorldSave,
  WorldArchive,
  WorldDetail,
  WorldTemplate,
} from '@/api/types'
import type { FavorabilityRow, SidebarMode, UiMessage } from '@/types/ui'
import { formatTime, splitMessageContent } from '@/utils/ui'

type CharacterTemplateBase = CharacterTemplate & { id: number }

export function useGalchatApp() {
  const sessionSnapshot = currentSession()
  const session = reactive({
    token: sessionSnapshot.token,
    id: sessionSnapshot.id as number | null,
    username: sessionSnapshot.username,
  })

  const sidebarMode = ref<SidebarMode>('worlds')
  const authDialogVisible = ref(!session.token)
  const authMode = ref<'login' | 'register'>('login')
  const authForm = reactive({ email: '', password: '', confirmPassword: '', verificationCode: '' })
  const authLoading = ref(false)
  const authCodeLoading = ref(false)
  const authCodeCooldown = ref(0)
  let authCodeTimer: number | undefined
  const accountDialogVisible = ref(false)
  const accountLoading = ref(false)
  const passwordDialogVisible = ref(false)
  const passwordLoading = ref(false)
  const passwordCodeLoading = ref(false)
  const passwordCodeCooldown = ref(0)
  let passwordCodeTimer: number | undefined
  const accountForm = reactive({
    username: '',
    email: '',
    birthday: '',
  })
  const passwordForm = reactive({
    email: '',
    newPassword: '',
    confirmPassword: '',
    verificationCode: '',
  })

  const loading = reactive({
    app: false,
    worlds: false,
    characters: false,
    events: false,
    history: false,
    sending: false,
    withdrawing: false,
    worldSave: false,
  })

  const worldTemplates = ref<WorldTemplate[]>([])
  const userWorlds = ref<UserWorld[]>([])
  const selectedWorld = ref<UserWorld | null>(null)
  const selectedWorldDetail = ref<UserWorld | null>(null)
  const characters = ref<UserCharacter[]>([])
  const selectedCharacter = ref<UserCharacter | null>(null)
  const stories = ref<StoryListItem[]>([])
  const activeStory = ref<ActiveStory | null>(null)
  const worldSave = ref<UserWorldSave | null>(null)
  const worldSaveActionLoading = ref(false)
  const storyDetailDialogVisible = ref(false)
  const storyDetailLoading = ref(false)
  const selectedStoryDetail = ref<StoryDetail | null>(null)
  const characterPanelCollapsed = ref(false)

  const createWorldDialogVisible = ref(false)
  const createWorldStep = ref(0)
  const createWorldForm = reactive({
    worldId: undefined as number | undefined,
    name: '',
    acitvePushStatus: true,
    dailyCompanionMode: true,
    favorSystemStatus: 'NORMAL',
    eotDetectionStatus: true,
    thinkStatus: true,
    addSpecialPrompt: false,
  })
  const createTemplateDialogVisible = ref(false)
  const createTemplateMode = ref<'create' | 'edit'>('create')
  const templateImageUploading = ref(false)
  const createTemplateImageFileName = ref('')
  const createTemplateForm = reactive({
    name: '',
    image: '',
    author: '',
    background: '',
    visible: true,
  })
  const createCharacterDialogVisible = ref(false)
  const createCharacterTemplateDialogVisible = ref(false)
  const characterTemplateMode = ref<'create' | 'edit'>('create')
  const selectedEditCharacterTemplateId = ref<number | undefined>()
  const characterDeleteDialogVisible = ref(false)
  const characterImageUploading = ref(false)
  const createCharacterImageFileName = ref('')
  const characterCreating = ref(false)
  const characterPromptSaving = ref(false)
  const characterFavorSaving = ref(false)
  const characterDeleting = ref(false)
  const characterTemplateLoading = ref(false)
  const selectedWorldTemplate = ref<WorldTemplate | null>(null)
  const worldCharacterTemplates = ref<CharacterTemplate[]>([])
  const characterTemplateLabels = reactive<Record<number, string>>({})
  const addCharacterForm = reactive({
    characterId: undefined as number | undefined,
    userInfoPrompt: '',
  })
  const createCharacterForm = reactive({
    name: '',
    image: '',
    background: '',
    personality: '',
    initFavor: 0,
    favorabilityRows: [] as FavorabilityRow[],
  })

  const worldDetailDialogVisible = ref(false)
  const worldDetailLoading = ref(false)
  const worldDetails = ref<WorldDetail[]>([])
  const worldDetailForm = reactive({
    about: '',
    details: '',
  })
  const worldSettingsDialogVisible = ref(false)
  const worldSettingsLoading = ref(false)
  const worldImporting = ref(false)
  const worldExporting = ref(false)
  const worldDeleteDialogVisible = ref(false)
  const worldDeleting = ref(false)
  const worldSettingsForm = reactive({
    name: '',
    acitvePushStatus: true,
    favorSystemStatus: 'NORMAL',
    eotDetectionStatus: true,
  })

  const startStoryDialogVisible = ref(false)
  const advanceStoryDialogVisible = ref(false)
  const endStoryDialogVisible = ref(false)
  const storyActionLoading = ref(false)
  const storyForm = reactive({
    title: '',
    theme: '',
    currentScene: '',
    opening: '',
    characterIds: [] as number[],
  })
  const storyAdvanceForm = reactive({
    transition: '',
  })
  const storyEndForm = reactive({
    ending: '',
  })

  const messageInput = ref('')
  const messageList = ref<UiMessage[]>([])
  const messageScroller = ref<HTMLElement | null>(null)

  let chatSocket: WebSocket | null = null
  let chatSocketUserWorldId: number | null = null
  let chatSocketConnecting: Promise<WebSocket> | null = null
  let lastSocketTypingContext: string | null = null
  let ignoreNextInputTypingChange = false
  let messageInputComposing = false
  let typingSignalVersion = 0
  let browserNotificationPermissionRequested = false

  const isLoggedIn = computed(() => Boolean(session.token))
  const hasSelectedWorld = computed(() => Boolean(selectedWorld.value))
  const hasSelectedCharacter = computed(() => Boolean(selectedCharacter.value))
  const isWorldSelectionMode = computed(() => sidebarMode.value === 'worlds')
  const selectedWorldId = computed(() => selectedWorld.value?.id)
  const selectedTemplateWorldId = computed(() => selectedWorldDetail.value?.worldId)
  const canEditSelectedWorld = computed(() => Boolean(selectedWorldDetail.value?.myWorld))

  const selectedWorldName = computed(() => selectedWorld.value?.name || '未选择世界')
  const selectedCharacterName = computed(() => selectedCharacter.value?.characterName || '未选择角色')
  const createWorldStepIsLast = computed(() => createWorldStep.value === 4)
  const existingCharacterIds = computed(() => new Set(characters.value.map((character) => character.characterId)))
  const availableCharacterTemplates = computed(() =>
    worldCharacterTemplates.value
      .filter(hasCharacterTemplateId)
      .filter((template) => !existingCharacterIds.value.has(template.id)),
  )
  const editableCharacterTemplates = computed(() =>
    worldCharacterTemplates.value.filter(hasCharacterTemplateId),
  )
  const availableCharacterTemplateIds = computed(() =>
    availableCharacterTemplates.value.map((template) => template.id),
  )
  const selectedAddCharacterTemplate = computed(() =>
    availableCharacterTemplates.value.find((template) => template.id === addCharacterForm.characterId) || null,
  )
  const selectedEditCharacterTemplate = computed(() =>
    worldCharacterTemplates.value.find((template) => template.id === selectedEditCharacterTemplateId.value) || null,
  )
  const selectedCreateTemplate = computed(() =>
    worldTemplates.value.find((template) => template.id === createWorldForm.worldId),
  )
  const createTemplateDialogTitle = computed(() =>
    createTemplateMode.value === 'edit' ? '修改世界模板' : '创建新的世界模板',
  )
  const createTemplateSubmitLabel = computed(() =>
    createTemplateMode.value === 'edit' ? '保存模板' : '创建模板',
  )
  const createCharacterTemplateDialogTitle = computed(() =>
    characterTemplateMode.value === 'edit' ? '修改角色模板' : '创建角色模板',
  )
  const createCharacterTemplateSubmitLabel = computed(() =>
    characterTemplateMode.value === 'edit' ? '保存模板' : '创建模板',
  )

  const averageFavor = computed(() => {
    const values = characters.value
      .map((character) => character.favorValue)
      .filter((value): value is number => typeof value === 'number')
    if (values.length === 0) {
      return 0
    }
    return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length)
  })

  const activeStoryTitle = computed(() => activeStory.value?.storyEvent?.title || '暂无进行中事件')
  const selectedStoryCharacterIds = computed(
    () => new Set(activeStory.value?.characters.map((character) => character.characterId) || []),
  )
  const canWithdrawLatestMessage = computed(() => {
    if (
      loading.history ||
      loading.sending ||
      loading.withdrawing ||
      !selectedWorldId.value ||
      !selectedCharacter.value
    ) {
      return false
    }

    const conversationMessages = messageList.value.filter((message) =>
      message.role === 'user' || message.role === 'assistant',
    )
    const latestConversationMessage = conversationMessages.at(-1)
    if (
      latestConversationMessage?.role !== 'assistant' ||
      latestConversationMessage.complete === false
    ) {
      return false
    }

    return conversationMessages.some((message) => message.role === 'user')
  })

  watch(
    () => createWorldForm.thinkStatus,
    (thinkStatus) => {
      if (thinkStatus) {
        createWorldForm.eotDetectionStatus = false
      } else {
        createWorldForm.addSpecialPrompt = false
      }
    },
  )

  watch(selectedEditCharacterTemplateId, (characterId) => {
    if (
      characterTemplateMode.value !== 'edit' ||
      !createCharacterTemplateDialogVisible.value ||
      !selectedWorldId.value ||
      !characterId
    ) {
      return
    }

    void loadEditableCharacterTemplate(characterId)
  })

  function isComposerTyping(value = messageInput.value) {
    return messageInputComposing || value.length > 0
  }

  watch(messageInput, (value) => {
    if (ignoreNextInputTypingChange) {
      ignoreNextInputTypingChange = false
      return
    }

    void sendSocketTyping(isComposerTyping(value))
  })

  watch(
    () => [
      selectedTemplateWorldId.value,
      selectedWorldId.value,
      selectedCharacter.value?.characterId,
      selectedWorldDetail.value?.thinkStatus,
    ],
    () => {
      if (isComposerTyping()) {
        void sendSocketTyping(true)
      }
    },
  )

  function isWorldActive(world: UserWorld) {
    return selectedWorld.value?.id === world.id
  }

  function isCharacterActive(character: UserCharacter) {
    return selectedCharacter.value?.characterId === character.characterId
  }

  function messageRole(history: ChatHistory): UiMessage['role'] {
    if (history.type === 'thinking') return 'thinking'
    if (history.type === 'tool') return 'tool'
    if (history.type?.startsWith('story_')) return 'story'
    if (history.type === 'ASSISTANT' || history.type === 'assistant') return 'assistant'
    return 'user'
  }

  function shouldReplaceAiPronouns() {
    return Boolean(selectedWorldDetail.value?.addSpecialPrompt || selectedWorld.value?.addSpecialPrompt)
  }

  function replaceAiPronouns(content: string, role: UiMessage['role']) {
    if (!shouldReplaceAiPronouns() || (role !== 'assistant' && role !== 'thinking')) {
      return content
    }

    return content.replace(/[他她]/g, 'ta')
  }

  function toUiMessage(history: ChatHistory): UiMessage {
    const role = messageRole(history)
    const content = history.content || (role === 'tool' ? '调用了工具' : '')
    return {
      id: `history-${history.id || `${role}-${Math.random()}`}`,
      role,
      content: replaceAiPronouns(content, role),
      time: formatTime(history.timestamp),
    }
  }

  function toSplitUiMessages(history: ChatHistory, idPrefix = 'history'): UiMessage[] {
    const role = messageRole(history)
    const content = replaceAiPronouns(
      history.content || (role === 'tool' ? '调用了工具' : ''),
      role,
    )

    return splitMessageContent(content).map((part, index) => ({
      id: `${idPrefix}-${history.id || Date.now()}-${index}`,
      role,
      content: part,
      time: formatTime(history.timestamp),
    }))
  }

  function isSelectedChatHistory(history: ChatHistory) {
    return (
      history.userWorldId === selectedWorldId.value &&
      history.characterId === selectedCharacter.value?.characterId
    )
  }

  function appendHistoryMessage(history: ChatHistory, splitContent = false) {
    if (splitContent) {
      messageList.value.push(...toSplitUiMessages(history, 'socket'))
      return
    }

    messageList.value.push(toUiMessage(history))
  }

  function notifyBrowserMessage(title: string, body: string, tag: string) {
    if (!body || !('Notification' in window)) {
      return
    }

    const showNotification = () => {
      new window.Notification(title, {
        body,
        tag,
      })
    }

    if (window.Notification.permission === 'granted') {
      showNotification()
      return
    }

    if (window.Notification.permission !== 'default' || browserNotificationPermissionRequested) {
      return
    }

    browserNotificationPermissionRequested = true
    void window.Notification.requestPermission()
      .then((permission) => {
        if (permission === 'granted') {
          showNotification()
        }
      })
      .catch(() => undefined)
  }

  function notifyPushedMessage(history: ChatHistory) {
    const role = messageRole(history)
    if (role !== 'assistant' && role !== 'story') {
      return
    }

    const content = replaceAiPronouns((history.content || '').trim(), role)
    if (!content) {
      return
    }

    const characterName = characters.value.find(
      (character) => character.characterId === history.characterId,
    )?.characterName
    const title = role === 'story'
      ? `${selectedWorldName.value} 有新的事件消息`
      : `${characterName || selectedCharacterName.value || '角色'} 发来消息`
    const message = content.length > 90 ? `${content.slice(0, 90)}...` : content
    const tag = `galchat-${history.userWorldId || selectedWorldId.value || 'world'}-${history.characterId || 'story'}`

    ElNotification({
      title,
      message,
      type: 'info',
      position: 'top-right',
      duration: 4500,
      showClose: true,
    })
    notifyBrowserMessage(title, message, tag)
  }

  function buildSelectedChatPayload(message = ''): ChatMessagePayload | null {
    const worldId = selectedTemplateWorldId.value
    const userWorldId = selectedWorldId.value
    const characterId = selectedCharacter.value?.characterId

    if (!worldId || !userWorldId || !characterId) {
      return null
    }

    return {
      worldId,
      userWorldId,
      characterId,
      message,
    }
  }

  function typingContextKey(payload: ChatMessagePayload) {
    return `${payload.worldId}:${payload.userWorldId}:${payload.characterId}`
  }

  async function withMessage<T>(task: () => Promise<T>, fallback: string) {
    try {
      return await task()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : fallback)
      throw error
    }
  }

  function syncSessionUser(username: string, id = session.id) {
    session.username = username
    if (id) {
      localStorage.setItem('galchat.userId', String(id))
    }
    localStorage.setItem('galchat.username', username)
  }

  function startAuthCodeCooldown(seconds = 60) {
    if (authCodeTimer) {
      window.clearInterval(authCodeTimer)
    }
    authCodeCooldown.value = seconds
    authCodeTimer = window.setInterval(() => {
      authCodeCooldown.value -= 1
      if (authCodeCooldown.value <= 0 && authCodeTimer) {
        window.clearInterval(authCodeTimer)
        authCodeTimer = undefined
        authCodeCooldown.value = 0
      }
    }, 1000)
  }

  function switchAuthMode() {
    authMode.value = authMode.value === 'login' ? 'register' : 'login'
    authForm.confirmPassword = ''
    authForm.verificationCode = ''
  }

  function startPasswordCodeCooldown(seconds = 60) {
    if (passwordCodeTimer) {
      window.clearInterval(passwordCodeTimer)
    }
    passwordCodeCooldown.value = seconds
    passwordCodeTimer = window.setInterval(() => {
      passwordCodeCooldown.value -= 1
      if (passwordCodeCooldown.value <= 0 && passwordCodeTimer) {
        window.clearInterval(passwordCodeTimer)
        passwordCodeTimer = undefined
        passwordCodeCooldown.value = 0
      }
    }, 1000)
  }

  async function sendRegisterEmailCode() {
    if (authMode.value !== 'register') {
      return
    }
    if (!authForm.email.trim()) {
      ElMessage.warning('请输入邮箱')
      return
    }
    if (authCodeCooldown.value > 0) {
      return
    }

    authCodeLoading.value = true
    try {
      await api.sendRegisterEmailCode(authForm.email.trim())
      startAuthCodeCooldown()
      ElMessage.success('验证码已发送')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '验证码发送失败')
    } finally {
      authCodeLoading.value = false
    }
  }

  async function sendPasswordEmailCode() {
    if (!passwordForm.email.trim()) {
      ElMessage.warning('邮箱为空，请重新打开修改密码窗口')
      return
    }
    if (passwordCodeCooldown.value > 0) {
      return
    }

    passwordCodeLoading.value = true
    try {
      await api.sendPasswordEmailCode(passwordForm.email.trim())
      startPasswordCodeCooldown()
      ElMessage.success('验证码已发送')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '验证码发送失败')
    } finally {
      passwordCodeLoading.value = false
    }
  }

  async function submitAuth() {
    if (!authForm.email.trim() || !authForm.password.trim()) {
      ElMessage.warning('请输入邮箱和密码')
      return
    }
    if (authMode.value === 'register' && !authForm.confirmPassword) {
      ElMessage.warning('请确认密码')
      return
    }
    if (authMode.value === 'register' && authForm.password !== authForm.confirmPassword) {
      ElMessage.warning('两次输入的密码不一致')
      return
    }
    if (authMode.value === 'register' && !authForm.verificationCode.trim()) {
      ElMessage.warning('请输入邮箱验证码')
      return
    }

    authLoading.value = true
    try {
      const isRegister = authMode.value === 'register'
      const result =
        isRegister
          ? await api.register(
              authForm.email.trim(),
              authForm.password,
              authForm.verificationCode.trim(),
            )
          : await api.login(authForm.email.trim(), authForm.password)

      saveSession(result)
      session.token = result.token
      session.id = result.id
      session.username = result.username
      authDialogVisible.value = false
      authForm.password = ''
      authForm.confirmPassword = ''
      authForm.verificationCode = ''
      ElMessage.success(isRegister ? '注册成功' : '登录成功')
      await loadAppData()
      if (isRegister) {
        await openAccountSettings()
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '登录失败')
    } finally {
      authLoading.value = false
    }
  }

  async function openAccountSettings() {
    accountDialogVisible.value = true
    accountLoading.value = true
    try {
      const user = await api.getUserInfo()
      accountForm.username = user.username || ''
      accountForm.email = user.email || ''
      accountForm.birthday = user.birthday || ''
      if (user.id) {
        session.id = user.id
      }
      syncSessionUser(user.username || session.username || '', user.id || session.id)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载账号信息失败')
    } finally {
      accountLoading.value = false
    }
  }

  async function openPasswordSettings() {
    passwordDialogVisible.value = true
    passwordForm.email = accountForm.email || ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordForm.verificationCode = ''
    passwordLoading.value = true
    try {
      const user = await api.getUserInfo()
      passwordForm.email = user.email || passwordForm.email
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载账号信息失败')
    } finally {
      passwordLoading.value = false
    }
  }

  async function submitAccountProfile() {
    if (!accountForm.username.trim()) {
      ElMessage.warning('请输入用户名')
      return
    }

    accountLoading.value = true
    try {
      await api.updateUserInfo({
        username: accountForm.username.trim(),
        birthday: accountForm.birthday || undefined,
      })
      syncSessionUser(accountForm.username.trim())
      ElMessage.success('账号资料已保存')
      accountDialogVisible.value = false
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存账号资料失败')
    } finally {
      accountLoading.value = false
    }
  }

  async function submitPassword() {
    if (!passwordForm.email.trim() || !passwordForm.newPassword) {
      ElMessage.warning('请输入邮箱和新密码')
      return
    }
    if (!passwordForm.verificationCode.trim()) {
      ElMessage.warning('请输入邮箱验证码')
      return
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      ElMessage.warning('两次输入的新密码不一致')
      return
    }

    passwordLoading.value = true
    try {
      await api.updatePassword({
        email: passwordForm.email.trim(),
        newPassword: passwordForm.newPassword,
        verificationCode: passwordForm.verificationCode.trim(),
      })
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
      passwordForm.verificationCode = ''
      ElMessage.success('密码已更新')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '更新密码失败')
    } finally {
      passwordLoading.value = false
    }
  }

  async function ensureUser() {
    if (!session.token) {
      return false
    }
    if (session.id) {
      return true
    }

    const user = await api.getUserInfo()
    session.id = user.id
    session.username = user.username
    localStorage.setItem('galchat.userId', String(user.id))
    localStorage.setItem('galchat.username', user.username)
    return true
  }

  async function loadAppData() {
    if (!(await ensureUser())) {
      return
    }

    loading.app = true
    try {
      await Promise.all([loadWorlds(), loadWorldTemplates()])
    } finally {
      loading.app = false
    }
  }

  async function loadWorlds() {
    if (!session.id) return
    loading.worlds = true
    try {
      userWorlds.value = await api.listUserWorlds(session.id)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载已有世界失败')
    } finally {
      loading.worlds = false
    }
  }

  async function loadWorldTemplates() {
    loading.worlds = true
    try {
      worldTemplates.value = await api.listWorldTemplates()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载世界模板失败')
    } finally {
      loading.worlds = false
    }
  }

  async function refreshCurrentWorld() {
    if (!selectedWorld.value) {
      await loadAppData()
      return
    }
    await selectWorld(selectedWorld.value)
  }

  async function refreshWorkspace() {
    if (isWorldSelectionMode.value) {
      await loadAppData()
      return
    }
    await refreshCurrentWorld()
  }

  async function selectWorld(world: UserWorld) {
    if (selectedWorld.value?.id !== world.id) {
      await sendSocketTyping(false)
    }
    closeChatSocket()
    selectedWorld.value = world
    selectedWorldTemplate.value = null
    selectedCharacter.value = null
    messageList.value = []
    sidebarMode.value = 'characters'
    characterPanelCollapsed.value = false

    await withMessage(async () => {
      const [detail] = await Promise.all([
        api.getUserWorld(world.id),
        loadCharacters(world.id),
        loadWorldEvents(world.id),
        loadWorldSave(world.id),
      ])
      selectedWorldDetail.value = detail
    }, '加载世界失败')
  }

  async function openWorldOverview() {
    if (selectedCharacter.value) {
      await sendSocketTyping(false)
    }
    closeChatSocket()
    selectedCharacter.value = null
    messageList.value = []
    sidebarMode.value = 'characters'
    characterPanelCollapsed.value = false
  }

  async function loadCharacters(userWorldId: number) {
    loading.characters = true
    try {
      characters.value = await api.listCharacters(userWorldId)
      syncSelectedCharacter(userWorldId)
    } finally {
      loading.characters = false
    }
  }

  function syncSelectedCharacter(userWorldId: number, characterId = selectedCharacter.value?.characterId) {
    if (!characterId || selectedWorldId.value !== userWorldId) {
      return
    }

    const updatedCharacter = characters.value.find(
      (character) => character.characterId === characterId,
    )
    if (updatedCharacter) {
      selectedCharacter.value = updatedCharacter
    }
  }

  async function refreshCurrentChatStatus(userWorldId: number, characterId = selectedCharacter.value?.characterId) {
    await Promise.all([loadCharacters(userWorldId), loadWorldEvents(userWorldId)])
    syncSelectedCharacter(userWorldId, characterId)
  }

  async function loadWorldEvents(userWorldId: number) {
    loading.events = true
    try {
      const [storyList, active] = await Promise.all([
        api.listStories(userWorldId),
        api.getActiveStory(userWorldId),
      ])
      stories.value = storyList
      activeStory.value = active
    } finally {
      loading.events = false
    }
  }

  async function loadWorldSave(userWorldId: number) {
    loading.worldSave = true
    try {
      worldSave.value = await api.getWorldSave(userWorldId)
    } finally {
      loading.worldSave = false
    }
  }

  async function submitWorldSave(remark: string, done?: (success: boolean) => void) {
    const userWorldId = selectedWorldId.value
    if (!userWorldId) {
      done?.(false)
      return
    }

    worldSaveActionLoading.value = true
    try {
      worldSave.value = await api.saveWorld(userWorldId, remark.trim())
      ElMessage.success('存档已保存')
      done?.(true)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '存档失败')
      done?.(false)
    } finally {
      worldSaveActionLoading.value = false
    }
  }

  async function submitWorldLoad(done?: (success: boolean) => void) {
    const userWorldId = selectedWorldId.value
    if (!userWorldId || !worldSave.value) {
      done?.(false)
      return
    }

    worldSaveActionLoading.value = true
    try {
      await api.loadWorldSave(userWorldId)
      ElMessage.success('读档完成')
      await Promise.all([loadCharacters(userWorldId), loadWorldEvents(userWorldId)])
      if (selectedCharacter.value) {
        await loadHistory()
      }
      done?.(true)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '读档失败')
      done?.(false)
    } finally {
      worldSaveActionLoading.value = false
    }
  }

  async function openStoryDetail(storyEventId: number) {
    storyDetailDialogVisible.value = true
    storyDetailLoading.value = true
    selectedStoryDetail.value = null
    try {
      selectedStoryDetail.value = await api.getStoryDetail(storyEventId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载事件详情失败')
    } finally {
      storyDetailLoading.value = false
    }
  }

  async function selectCharacter(character: UserCharacter) {
    selectedCharacter.value = character
    characterPanelCollapsed.value = false
    await loadHistory()
  }

  async function loadHistory() {
    if (!selectedWorldId.value || !selectedCharacter.value) {
      return
    }

    loading.history = true
    try {
      const history = await api.listHistory(selectedWorldId.value, selectedCharacter.value.characterId)
      messageList.value = selectedWorldDetail.value?.thinkStatus
        ? history.map(toUiMessage)
        : history.flatMap((item) => toSplitUiMessages(item))
      await scrollToBottom()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载聊天历史失败')
    } finally {
      loading.history = false
    }
  }

  function openCreateWorld(template?: WorldTemplate) {
    createWorldForm.worldId = template?.id
    createWorldForm.name = template?.name || ''
    createWorldForm.acitvePushStatus = true
    createWorldForm.dailyCompanionMode = true
    createWorldForm.favorSystemStatus = 'NORMAL'
    createWorldForm.thinkStatus = true
    createWorldForm.addSpecialPrompt = false
    createWorldForm.eotDetectionStatus = false
    createWorldStep.value = 0
    createWorldDialogVisible.value = true
  }

  function openCreateTemplate() {
    createTemplateMode.value = 'create'
    createTemplateForm.name = ''
    createTemplateForm.image = ''
    createTemplateImageFileName.value = ''
    createTemplateForm.author = session.username || ''
    createTemplateForm.background = ''
    createTemplateForm.visible = true
    createTemplateDialogVisible.value = true
  }

  async function openEditWorldTemplate() {
    const userWorldId = selectedWorldId.value
    if (!userWorldId || !canEditSelectedWorld.value) {
      return
    }

    createTemplateMode.value = 'edit'
    createTemplateDialogVisible.value = true
    templateImageUploading.value = true
    try {
      const template = await api.getMyWorldTemplate(userWorldId)
      createTemplateForm.name = template.name || ''
      createTemplateForm.image = template.image || ''
      createTemplateImageFileName.value = template.image ? '已使用现有图片' : ''
      createTemplateForm.author = template.author || ''
      createTemplateForm.background = template.background || ''
      createTemplateForm.visible = template.visible ?? true
    } catch (error) {
      createTemplateDialogVisible.value = false
      ElMessage.error(error instanceof Error ? error.message : '加载世界模板失败')
    } finally {
      templateImageUploading.value = false
    }
  }

  async function handleTemplateImageChange(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) {
      return
    }

    templateImageUploading.value = true
    try {
      createTemplateForm.image = await uploadImage(file)
      createTemplateImageFileName.value = file.name
      ElMessage.success('图片已上传')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    } finally {
      templateImageUploading.value = false
    }
  }

  async function handleCharacterImageChange(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) {
      return
    }

    characterImageUploading.value = true
    try {
      createCharacterForm.image = await uploadImage(file)
      createCharacterImageFileName.value = file.name
      ElMessage.success('图片已上传')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '图片上传失败')
    } finally {
      characterImageUploading.value = false
    }
  }

  async function submitCreateTemplate() {
    if (!createTemplateForm.name.trim() || !createTemplateForm.background.trim()) {
      ElMessage.warning('请填写模板名称和世界背景')
      return
    }

    await withMessage(async () => {
      const payload: WorldTemplate = {
        name: createTemplateForm.name.trim(),
        image: createTemplateForm.image.trim(),
        author: createTemplateForm.author.trim(),
        background: createTemplateForm.background.trim(),
        visible: createTemplateForm.visible,
      }
      if (createTemplateMode.value === 'edit') {
        const userWorldId = selectedWorldId.value
        if (!userWorldId) {
          return
        }
        await api.updateMyWorldTemplate(userWorldId, payload)
        await Promise.all([loadWorldTemplates(), loadSelectedWorldTemplate()])
        createTemplateDialogVisible.value = false
        ElMessage.success('世界模板已保存')
        return
      }

      await api.createWorldTemplate(payload)
      await loadWorldTemplates()
      const createdTemplate = worldTemplates.value.find(
        (template) =>
          template.name === createTemplateForm.name.trim() &&
          (!createTemplateForm.image || template.image === createTemplateForm.image.trim()),
      )
      createWorldForm.worldId = createdTemplate?.id
      createWorldForm.name = createTemplateForm.name.trim()
      createTemplateDialogVisible.value = false
      ElMessage.success(createdTemplate ? '模板已创建并选中' : '模板已创建，请在列表中选择')
    }, '创建模板失败')
  }

  async function openWorldDetailsFromTemplateDialog() {
    createTemplateDialogVisible.value = false
    await openWorldDetails()
  }

  function openCreateCharacter() {
    addCharacterForm.characterId = undefined
    addCharacterForm.userInfoPrompt = ''
    createCharacterDialogVisible.value = true
    void loadSelectedWorldTemplate()
  }

  function characterTemplateLabel(characterId: number) {
    return worldCharacterTemplates.value.find((template) => template.id === characterId)?.name
      || characterTemplateLabels[characterId]
      || `角色模板 #${characterId}`
  }

  function hasCharacterTemplateId(template: CharacterTemplate): template is CharacterTemplateBase {
    return typeof template.id === 'number'
  }

  async function loadSelectedWorldTemplate() {
    const worldId = selectedTemplateWorldId.value
    if (!worldId) {
      selectedWorldTemplate.value = null
      worldCharacterTemplates.value = []
      return null
    }

    characterTemplateLoading.value = true
    try {
      const [worldTemplate, characterTemplates] = await Promise.all([
        api.getWorldTemplate(worldId),
        api.listCharacterTemplates(worldId),
      ])
      selectedWorldTemplate.value = worldTemplate
      worldCharacterTemplates.value = characterTemplates
      characterTemplates.forEach((template) => {
        if (template.id && template.name) {
          characterTemplateLabels[template.id] = template.name
        }
      })
      return selectedWorldTemplate.value
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载角色模板失败')
      throw error
    } finally {
      characterTemplateLoading.value = false
    }
  }

  function openCreateCharacterTemplate() {
    characterTemplateMode.value = 'edit'
    selectedEditCharacterTemplateId.value = undefined
    resetCharacterTemplateForm()
    createCharacterTemplateDialogVisible.value = true
    void loadSelectedWorldTemplate()
  }

  function openCreateCharacterTemplateForm() {
    characterTemplateMode.value = 'create'
    selectedEditCharacterTemplateId.value = undefined
    resetCharacterTemplateForm()
  }

  function resetCharacterTemplateForm() {
    createCharacterForm.name = ''
    createCharacterForm.image = ''
    createCharacterImageFileName.value = ''
    createCharacterForm.background = ''
    createCharacterForm.personality = ''
    createCharacterForm.initFavor = 0
    createCharacterForm.favorabilityRows = []
  }

  function fillCharacterTemplateForm(template: CharacterTemplate) {
    createCharacterForm.name = template.name || ''
    createCharacterForm.image = template.image || ''
    createCharacterImageFileName.value = template.image ? '已使用现有图片' : ''
    createCharacterForm.background = template.background || ''
    createCharacterForm.personality = template.personality || ''
    createCharacterForm.initFavor = template.initFavor || 0
    createCharacterForm.favorabilityRows = Object.entries(template.favorability || {}).map(
      ([threshold, prompt]) => ({
        id: `favor-${threshold}-${Date.now()}-${Math.random()}`,
        threshold: Number(threshold),
        prompt,
      }),
    )
  }

  async function loadEditableCharacterTemplate(characterId: number) {
    const userWorldId = selectedWorldId.value
    if (!userWorldId) {
      return
    }

    characterTemplateLoading.value = true
    try {
      const template = await api.getMyCharacterTemplate(userWorldId, characterId)
      fillCharacterTemplateForm(template)
      if (template.id && template.name) {
        characterTemplateLabels[template.id] = template.name
      }
    } catch (error) {
      resetCharacterTemplateForm()
      ElMessage.error(error instanceof Error ? error.message : '加载角色模板失败')
    } finally {
      characterTemplateLoading.value = false
    }
  }

  function addFavorabilityRow() {
    createCharacterForm.favorabilityRows.push({
      id: `favor-${Date.now()}-${Math.random()}`,
      threshold: undefined,
      prompt: '',
    })
  }

  function removeFavorabilityRow(rowId: string) {
    createCharacterForm.favorabilityRows = createCharacterForm.favorabilityRows.filter(
      (row) => row.id !== rowId,
    )
  }

  function parseFavorability(rows: FavorabilityRow[]) {
    const favorability: Record<string, string> = {}
    rows.forEach((row) => {
      if (typeof row.threshold === 'number' && row.prompt.trim()) {
        favorability[String(row.threshold)] = row.prompt.trim()
      }
    })
    return Object.keys(favorability).length > 0 ? favorability : undefined
  }

  async function submitCreateCharacter() {
    const userWorldId = selectedWorldId.value
    if (!userWorldId) {
      return
    }
    if (!addCharacterForm.characterId) {
      ElMessage.warning('请选择角色模板')
      return
    }

    const characterId = addCharacterForm.characterId
    const normalizedPrompt = addCharacterForm.userInfoPrompt.trim()
    characterCreating.value = true
    try {
      await api.addCharacter(userWorldId, characterId)
      await api.updateCharacterPrompt(userWorldId, characterId, {
        userInfoPrompt: normalizedPrompt || undefined,
      })
      createCharacterDialogVisible.value = false
      ElMessage.success('角色已添加')
      await loadCharacters(userWorldId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '添加角色失败')
    } finally {
      characterCreating.value = false
    }
  }

  async function updateCharacterPrompt(userInfoPrompt: string) {
    const userWorldId = selectedWorldId.value
    const character = selectedCharacter.value
    if (!userWorldId || !character) {
      return
    }

    characterPromptSaving.value = true
    try {
      const normalizedPrompt = userInfoPrompt.trim()
      await api.updateCharacterPrompt(userWorldId, character.characterId, {
        userInfoPrompt: normalizedPrompt || undefined,
      })
      character.userInfoPrompt = normalizedPrompt
      const cachedCharacter = characters.value.find((item) => item.characterId === character.characterId)
      if (cachedCharacter) {
        cachedCharacter.userInfoPrompt = normalizedPrompt
      }
      ElMessage.success('角色提示词已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存角色提示词失败')
    } finally {
      characterPromptSaving.value = false
    }
  }

  async function updateCharacterFavor(favorValue: number) {
    const userWorldId = selectedWorldId.value
    const character = selectedCharacter.value
    if (!userWorldId || !character || !canEditSelectedWorld.value) {
      return
    }
    if (!Number.isFinite(favorValue) || favorValue < 0 || favorValue > 100) {
      ElMessage.warning('好感度必须在0-100之间')
      return
    }

    const normalizedFavorValue = Math.round(favorValue)
    characterFavorSaving.value = true
    try {
      await api.updateMyCharacterFavor(userWorldId, character.characterId, normalizedFavorValue)
      character.favorValue = normalizedFavorValue
      const cachedCharacter = characters.value.find((item) => item.characterId === character.characterId)
      if (cachedCharacter) {
        cachedCharacter.favorValue = normalizedFavorValue
      }
      ElMessage.success('好感度已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存好感度失败')
    } finally {
      characterFavorSaving.value = false
    }
  }

  function openDeleteCharacterConfirm() {
    if (!selectedWorldId.value || !selectedCharacter.value) {
      return
    }
    characterDeleteDialogVisible.value = true
  }

  async function submitDeleteCharacter() {
    const userWorldId = selectedWorldId.value
    const characterId = selectedCharacter.value?.characterId
    if (!userWorldId || !characterId) {
      return
    }

    characterDeleting.value = true
    try {
      await sendSocketTyping(false)
      closeChatSocket()
      await api.deleteCharacter(userWorldId, characterId)
      characters.value = characters.value.filter((character) => character.characterId !== characterId)
      selectedCharacter.value = null
      messageList.value = []
      characterDeleteDialogVisible.value = false
      ElMessage.success('角色已删除')
      await Promise.all([loadCharacters(userWorldId), loadWorldEvents(userWorldId)])
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '删除角色失败')
    } finally {
      characterDeleting.value = false
    }
  }

  async function submitCreateCharacterTemplate() {
    const worldId = selectedTemplateWorldId.value
    if (!worldId) {
      return
    }
    if (!createCharacterForm.name.trim()) {
      ElMessage.warning('请输入角色模板名称')
      return
    }

    characterCreating.value = true
    try {
      const payload: CharacterTemplate = {
        name: createCharacterForm.name.trim(),
        image: createCharacterForm.image.trim(),
        background: createCharacterForm.background.trim(),
        personality: createCharacterForm.personality.trim(),
        initFavor: createCharacterForm.initFavor,
        favorability: parseFavorability(createCharacterForm.favorabilityRows),
      }

      if (characterTemplateMode.value === 'edit') {
        const userWorldId = selectedWorldId.value
        const characterId = selectedEditCharacterTemplateId.value
        if (!userWorldId || !characterId) {
          ElMessage.warning('请先选择角色模板')
          return
        }
        await api.updateMyCharacterTemplate(userWorldId, characterId, payload)
        characterTemplateLabels[characterId] = payload.name
        await loadSelectedWorldTemplate()
        const updatedTemplate = worldCharacterTemplates.value.find((template) => template.id === characterId)
        if (updatedTemplate) {
          fillCharacterTemplateForm(updatedTemplate)
        }
        createCharacterTemplateDialogVisible.value = false
        ElMessage.success('角色模板已保存')
        return
      }

      await api.createCharacterTemplate(worldId, payload)
      await loadSelectedWorldTemplate()
      createCharacterTemplateDialogVisible.value = false
      ElMessage.success('角色模板已创建')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存角色模板失败')
    } finally {
      characterCreating.value = false
    }
  }

  async function openWorldDetails() {
    if (!selectedTemplateWorldId.value || !canEditSelectedWorld.value) {
      return
    }
    worldDetailDialogVisible.value = true
    worldDetailForm.about = ''
    worldDetailForm.details = ''
    await loadWorldDetails()
  }

  function openWorldSettings() {
    const world = selectedWorldDetail.value || selectedWorld.value
    if (!world) {
      return
    }

    worldSettingsForm.name = world.name || ''
    worldSettingsForm.acitvePushStatus = world.acitvePushStatus ?? true
    worldSettingsForm.favorSystemStatus = world.favorSystemStatus || 'NORMAL'
    worldSettingsForm.eotDetectionStatus = world.eotDetectionStatus ?? true
    worldSettingsDialogVisible.value = true
  }

  async function submitWorldSettings() {
    const worldId = selectedWorldId.value
    if (!worldId) {
      return
    }
    if (!worldSettingsForm.name.trim()) {
      ElMessage.warning('请输入世界名称')
      return
    }

    worldSettingsLoading.value = true
    try {
      await api.updateUserWorld(worldId, {
        name: worldSettingsForm.name.trim(),
        acitvePushStatus: worldSettingsForm.acitvePushStatus,
        favorSystemStatus: worldSettingsForm.favorSystemStatus,
        eotDetectionStatus: worldSettingsForm.eotDetectionStatus,
      })
      const updatedWorld = await api.getUserWorld(worldId)
      selectedWorldDetail.value = updatedWorld
      selectedWorld.value = {
        ...(selectedWorld.value || updatedWorld),
        ...updatedWorld,
      }
      userWorlds.value = userWorlds.value.map((world) =>
        world.id === worldId ? { ...world, name: updatedWorld.name, image: updatedWorld.image } : world,
      )
      worldSettingsDialogVisible.value = false
      ElMessage.success('世界设置已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存世界设置失败')
    } finally {
      worldSettingsLoading.value = false
    }
  }

  function downloadTextFile(content: string, filename: string, type: string) {
    const blob = new Blob([content], { type })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }

  function sanitizeFilename(name: string) {
    return name.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, '-')
  }

  async function exportCurrentWorld() {
    const worldId = selectedWorldId.value
    if (!worldId) {
      return
    }

    worldExporting.value = true
    try {
      const archive = await api.exportMyWorldArchive(worldId)
      const name = sanitizeFilename(selectedWorldName.value) || `world-${worldId}`
      downloadTextFile(archive, `galchat-${name}.json`, 'application/json;charset=utf-8')
      ElMessage.success('世界已导出')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '导出世界失败')
    } finally {
      worldExporting.value = false
    }
  }

  async function importWorldArchive(file: File) {
    worldImporting.value = true
    try {
      const archive = JSON.parse(await file.text()) as WorldArchive
      const result = await api.importWorldArchive(archive)
      await loadWorldTemplates()
      ElMessage.success(`世界模板「${result.name}」已导入`)
    } catch (error) {
      ElMessage.error(error instanceof SyntaxError ? '导入文件不是有效的 JSON' : error instanceof Error ? error.message : '导入世界模板失败')
    } finally {
      worldImporting.value = false
    }
  }

  function openDeleteWorldConfirm() {
    if (!selectedWorldId.value) {
      return
    }
    worldDeleteDialogVisible.value = true
  }

  async function submitDeleteWorld() {
    const worldId = selectedWorldId.value
    if (!worldId) {
      return
    }

    worldDeleting.value = true
    try {
      await sendSocketTyping(false)
      closeChatSocket()
      await api.deleteUserWorld(worldId)
      userWorlds.value = userWorlds.value.filter((world) => world.id !== worldId)
      worldDeleteDialogVisible.value = false
      worldSettingsDialogVisible.value = false
      resetSelection()
      ElMessage.success('世界已删除')
      await loadWorlds()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '删除世界失败')
    } finally {
      worldDeleting.value = false
    }
  }

  async function loadWorldDetails() {
    const worldId = selectedTemplateWorldId.value
    if (!worldId) {
      return
    }

    worldDetailLoading.value = true
    try {
      worldDetails.value = await api.listWorldDetails(worldId)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载世界设定失败')
    } finally {
      worldDetailLoading.value = false
    }
  }

  async function submitWorldDetail() {
    const worldId = selectedTemplateWorldId.value
    if (!worldId) {
      return
    }
    if (!worldDetailForm.about.trim() || !worldDetailForm.details.trim()) {
      ElMessage.warning('请填写设定主题和内容')
      return
    }

    worldDetailLoading.value = true
    try {
      await api.createWorldDetail(worldId, {
        about: worldDetailForm.about.trim(),
        details: worldDetailForm.details.trim(),
      })
      worldDetailForm.about = ''
      worldDetailForm.details = ''
      ElMessage.success('世界设定已添加')
      await loadWorldDetails()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '添加世界设定失败')
    } finally {
      worldDetailLoading.value = false
    }
  }

  async function deleteWorldDetail(detail: WorldDetail) {
    const worldId = selectedTemplateWorldId.value
    if (!worldId || !detail.id) {
      return
    }

    worldDetailLoading.value = true
    try {
      await api.deleteWorldDetail(worldId, detail.id)
      ElMessage.success('世界设定已删除')
      await loadWorldDetails()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '删除世界设定失败')
    } finally {
      worldDetailLoading.value = false
    }
  }

  function canProceedCreateWorldStep() {
    if (createWorldStep.value === 0) {
      if (!createWorldForm.worldId) {
        ElMessage.warning('请先选择世界模板')
        return false
      }
      return true
    }
    return true
  }

  function nextCreateWorldStep() {
    if (!canProceedCreateWorldStep()) {
      return
    }
    createWorldStep.value = Math.min(4, createWorldStep.value + 1)
  }

  function previousCreateWorldStep() {
    createWorldStep.value = Math.max(0, createWorldStep.value - 1)
  }

  async function submitCreateWorld() {
    if (!createWorldForm.worldId) {
      ElMessage.warning('请选择一个世界模板')
      return
    }

    await withMessage(async () => {
      const thinkStatus = createWorldForm.thinkStatus
      await api.createUserWorld({
        worldId: createWorldForm.worldId as number,
        name: createWorldForm.name.trim(),
        acitvePushStatus: createWorldForm.acitvePushStatus,
        dailyCompanionMode: createWorldForm.dailyCompanionMode,
        favorSystemStatus: createWorldForm.favorSystemStatus,
        eotDetectionStatus: thinkStatus ? false : createWorldForm.eotDetectionStatus,
        thinkStatus,
        addSpecialPrompt: thinkStatus && createWorldForm.addSpecialPrompt,
      })
      createWorldDialogVisible.value = false
      ElMessage.success('世界已创建')
      await loadWorlds()
    }, '创建世界失败')
  }

  function openStartStory() {
    storyForm.title = ''
    storyForm.theme = ''
    storyForm.currentScene = ''
    storyForm.opening = ''
    storyForm.characterIds = selectedCharacter.value
      ? [selectedCharacter.value.characterId]
      : characters.value.slice(0, 2).map((character) => character.characterId)
    startStoryDialogVisible.value = true
  }

  async function submitStartStory() {
    if (!selectedWorldId.value) return
    if (!storyForm.title.trim() || !storyForm.currentScene.trim()) {
      ElMessage.warning('请填写事件标题和当前场景')
      return
    }
    if (storyForm.characterIds.length === 0) {
      ElMessage.warning('至少选择一个参与角色')
      return
    }

    await withMessage(async () => {
      await api.startStory({
        userWorldId: selectedWorldId.value as number,
        title: storyForm.title.trim(),
        theme: storyForm.theme.trim(),
        currentScene: storyForm.currentScene.trim(),
        opening: storyForm.opening.trim(),
        characterIds: storyForm.characterIds,
      })
      startStoryDialogVisible.value = false
      ElMessage.success('新事件已开启')
      await loadWorldEvents(selectedWorldId.value as number)
    }, '开启事件失败')
  }

  function openAdvanceStory() {
    if (!activeStory.value) {
      ElMessage.warning('当前没有进行中的事件')
      return
    }
    storyAdvanceForm.transition = ''
    advanceStoryDialogVisible.value = true
  }

  async function submitAdvanceStory() {
    const storyEventId = activeStory.value?.storyEvent.id
    const userWorldId = selectedWorldId.value
    if (!storyEventId || !userWorldId) return
    if (!storyAdvanceForm.transition.trim()) {
      ElMessage.warning('请填写事件推进说明')
      return
    }

    storyActionLoading.value = true
    try {
      await api.advanceStory(storyEventId, {
        transition: storyAdvanceForm.transition.trim(),
      })
      advanceStoryDialogVisible.value = false
      ElMessage.success('事件已推进')
      await loadWorldEvents(userWorldId)
      if (selectedCharacter.value) {
        await loadHistory()
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '推进事件失败')
    } finally {
      storyActionLoading.value = false
    }
  }

  function openEndStory() {
    if (!activeStory.value) {
      ElMessage.warning('当前没有进行中的事件')
      return
    }
    storyEndForm.ending = ''
    endStoryDialogVisible.value = true
  }

  async function submitEndStory() {
    const storyEventId = activeStory.value?.storyEvent.id
    const userWorldId = selectedWorldId.value
    if (!storyEventId || !userWorldId) return

    storyActionLoading.value = true
    try {
      await api.endStory(storyEventId, {
        ending: storyEndForm.ending.trim(),
      })
      endStoryDialogVisible.value = false
      ElMessage.success('事件已结束')
      await loadWorldEvents(userWorldId)
      if (selectedCharacter.value) {
        await loadHistory()
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '结束事件失败')
    } finally {
      storyActionLoading.value = false
    }
  }

  function closeChatSocket() {
    chatSocketConnecting = null
    chatSocketUserWorldId = null
    lastSocketTypingContext = null
    if (chatSocket) {
      chatSocket.close()
      chatSocket = null
    }
  }

  function handleSocketMessage(event: MessageEvent<string>) {
    let data: ChatHistory
    try {
      data = JSON.parse(event.data) as ChatHistory
    } catch {
      return
    }

    if (!data || typeof data.type !== 'string' || typeof data.content !== 'string') {
      return
    }
    notifyPushedMessage(data)
    if (!isSelectedChatHistory(data)) {
      return
    }

    appendHistoryMessage(data, true)
    void scrollToBottom()
    if (messageRole(data) === 'assistant' && data.userWorldId && data.characterId) {
      void refreshCurrentChatStatus(data.userWorldId, data.characterId)
    }
  }

  function ensureChatSocket(userWorldId: number) {
    if (
      chatSocket &&
      chatSocketUserWorldId === userWorldId &&
      chatSocket.readyState === WebSocket.OPEN
    ) {
      return Promise.resolve(chatSocket)
    }
    if (chatSocketConnecting && chatSocketUserWorldId === userWorldId) {
      return chatSocketConnecting
    }

    closeChatSocket()
    const socket = createChatSocket(userWorldId)
    chatSocket = socket
    chatSocketUserWorldId = userWorldId

    chatSocketConnecting = new Promise<WebSocket>((resolve, reject) => {
      let settled = false

      const fail = (message: string) => {
        if (!settled) {
          settled = true
          chatSocketConnecting = null
          reject(new Error(message))
        }
      }

      socket.addEventListener('open', () => {
        settled = true
        chatSocketConnecting = null
        resolve(socket)
      }, { once: true })

      socket.addEventListener('message', handleSocketMessage)

      socket.addEventListener('error', () => {
        fail('WebSocket 连接失败')
      })

      socket.addEventListener('close', () => {
        if (chatSocket === socket) {
          chatSocket = null
          chatSocketUserWorldId = null
          chatSocketConnecting = null
        }
        fail('WebSocket 连接已关闭')
      })
    })

    return chatSocketConnecting
  }

  async function socketForTyping(userWorldId: number, isTyping: boolean) {
    if (isTyping) {
      return ensureChatSocket(userWorldId)
    }
    if (
      chatSocket &&
      chatSocketUserWorldId === userWorldId &&
      chatSocket.readyState === WebSocket.OPEN
    ) {
      return chatSocket
    }
    if (chatSocketConnecting && chatSocketUserWorldId === userWorldId) {
      return chatSocketConnecting
    }
    return null
  }

  async function sendSocketTyping(isTyping: boolean) {
    if (selectedWorldDetail.value?.thinkStatus !== false) {
      return
    }

    const payload = buildSelectedChatPayload('')
    if (!payload) {
      return
    }

    const contextKey = typingContextKey(payload)
    if (isTyping) {
      if (lastSocketTypingContext === contextKey) {
        return
      }
      lastSocketTypingContext = contextKey
    } else {
      if (lastSocketTypingContext !== contextKey) {
        return
      }
      lastSocketTypingContext = null
    }

    const version = ++typingSignalVersion
    try {
      const socket = await socketForTyping(payload.userWorldId, isTyping)
      if (!socket || socket.readyState !== WebSocket.OPEN || version !== typingSignalVersion) {
        return
      }

      socket.send(JSON.stringify({ ...payload, type: 'typing', isTyping }))
    } catch {
      if (isTyping && lastSocketTypingContext === contextKey) {
        lastSocketTypingContext = null
      }
    }
  }

  function handleComposerFocus() {
    if (isComposerTyping()) {
      void sendSocketTyping(true)
    }
  }

  function handleComposerCompositionChange(isComposing: boolean, value = messageInput.value) {
    messageInputComposing = isComposing
    void sendSocketTyping(isComposerTyping(value))
  }

  async function sendSocketChat(payload: ChatMessagePayload) {
    const socket = await ensureChatSocket(payload.userWorldId)
    if (socket.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket 尚未连接')
    }

    typingSignalVersion += 1
    lastSocketTypingContext = null
    socket.send(JSON.stringify({ ...payload, type: 'fragment', message: `${payload.message}\n` }))
    socket.send(JSON.stringify({ ...payload, type: 'typing', message: '', isTyping: false }))
  }

  async function sendStreamingChat(payload: ChatMessagePayload) {
    let assistantMessage: UiMessage | null = null
    let hasAssistantContent = false

    const ensureAssistantMessage = () => {
      if (!assistantMessage) {
        assistantMessage = {
          id: `assistant-${Date.now()}-${Math.random()}`,
          role: 'assistant',
          content: '',
        }
        messageList.value.push(assistantMessage)
      }
      return assistantMessage
    }

    const closeAssistantSegment = () => {
      if (assistantMessage?.content.trim()) {
        assistantMessage = null
      }
    }

    const appendThinking = (content?: string) => {
      const value = replaceAiPronouns(content || '正在整理记忆', 'thinking')
      const lastMessage = messageList.value[messageList.value.length - 1]
      if (lastMessage?.role === 'thinking') {
        lastMessage.content += value
      } else {
        messageList.value.push({
          id: `thinking-${Date.now()}-${Math.random()}`,
          role: 'thinking',
          content: value,
        })
      }
      void scrollToBottom()
    }

    await streamChat(payload, (chunk) => {
      if (chunk.type === 'thinking') {
        closeAssistantSegment()
        appendThinking(chunk.content)
        return
      }

      if (chunk.type === 'tool') {
        closeAssistantSegment()
        messageList.value.push({
          id: `tool-${Date.now()}-${Math.random()}`,
          role: 'tool',
          content: chunk.content || '调用了工具',
        })
        void scrollToBottom()
        return
      }

      if (chunk.type === 'reponse' || chunk.type === 'response') {
        const content = replaceAiPronouns(chunk.content || '', 'assistant')
        ensureAssistantMessage().content += content
        hasAssistantContent ||= Boolean(content.trim())
        void scrollToBottom()
      }
    })

    if (!hasAssistantContent) {
      const fallbackMessage = ensureAssistantMessage()
      fallbackMessage.content = '暂时没有收到角色回复。'
      fallbackMessage.complete = false
    }
  }

  async function sendMessage() {
    const content = messageInput.value.trim()
    const worldId = selectedTemplateWorldId.value
    const userWorldId = selectedWorldId.value
    const characterId = selectedCharacter.value?.characterId
    const isThinkingChat = Boolean(selectedWorldDetail.value?.thinkStatus)

    if (loading.sending || !content || !worldId || !userWorldId || !characterId) {
      return
    }

    const payload: ChatMessagePayload = {
      type: 'chat',
      worldId,
      userWorldId,
      characterId,
      message: content,
    }

    messageList.value.push({
      id: `user-${Date.now()}`,
      role: 'user',
      content,
      time: '刚刚',
    })
    ignoreNextInputTypingChange = true
    messageInput.value = ''
    loading.sending = true
    await scrollToBottom()

    try {
      if (isThinkingChat) {
        await sendStreamingChat(payload)
        await refreshCurrentChatStatus(userWorldId, characterId)
      } else {
        await sendSocketChat(payload)
      }
    } catch (error) {
      messageList.value.push({
        id: `assistant-error-${Date.now()}`,
        role: 'assistant',
        content: '发送失败，请稍后再试。',
        complete: false,
      })
      ElMessage.error(error instanceof Error ? error.message : '发送失败')
    } finally {
      loading.sending = false
      await scrollToBottom()
    }
  }

  async function withdrawLatestMessage() {
    const userWorldId = selectedWorldId.value
    const characterId = selectedCharacter.value?.characterId
    if (!userWorldId || !characterId || !canWithdrawLatestMessage.value) {
      return
    }

    loading.withdrawing = true
    try {
      await api.withdrawLatestMessage(userWorldId, characterId)
      ElMessage.success('已撤回上一轮消息')
      await Promise.all([loadHistory(), loadCharacters(userWorldId), loadWorldEvents(userWorldId)])
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '撤回失败')
    } finally {
      loading.withdrawing = false
    }
  }

  async function scrollToBottom() {
    await nextTick()
    if (messageScroller.value) {
      messageScroller.value.scrollTop = messageScroller.value.scrollHeight
    }
  }

  function resetSelection() {
    closeChatSocket()
    selectedWorld.value = null
    selectedWorldDetail.value = null
    selectedCharacter.value = null
    characters.value = []
    stories.value = []
    activeStory.value = null
    worldSave.value = null
    messageList.value = []
    sidebarMode.value = 'worlds'
  }

  function logout() {
    clearSession()
    session.token = ''
    session.id = null
    session.username = ''
    resetSelection()
    accountDialogVisible.value = false
    passwordDialogVisible.value = false
    createCharacterDialogVisible.value = false
    createCharacterTemplateDialogVisible.value = false
    characterDeleteDialogVisible.value = false
    worldDetailDialogVisible.value = false
    worldSettingsDialogVisible.value = false
    worldDeleteDialogVisible.value = false
    authMode.value = 'login'
    authDialogVisible.value = true
  }

  function handleUnauthorizedSession() {
    if (!session.token && authDialogVisible.value) {
      return
    }
    session.token = ''
    session.id = null
    session.username = ''
    resetSelection()
    accountDialogVisible.value = false
    passwordDialogVisible.value = false
    createWorldDialogVisible.value = false
    createTemplateDialogVisible.value = false
    createCharacterDialogVisible.value = false
    createCharacterTemplateDialogVisible.value = false
    characterDeleteDialogVisible.value = false
    worldDetailDialogVisible.value = false
    worldSettingsDialogVisible.value = false
    worldDeleteDialogVisible.value = false
    startStoryDialogVisible.value = false
    authMode.value = 'login'
    authDialogVisible.value = true
    ElMessage.warning('登录状态已失效，请重新登录')
  }

  onMounted(() => {
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorizedSession)
    if (isLoggedIn.value) {
      void loadAppData()
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorizedSession)
    if (authCodeTimer) {
      window.clearInterval(authCodeTimer)
    }
    if (passwordCodeTimer) {
      window.clearInterval(passwordCodeTimer)
    }
    closeChatSocket()
  })

  return {
    session,
    sidebarMode,
    authDialogVisible,
    authMode,
    authForm,
    authLoading,
    authCodeLoading,
    authCodeCooldown,
    accountDialogVisible,
    accountLoading,
    passwordDialogVisible,
    passwordLoading,
    passwordCodeLoading,
    passwordCodeCooldown,
    accountForm,
    passwordForm,
    loading,
    worldTemplates,
    userWorlds,
    selectedWorld,
    selectedWorldDetail,
    characters,
    selectedCharacter,
    stories,
    activeStory,
    worldSave,
    worldSaveActionLoading,
    storyDetailDialogVisible,
    storyDetailLoading,
    selectedStoryDetail,
    characterPanelCollapsed,
    createWorldDialogVisible,
    createWorldStep,
    createWorldForm,
    createTemplateDialogVisible,
    createTemplateMode,
    templateImageUploading,
    createTemplateImageFileName,
    createTemplateForm,
    createCharacterDialogVisible,
    createCharacterTemplateDialogVisible,
    characterTemplateMode,
    selectedEditCharacterTemplateId,
    characterDeleteDialogVisible,
    characterImageUploading,
    createCharacterImageFileName,
    characterCreating,
    characterPromptSaving,
    characterFavorSaving,
    characterDeleting,
    characterTemplateLoading,
    selectedWorldTemplate,
    worldCharacterTemplates,
    addCharacterForm,
    createCharacterForm,
    worldDetailDialogVisible,
    worldDetailLoading,
    worldDetails,
    worldDetailForm,
    worldSettingsDialogVisible,
    worldSettingsLoading,
    worldImporting,
    worldExporting,
    worldDeleteDialogVisible,
    worldDeleting,
    worldSettingsForm,
    startStoryDialogVisible,
    advanceStoryDialogVisible,
    endStoryDialogVisible,
    storyActionLoading,
    storyForm,
    storyAdvanceForm,
    storyEndForm,
    messageInput,
    messageList,
    messageScroller,
    isLoggedIn,
    hasSelectedWorld,
    hasSelectedCharacter,
    isWorldSelectionMode,
    selectedWorldId,
    selectedTemplateWorldId,
    canEditSelectedWorld,
    selectedWorldName,
    selectedCharacterName,
    createWorldStepIsLast,
    availableCharacterTemplates,
    editableCharacterTemplates,
    availableCharacterTemplateIds,
    selectedAddCharacterTemplate,
    selectedEditCharacterTemplate,
    selectedCreateTemplate,
    createTemplateDialogTitle,
    createTemplateSubmitLabel,
    createCharacterTemplateDialogTitle,
    createCharacterTemplateSubmitLabel,
    averageFavor,
    activeStoryTitle,
    selectedStoryCharacterIds,
    canWithdrawLatestMessage,
    isWorldActive,
    isCharacterActive,
    submitAuth,
    switchAuthMode,
    sendRegisterEmailCode,
    sendPasswordEmailCode,
    openAccountSettings,
    openPasswordSettings,
    submitAccountProfile,
    submitPassword,
    loadAppData,
    loadWorlds,
    loadWorldTemplates,
    refreshCurrentWorld,
    refreshWorkspace,
    selectWorld,
    openWorldOverview,
    submitWorldSave,
    submitWorldLoad,
    openStoryDetail,
    selectCharacter,
    openCreateWorld,
    openCreateTemplate,
    openEditWorldTemplate,
    handleTemplateImageChange,
    handleCharacterImageChange,
    submitCreateTemplate,
    openWorldDetailsFromTemplateDialog,
    openCreateCharacter,
    characterTemplateLabel,
    openCreateCharacterTemplate,
    openCreateCharacterTemplateForm,
    addFavorabilityRow,
    removeFavorabilityRow,
    submitCreateCharacter,
    updateCharacterPrompt,
    updateCharacterFavor,
    openDeleteCharacterConfirm,
    submitDeleteCharacter,
    submitCreateCharacterTemplate,
    openWorldDetails,
    openWorldSettings,
    submitWorldSettings,
    exportCurrentWorld,
    importWorldArchive,
    openDeleteWorldConfirm,
    submitDeleteWorld,
    submitWorldDetail,
    deleteWorldDetail,
    nextCreateWorldStep,
    previousCreateWorldStep,
    submitCreateWorld,
    openStartStory,
    submitStartStory,
    openAdvanceStory,
    submitAdvanceStory,
    openEndStory,
    submitEndStory,
    handleComposerFocus,
    handleComposerCompositionChange,
    sendMessage,
    withdrawLatestMessage,
    logout,
  }
}
