import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
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
  WorldDetail,
  WorldTemplate,
} from '@/api/types'
import type { FavorabilityRow, SidebarMode, UiMessage } from '@/types/ui'
import { formatTime, splitMessageContent } from '@/utils/ui'

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
  const authForm = reactive({ email: '', password: '' })
  const authLoading = ref(false)
  const accountDialogVisible = ref(false)
  const accountLoading = ref(false)
  const passwordDialogVisible = ref(false)
  const passwordLoading = ref(false)
  const accountForm = reactive({
    username: '',
    email: '',
    birthday: '',
  })
  const passwordForm = reactive({
    email: '',
    oldPassword: '',
    newPassword: '',
    confirmPassword: '',
  })

  const loading = reactive({
    app: false,
    worlds: false,
    characters: false,
    events: false,
    history: false,
    sending: false,
  })

  const worldTemplates = ref<WorldTemplate[]>([])
  const userWorlds = ref<UserWorld[]>([])
  const selectedWorld = ref<UserWorld | null>(null)
  const selectedWorldDetail = ref<UserWorld | null>(null)
  const characters = ref<UserCharacter[]>([])
  const selectedCharacter = ref<UserCharacter | null>(null)
  const stories = ref<StoryListItem[]>([])
  const activeStory = ref<ActiveStory | null>(null)
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
    favorSystemStatus: 'NORMAL',
    eotDetectionStatus: true,
    thinkStatus: true,
    addSpecialPrompt: false,
  })
  const createTemplateDialogVisible = ref(false)
  const templateImageUploading = ref(false)
  const createTemplateForm = reactive({
    name: '',
    image: '',
    author: '',
    background: '',
    visible: true,
  })
  const createCharacterDialogVisible = ref(false)
  const createCharacterTemplateDialogVisible = ref(false)
  const characterImageUploading = ref(false)
  const characterCreating = ref(false)
  const characterPromptSaving = ref(false)
  const characterTemplateLoading = ref(false)
  const selectedWorldTemplate = ref<WorldTemplate | null>(null)
  const characterTemplateLabels = reactive<Record<number, string>>({})
  const addCharacterForm = reactive({
    characterId: undefined as number | undefined,
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
  let typingSignalVersion = 0

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
  const availableCharacterTemplateIds = computed(() =>
    (selectedWorldTemplate.value?.characterIds || []).filter((id) => !existingCharacterIds.value.has(id)),
  )
  const selectedCreateTemplate = computed(() =>
    worldTemplates.value.find((template) => template.id === createWorldForm.worldId),
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

  watch(messageInput, (value) => {
    if (ignoreNextInputTypingChange) {
      ignoreNextInputTypingChange = false
      return
    }

    void sendSocketTyping(value.length > 0)
  })

  watch(
    () => [
      selectedTemplateWorldId.value,
      selectedWorldId.value,
      selectedCharacter.value?.characterId,
      selectedWorldDetail.value?.thinkStatus,
    ],
    () => {
      if (messageInput.value.length > 0) {
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

  function toUiMessage(history: ChatHistory): UiMessage {
    const role = messageRole(history)
    return {
      id: `history-${history.id || `${role}-${Math.random()}`}`,
      role,
      content: history.content || (role === 'tool' ? '调用了角色记忆与状态工具' : ''),
      time: formatTime(history.timestamp),
    }
  }

  function toSplitUiMessages(history: ChatHistory, idPrefix = 'history'): UiMessage[] {
    const role = messageRole(history)
    const content = history.content || (role === 'tool' ? '调用了角色记忆与状态工具' : '')

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

  async function submitAuth() {
    if (!authForm.email.trim() || !authForm.password.trim()) {
      ElMessage.warning('请输入邮箱和密码')
      return
    }

    authLoading.value = true
    try {
      const isRegister = authMode.value === 'register'
      const result =
        isRegister
          ? await api.register(authForm.email.trim(), authForm.password)
          : await api.login(authForm.email.trim(), authForm.password)

      saveSession(result)
      session.token = result.token
      session.id = result.id
      session.username = result.username
      authDialogVisible.value = false
      authForm.password = ''
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
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
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
    if (!accountForm.username.trim() || !accountForm.email.trim()) {
      ElMessage.warning('请输入用户名和邮箱')
      return
    }

    accountLoading.value = true
    try {
      await api.updateUserInfo({
        username: accountForm.username.trim(),
        email: accountForm.email.trim(),
        birthday: accountForm.birthday || undefined,
      })
      syncSessionUser(accountForm.username.trim())
      ElMessage.success('账号资料已保存')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存账号资料失败')
    } finally {
      accountLoading.value = false
    }
  }

  async function submitPassword() {
    if (!passwordForm.email.trim() || !passwordForm.oldPassword || !passwordForm.newPassword) {
      ElMessage.warning('请输入邮箱、旧密码和新密码')
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
        oldPassword: passwordForm.oldPassword,
        newPassword: passwordForm.newPassword,
      })
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
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
    } finally {
      loading.characters = false
    }
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
    createWorldForm.favorSystemStatus = 'NORMAL'
    createWorldForm.thinkStatus = true
    createWorldForm.addSpecialPrompt = false
    createWorldForm.eotDetectionStatus = false
    createWorldStep.value = 0
    createWorldDialogVisible.value = true
  }

  function openCreateTemplate() {
    createTemplateForm.name = ''
    createTemplateForm.image = ''
    createTemplateForm.author = session.username || ''
    createTemplateForm.background = ''
    createTemplateForm.visible = true
    createTemplateDialogVisible.value = true
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
      await api.createWorldTemplate({
        name: createTemplateForm.name.trim(),
        image: createTemplateForm.image.trim(),
        author: createTemplateForm.author.trim(),
        background: createTemplateForm.background.trim(),
        visible: createTemplateForm.visible,
      })
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

  function openCreateCharacter() {
    addCharacterForm.characterId = undefined
    createCharacterDialogVisible.value = true
    void loadSelectedWorldTemplate()
  }

  function characterTemplateLabel(characterId: number) {
    return characterTemplateLabels[characterId] || `角色模板 #${characterId}`
  }

  async function loadSelectedWorldTemplate() {
    const worldId = selectedTemplateWorldId.value
    if (!worldId) {
      selectedWorldTemplate.value = null
      return null
    }

    characterTemplateLoading.value = true
    try {
      selectedWorldTemplate.value = await api.getWorldTemplate(worldId)
      return selectedWorldTemplate.value
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载角色模板失败')
      throw error
    } finally {
      characterTemplateLoading.value = false
    }
  }

  function openCreateCharacterTemplate() {
    createCharacterForm.name = ''
    createCharacterForm.image = ''
    createCharacterForm.background = ''
    createCharacterForm.personality = ''
    createCharacterForm.initFavor = 0
    createCharacterForm.favorabilityRows = []
    createCharacterTemplateDialogVisible.value = true
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

    characterCreating.value = true
    try {
      await api.addCharacter(userWorldId, addCharacterForm.characterId)
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
      const before = selectedWorldTemplate.value || (await loadSelectedWorldTemplate())
      const payload: CharacterTemplate = {
        name: createCharacterForm.name.trim(),
        image: createCharacterForm.image.trim(),
        background: createCharacterForm.background.trim(),
        personality: createCharacterForm.personality.trim(),
        initFavor: createCharacterForm.initFavor,
        favorability: parseFavorability(createCharacterForm.favorabilityRows),
      }
      await api.createCharacterTemplate(worldId, payload)
      await loadSelectedWorldTemplate()
      const beforeIds = new Set(before?.characterIds || [])
      const createdCharacterId = selectedWorldTemplate.value?.characterIds?.find((id) => !beforeIds.has(id))
      if (createdCharacterId) {
        characterTemplateLabels[createdCharacterId] = payload.name
      }
      createCharacterTemplateDialogVisible.value = false
      ElMessage.success('角色模板已创建')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '创建角色模板失败')
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
    if (!isSelectedChatHistory(data)) {
      return
    }

    appendHistoryMessage(data, true)
    void scrollToBottom()
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
    if (messageInput.value.length > 0) {
      void sendSocketTyping(true)
    }
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
      const value = content || '正在整理记忆'
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
          content: chunk.content || '调用了记忆检索或好感度工具',
        })
        void scrollToBottom()
        return
      }

      if (chunk.type === 'reponse' || chunk.type === 'response') {
        const content = chunk.content || ''
        ensureAssistantMessage().content += content
        hasAssistantContent ||= Boolean(content.trim())
        void scrollToBottom()
      }
    })

    if (!hasAssistantContent) {
      ensureAssistantMessage().content = '暂时没有收到角色回复。'
    }
  }

  async function sendMessage() {
    const content = messageInput.value.trim()
    const worldId = selectedTemplateWorldId.value
    const userWorldId = selectedWorldId.value
    const characterId = selectedCharacter.value?.characterId
    const isThinkingChat = Boolean(selectedWorldDetail.value?.thinkStatus)

    if ((isThinkingChat && loading.sending) || !content || !worldId || !userWorldId || !characterId) {
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
    if (isThinkingChat) {
      loading.sending = true
    }
    await scrollToBottom()

    try {
      if (isThinkingChat) {
        await sendStreamingChat(payload)
      } else {
        await sendSocketChat(payload)
      }
      await Promise.all([loadCharacters(userWorldId), loadWorldEvents(userWorldId)])
    } catch (error) {
      messageList.value.push({
        id: `assistant-error-${Date.now()}`,
        role: 'assistant',
        content: '发送失败，请稍后再试。',
      })
      ElMessage.error(error instanceof Error ? error.message : '发送失败')
    } finally {
      if (isThinkingChat) {
        loading.sending = false
      }
      await scrollToBottom()
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
    worldDetailDialogVisible.value = false
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
    worldDetailDialogVisible.value = false
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
    closeChatSocket()
  })

  return {
    session,
    sidebarMode,
    authDialogVisible,
    authMode,
    authForm,
    authLoading,
    accountDialogVisible,
    accountLoading,
    passwordDialogVisible,
    passwordLoading,
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
    storyDetailDialogVisible,
    storyDetailLoading,
    selectedStoryDetail,
    characterPanelCollapsed,
    createWorldDialogVisible,
    createWorldStep,
    createWorldForm,
    createTemplateDialogVisible,
    templateImageUploading,
    createTemplateForm,
    createCharacterDialogVisible,
    createCharacterTemplateDialogVisible,
    characterImageUploading,
    characterCreating,
    characterPromptSaving,
    characterTemplateLoading,
    selectedWorldTemplate,
    addCharacterForm,
    createCharacterForm,
    worldDetailDialogVisible,
    worldDetailLoading,
    worldDetails,
    worldDetailForm,
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
    availableCharacterTemplateIds,
    selectedCreateTemplate,
    averageFavor,
    activeStoryTitle,
    selectedStoryCharacterIds,
    isWorldActive,
    isCharacterActive,
    submitAuth,
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
    openStoryDetail,
    selectCharacter,
    openCreateWorld,
    openCreateTemplate,
    handleTemplateImageChange,
    handleCharacterImageChange,
    submitCreateTemplate,
    openCreateCharacter,
    characterTemplateLabel,
    openCreateCharacterTemplate,
    addFavorabilityRow,
    removeFavorabilityRow,
    submitCreateCharacter,
    updateCharacterPrompt,
    submitCreateCharacterTemplate,
    openWorldDetails,
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
    sendMessage,
    logout,
  }
}
