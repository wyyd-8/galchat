import assert from 'node:assert/strict'
import test from 'node:test'
import type { ReplyPlanItem } from '../api/types.ts'
import { replyPlanActorName, replyPlanSignature, shouldShowSavePlan, visibleReplyPlanItems } from './replyPlanState.ts'

const item = (actorId: number, order: number): ReplyPlanItem => ({
  actorType: 'character',
  actorId,
  order,
})

test('does not show save for the reply order that was loaded', () => {
  const loaded = replyPlanSignature([item(11, 1), item(22, 2)])

  assert.equal(shouldShowSavePlan(true, loaded, [item(11, 1), item(22, 2)]), false)
})

test('shows save after the reply order changes', () => {
  const loaded = replyPlanSignature([item(11, 1), item(22, 2)])

  assert.equal(shouldShowSavePlan(true, loaded, [item(22, 1), item(11, 2)]), true)
})

test('does not show save when the reply plan cannot be edited', () => {
  const loaded = replyPlanSignature([item(11, 1), item(22, 2)])

  assert.equal(shouldShowSavePlan(false, loaded, [item(22, 1), item(11, 2)]), false)
})

test('hides KP actions from the TRPG action order', () => {
  const player: ReplyPlanItem = { actorType: 'user', actorId: 101, order: 1 }
  const kp: ReplyPlanItem = { actorType: 'kp', order: 2 }

  assert.deepEqual(visibleReplyPlanItems('trpg', [player, kp]), [player])
})

test('uses the signed-in username for a user-controlled investigator', () => {
  const player: ReplyPlanItem = { actorType: 'user', actorId: 101, order: 1 }

  assert.equal(replyPlanActorName(player, '爱丽丝'), '爱丽丝')
})

test('keeps a KP-controlled combat NPC without exposing the KP label', () => {
  const npc: ReplyPlanItem = { actorType: 'kp', subjectCharacterId: 501, order: 1 }

  assert.deepEqual(visibleReplyPlanItems('trpg', [npc]), [npc])
  assert.equal(replyPlanActorName(npc, '爱丽丝'), 'NPC #501')
})
