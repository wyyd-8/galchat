import assert from 'node:assert/strict'
import test from 'node:test'
import type { InvestigatorCardSummary } from '../api/types.ts'
import type { TrpgExecutionActor } from './trpgExecutionState.ts'
import * as explorationOverview from './trpgExplorationOverview.ts'

const actor: TrpgExecutionActor = {
  item: {
    order: 1,
    actorType: 'character',
    actorId: 99,
    subjectCharacterId: 501,
    subjectCharacterName: '林恩',
  },
  name: '林恩',
  status: 'running',
  statusLabel: '行动中',
  genericKp: false,
}

test('builds fixed investigation checks and the three strongest exploration specialties', () => {
  const buildExplorationHoverCard = (explorationOverview as typeof explorationOverview & {
    buildExplorationHoverCard?: (
      actor: TrpgExecutionActor,
      cards: InvestigatorCardSummary[],
    ) => unknown
  }).buildExplorationHoverCard
  assert.ok(buildExplorationHoverCard, 'exploration execution should expose investigator hover details')

  const cards: InvestigatorCardSummary[] = [{
    cardId: 501,
    actorType: 'PLAYER',
    name: '林恩',
    checkValues: {
      侦查: 70,
      聆听: 55,
      图书馆使用: 40,
      心理学: 75,
      潜行: 60,
      追踪: 50,
      说服: 45,
      斗殴: 90,
      母语: 80,
      LUCK: 45,
    },
    hpCurrent: 10,
    hpMax: 12,
    sanCurrent: 54,
    sanMax: 60,
    temporaryInsanity: true,
  }]

  assert.deepEqual(buildExplorationHoverCard(actor, cards), {
    name: '林恩',
    roleLabel: '调查员',
    statuses: ['临时疯狂'],
    resources: [
      { label: 'SAN', value: '54 / 60' },
      { label: '幸运', value: '45' },
      { label: 'HP', value: '10 / 12' },
    ],
    commonChecks: [
      { label: '侦查', value: '70' },
      { label: '聆听', value: '55' },
      { label: '图书馆', value: '40' },
    ],
    specialtyChecks: [
      { label: '心理学', value: '75' },
      { label: '潜行', value: '60' },
      { label: '追踪', value: '50' },
    ],
  })
})

test('keeps specialty selection deterministic and supports specialized exploration skills', () => {
  const buildExplorationHoverCard = (explorationOverview as typeof explorationOverview & {
    buildExplorationHoverCard?: (
      actor: TrpgExecutionActor,
      cards: InvestigatorCardSummary[],
    ) => { specialtyChecks: Array<{ label: string; value: string }> } | null
  }).buildExplorationHoverCard
  assert.ok(buildExplorationHoverCard)

  const card = buildExplorationHoverCard(actor, [{
    cardId: 501,
    actorType: 'BOT',
    name: '林恩',
    checkValues: {
      侦查: 25,
      聆听: 20,
      图书馆使用: 20,
      '科学:化学': 65,
      '艺术和手艺:摄影': 65,
      神秘学: 65,
      射击: 80,
    },
  }])

  assert.deepEqual(card?.specialtyChecks, [
    { label: '神秘学', value: '65' },
    { label: '科学:化学', value: '65' },
    { label: '艺术和手艺:摄影', value: '65' },
  ])
})

test('does not create exploration details for KP and NPC rows without an investigator card', () => {
  const buildExplorationHoverCard = (explorationOverview as typeof explorationOverview & {
    buildExplorationHoverCard?: (
      actor: TrpgExecutionActor,
      cards: InvestigatorCardSummary[],
    ) => unknown
  }).buildExplorationHoverCard
  assert.ok(buildExplorationHoverCard)

  assert.equal(buildExplorationHoverCard(actor, []), null)
})

test('treats non-combat action and knowledge skills as exploration specialties', () => {
  const card = explorationOverview.buildExplorationHoverCard(actor, [{
    cardId: 501,
    actorType: 'PLAYER',
    name: '林恩',
    checkValues: {
      侦查: 25,
      聆听: 20,
      图书馆使用: 20,
      攀爬: 80,
      估价: 70,
      克苏鲁神话: 60,
      心理学: 10,
    },
  }])

  assert.deepEqual(card?.specialtyChecks, [
    { label: '攀爬', value: '80' },
    { label: '估价', value: '70' },
    { label: '克苏鲁神话', value: '60' },
  ])
})
