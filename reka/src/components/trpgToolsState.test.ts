import assert from 'node:assert/strict'
import test from 'node:test'
import type { Character, InvestigatorCardSummary } from '../api/types.ts'
import { buildToolCharacterTargets } from './trpgToolsState.ts'

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
  assert.deepEqual(buildToolCharacterTargets(characters, cards), [
    {
      key: 'player',
      actorType: 'PLAYER',
      name: '玩家调查员',
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
