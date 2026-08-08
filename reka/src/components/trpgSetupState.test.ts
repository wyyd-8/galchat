import assert from 'node:assert/strict'
import test from 'node:test'
import type { InvestigatorCardSummary } from '../api/types.ts'
import {
  buildBindingTargets,
  canAutoGenerateCard,
  decodeParticipantIds,
  encodeParticipantIds,
  hasMissingBindings,
  loadBindingTargetContent,
  resolveParticipantIds,
  toggleParticipantSelection,
} from './trpgSetupState.ts'

const card = (cardId: number, actorType: 'PLAYER' | 'BOT', participantId?: number): InvestigatorCardSummary => ({
  cardId,
  actorType,
  participantId,
  name: `调查员 ${cardId}`,
  checkValues: {},
})

test('adds multiple investigators and previews the most recently selected one', () => {
  const first = toggleParticipantSelection([], null, 11)
  const second = toggleParticipantSelection(first.selectedIds, first.previewId, 22)

  assert.deepEqual(second, { selectedIds: [11, 22], previewId: 22 })
})

test('clears the preview when any selected investigator is deselected', () => {
  const result = toggleParticipantSelection([11, 22], 22, 11)

  assert.deepEqual(result, { selectedIds: [22], previewId: null })
})

test('builds one player target followed by only the selected AI investigators', () => {
  const targets = buildBindingTargets([11, 22], [card(101, 'PLAYER'), card(102, 'BOT', 22)])

  assert.deepEqual(targets, [
    { key: 'player', actorType: 'PLAYER', boundCardId: 101 },
    { key: 'character:11', actorType: 'BOT', participantId: 11 },
    { key: 'character:22', actorType: 'BOT', participantId: 22, boundCardId: 102 },
  ])
})

test('reports that card binding is incomplete while any target has no card', () => {
  assert.equal(hasMissingBindings([11, 22], [card(101, 'PLAYER'), card(102, 'BOT', 22)]), true)
  assert.equal(hasMissingBindings([11, 22], [card(101, 'PLAYER'), card(102, 'BOT', 11), card(103, 'BOT', 22)]), false)
})

test('offers automatic generation only for an unbound AI investigator', () => {
  assert.equal(canAutoGenerateCard({ key: 'character:11', actorType: 'BOT', participantId: 11 }), true)
  assert.equal(canAutoGenerateCard({ key: 'player', actorType: 'PLAYER' }), false)
  assert.equal(canAutoGenerateCard({ key: 'character:11', actorType: 'BOT', participantId: 11, boundCardId: 101 }), false)
})

test('loads an active draft when reopening an unbound AI investigator', async () => {
  const restoredDraft = { draftId: 501, status: 'PREVIEW_READY' }

  const result = await loadBindingTargetContent(
    { key: 'character:11', actorType: 'BOT', participantId: 11 },
    async () => ({ cardId: 999 }),
    async () => restoredDraft,
  )

  assert.deepEqual(result, { card: null, draft: restoredDraft })
})

test('round trips distinct participant ids for reopening a run before its first turn', () => {
  assert.equal(encodeParticipantIds([22, 11, 22]), '[22,11]')
  assert.deepEqual(decodeParticipantIds('[22,11]'), [22, 11])
  assert.deepEqual(decodeParticipantIds('not-json'), [])
})

test('uses server conversation members so unbound AI investigators remain visible after reopening', () => {
  assert.deepEqual(resolveParticipantIds([11, 22], [], []), [11, 22])
  assert.deepEqual(resolveParticipantIds([11, 22], [33], [44]), [11, 22])
})
