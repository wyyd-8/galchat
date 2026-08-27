import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick, ref } from 'vue'
import type { Character, CocSkill, GroupMessage, InvestigatorCardSummary, TrpgSave } from '../api/types.ts'
import * as trpgToolsState from './trpgToolsState.ts'

const { buildToolCharacterTargets, toolDialogContentClass } = trpgToolsState

const characters: Character[] = [
  {
    userWorldId: 7,
    characterId: 11,
    characterName: '艾琳',
    characterImage: '/images/erin.png',
  },
  {
    userWorldId: 7,
    characterId: 22,
    characterName: '罗伯特',
  },
]

const cards: InvestigatorCardSummary[] = [
  { cardId: 101, actorType: 'PLAYER', name: '玩家卡', checkValues: {} },
  { cardId: 102, actorType: 'BOT', participantId: 22, name: '罗伯特', checkValues: {} },
]

function groupMessage(id: number, content = `消息 ${id}`): GroupMessage {
  return {
    id,
    conversationId: 51,
    speakerType: 'character',
    speakerName: '艾琳',
    messageKind: 'dialogue',
    content,
    sequenceNo: id,
    status: 'completed',
  }
}

test('builds the character-card selector with the player first and each matching card state', () => {
  assert.deepEqual(buildToolCharacterTargets(characters, cards, [11, 22], '旅人甲'), [
    {
      key: 'player',
      actorType: 'PLAYER',
      name: '旅人甲',
      cardId: 101,
    },
    {
      key: 'character:11',
      actorType: 'BOT',
      participantId: 11,
      name: '艾琳',
      image: '/images/erin.png',
    },
    {
      key: 'character:22',
      actorType: 'BOT',
      participantId: 22,
      name: '罗伯特',
      cardId: 102,
    },
  ])
})

test('uses the signed-in username and AI character names for tool targets', () => {
  assert.deepEqual(
    buildToolCharacterTargets(characters, cards, [11, 22], '旅人甲').map((target) => target.name),
    ['旅人甲', '艾琳', '罗伯特'],
  )
})

test('excludes AI investigators that do not belong to the current TRPG run', () => {
  assert.deepEqual(buildToolCharacterTargets(characters, cards, [22], '旅人甲'), [
    {
      key: 'player',
      actorType: 'PLAYER',
      name: '旅人甲',
      cardId: 101,
    },
    {
      key: 'character:22',
      actorType: 'BOT',
      participantId: 22,
      name: '罗伯特',
      cardId: 102,
    },
  ])
})

test('selects the tools character target that owns a requested card id', () => {
  const preferredTargetKey = (trpgToolsState as typeof trpgToolsState & {
    preferredToolCharacterTargetKey?: (
      targets: ReturnType<typeof buildToolCharacterTargets>,
      cardId: number | null,
      fallbackKey: string,
    ) => string
  }).preferredToolCharacterTargetKey
  assert.ok(preferredTargetKey, 'TRPG tools should resolve a requested card to its character target')

  const targets = buildToolCharacterTargets(characters, cards, [11, 22], '旅人甲')
  assert.equal(preferredTargetKey(targets, 102, 'player'), 'character:22')
  assert.equal(preferredTargetKey(targets, 999, 'player'), 'player')
  assert.equal(preferredTargetKey(targets, null, 'character:11'), 'character:11')
})

test('uses the third-stage dialog width only while the character-card tab is selected', () => {
  assert.equal(toolDialogContentClass('card'), 'trpg-binding-dialog trpg-tools-character-dialog')
  assert.equal(toolDialogContentClass('status'), '')
  assert.equal(toolDialogContentClass('dice'), '')
})

