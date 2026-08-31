import assert from 'node:assert/strict'
import test from 'node:test'
import {
  filterStepwiseWeapons,
  restoreStepwiseWeaponCodes,
  stepwiseWeaponSelectionPayload,
} from './stepwiseWeaponCatalog.ts'
import type { CharacterCardCreationWeaponRule } from '@/api/types'

const catalog: CharacterCardCreationWeaponRule[] = [
  {
    code: 'SMALL_KNIFE', name: '小型刀具（折叠刀等）', skillName: '斗殴', damage: '1D4+DB',
    range: '接触', attacksPerRound: '1', eras: ['1920S', 'MODERN'], kind: 'MELEE',
    canImpale: true, abnormal: false, riskTags: [],
  },
  {
    code: 'WHIP', name: '长鞭', skillName: '格斗:鞭', damage: '1D3+半DB', range: '3m',
    attacksPerRound: '1', eras: ['1920S'], kind: 'MELEE', canImpale: false,
    abnormal: false, riskTags: ['显眼'],
  },
  {
    code: 'GLOCK_17', name: '9mm 格洛克17', skillName: '射击:手枪', damage: '1D10',
    range: '15m', attacksPerRound: '1（3）', ammoCapacity: 17, malfunction: '98',
    eras: ['MODERN'], kind: 'FIREARM', canImpale: true, abnormal: false, riskTags: ['高噪声'],
  },
]

test('filters the shared starting-weapon catalog by the selected era', () => {
  assert.deepEqual(filterStepwiseWeapons(catalog, '1920S').map((weapon) => weapon.code), [
    'SMALL_KNIFE', 'WHIP',
  ])
  assert.deepEqual(filterStepwiseWeapons(catalog, 'MODERN').map((weapon) => weapon.code), [
    'SMALL_KNIFE', 'GLOCK_17',
  ])
  assert.deepEqual(filterStepwiseWeapons(catalog, '现代').map((weapon) => weapon.code), [
    'SMALL_KNIFE', 'GLOCK_17',
  ])
})

test('submits only catalog codes instead of editable weapon statistics', () => {
  assert.deepEqual(stepwiseWeaponSelectionPayload([
    'GLOCK_17', 'SMALL_KNIFE', 'STUN_GUN', 'CHAINSAW',
  ]), [
    { code: 'GLOCK_17' },
    { code: 'SMALL_KNIFE' },
    { code: 'STUN_GUN' },
  ])
})

test('restores selected options from canonical weapons saved in a draft', () => {
  assert.deepEqual(restoreStepwiseWeaponCodes(catalog, [
    { name: '9mm 格洛克17' },
    { name: '小型刀具（折叠刀等）' },
    { name: '旧草稿中的自定义武器' },
  ]), ['GLOCK_17', 'SMALL_KNIFE'])
})
