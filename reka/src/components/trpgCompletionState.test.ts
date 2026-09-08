import { test } from 'node:test'
import assert from 'node:assert/strict'
import { completionDice } from './trpgCompletionState.ts'
import type { TrpgCompletionRoll } from '../api/types.ts'

test('team totals equal investigators and exclusive result categories', () => {
  const rolls: TrpgCompletionRoll[] = [
    { characterId: 1, turnNo: 1, checkName: '侦查', value: 1, target: 60, outcome: 'CRITICAL_SUCCESS' },
    { characterId: 1, turnNo: 2, checkName: '侦查', value: 100, target: 60, outcome: 'FUMBLE' },
    { characterId: 2, turnNo: 3, checkName: '潜行', value: 45, target: 60, outcome: 'SUCCESS' },
    { characterId: 2, turnNo: 4, checkName: '潜行', value: 90, target: 60, outcome: 'FAILURE' },
  ]
  assert.equal(completionDice(rolls, null).total, 4)
  assert.deepEqual(completionDice(rolls, null).counts.map((item) => item.count), [1, 1, 1, 1])
  assert.equal(completionDice(rolls, 1).total + completionDice(rolls, 2).total, 4)
  assert.equal(completionDice(rolls, 1).highlights.length, 2)
  assert.equal(completionDice(rolls, 2).highlights.length, 0)
  assert.equal(completionDice([], null).total, 0)
})
