import type { CharacterCardCreationWeaponRule, CocWeapon } from '@/api/types'

export const MAX_STEPWISE_WEAPONS = 3

export function normalizeStepwiseEra(era?: string): '1920S' | 'MODERN' {
  const normalized = era?.trim().toLocaleLowerCase()
  if (normalized === '现代' || normalized === 'modern' || normalized === '现代社会') return 'MODERN'
  return '1920S'
}

export function filterStepwiseWeapons(
  catalog: CharacterCardCreationWeaponRule[],
  era: string,
): CharacterCardCreationWeaponRule[] {
  const normalizedEra = normalizeStepwiseEra(era)
  return catalog.filter((weapon) => weapon.eras.includes(normalizedEra))
}

export function stepwiseWeaponSelectionPayload(codes: string[]): Array<{ code: string }> {
  return [...new Set(codes)].slice(0, MAX_STEPWISE_WEAPONS).map((code) => ({ code }))
}

export function restoreStepwiseWeaponCodes(
  catalog: CharacterCardCreationWeaponRule[],
  weapons: Array<Pick<CocWeapon, 'name'>>,
): string[] {
  const codeByName = new Map(catalog.map((weapon) => [weapon.name, weapon.code]))
  return weapons.flatMap((weapon) => {
    const code = codeByName.get(weapon.name)
    return code ? [code] : []
  }).slice(0, MAX_STEPWISE_WEAPONS)
}
