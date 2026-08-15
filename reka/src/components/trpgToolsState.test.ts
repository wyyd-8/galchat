import assert from 'node:assert/strict'
import test from 'node:test'
import { nextTick, ref } from 'vue'
import type { Character, InvestigatorCardSummary } from '../api/types.ts'
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

test('uses the third-stage dialog width only while the character-card tab is selected', () => {
  assert.equal(toolDialogContentClass('card'), 'trpg-binding-dialog trpg-tools-character-dialog')
  assert.equal(toolDialogContentClass('status'), '')
  assert.equal(toolDialogContentClass('dice'), '')
})

test('clears pending destructive confirmations when the tools dialog closes', async () => {
  const open = ref(true)
  const selectedTab = ref('status')
  const confirmations = trpgToolsState.useToolConfirmations(open, selectedTab)
  confirmations.confirmRollback.value = true

  open.value = false
  await nextTick()

  assert.equal(confirmations.confirmRollback.value, false)
})

test('clears pending destructive confirmations when switching tools', async () => {
  const open = ref(true)
  const selectedTab = ref('status')
  const confirmations = trpgToolsState.useToolConfirmations(open, selectedTab)
  confirmations.confirmRollback.value = true
  confirmations.confirmLoad.value = true

  selectedTab.value = 'dice'
  await nextTick()

  assert.equal(confirmations.confirmRollback.value, false)
  assert.equal(confirmations.confirmLoad.value, false)
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
