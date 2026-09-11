const ATTRIBUTE_ALIASES = {
  str: ['STR', '力量'],
  con: ['CON', '体质'],
  siz: ['SIZ', '体型'],
  dex: ['DEX', '敏捷'],
  app: ['APP', '外貌'],
  intValue: ['INT', '智力', '灵感'],
  pow: ['POW', '意志'],
  edu: ['EDU', '教育'],
} as const

export interface ModuleCharacterImportDraft {
  character: Record<string, string | number>
  skills: Array<{ displayName: string, value: number }>
  unresolvedLines: string[]
  missingAttributes: string[]
}

export function parseModuleCharacterText(text: string, skillNames: string[]): ModuleCharacterImportDraft {
  const originalLines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const lines = originalLines.map(normalizeText)
  const values: Record<string, number> = {}
  for (const [field, aliases] of Object.entries(ATTRIBUTE_ALIASES)) {
    const value = findNumber(lines.join(' '), aliases)
    if (value != null) values[field] = value
  }

  const missingAttributes = Object.keys(ATTRIBUTE_ALIASES).filter((field) => values[field] == null)
  const name = lines.find((line) => !containsKnownField(line) && !/%/.test(line)) || ''
  const con = values.con
  const siz = values.siz
  const pow = values.pow
  const derived = deriveCharacterValues(values)
  const hp = findNumber(lines.join(' '), ['HP', '生命值']) ?? derived.hpMax
  const san = findNumber(lines.join(' '), ['SAN', '理智']) ?? derived.sanMax
  const mp = findNumber(lines.join(' '), ['MP', '魔法值']) ?? derived.mpMax
  const damageBonus = findTextValue(lines, ['伤害加值', '伤害奖励', 'DB']) ?? derived.damageBonus
  const build = findNumber(lines.join(' '), ['体格', 'BUILD']) ?? derived.build
  const mov = findNumber(lines.join(' '), ['移动', 'MOV']) ?? derived.mov

  const skills: Array<{ displayName: string, value: number }> = []
  const unresolvedLines: string[] = []
  const parseableSkillNames = skillNames
    .filter((skill) => !skill.includes(':'))
    .sort((left, right) => right.length - left.length)
  for (const [index, line] of lines.entries()) {
    if (!/(\d{1,3})\s*%/.test(line)) continue
    if (/伤害/i.test(line)) unresolvedLines.push(originalLines[index]!)
    const skillValues = findSkillValues(line, parseableSkillNames)
    if (!skillValues.length) continue
    for (const skillValue of skillValues) {
      if (!skills.some((skill) => skill.displayName === skillValue.displayName)) skills.push(skillValue)
    }
  }

  const character: Record<string, string | number> = { name, ...values }
  if (hp != null) Object.assign(character, { hpMax: hp, hpCurrent: hp })
  if (san != null) Object.assign(character, { sanMax: san, sanCurrent: san })
  if (mp != null) Object.assign(character, { mpMax: mp, mpCurrent: mp })
  if (damageBonus != null) character.damageBonus = damageBonus
  if (build != null) character.build = build
  if (mov != null) character.mov = mov
  return { character, skills, unresolvedLines, missingAttributes }
}

export function validateWeaponDamage(damage: string, skillName: string): string | null {
  const normalized = normalizeText(damage).replace(/\s+/g, '').toUpperCase()
  if (!normalized) return '请填写伤害'
  if (normalized.includes('/')) {
    if (skillName !== '射击:步枪/霰弹枪') return '只有霰弹枪可以填写近/中/远三级伤害'
    const tiers = normalized.split('/')
    if (tiers.length !== 3 || tiers.some((tier) => !isSingleDamage(tier))) return '霰弹枪伤害需填写近/中/远三个合规表达式'
    return null
  }
  return isSingleDamage(normalized) ? null : '伤害仅支持数字、掷骰、DB、半DB或晕眩'
}

function normalizeText(value: string) {
  return value
    .replace(/：/g, ':')
    .replace(/（/g, '(')
    .replace(/）/g, ')')
    .replace(/％/g, '%')
    .replace(/＋/g, '+')
    .replace(/－/g, '-')
}

function containsKnownField(line: string) {
  return Object.values(ATTRIBUTE_ALIASES).flat().some((alias) => new RegExp(`${escapeRegExp(alias)}\\s*[:：]?\\s*\\d+`, 'i').test(line))
}

function findNumber(text: string, aliases: readonly string[]) {
  for (const alias of aliases) {
    const match = text.match(new RegExp(`${escapeRegExp(alias)}\\s*[:：]?\\s*(-?\\d+)`, 'i'))
    if (match) return Number(match[1])
  }
  return undefined
}

function findTextValue(lines: string[], aliases: string[]) {
  for (const line of lines) {
    for (const alias of aliases) {
      const match = line.match(new RegExp(`${escapeRegExp(alias)}\\s*[:：]?\\s*([+\\-]?\\d+D\\d+|半DB|[+\\-]?DB|0)`, 'i'))
      if (match) return match[1]!.toUpperCase()
    }
  }
  return undefined
}

function findSkillValues(line: string, skillNames: string[]) {
  if (!skillNames.length) return []
  const alternatives = skillNames.map(escapeRegExp).join('|')
  const matches = Array.from(line.matchAll(new RegExp(`(${alternatives})\\s*\\)?\\s*:?\\s*(\\d{1,3})\\s*%`, 'g')))
  if (matches.length) return matches.map((match) => ({ displayName: match[1]!, value: Number(match[2]) }))

  const percent = line.match(/(\d{1,3})\s*%/)
  const skill = skillNames.find((name) => line.includes(name))
  return percent && skill ? [{ displayName: skill, value: Number(percent[1]) }] : []
}

function isSingleDamage(value: string) {
  if (value === '晕眩') return true
  if (/^\d+$/.test(value)) return true
  return /^(?:\d+D\d+|\d+)(?:[+\-](?:\d+D\d+|\d+|DB|半DB))?$/.test(value)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
import { deriveCharacterValues } from './cocModuleCharacterDerived.ts'