test('builds all three rollback actions and keeps unavailable points disabled', () => {
  const actions = trpgToolsState.buildToolRollbackActions({
    turn: { available: true, savedAt: '2026-08-20T12:00:00', willDeleteManualSave: false, investigators: [] },
    scene: { available: false, willDeleteManualSave: false, investigators: [] },
    initial: { available: false, willDeleteManualSave: false, investigators: [] },
  })

  assert.deepEqual(actions.map(({ key, title, point }) => ({ key, title, available: point.available })), [
    { key: 'turn', title: '回退至行动轮开始', available: true },
    { key: 'scene', title: '回退至场景开始', available: false },
    { key: 'initial', title: '回退至初始状态', available: false },
  ])
})

test('orders manual and automatic restore points on one recovery timeline', () => {
  const save: TrpgSave = {
    id: 9,
    conversationId: 51,
    savedAt: '2026-08-20T12:05:00',
    remark: '书房门前',
    investigators: [],
  }
  const timeline = trpgToolsState.buildToolRecoveryTimeline(save, {
    turn: {
      available: true,
      savedAt: '2026-08-20T12:10:00',
      willDeleteManualSave: true,
      investigators: [],
    },
    scene: {
      available: true,
      savedAt: '2026-08-20T11:00:00',
      willDeleteManualSave: false,
      investigators: [],
    },
    initial: {
      available: false,
      willDeleteManualSave: false,
      investigators: [],
    },
  })

  assert.deepEqual(timeline.map(({ key, kind, title, available }) => ({
    key, kind, title, available,
  })), [
    { key: 'turn', kind: 'automatic', title: '行动轮开始', available: true },
    { key: 'load', kind: 'manual', title: '手动存档', available: true },
    { key: 'scene', kind: 'automatic', title: '主场景开始', available: true },
    { key: 'initial', kind: 'automatic', title: '初始状态', available: false },
  ])
  assert.equal(timeline[1]?.remark, '书房门前')
  assert.equal(timeline[0]?.willDeleteManualSave, true)
})

test('resolves investigator state from either a manual save or an automatic point', () => {
  const manualInvestigator = {
    characterId: 1,
    name: '林恩',
    hpCurrent: 8,
    hpMax: 12,
  }
  const automaticInvestigator = {
    characterId: 2,
    name: '米娅',
    sanCurrent: 42,
    sanMax: 60,
  }
  const save: TrpgSave = {
    id: 9,
    conversationId: 51,
    investigators: [manualInvestigator],
  }
  const actions = trpgToolsState.buildToolRollbackActions({
    turn: {
      available: true,
      willDeleteManualSave: false,
      investigators: [automaticInvestigator],
    },
    scene: { available: false, willDeleteManualSave: false, investigators: [] },
    initial: { available: false, willDeleteManualSave: false, investigators: [] },
  })

  assert.deepEqual(
    trpgToolsState.resolveToolRestoreInvestigators('load', save, actions),
    [manualInvestigator],
  )
  assert.deepEqual(
    trpgToolsState.resolveToolRestoreInvestigators('turn', save, actions),
    [automaticInvestigator],
  )
})

test('prioritizes terminal investigator conditions in restore previews', () => {
  assert.equal(trpgToolsState.restoreInvestigatorCondition({ dead: true, dying: true, unconscious: true }), '死亡')
  assert.equal(trpgToolsState.restoreInvestigatorCondition({ dying: true, unconscious: true }), '濒死')
  assert.equal(trpgToolsState.restoreInvestigatorCondition({ unconscious: true }), '昏迷')
  assert.equal(trpgToolsState.restoreInvestigatorCondition({}), null)
})

test('uses two retained and two deleted messages when loaded history covers the rollback boundary', () => {
  const preview = trpgToolsState.buildLoadedRollbackMessagePreview(
    [groupMessage(50), groupMessage(20), groupMessage(40), groupMessage(30), groupMessage(10)],
    30,
  )

  assert.deepEqual(preview, {
    retained: [groupMessage(20), groupMessage(30)],
    deleted: [groupMessage(40), groupMessage(50)],
    deletedMessagesOmitted: false,
  })
})

