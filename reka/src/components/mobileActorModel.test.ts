import assert from 'node:assert/strict'
import test from 'node:test'
import { sceneModelTarget, sceneModelPayload } from './mobileActorModel.ts'

const actor = (actorType: string, actorId?: number) => ({ actorType, actorId, subjectCharacterId: 99, order: 1 })
test('scene models target the replying character rather than its investigator card', () => {
  assert.deepEqual(sceneModelTarget(actor('character', 7)), { actorType: 'character', actorId: 7 })
  assert.deepEqual(sceneModelTarget(actor('kp', 99)), { actorType: 'kp' })
  assert.equal(sceneModelTarget(actor('user', 8)), null)
  assert.equal(sceneModelTarget(actor('character')), null)
})
test('switching models preserves manual control, supports default, and rejects missing connections', () => {
  const target = { actorType: 'character' as const, actorId: 7 }
  const runtime = { ...target, controlMode: 'MANUAL' as const, modelApiAvailable: true, modelApiId: 2 }
  assert.deepEqual(sceneModelPayload(target, runtime, '4', [{ id: 4 }]), { ...target, controlMode: 'MANUAL', modelApiId: 4 })
  assert.deepEqual(sceneModelPayload(target, runtime, '', []), { ...target, controlMode: 'MANUAL', modelApiId: undefined })
  assert.equal(sceneModelPayload(target, runtime, '2', [{ id: 4 }]), null)
  assert.deepEqual(sceneModelPayload({ actorType: 'kp' }, undefined, '', []), { actorType: 'kp', controlMode: 'MODEL', modelApiId: undefined })
})
