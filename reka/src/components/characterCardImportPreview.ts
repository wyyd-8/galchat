const ATTRIBUTE_CODES = ['STR', 'CON', 'SIZ', 'DEX', 'APP', 'INT', 'POW', 'EDU'] as const

export interface CharacterCardImportPreview {
  ready: boolean
  completion: number
  identity: { name: string, occupation: string, sex: string, age: number } | null
  attributes: Record<string, number>
  missingAttributes: string[]
  skillCount: number
  checks: Array<{ code: 'IDENTITY' | 'ATTRIBUTES' | 'SKILLS' | 'BACKGROUND', state: 'complete' | 'missing' | 'optional' }>
  warnings: string[]
}

export function analyzeCharacterCardImport(text: string): CharacterCardImportPreview {
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const identityMatch = lines.find((line) => /^[^，,]+[，,]\s*[^，,]+[，,]\s*[^，,]+[，,]\s*\d+岁$/.test(line))
    ?.match(/^([^，,]+)[，,]\s*([^，,]+)[，,]\s*([^，,]+)[，,]\s*(\d+)岁$/)
  const identity = identityMatch
    ? {
      name: identityMatch[1]!.trim(),
      occupation: identityMatch[2]!.trim(),
      sex: identityMatch[3]!.trim(),
      age: Number(identityMatch[4]),
    }
    : null
  const attributes: Record<string, number> = {}
  const attributePattern = /\b(STR|CON|SIZ|DEX|APP|INT|POW|EDU)\s+(\d+)\b/gi
  for (const line of lines) {
    for (const match of line.matchAll(attributePattern)) {
      attributes[match[1]!.toUpperCase()] = Number(match[2])
    }
  }
  const missingAttributes = ATTRIBUTE_CODES.filter((code) => attributes[code] == null)
  const skillSectionIndex = lines.findIndex((line) => /技能/.test(line) && /[—-]/.test(line))
  const backgroundSectionIndex = lines.findIndex((line) => /背景故事/.test(line) && /[—-]/.test(line))
  const nextSectionIndex = skillSectionIndex < 0
    ? -1
    : lines.findIndex((line, index) => index > skillSectionIndex && /[—-]/.test(line))
  const skillLines = skillSectionIndex < 0
    ? []
    : lines.slice(skillSectionIndex + 1, nextSectionIndex < 0 ? undefined : nextSectionIndex)
  const skillCount = skillLines.filter((line) => /^.+?\s+\d+%/.test(line)).length
  const warnings: string[] = []
  if (identity && (identity.age < 15 || identity.age > 90)) warnings.push('年龄需在 15–90 岁之间')
  const outOfRange = Object.entries(attributes).filter(([, value]) => value < 10 || value > 90)
  if (outOfRange.length) warnings.push(`${outOfRange.map(([code]) => code).join('、')} 需在 10–90 之间`)
  const total = Object.values(attributes).reduce((sum, value) => sum + value, 0)
  if (!missingAttributes.length && total > 460) warnings.push(`八项属性总和 ${total}，不能超过 460`)
  const completedRequired = (identity ? 1 : 0) + ATTRIBUTE_CODES.length - missingAttributes.length
  const ready = identity != null && missingAttributes.length === 0 && warnings.length === 0

  return {
    ready,
    completion: Math.round(completedRequired / (ATTRIBUTE_CODES.length + 1) * 100),
    identity,
    attributes,
    missingAttributes,
    skillCount,
    checks: [
      { code: 'IDENTITY', state: identity ? 'complete' : 'missing' },
      { code: 'ATTRIBUTES', state: missingAttributes.length ? 'missing' : 'complete' },
      { code: 'SKILLS', state: skillCount ? 'complete' : 'optional' },
      { code: 'BACKGROUND', state: backgroundSectionIndex >= 0 ? 'complete' : 'optional' },
    ],
    warnings,
  }
}