test('requires a boundary fetch when loaded history starts after the rollback boundary', () => {
  assert.equal(trpgToolsState.buildLoadedRollbackMessagePreview(
    [groupMessage(40), groupMessage(50)],
    30,
  ), null)
})

test('shows three fetched retained messages and omits deleted messages when history does not cover the boundary', () => {
  const preview = trpgToolsState.buildFetchedRollbackMessagePreview(
    [groupMessage(10), groupMessage(40), groupMessage(30), groupMessage(20)],
    30,
  )

  assert.deepEqual(preview, {
    retained: [groupMessage(10), groupMessage(20), groupMessage(30)],
    deleted: [],
    deletedMessagesOmitted: true,
  })
})

test('formats a hydrated dice message as dice plus its title', () => {
  const message: GroupMessage = {
    ...groupMessage(60),
    messageKind: 'dice_roll',
    content: '',
    diceRoll: {
      summary: {
        id: 9,
        conversationId: 51,
        reason: '侦查门锁',
        status: 'completed',
      },
      results: [],
    },
  }

  assert.equal(
    trpgToolsState.formatRollbackPreviewMessage(message),
    '掷骰：侦查门锁',
  )
})

test('does not fetch history when loaded messages already cover the rollback boundary', async () => {
  let fetchCount = 0
  const preview = await trpgToolsState.resolveRollbackMessagePreview(
    [groupMessage(20), groupMessage(30), groupMessage(40)],
    30,
    async () => {
      fetchCount += 1
      return []
    },
  )

  assert.equal(fetchCount, 0)
  assert.deepEqual(preview.retained.map((message) => message.id), [20, 30])
  assert.deepEqual(preview.deleted.map((message) => message.id), [40])
})

test('fetches exactly three messages ending at an uncovered rollback boundary', async () => {
  const requests: Array<{ beforeId: number, size: number }> = []
  const preview = await trpgToolsState.resolveRollbackMessagePreview(
    [groupMessage(40), groupMessage(50)],
    30,
    async (beforeId, size) => {
      requests.push({ beforeId, size })
      return [groupMessage(10), groupMessage(20), groupMessage(30)]
    },
  )

  assert.deepEqual(requests, [{ beforeId: 31, size: 3 }])
  assert.deepEqual(preview.retained.map((message) => message.id), [10, 20, 30])
  assert.equal(preview.deletedMessagesOmitted, true)
})

test('does not request history for the initial-state boundary', async () => {
  let fetchCount = 0
  const preview = await trpgToolsState.resolveRollbackMessagePreview(
    [groupMessage(10)],
    0,
    async () => {
      fetchCount += 1
      return []
    },
  )

  assert.equal(fetchCount, 0)
  assert.deepEqual(preview.retained, [])
  assert.equal(preview.deletedMessagesOmitted, true)
})

test('formats a UTC KP prompt timestamp in the selected local time zone', () => {
  const formatKpPromptUpdatedAt = (trpgToolsState as typeof trpgToolsState & {
    formatKpPromptUpdatedAt?: (value?: string, timeZone?: string) => string
  }).formatKpPromptUpdatedAt

  assert.ok(formatKpPromptUpdatedAt, 'TRPG tools should expose KP prompt timestamp formatting')
  assert.equal(
    formatKpPromptUpdatedAt('2026-08-20T08:30:00Z', 'Asia/Shanghai'),
    '2026-08-20 16:30',
  )
})

test('requires a second dialog only when rollback will delete the manual save', async () => {
  const open = ref(true)
  const selectedTab = ref('status')
  const confirmations = trpgToolsState.useToolRestoreConfirmation(open, selectedTab)

  confirmations.request('scene', true)
  assert.equal(confirmations.stage.value, 'primary')
  assert.equal(confirmations.advance(), false)
  assert.equal(confirmations.stage.value, 'delete-manual-save')
  assert.equal(confirmations.advance(), true)

  confirmations.clear()
  confirmations.request('load', true)
  assert.equal(confirmations.advance(), true)
})

