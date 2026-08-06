import assert from 'node:assert/strict'
import test from 'node:test'
import type { ReplyPlanItem } from '../api/types.ts'
import { replyPlanSignature, shouldShowSavePlan } from './replyPlanState.ts'

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
