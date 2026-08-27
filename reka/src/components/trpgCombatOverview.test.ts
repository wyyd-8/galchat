import assert from 'node:assert/strict'
import test from 'node:test'
import type { TrpgCombatParticipantOverview } from '../api/types.ts'
import type { TrpgExecutionActor } from './trpgExecutionState.ts'
import * as combatOverview from './trpgCombatOverview.ts'

const { buildCombatHoverCard } = combatOverview

const actor = (subjectCharacterId: number, actorId: number): TrpgExecutionActor => ({
  item: {
    order: 1,
    actorType: 'character',
    actorId,
    subjectCharacterId,
    subjectCharacterName: '林恩',
  },
  name: '林恩',
  status: 'running',
  statusLabel: '行动中',
  genericKp: false,
})

test('maps combat hover details by character-card id and shows investigator basics', () => {
  const overviews: TrpgCombatParticipantOverview[] = [{
    characterId: 501,
    name: '林恩',
    investigator: true,
    statuses: ['重伤', '处于掩护'],
    hpCurrent: 7,
    hpMax: 12,
    armor: 2,
    dex: 65,
    build: 0,
    mov: 8,
    damageBonus: '0',
  }]

  const card = buildCombatHoverCard(actor(501, 9999), overviews)

  assert.deepEqual(card, {
    name: '林恩',
    roleLabel: '调查员',
    statuses: ['重伤', '处于掩护'],
    metrics: [
      { label: 'HP', value: '7 / 12' },
      { label: '护甲', value: '2' },
      { label: 'DEX', value: '65' },
      { label: '体格', value: '0' },
      { label: 'MOV', value: '8' },
      { label: '伤害加值', value: '0' },
    ],
  })
})

test('never renders basic combat values for an npc', () => {
  const overviews: TrpgCombatParticipantOverview[] = [{
    characterId: 601,
    name: '食尸鬼',
    investigator: false,
    statuses: ['眩晕（剩余2回合）'],
    hpCurrent: 99,
    hpMax: 99,
    armor: 8,
    dex: 90,
    build: 3,
    mov: 12,
    damageBonus: '+2D6',
  }]

  const card = buildCombatHoverCard(actor(601, 42), overviews)

  assert.deepEqual(card, {
    name: '食尸鬼',
    roleLabel: 'NPC',
    statuses: ['眩晕（剩余2回合）'],
    metrics: [],
  })
})

test('only resolves investigator combatants to character-card navigation targets', () => {
  const investigatorCardId = (combatOverview as typeof combatOverview & {
    combatInvestigatorCardId?: (
      actor: TrpgExecutionActor,
      overviews: TrpgCombatParticipantOverview[],
    ) => number | null
  }).combatInvestigatorCardId
  assert.ok(investigatorCardId, 'combat execution should expose investigator card navigation targets')

  assert.equal(investigatorCardId(actor(501, 9999), [{
    characterId: 501,
    name: '林恩',
    investigator: true,
    statuses: [],
  }]), 501)
  assert.equal(investigatorCardId(actor(601, 42), [{
    characterId: 601,
    name: '食尸鬼',
    investigator: false,
    statuses: [],
  }]), null)
})