test('clears the restore dialog when closing or switching tools', async () => {
  const open = ref(true)
  const selectedTab = ref('status')
  const confirmations = trpgToolsState.useToolRestoreConfirmation(open, selectedTab)
  confirmations.request('turn', false)

  selectedTab.value = 'dice'
  await nextTick()
  assert.equal(confirmations.action.value, null)

  confirmations.request('initial', true)
  open.value = false
  await nextTick()
  assert.equal(confirmations.action.value, null)
})

test('formats a CoC check rate as full, half, and fifth values', () => {
  const formatCheckRate = (trpgToolsState as typeof trpgToolsState & {
    formatCheckRate?: (value?: number) => string
  }).formatCheckRate

  assert.ok(formatCheckRate, 'TRPG tools should expose check-rate formatting')
  assert.equal(formatCheckRate(40), '40% / 20% / 8%')
  assert.equal(formatCheckRate(1), '1% / 0% / 0%')
  assert.equal(formatCheckRate(undefined), '—')
})

test('shows weapon risk details only for abnormal or unrecognized weapons', () => {
  const shouldShowWeaponRisk = (trpgToolsState as typeof trpgToolsState & {
    shouldShowWeaponRisk?: (weapon: { abnormal?: boolean, riskTags?: string[] }) => boolean
  }).shouldShowWeaponRisk

  assert.ok(shouldShowWeaponRisk, 'TRPG tools should expose the weapon risk display rule')
  assert.equal(shouldShowWeaponRisk({ abnormal: true, riskTags: ['显眼', '笨重'] }), true)
  assert.equal(shouldShowWeaponRisk({ abnormal: false, riskTags: ['未识别武器'] }), true)
  assert.equal(shouldShowWeaponRisk({ abnormal: false, riskTags: ['高噪声'] }), false)
  assert.equal(shouldShowWeaponRisk({ abnormal: false, riskTags: [] }), false)
})

test('turns each weapon risk tag into its corresponding player guidance', () => {
  const buildWeaponRiskGuidance = (trpgToolsState as typeof trpgToolsState & {
    buildWeaponRiskGuidance?: (riskTags?: string[]) => Array<{ tag: string, message: string }>
  }).buildWeaponRiskGuidance

  assert.ok(buildWeaponRiskGuidance, 'TRPG tools should expose weapon risk guidance')
  const guidance = buildWeaponRiskGuidance([
    '显眼', '高噪声', '笨重', '严格管制', '破坏现场', '未识别武器',
  ])

  assert.deepEqual(guidance.map((item) => item.tag), [
    '显眼', '高噪声', '笨重', '严格管制', '破坏现场', '未识别武器',
  ])
  assert.match(guidance[0]?.message || '', /公开携带|引起注意/)
  assert.match(guidance[1]?.message || '', /声响|暴露位置/)
  assert.match(guidance[2]?.message || '', /隐藏|狭窄空间/)
  assert.match(guidance[3]?.message || '', /当地法律|执法/)
  assert.match(guidance[4]?.message || '', /损坏线索|调查现场/)
  assert.match(guidance[5]?.message || '', /大型棍棒/)
  assert.match(guidance[5]?.message || '', /只能使用近战攻击/)
})

