export const CHARACTER_DERIVED_FIELDS = ['hpMax', 'sanMax', 'mpMax', 'damageBonus', 'build', 'mov'] as const

export type CharacterDerivedField = typeof CHARACTER_DERIVED_FIELDS[number]
export interface EditableCharacterValues {
  str?: number
  con?: number
  siz?: number
  dex?: number
  pow?: number
  hpMax?: number
  sanMax?: number
  mpMax?: number
  damageBonus?: string
  build?: number
  mov?: number
}

export function automaticDerivedFields(character: EditableCharacterValues): Set<CharacterDerivedField> {
  return new Set(CHARACTER_DERIVED_FIELDS.filter((field) => isBlank(character[field])))
}

export function syncAutomaticDerivedValues(
  character: EditableCharacterValues,
  automaticFields: ReadonlySet<CharacterDerivedField>,
) {
  const derived = deriveCharacterValues(character)
  for (const field of automaticFields) Object.assign(character, { [field]: derived[field] })
}

export function deriveCharacterValues(character: EditableCharacterValues): Partial<Record<CharacterDerivedField, string | number>> {
  const str = positiveNumber(character.str)
  const con = positiveNumber(character.con)
  const siz = positiveNumber(character.siz)
  const dex = positiveNumber(character.dex)
  const pow = positiveNumber(character.pow)
  const values: Partial<Record<CharacterDerivedField, string | number>> = {}

  if (con != null && siz != null) values.hpMax = Math.floor((con + siz) / 10)
  if (pow != null) {
    values.sanMax = pow
    values.mpMax = Math.floor(pow / 5)
  }
  if (str != null && siz != null) {
    const total = str + siz
    if (total <= 64) Object.assign(values, { damageBonus: '-2', build: -2 })
    else if (total <= 84) Object.assign(values, { damageBonus: '-1', build: -1 })
    else if (total <= 124) Object.assign(values, { damageBonus: '0', build: 0 })
    else if (total <= 164) Object.assign(values, { damageBonus: '+1D4', build: 1 })
    else if (total <= 204) Object.assign(values, { damageBonus: '+1D6', build: 2 })
    else {
      const dice = Math.floor((total - 205) / 80) + 2
      Object.assign(values, { damageBonus: `+${dice}D6`, build: dice + 1 })
    }
  }
  if (str != null && dex != null && siz != null) {
    values.mov = str < siz && dex < siz ? 7 : str > siz && dex > siz ? 9 : 8
  }
  return values
}

function positiveNumber(value: number | undefined) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : undefined
}

function isBlank(value: string | number | undefined) {
  return value == null || value === ''
}
