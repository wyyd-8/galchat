import assert from 'node:assert/strict'
import test from 'node:test'
import * as derived from './cocModuleCharacterDerived.ts'

test('fills blank derived values and keeps them following primary attributes', () => {
  const character: derived.EditableCharacterValues = {
    str: 50, con: 60, siz: 65, dex: 65, pow: 70,
    hpMax: undefined, sanMax: undefined, mpMax: undefined,
    damageBonus: undefined, build: undefined, mov: undefined,
  }
  const automatic = derived.automaticDerivedFields(character)

  derived.syncAutomaticDerivedValues(character, automatic)
  assert.deepEqual({
    hp: character.hpMax,
    san: character.sanMax,
    mp: character.mpMax,
    damageBonus: character.damageBonus,
    build: character.build,
    mov: character.mov,
  }, { hp: 12, san: 70, mp: 14, damageBonus: '0', build: 0, mov: 8 })

  character.con = 70
  character.pow = 80
  derived.syncAutomaticDerivedValues(character, automatic)
  assert.equal(character.hpMax, 13)
  assert.equal(character.sanMax, 80)
  assert.equal(character.mpMax, 16)
})

test('does not overwrite a derived value after that field was manually edited', () => {
  const character: derived.EditableCharacterValues = {
    str: 50, con: 60, siz: 65, dex: 65, pow: 70,
    hpMax: undefined, sanMax: undefined, mpMax: undefined,
    damageBonus: undefined, build: undefined, mov: undefined,
  }
  const automatic = derived.automaticDerivedFields(character)
  derived.syncAutomaticDerivedValues(character, automatic)

  character.hpMax = 20
  automatic.delete('hpMax')
  character.con = 70
  character.pow = 80
  derived.syncAutomaticDerivedValues(character, automatic)

  assert.equal(character.hpMax, 20)
  assert.equal(character.sanMax, 80)
  assert.equal(character.mpMax, 16)
})

test('keeps derived values empty until all attributes required by their formulas exist', () => {
  const character: derived.EditableCharacterValues = {
    str: 50, con: 60, siz: 0, dex: 65, pow: 0,
    hpMax: undefined, sanMax: undefined, mpMax: undefined,
    damageBonus: undefined, build: undefined, mov: undefined,
  }
  const automatic = derived.automaticDerivedFields(character)

  derived.syncAutomaticDerivedValues(character, automatic)

  assert.equal(character.hpMax, undefined)
  assert.equal(character.sanMax, undefined)
  assert.equal(character.mpMax, undefined)
  assert.equal(character.damageBonus, undefined)
  assert.equal(character.build, undefined)
  assert.equal(character.mov, undefined)
})