test('collapses unallocated specializations including combat groups into category rows', () => {
  const buildSkillDisplayItems = (trpgToolsState as typeof trpgToolsState & {
    buildSkillDisplayItems?: (skills: CocSkill[], activeGroup?: string | null) => Array<{
      kind: 'category' | 'skill', displayName: string, value?: number
    }>
  }).buildSkillDisplayItems
  assert.ok(buildSkillDisplayItems, 'TRPG tools should build skill display rows')

  const items = buildSkillDisplayItems([
    { id: 1, characterId: 9, displayName: '会计', category: '知识', baseValue: 5, value: 5 },
    { id: 2, characterId: 9, displayName: '科学', category: '科学', baseValue: 1, value: 1 },
    { id: 3, characterId: 9, displayName: '科学:天文学', category: '科学', specialization: '天文学', baseValue: 1, value: 1 },
    { id: 4, characterId: 9, displayName: '科学:地质学', category: '科学', specialization: '地质学', baseValue: 1, value: 40 },
    { id: 5, characterId: 9, displayName: '格斗:斧', category: '格斗', specialization: '斧', baseValue: 15, value: 15 },
    { id: 6, characterId: 9, displayName: '母语', category: '语言', baseValue: 60, value: 60 },
    { id: 7, characterId: 9, displayName: '斗殴', category: '格斗', baseValue: 25, value: 25 },
  ])

  assert.deepEqual(items.map(({ kind, displayName, value }) => ({ kind, displayName, value })), [
    { kind: 'skill', displayName: '会计', value: 5 },
    { kind: 'category', displayName: '科学', value: undefined },
    { kind: 'skill', displayName: '科学:地质学', value: 40 },
    { kind: 'category', displayName: '格斗', value: undefined },
    { kind: 'skill', displayName: '母语', value: 60 },
  ])
})

test('moves an opened category first and shows only every skill in that category', () => {
  const skills: CocSkill[] = [
    { id: 1, characterId: 9, displayName: '会计', category: '知识', baseValue: 5, value: 5 },
    { id: 2, characterId: 9, displayName: '科学', category: '科学', baseValue: 1, value: 1 },
    { id: 3, characterId: 9, displayName: '科学:天文学', category: '科学', specialization: '天文学', baseValue: 1, value: 1 },
    { id: 4, characterId: 9, displayName: '科学:地质学', category: '科学', specialization: '地质学', baseValue: 1, value: 40 },
    { id: 5, characterId: 9, displayName: '格斗:斧', category: '格斗', specialization: '斧', baseValue: 15, value: 15 },
  ]

  assert.deepEqual(trpgToolsState.buildSkillDisplayItems(skills, '科学')
    .map(({ kind, displayName, value }) => ({ kind, displayName, value })), [
    { kind: 'category', displayName: '科学', value: undefined },
    { kind: 'skill', displayName: '科学:天文学', value: 1 },
    { kind: 'skill', displayName: '科学:地质学', value: 40 },
  ])
})

test('clicking the opened skill category returns to the overview', () => {
  const nextSkillGroup = (trpgToolsState as typeof trpgToolsState & {
    nextSkillGroup?: (current: string | null, requested: string) => string | null
  }).nextSkillGroup
  assert.ok(nextSkillGroup, 'TRPG tools should expose skill-category navigation')

  assert.equal(nextSkillGroup(null, '科学'), '科学')
  assert.equal(nextSkillGroup('科学', '科学'), null)
  assert.equal(nextSkillGroup('科学', '格斗'), '格斗')
})

test('resolves a weapon success rate from its normalized skill name', () => {
  const resolveWeaponCheckValue = (trpgToolsState as typeof trpgToolsState & {
    resolveWeaponCheckValue?: (
      weapon: { name: string, skillName?: string },
      skills: Array<{ displayName: string, value: number }>,
    ) => number | undefined
  }).resolveWeaponCheckValue

  assert.ok(resolveWeaponCheckValue, 'TRPG tools should resolve weapon check values')
  const skills = [
    { displayName: '射击:手枪', value: 55 },
    { displayName: '格斗:斗殴', value: 65 },
  ]
  assert.equal(resolveWeaponCheckValue({ name: '左轮手枪', skillName: '射击：手枪' }, skills), 55)
  assert.equal(resolveWeaponCheckValue({ name: '徒手格斗' }, skills), 65)
  assert.equal(resolveWeaponCheckValue({ name: '未知武器' }, skills), undefined)
})
