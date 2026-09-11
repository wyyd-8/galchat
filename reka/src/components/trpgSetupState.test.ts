import assert from 'node:assert/strict'
import test from 'node:test'
import type { InvestigatorCardSummary } from '../api/types.ts'
import * as setupState from './trpgSetupState.ts'
import {
  buildBindingTargets,
  canCreateTrpgRun,
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

test('allows creating a TRPG run without selecting AI investigators', () => {
  assert.equal(canCreateTrpgRun('孤身调查', 3, false), true)
  assert.equal(canCreateTrpgRun('', 3, false), false)
  assert.equal(canCreateTrpgRun('孤身调查', 0, false), false)
  assert.equal(canCreateTrpgRun('孤身调查', 3, true), false)
})

test('selecting investigators preserves the independently viewed profile', () => {
  const first = toggleParticipantSelection([], 33, 11)
  const second = toggleParticipantSelection(first.selectedIds, first.previewId, 22)

  assert.deepEqual(second, { selectedIds: [11, 22], previewId: 33 })
})

test('deselecting an investigator preserves the viewed profile', () => {
  const result = toggleParticipantSelection([11, 22], 22, 11)

  assert.deepEqual(result, { selectedIds: [22], previewId: 22 })
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

test('presents three distinct creation methods while disabling AI generation for players', () => {
  const buildOptions = (setupState as unknown as {
    buildCharacterCardCreationMethods?: (actorType: 'PLAYER' | 'BOT') => unknown
  }).buildCharacterCardCreationMethods

  assert.deepEqual(buildOptions?.('BOT'), [
    { id: 'STEP', enabled: true },
    { id: 'AUTO', enabled: true },
    { id: 'IMPORT', enabled: true },
  ])
  assert.deepEqual(buildOptions?.('PLAYER'), [
    { id: 'STEP', enabled: true },
    { id: 'AUTO', enabled: false },
    { id: 'IMPORT', enabled: true },
  ])
})

test('fills every omitted stepwise background entry when preparing the frontend request', () => {
  const prepareBackground = (setupState as unknown as {
    prepareStepwiseBackgroundSubmission?: (
      categories: readonly string[],
      entries: Record<string, string>,
      keyConnectionCategory?: string,
    ) => { entries: Record<string, string>, keyConnectionCategory: string }
  }).prepareStepwiseBackgroundSubmission

  assert.equal(typeof prepareBackground, 'function')
  assert.deepEqual(prepareBackground?.(
    ['APPEARANCE', 'IDEOLOGY', 'SIGNIFICANT_PEOPLE', 'MEANINGFUL_LOCATIONS', 'TREASURED_POSSESSIONS', 'TRAITS'],
    {},
  ), {
    entries: {
      APPEARANCE: '（空）',
      IDEOLOGY: '（空）',
      SIGNIFICANT_PEOPLE: '（空）',
      MEANINGFUL_LOCATIONS: '（空）',
      TREASURED_POSSESSIONS: '（空）',
      TRAITS: '（空）',
    },
    keyConnectionCategory: 'IDEOLOGY',
  })
})

test('keeps an explicit key connection while filling other omitted background entries', () => {
  const prepareBackground = (setupState as unknown as {
    prepareStepwiseBackgroundSubmission?: (
      categories: readonly string[],
      entries: Record<string, string>,
      keyConnectionCategory?: string,
    ) => { entries: Record<string, string>, keyConnectionCategory: string }
  }).prepareStepwiseBackgroundSubmission

  assert.deepEqual(prepareBackground?.(
    ['APPEARANCE', 'IDEOLOGY', 'SIGNIFICANT_PEOPLE', 'TRAITS'],
    { SIGNIFICANT_PEOPLE: '老友艾伦' },
    'SIGNIFICANT_PEOPLE',
  ), {
    entries: {
      APPEARANCE: '（空）',
      IDEOLOGY: '（空）',
      SIGNIFICANT_PEOPLE: '老友艾伦',
      TRAITS: '（空）',
    },
    keyConnectionCategory: 'SIGNIFICANT_PEOPLE',
  })
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

test('loads an active draft when reopening the unbound player investigator', async () => {
  const restoredDraft = { draftId: 502, status: 'IN_PROGRESS' }

  const result = await loadBindingTargetContent(
    { key: 'player', actorType: 'PLAYER' },
    async () => ({ cardId: 999 }),
    async (participantId) => {
      assert.equal(participantId, undefined)
      return restoredDraft
    },
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
