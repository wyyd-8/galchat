<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  Compass,
  Delete,
  EditPen,
  House,
  Plus,
  Refresh,
  Setting,
  User,
} from '@element-plus/icons-vue'
import {
  api,
  clearSession,
  createChatSocket,
  currentSession,
  saveSession,
  streamChat,
  UNAUTHORIZED_EVENT,
  uploadImage,
} from './api/client'
import type {
  ActiveStory,
  CharacterTemplate,
  ChatHistory,
  ChatMessagePayload,
  StoryListItem,
  UserCharacter,
  UserWorld,
  WorldDetail,
  WorldTemplate,
} from './api/types'

type SidebarMode = 'worlds' | 'characters'

interface UiMessage {
  id: string
  role: 'user' | 'assistant' | 'thinking' | 'tool' | 'story'
  content: string
  time?: string
}

interface FavorabilityRow {
  id: string
  threshold: number | undefined
  prompt: string
}

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
const storyForm = reactive({
  title: '',
  theme: '',
  currentScene: '',
  opening: '',
  characterIds: [] as number[],
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
const selectedStoryCharacterIds = computed(() =>
  new Set(activeStory.value?.characters.map((character) => character.characterId) || []),
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

function imageStyle(image?: string) {
  return image ? { backgroundImage: `url(${image})` } : {}
}

function firstText(text?: string) {
  return text?.trim().charAt(0) || 'G'
}

function isWorldActive(world: UserWorld) {
  return selectedWorld.value?.id === world.id
}

function isCharacterActive(character: UserCharacter) {
  return selectedCharacter.value?.characterId === character.characterId
}

function favorTone(value?: number) {
  const favor = value ?? 0
  if (favor >= 80) return 'success'
  if (favor >= 50) return 'primary'
  if (favor >= 20) return 'warning'
  return 'danger'
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
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

function splitMessageContent(content: string) {
  const parts = content
    .split(/\r?\n/)
    .map((part) => part.trim())
    .filter(Boolean)
  return parts.length > 0 ? parts : [content]
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
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">G</div>
        <div>
          <h1>GalChat</h1>
          <p>角色世界会话台</p>
        </div>
      </div>

      <el-radio-group v-model="sidebarMode" class="mode-switch" size="large">
        <el-radio-button value="worlds">
          <el-icon><Compass /></el-icon>
          世界
        </el-radio-button>
        <el-radio-button value="characters" :disabled="!hasSelectedWorld">
          <el-icon><User /></el-icon>
          角色
        </el-radio-button>
      </el-radio-group>

      <div class="sidebar-scroll">
        <template v-if="sidebarMode === 'worlds'">
          <div class="sidebar-title">
            <span>已有世界</span>
            <el-button :icon="Refresh" text circle @click="loadWorlds" />
          </div>

          <button
            v-for="world in userWorlds"
            :key="world.id"
            class="nav-item"
            :class="{ active: isWorldActive(world) }"
            @click="selectWorld(world)"
          >
            <span class="avatar" :style="imageStyle(world.image)">
              <span v-if="!world.image">{{ firstText(world.name) }}</span>
            </span>
            <span class="nav-copy">
              <strong>{{ world.name }}</strong>
              <small>进入角色选择</small>
            </span>
          </button>

          <el-empty
            v-if="!loading.worlds && userWorlds.length === 0"
            description="还没有创建世界"
            :image-size="72"
          />
        </template>

        <template v-else>
          <button class="back-row" @click="sidebarMode = 'worlds'">
            <el-icon><ArrowLeft /></el-icon>
            {{ selectedWorldName }}
          </button>

          <div class="sidebar-title">
            <span>选择角色</span>
            <el-button :icon="Refresh" text circle @click="refreshCurrentWorld" />
          </div>

          <button
            v-for="character in characters"
            :key="character.characterId"
            class="nav-item character-nav"
            :class="{ active: isCharacterActive(character) }"
            @click="selectCharacter(character)"
          >
            <span class="avatar" :style="imageStyle(character.characterImage)">
              <span v-if="!character.characterImage">{{ firstText(character.characterName) }}</span>
            </span>
            <span class="nav-copy">
              <strong>{{ character.characterName }}</strong>
              <small>{{ character.lastChatContent || '尚未开始对话' }}</small>
            </span>
            <el-tag :type="favorTone(character.favorValue)" size="small" round>
              {{ character.favorValue ?? 0 }}
            </el-tag>
          </button>

          <el-empty
            v-if="!loading.characters && characters.length === 0"
            description="该世界还没有角色"
            :image-size="72"
          />
        </template>
      </div>

      <div class="user-dock">
        <button v-if="isLoggedIn" class="user-card" @click="openAccountSettings">
          <span class="avatar user-avatar">{{ firstText(session.username) }}</span>
          <span>
            <strong>{{ session.username || '已登录用户' }}</strong>
            <small>账号设置</small>
          </span>
        </button>
        <el-button v-else type="primary" class="login-button" @click="authDialogVisible = true">
          登录 / 注册
        </el-button>
        <el-dropdown v-if="isLoggedIn" trigger="click">
          <el-button :icon="Setting" circle />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="openAccountSettings">账号设置</el-dropdown-item>
              <el-dropdown-item @click="openPasswordSettings">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <main class="workspace" v-loading="loading.app">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">
            <span v-if="isWorldSelectionMode || !hasSelectedWorld">世界入口</span>
            <span v-else-if="!hasSelectedCharacter">世界概览</span>
            <span v-else>实时聊天</span>
          </p>
          <h2>
            <span v-if="isWorldSelectionMode || !hasSelectedWorld">选择或创建一个世界</span>
            <span v-else-if="!hasSelectedCharacter">{{ selectedWorldName }}</span>
            <span v-else>{{ selectedCharacterName }}</span>
          </h2>
        </div>

        <div class="header-actions">
          <el-tag v-if="!isWorldSelectionMode && hasSelectedWorld" effect="plain" round>
            <el-icon><House /></el-icon>
            {{ selectedWorldName }}
          </el-tag>
          <el-button
            v-if="!isWorldSelectionMode && hasSelectedWorld && canEditSelectedWorld"
            :icon="EditPen"
            @click="openWorldDetails"
          >
            修改世界设定
          </el-button>
          <el-button
            v-if="!isWorldSelectionMode && hasSelectedWorld && canEditSelectedWorld"
            :icon="Plus"
            @click="openCreateCharacterTemplate"
          >
            创建角色模板
          </el-button>
          <el-button
            v-if="!isWorldSelectionMode && hasSelectedWorld"
            :icon="Plus"
            type="primary"
            @click="openCreateCharacter"
          >
            创建新角色
          </el-button>
          <el-button :icon="Refresh" circle @click="refreshWorkspace" />
        </div>
      </header>

      <section v-if="isWorldSelectionMode || !hasSelectedWorld" class="world-stage">
        <div class="section-panel">
          <div class="section-heading">
            <div>
              <h3>已有世界</h3>
              <p>继续你的世界、角色关系和剧情进度。</p>
            </div>
            <el-button :icon="Plus" type="primary" @click="openCreateWorld()">创建世界</el-button>
          </div>

          <div class="world-grid">
            <button
              v-for="world in userWorlds"
              :key="world.id"
              class="world-card"
              @click="selectWorld(world)"
            >
              <span class="world-cover" :style="imageStyle(world.image)">
                <span v-if="!world.image">{{ firstText(world.name) }}</span>
              </span>
              <span>
                <strong>{{ world.name }}</strong>
                <small>选择后进入角色列表</small>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>

          <el-empty
            v-if="!loading.worlds && userWorlds.length === 0"
            description="你还没有自己的世界"
          />
        </div>

        <div class="section-panel">
          <div class="section-heading">
            <div>
              <h3>可创建世界</h3>
              <p>从后端世界模板创建用户世界。</p>
            </div>
          </div>

          <div class="template-list">
            <article v-for="template in worldTemplates" :key="template.id" class="template-row">
              <span class="avatar large" :style="imageStyle(template.image)">
                <span v-if="!template.image">{{ firstText(template.name) }}</span>
              </span>
              <div>
                <h4>{{ template.name }}</h4>
                <p>{{ template.background || '暂无背景简介，创建后可在后端补充世界详情。' }}</p>
              </div>
              <el-button type="primary" plain @click="openCreateWorld(template)">
                创建
              </el-button>
            </article>
          </div>
        </div>
      </section>

      <section v-else-if="!hasSelectedCharacter" class="overview-stage">
        <div class="metric-strip">
          <article>
            <span>角色数</span>
            <strong>{{ characters.length }}</strong>
          </article>
          <article>
            <span>平均好感</span>
            <strong>{{ averageFavor }}</strong>
          </article>
          <article>
            <span>故事事件</span>
            <strong>{{ stories.length }}</strong>
          </article>
          <article>
            <span>进行中</span>
            <strong>{{ activeStory ? 1 : 0 }}</strong>
          </article>
        </div>

        <div class="overview-grid">
          <div class="section-panel">
            <div class="section-heading">
              <div>
                <h3>角色好感度概况</h3>
                <p>选择右侧角色后进入聊天主窗口。</p>
              </div>
            </div>

            <div class="favor-list">
              <button
                v-for="character in characters"
                :key="character.characterId"
                class="favor-row"
                @click="selectCharacter(character)"
              >
                <span class="avatar" :style="imageStyle(character.characterImage)">
                  <span v-if="!character.characterImage">{{ firstText(character.characterName) }}</span>
                </span>
                <span class="favor-copy">
                  <strong>{{ character.characterName }}</strong>
                  <el-progress
                    :percentage="Math.max(0, Math.min(100, character.favorValue ?? 0))"
                    :stroke-width="8"
                    :show-text="false"
                  />
                </span>
                <span class="favor-number">{{ character.favorValue ?? 0 }}</span>
              </button>
            </div>
          </div>

          <div class="section-panel">
            <div class="section-heading">
              <div>
                <h3>事件总览</h3>
                <p>{{ activeStoryTitle }}</p>
              </div>
              <el-button :icon="Plus" type="primary" @click="openStartStory">开启新事件</el-button>
            </div>

            <article v-if="activeStory" class="active-story">
              <el-tag type="success" effect="dark" round>进行中</el-tag>
              <h4>{{ activeStory.storyEvent.title }}</h4>
              <p>{{ activeStory.storyEvent.currentScene || activeStory.storyEvent.opening }}</p>
              <div class="story-characters">
                <el-tag
                  v-for="character in characters.filter((item) => selectedStoryCharacterIds.has(item.characterId))"
                  :key="character.characterId"
                  round
                >
                  {{ character.characterName }}
                </el-tag>
              </div>
            </article>

            <div class="story-list">
              <button v-for="story in stories" :key="story.id" class="story-row">
                <span>{{ story.title }}</span>
                <el-icon><ArrowRight /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </section>

      <section v-else class="chat-stage">
        <div class="chat-window">
          <div ref="messageScroller" class="messages" v-loading="loading.history">
            <div v-if="messageList.length === 0" class="empty-chat">
              <el-icon><ChatDotRound /></el-icon>
              <h3>和 {{ selectedCharacterName }} 开始对话</h3>
              <p>角色会结合世界背景、历史记忆、剧情事件和好感度回应。</p>
            </div>

            <article
              v-for="message in messageList"
              :key="message.id"
              class="message"
              :class="message.role"
            >
              <div v-if="message.role === 'thinking'" class="thinking-content">
                <p>{{ message.content }}</p>
              </div>
              <div v-else-if="message.role === 'tool'" class="tool-line">
                <span>{{ message.content }}</span>
              </div>
              <div v-else class="message-bubble">
                <p>{{ message.content }}</p>
                <small v-if="message.time">{{ message.time }}</small>
              </div>
            </article>
          </div>

          <div class="composer">
            <el-input
              v-model="messageInput"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              resize="none"
              placeholder="输入给角色的消息"
              @focus="handleComposerFocus"
              @keydown.enter.exact.prevent="sendMessage"
            />
            <el-button type="primary" :loading="loading.sending" @click="sendMessage">
              发送
            </el-button>
          </div>
        </div>
      </section>
    </main>

    <aside
      v-if="!isWorldSelectionMode && hasSelectedCharacter"
      class="character-panel"
      :class="{ collapsed: characterPanelCollapsed }"
    >
      <button class="collapse-button" @click="characterPanelCollapsed = !characterPanelCollapsed">
        <el-icon>
          <ArrowRight v-if="!characterPanelCollapsed" />
          <ArrowLeft v-else />
        </el-icon>
      </button>

      <template v-if="!characterPanelCollapsed && selectedCharacter">
        <span class="avatar portrait" :style="imageStyle(selectedCharacter.characterImage)">
          <span v-if="!selectedCharacter.characterImage">
            {{ firstText(selectedCharacter.characterName) }}
          </span>
        </span>
        <h3>{{ selectedCharacter.characterName }}</h3>
        <p class="panel-muted">{{ selectedWorldName }}</p>

        <div class="panel-stat">
          <span>好感度</span>
          <strong>{{ selectedCharacter.favorValue ?? 0 }}</strong>
        </div>
        <el-progress
          :percentage="Math.max(0, Math.min(100, selectedCharacter.favorValue ?? 0))"
          :stroke-width="10"
        />

        <div class="info-block">
          <h4>最近对话</h4>
          <p class="recent-chat-content">{{ selectedCharacter.lastChatContent || '暂无最近对话' }}</p>
          <small>{{ formatTime(selectedCharacter.lastChatTime) }}</small>
        </div>

        <div class="info-block">
          <h4>当前事件</h4>
          <p>{{ activeStory?.storyEvent.title || '暂无进行中的事件' }}</p>
          <small>{{ activeStory?.storyEvent.currentScene }}</small>
        </div>
      </template>
    </aside>

    <el-dialog v-model="authDialogVisible" width="420px" :close-on-click-modal="false">
      <template #header>
        <div class="dialog-title">
          <h3>{{ authMode === 'login' ? '登录 GalChat' : '注册 GalChat' }}</h3>
          <p>登录后即可读取你的世界、角色和聊天历史。</p>
        </div>
      </template>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="邮箱">
          <el-input
            v-model="authForm.email"
            autocomplete="email"
            inputmode="email"
            placeholder="请输入邮箱"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="authForm.password"
            type="password"
            autocomplete="current-password"
            show-password
            @keydown.enter="submitAuth"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="authMode = authMode === 'login' ? 'register' : 'login'">
          {{ authMode === 'login' ? '去注册' : '去登录' }}
        </el-button>
        <el-button type="primary" :loading="authLoading" @click="submitAuth">
          {{ authMode === 'login' ? '登录' : '注册' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="accountDialogVisible" title="账号设置" width="560px">
      <el-form label-position="top" v-loading="accountLoading">
        <div class="account-form-grid">
          <el-form-item label="用户名">
            <el-input v-model="accountForm.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="accountForm.email" autocomplete="email" placeholder="请输入邮箱" />
          </el-form-item>
        </div>
        <el-form-item label="生日">
          <el-date-picker
            v-model="accountForm.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="accountDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="accountLoading" @click="submitAccountProfile">
          保存账号
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="520px">
      <el-form label-position="top" v-loading="passwordLoading">
        <el-form-item label="邮箱">
          <el-input v-model="passwordForm.email" autocomplete="email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="旧密码">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <div class="account-form-grid">
          <el-form-item label="新密码">
            <el-input v-model="passwordForm.newPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordLoading" @click="submitPassword">
          更新密码
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createWorldDialogVisible" title="创建世界" width="620px">
      <el-steps :active="createWorldStep" finish-status="success" simple class="create-steps">
        <el-step title="模板" />
        <el-step title="好感" />
        <el-step title="主动" />
        <el-step title="思考" />
        <el-step title="输入" />
      </el-steps>

      <el-form label-position="top" class="create-world-form">
        <section v-if="createWorldStep === 0" class="create-step-panel">
          <el-form-item label="世界模板">
            <el-select v-model="createWorldForm.worldId" filterable placeholder="选择模板">
              <el-option
                v-for="template in worldTemplates"
                :key="template.id"
                :label="template.name"
                :value="template.id"
              />
            </el-select>
            <p class="field-help">
              世界模板提供世界背景、封面和默认角色来源；创建世界时会记录所选模板 ID，并用于后续聊天上下文构建。
            </p>
          </el-form-item>

          <el-button class="create-template-button" plain :icon="Plus" @click="openCreateTemplate">
            创建新的模板
          </el-button>

          <el-form-item label="世界名称">
            <el-input v-model="createWorldForm.name" placeholder="留空则使用模板名称" />
            <p class="field-help">
              名称只用于你的世界列表展示，不会改变原始模板内容。
            </p>
          </el-form-item>

          <div v-if="selectedCreateTemplate" class="selected-template">
            <span class="avatar large" :style="imageStyle(selectedCreateTemplate.image)">
              <span v-if="!selectedCreateTemplate.image">
                {{ firstText(selectedCreateTemplate.name) }}
              </span>
            </span>
            <div>
              <strong>{{ selectedCreateTemplate.name }}</strong>
              <p>{{ selectedCreateTemplate.background || '暂无背景简介' }}</p>
            </div>
          </div>
        </section>

        <section v-else-if="createWorldStep === 1" class="create-step-panel">
          <el-form-item label="好感度提升难度">
            <el-radio-group v-model="createWorldForm.favorSystemStatus" class="option-stack">
              <el-radio value="EASY" border>
                简单
                <span>面对陌生人，人们总是倾向于信任，而非怀疑。角色更容易被善意、陪伴和选择打动。</span>
              </el-radio>
              <el-radio value="NORMAL" border>
                标准
                <span>关系会随着稳定互动自然推进。好感变化克制但可感知，适合大多数日常和剧情向世界。</span>
              </el-radio>
              <el-radio value="HARD" border>
                困难
                <span>信任需要更长时间建立。角色会更看重持续行动、关键承诺和明确选择。</span>
              </el-radio>
            </el-radio-group>
            <p class="field-help">
              难度只影响角色关系系统中的好感变化速度，不会改变聊天原文。
            </p>
          </el-form-item>
        </section>

        <section v-else-if="createWorldStep === 2" class="create-step-panel">
          <el-form-item label="主动聊天功能">
            <el-switch v-model="createWorldForm.acitvePushStatus" active-text="开启" inactive-text="关闭" />
            <p class="field-help">
              开启后，系统可根据聊天中提及的事件触发后续主动关怀；主动聊天会记录聊天提及事件，仅用于主动聊天功能。
            </p>
          </el-form-item>
        </section>

        <section v-else-if="createWorldStep === 3" class="create-step-panel">
          <el-form-item label="思考模式">
            <el-switch v-model="createWorldForm.thinkStatus" active-text="开启" inactive-text="关闭" />
            <p class="field-help">
              开启后会以流式方式展示思考与回复过程；此模式不支持多条对话合并，优化输入会自动关闭。
            </p>
          </el-form-item>
          <el-form-item label="角色沉浸思考">
            <el-switch
              v-model="createWorldForm.addSpecialPrompt"
              :disabled="!createWorldForm.thinkStatus"
              active-text="开启"
              inactive-text="关闭"
            />
            <p class="field-help">
              开启后，思考过程会更偏向角色第一人称内心独白；仅在思考模式下生效。
            </p>
            <p class="field-help danger-help">
              注意：此功能会导致角色难以主动调用工具，从而发生记忆与世界观详细缺失、事件检索失效、好感度增加困难等问题，请谨慎开启。
            </p>
          </el-form-item>
        </section>

        <section v-else class="create-step-panel">
          <el-form-item label="优化输入">
            <el-switch
              v-model="createWorldForm.eotDetectionStatus"
              :disabled="createWorldForm.thinkStatus"
              active-text="开启"
              inactive-text="关闭"
            />
            <p class="field-help">
              开启后会判断用户输入是否完成，能更快得到响应，但可能出现“抢答”的情况；如果开启思考模式，该字段会固定为 false。
            </p>
          </el-form-item>
        </section>
      </el-form>

      <template #footer>
        <el-button @click="createWorldDialogVisible = false">取消</el-button>
        <el-button :disabled="createWorldStep === 0" @click="previousCreateWorldStep">上一步</el-button>
        <el-button v-if="!createWorldStepIsLast" type="primary" @click="nextCreateWorldStep">
          下一步
        </el-button>
        <el-button v-else type="primary" @click="submitCreateWorld">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createTemplateDialogVisible" title="创建新的世界模板" width="760px">
      <el-form label-position="top" class="template-form">
        <div class="template-form-grid">
          <el-form-item label="模板名称">
            <el-input v-model="createTemplateForm.name" placeholder="例如：雾港学院" />
            <p class="field-help">
              模板名称会出现在可创建世界列表中，并作为模板基础信息保存。
            </p>
          </el-form-item>

          <el-form-item label="作者">
            <el-input v-model="createTemplateForm.author" placeholder="留空也可以创建" />
            <p class="field-help">
              作者用于标记模板来源，该信息会随模板一同保存。
            </p>
          </el-form-item>
        </div>

        <el-form-item label="封面图片">
          <div class="upload-row">
            <el-input v-model="createTemplateForm.image" placeholder="上传后自动回填图片 URL" />
            <label class="upload-button">
              <input type="file" accept="image/*" @change="handleTemplateImageChange" />
              <span>{{ templateImageUploading ? '上传中' : '上传图片' }}</span>
            </label>
          </div>
          <p class="field-help">
            图片会通过后端上传接口保存，并将返回 URL 写入模板；图片 URL 会用于世界与模板展示。
          </p>
        </el-form-item>

        <el-form-item label="世界背景">
          <el-input
            v-model="createTemplateForm.background"
            type="textarea"
            :autosize="{ minRows: 5, maxRows: 8 }"
            placeholder="描述世界观、时代背景、核心冲突和关键规则"
          />
          <p class="field-help">
            世界背景会进入角色聊天上下文，请不要写入不希望角色长期参考的隐私信息。
          </p>
        </el-form-item>

        <el-form-item label="可见性">
          <el-switch v-model="createTemplateForm.visible" active-text="公开" inactive-text="私有" />
          <p class="field-help">
            公开模板可在模板列表中被看见，私有模板仅用于当前用户可访问范围。角色模板会在世界创建完成后添加，并由系统自动绑定到对应世界模板。
          </p>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createTemplateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateImageUploading" @click="submitCreateTemplate">
          创建模板
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createCharacterDialogVisible" title="创建新角色" width="520px">
      <el-form label-position="top" v-loading="characterTemplateLoading">
        <el-form-item label="角色模板">
          <el-select
            v-model="addCharacterForm.characterId"
            filterable
            placeholder="选择当前世界的角色模板"
          >
            <el-option
              v-for="characterId in availableCharacterTemplateIds"
              :key="characterId"
              :label="characterTemplateLabel(characterId)"
              :value="characterId"
            />
          </el-select>
          <p class="field-help">
            新角色只能从当前世界模板已有的角色模板创建；已经添加到该世界的角色不会再次显示。
          </p>
        </el-form-item>

        <el-empty
          v-if="!characterTemplateLoading && availableCharacterTemplateIds.length === 0"
          description="当前世界没有可添加的角色模板"
          :image-size="72"
        />
      </el-form>

      <template #footer>
        <el-button @click="createCharacterDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="availableCharacterTemplateIds.length === 0"
          :loading="characterCreating || characterTemplateLoading"
          @click="submitCreateCharacter"
        >
          添加角色
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createCharacterTemplateDialogVisible" title="创建角色模板" width="720px">
      <el-form label-position="top" class="template-form">
        <div class="template-form-grid">
          <el-form-item label="模板名称">
            <el-input v-model="createCharacterForm.name" placeholder="例如：林澈" />
          </el-form-item>
          <el-form-item label="初始好感">
            <el-input-number v-model="createCharacterForm.initFavor" :min="0" :max="100" />
          </el-form-item>
        </div>

        <el-form-item label="角色图片">
          <div class="upload-row">
            <el-input v-model="createCharacterForm.image" placeholder="上传后自动回填图片 URL" />
            <label class="upload-button">
              <input type="file" accept="image/*" @change="handleCharacterImageChange" />
              <span>{{ characterImageUploading ? '上传中' : '上传图片' }}</span>
            </label>
          </div>
        </el-form-item>

        <el-form-item label="角色背景">
          <el-input
            v-model="createCharacterForm.background"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="角色经历、身份、与世界的关系"
          />
        </el-form-item>

        <el-form-item label="性格设定">
          <el-input
            v-model="createCharacterForm.personality"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="角色说话方式、价值观、行为倾向"
          />
        </el-form-item>

        <el-form-item label="好感阶段">
          <div class="favorability-editor">
            <el-button plain :icon="Plus" @click="addFavorabilityRow">
              添加好感度提示词
            </el-button>
            <div class="favorability-list">
              <div
                v-for="row in createCharacterForm.favorabilityRows"
                :key="row.id"
                class="favorability-row"
              >
                <el-input-number
                  v-model="row.threshold"
                  :min="0"
                  :max="100"
                  placeholder="值"
                />
                <el-input
                  v-model="row.prompt"
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 5 }"
                  resize="none"
                  placeholder="该好感度下的角色提示词"
                />
                <el-button :icon="Delete" text type="danger" circle @click="removeFavorabilityRow(row.id)" />
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createCharacterTemplateDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="characterCreating || characterImageUploading"
          @click="submitCreateCharacterTemplate"
        >
          创建模板
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="worldDetailDialogVisible" title="修改世界设定" width="760px">
      <div class="world-detail-dialog" v-loading="worldDetailLoading">
        <div class="detail-list">
          <article v-for="detail in worldDetails" :key="detail.id" class="detail-row">
            <div>
              <h4>{{ detail.about || '未命名设定' }}</h4>
              <p>{{ detail.details }}</p>
            </div>
            <el-button :icon="Delete" text type="danger" circle @click="deleteWorldDetail(detail)" />
          </article>
          <el-empty
            v-if="!worldDetailLoading && worldDetails.length === 0"
            description="还没有世界设定"
            :image-size="72"
          />
        </div>

        <div class="dialog-section">
          <div class="dialog-section-title">
            <h4>添加设定</h4>
            <p>新增内容会进入该世界模板的详情库。</p>
          </div>
          <el-form label-position="top">
            <el-form-item label="设定主题">
              <el-input v-model="worldDetailForm.about" placeholder="例如：学院禁区" />
            </el-form-item>
            <el-form-item label="设定内容">
              <el-input
                v-model="worldDetailForm.details"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 7 }"
                placeholder="写下这个设定的规则、事实或背景"
              />
            </el-form-item>
          </el-form>
        </div>
      </div>

      <template #footer>
        <el-button @click="worldDetailDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" :loading="worldDetailLoading" @click="submitWorldDetail">
          添加设定
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="startStoryDialogVisible" title="开启新事件" width="560px">
      <el-form label-position="top">
        <el-form-item label="事件标题">
          <el-input v-model="storyForm.title" placeholder="例如：雨夜旧约" />
        </el-form-item>
        <el-form-item label="主题">
          <el-input v-model="storyForm.theme" placeholder="事件的情绪或目标" />
        </el-form-item>
        <el-form-item label="当前场景">
          <el-input
            v-model="storyForm.currentScene"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="角色正在面对的地点、状态和冲突"
          />
        </el-form-item>
        <el-form-item label="开场描述">
          <el-input
            v-model="storyForm.opening"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="可选，留空时后端会生成开场"
          />
        </el-form-item>
        <el-form-item label="参与角色">
          <el-select
            v-model="storyForm.characterIds"
            multiple
            placeholder="选择参与事件的角色"
          >
            <el-option
              v-for="character in characters"
              :key="character.characterId"
              :label="character.characterName"
              :value="character.characterId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startStoryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStartStory">开启事件</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 284px minmax(0, 1fr) auto;
  background:
    linear-gradient(135deg, rgba(24, 91, 116, 0.08), transparent 34%),
    linear-gradient(315deg, rgba(189, 74, 94, 0.08), transparent 36%),
    #f5f7fb;
  color: #202734;
}

.sidebar {
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 16px;
  border-right: 1px solid rgba(42, 52, 71, 0.1);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(18px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, #285c74, #c6576a);
}

.brand h1,
.brand p,
.workspace-header h2,
.workspace-header p,
.section-heading h3,
.section-heading p {
  margin: 0;
}

.brand h1 {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0;
}

.brand p,
.sidebar-title,
.nav-copy small,
.panel-muted,
.section-heading p,
.world-card small,
.template-row p,
.dialog-title p {
  color: #697386;
}

.mode-switch {
  width: 100%;
  margin-bottom: 18px;
}

.mode-switch :deep(.el-radio-button) {
  flex: 1;
}

.mode-switch :deep(.el-radio-button__inner) {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.sidebar-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding-right: 3px;
}

.sidebar-title,
.section-heading,
.header-actions,
.user-dock,
.back-row,
.story-row,
.favor-row,
.world-card,
.template-row {
  display: flex;
  align-items: center;
}

.sidebar-title {
  justify-content: space-between;
  font-size: 13px;
  margin: 8px 4px 10px;
}

.nav-item,
.back-row {
  width: 100%;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.nav-item {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  text-align: left;
  margin-bottom: 6px;
}

.nav-item:hover,
.nav-item.active,
.back-row:hover {
  background: #edf4f6;
}

.nav-item.active {
  outline: 1px solid rgba(40, 92, 116, 0.28);
}

.character-nav .nav-copy small {
  display: block;
  max-width: 126px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-copy {
  min-width: 0;
}

.nav-copy strong {
  display: block;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.avatar {
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background:
    linear-gradient(135deg, rgba(40, 92, 116, 0.22), rgba(198, 87, 106, 0.2)),
    #e7edf2;
  background-size: cover;
  background-position: center;
  color: #285c74;
  font-weight: 800;
}

.avatar.large {
  width: 52px;
  height: 52px;
}

.avatar.portrait {
  width: 88px;
  height: 88px;
  margin: 6px auto 14px;
  font-size: 28px;
}

.user-avatar {
  width: 34px;
  height: 34px;
}

.back-row {
  gap: 8px;
  padding: 10px;
  text-align: left;
  font-weight: 700;
}

.user-dock {
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid rgba(42, 52, 71, 0.08);
}

.user-card {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  padding: 8px;
  border-radius: 8px;
  background: #f3f6f8;
  text-align: left;
}

.user-card strong,
.user-card small {
  display: block;
}

.user-card strong {
  font-weight: 700;
}

.user-card small {
  color: #7a8494;
}

.login-button {
  flex: 1;
}

.workspace {
  min-width: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 24px;
  overflow: hidden;
}

.workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.eyebrow {
  color: #c6576a;
  font-size: 13px;
  font-weight: 700;
}

.workspace-header h2 {
  margin-top: 2px;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 0;
}

.header-actions {
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.world-stage,
.overview-stage,
.chat-stage {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.world-stage,
.overview-stage {
  display: grid;
  gap: 18px;
}

.section-panel {
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.84);
  padding: 18px;
  box-shadow: 0 18px 45px rgba(33, 43, 54, 0.06);
}

.section-heading {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-heading h3 {
  font-size: 18px;
  font-weight: 800;
}

.world-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.world-card,
.template-row,
.favor-row,
.story-row {
  width: 100%;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
}

.world-card {
  min-height: 94px;
  gap: 12px;
  padding: 12px;
  text-align: left;
  cursor: pointer;
}

.world-card:hover,
.template-row:hover,
.favor-row:hover,
.story-row:hover {
  border-color: rgba(40, 92, 116, 0.34);
  box-shadow: 0 12px 28px rgba(33, 43, 54, 0.08);
}

.world-cover {
  width: 70px;
  height: 70px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(40, 92, 116, 0.18), rgba(198, 87, 106, 0.22)),
    #e7edf2;
  background-size: cover;
  background-position: center;
  font-size: 22px;
  font-weight: 800;
  color: #285c74;
}

.world-card strong,
.world-card small {
  display: block;
}

.world-card strong,
.template-row h4 {
  font-weight: 800;
}

.world-card .el-icon {
  margin-left: auto;
}

.template-list {
  display: grid;
  gap: 10px;
}

.template-row {
  gap: 14px;
  padding: 14px;
}

.template-row div {
  min-width: 0;
  flex: 1;
}

.template-row h4,
.template-row p {
  margin: 0;
}

.template-row p {
  margin-top: 4px;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-strip article {
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
  padding: 16px;
}

.metric-strip span,
.panel-stat span {
  display: block;
  color: #697386;
  font-size: 13px;
}

.metric-strip strong,
.panel-stat strong {
  display: block;
  margin-top: 4px;
  font-size: 28px;
  font-weight: 800;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
  gap: 18px;
}

.favor-list {
  display: grid;
  gap: 10px;
}

.favor-row {
  gap: 12px;
  padding: 12px;
  cursor: pointer;
  text-align: left;
}

.favor-copy {
  min-width: 0;
  flex: 1;
}

.favor-copy strong {
  display: block;
  margin-bottom: 8px;
  font-weight: 800;
}

.favor-number {
  font-weight: 800;
  color: #285c74;
}

.active-story {
  padding: 16px;
  border-radius: 8px;
  background: #f0f7f3;
}

.active-story h4,
.active-story p {
  margin: 10px 0 0;
}

.active-story h4 {
  font-size: 18px;
  font-weight: 800;
}

.active-story p {
  color: #4d5968;
}

.story-characters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
}

.story-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.story-row {
  justify-content: space-between;
  padding: 12px;
  cursor: pointer;
}

.chat-stage {
  display: flex;
}

.chat-window {
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  overflow: hidden;
}

.messages {
  min-height: 0;
  overflow: auto;
  padding: 22px;
}

.empty-chat {
  height: 100%;
  display: grid;
  place-items: center;
  align-content: center;
  color: #697386;
  text-align: center;
}

.empty-chat .el-icon {
  width: 56px;
  height: 56px;
  margin-bottom: 12px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #edf4f6;
  color: #285c74;
  font-size: 28px;
}

.empty-chat h3,
.empty-chat p {
  margin: 0;
}

.empty-chat h3 {
  color: #202734;
  font-weight: 800;
}

.empty-chat p {
  margin-top: 6px;
}

.message {
  display: flex;
  margin-bottom: 12px;
}

.message.user {
  justify-content: flex-end;
}

.message.thinking {
  margin: 4px 0 8px;
}

.message.tool {
  margin: 12px 0;
}

.message-bubble {
  max-width: min(680px, 76%);
  border-radius: 8px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid rgba(42, 52, 71, 0.1);
}

.message.user .message-bubble {
  color: #fff;
  background: #285c74;
  border-color: #285c74;
}

.message.story .message-bubble {
  max-width: 560px;
  color: #586273;
  background: #f5f7f9;
  font-size: 13px;
}

.thinking-content {
  max-width: min(680px, 76%);
  padding: 0 4px;
  color: #9aa3af;
  font-size: 12px;
  line-height: 1.55;
}

.tool-line {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #7a8494;
  font-size: 12px;
}

.tool-line::before,
.tool-line::after {
  content: '';
  height: 1px;
  flex: 1;
  background: rgba(122, 132, 148, 0.2);
}

.message-bubble p,
.message-bubble small,
.thinking-content p {
  margin: 0;
  white-space: pre-wrap;
}

.message-bubble small {
  display: block;
  margin-top: 6px;
  opacity: 0.7;
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  padding: 14px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
  background: #fff;
}

.character-panel {
  width: 292px;
  height: 100vh;
  position: sticky;
  top: 0;
  padding: 22px 18px;
  border-left: 1px solid rgba(42, 52, 71, 0.1);
  background: rgba(255, 255, 255, 0.9);
  transition: width 0.2s ease;
}

.character-panel.collapsed {
  width: 54px;
  padding: 18px 8px;
}

.collapse-button {
  width: 34px;
  height: 34px;
  border: 1px solid rgba(42, 52, 71, 0.12);
  border-radius: 8px;
  display: grid;
  place-items: center;
  margin-left: auto;
  background: #fff;
  cursor: pointer;
}

.character-panel h3,
.panel-muted {
  margin: 0;
  text-align: center;
}

.character-panel h3 {
  font-size: 20px;
  font-weight: 800;
}

.panel-stat {
  margin: 22px 0 8px;
}

.info-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
}

.info-block h4,
.info-block p,
.info-block small {
  margin: 0;
}

.info-block h4 {
  font-size: 14px;
  font-weight: 800;
}

.info-block p {
  margin-top: 8px;
  color: #3f4958;
}

.recent-chat-content {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 5;
}

.info-block small {
  display: block;
  margin-top: 6px;
  color: #7a8494;
}

.dialog-title h3,
.dialog-title p {
  margin: 0;
}

.dialog-title h3 {
  font-weight: 800;
}

.dialog-title p {
  margin-top: 4px;
}

.create-steps {
  margin-bottom: 18px;
}

.create-world-form,
.template-form {
  max-height: 58vh;
  overflow: auto;
  padding-right: 4px;
}

.create-step-panel {
  min-height: 260px;
}

.field-help {
  width: 100%;
  margin: 8px 0 0;
  color: #697386;
  font-size: 13px;
  line-height: 1.55;
}

.danger-help {
  color: #c03535;
}

.create-template-button {
  width: 100%;
  margin-bottom: 18px;
}

.selected-template {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #f7fafc;
}

.selected-template strong,
.selected-template p {
  margin: 0;
}

.selected-template strong {
  font-weight: 800;
}

.selected-template p {
  margin-top: 4px;
  color: #697386;
}

.option-stack {
  width: 100%;
  display: grid;
  gap: 10px;
}

.option-stack :deep(.el-radio) {
  width: 100%;
  height: auto;
  align-items: flex-start;
  padding: 12px;
  margin-right: 0;
  white-space: normal;
}

.option-stack :deep(.el-radio__label) {
  display: grid;
  gap: 4px;
  color: #202734;
  font-weight: 800;
}

.option-stack :deep(.el-radio__label span) {
  color: #697386;
  font-size: 13px;
  font-weight: 400;
}

.template-form-grid,
.account-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.account-form-grid {
  align-items: start;
}

.account-form-grid :deep(.el-date-editor),
.el-form-item :deep(.el-date-editor) {
  width: 100%;
}

.dialog-section {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
}

.dialog-section-title h4,
.dialog-section-title p,
.detail-row h4,
.detail-row p {
  margin: 0;
}

.dialog-section-title {
  margin-bottom: 14px;
}

.dialog-section-title h4,
.detail-row h4 {
  font-size: 15px;
  font-weight: 800;
}

.dialog-section-title p,
.detail-row p {
  margin-top: 4px;
  color: #697386;
}

.world-detail-dialog {
  max-height: 62vh;
  overflow: auto;
  padding-right: 4px;
}

.detail-list {
  display: grid;
  gap: 10px;
}

.detail-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 12px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
}

.detail-row p {
  white-space: pre-wrap;
}

.favorability-editor {
  width: 100%;
  display: grid;
  gap: 10px;
}

.favorability-list {
  display: grid;
  gap: 8px;
}

.favorability-row {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
}

.favorability-row :deep(.el-input-number) {
  width: 100%;
}

.upload-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}

.upload-button {
  position: relative;
  min-width: 92px;
  height: 32px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #285c74;
  background: #fff;
  cursor: pointer;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

@media (max-width: 1100px) {
  .app-shell {
    grid-template-columns: 244px minmax(0, 1fr);
  }

  .character-panel {
    position: fixed;
    right: 0;
    z-index: 10;
    box-shadow: -18px 0 40px rgba(33, 43, 54, 0.12);
  }

  .overview-grid,
  .metric-strip,
  .template-form-grid,
  .account-form-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 760px) {
  .app-shell {
    display: block;
  }

  .sidebar,
  .workspace {
    height: auto;
    position: static;
  }

  .sidebar {
    min-height: 420px;
  }

  .workspace {
    padding: 16px;
  }

  .workspace-header,
  .section-heading {
    display: block;
  }

  .header-actions,
  .section-heading .el-button {
    margin-top: 12px;
  }

  .overview-grid,
  .metric-strip,
  .template-form-grid,
  .account-form-grid {
    grid-template-columns: 1fr;
  }

  .composer {
    grid-template-columns: 1fr;
  }

  .favorability-row {
    grid-template-columns: 1fr;
  }
}
</style>
