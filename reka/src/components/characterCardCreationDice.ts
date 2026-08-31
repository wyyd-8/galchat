import type { DiceResult } from '../api/types.ts'
import {
  resolveDiceSkin,
  type DicePlaybackGroupPresentation,
  type DicePlaybackRequest,
} from '../dice/domain/dicePlayback.ts'

export interface CharacterCreationDiceRoll {
  code: string
  formula: string
  dice: number[]
  result: number
}

export interface CharacterCreationAttributeRolls {
  rolls: CharacterCreationDiceRoll[]
  luckRolls: CharacterCreationDiceRoll[]
  educationGrowths?: Array<{
    checkRoll: number
    increaseRoll?: number
    eduBefore: number
    eduAfter: number
  }>
}

export interface CharacterCreationBackgroundPrompt {
  category: string
  rolls: number[]
  promptCodes: string[]
  prompts: string[]
}

export interface AutoCharacterCreationRolls {
  buildRolls?: {
    luck?: number
    luckRolls?: number[][]
    educationChecks?: number[]
    educationIncreases?: number[]
    educationGrowths?: Array<{ checkRoll: number, increaseRoll?: number }>
  }
}

const ATTRIBUTE_LABELS: Record<string, string> = {
  STR: '力量 STR',
  CON: '体质 CON',
  SIZ: '体型 SIZ',
  DEX: '敏捷 DEX',
  APP: '外貌 APP',
  INT: '智力 INT',
  POW: '意志 POW',
  EDU: '教育 EDU',
  LUCK: '幸运 LUCK',
}

const BACKGROUND_LABELS: Record<string, string> = {
  IDEOLOGY: '思想与信念',
  SIGNIFICANT_PEOPLE: '重要之人',
  MEANINGFUL_LOCATIONS: '意义非凡之地',
  TREASURED_POSSESSIONS: '宝贵之物',
  TRAITS: '特质',
}

export function buildAttributeDicePlayback(
  attributes: CharacterCreationAttributeRolls,
  skin: unknown,
  previousId: number,
): DicePlaybackRequest {
  const rolls = [...attributes.rolls, ...attributes.luckRolls]
  const codeOccurrences = new Map<string, number>()
  const modules: DiceResult['modules'] = rolls.map((roll) => ({
    expression: roll.formula,
    diceCount: roll.dice.length,
    diceSides: 6,
    modifier: 'NORMAL',
    dice: roll.dice.map((value) => ({ sides: 6, value, selected: true })),
    result: roll.result,
  }))
  const groups: DicePlaybackGroupPresentation[] = rolls.map((roll, index) => {
    const occurrence = (codeOccurrences.get(roll.code) || 0) + 1
    codeOccurrences.set(roll.code, occurrence)
    const baseLabel = ATTRIBUTE_LABELS[roll.code] || roll.code
    return {
      label: occurrence > 1 ? `${baseLabel} · 第 ${occurrence} 次` : baseLabel,
      checkName: roll.formula,
      outcomeLabel: String(roll.result),
      outcomeTone: 'none',
      success: false,
      moduleStart: index,
      moduleCount: 1,
      rollResult: roll.result,
    }
  })
  for (const [growthIndex, growth] of (attributes.educationGrowths || []).entries()) {
    const checkModuleStart = modules.length
    const percentileValue = growth.checkRoll === 100 ? 0 : growth.checkRoll
    modules.push({
      expression: '1D100',
      diceCount: 2,
      diceSides: 100,
      modifier: 'NORMAL',
      dice: [
        { sides: 10, value: Math.floor(percentileValue / 10), role: 'PERCENTILE_TENS', selected: true },
        { sides: 10, value: percentileValue % 10, role: 'PERCENTILE_ONES', selected: true },
      ],
      result: growth.checkRoll,
    })
    groups.push({
      label: `教育成长 ${growthIndex + 1} · 判定`,
      checkName: '1D100',
      outcomeLabel: String(growth.checkRoll),
      outcomeTone: 'none',
      success: growth.increaseRoll != null,
      moduleStart: checkModuleStart,
      moduleCount: 1,
      rollResult: growth.checkRoll,
    })
    if (growth.increaseRoll != null) {
      const increaseModuleStart = modules.length
      modules.push({
        expression: '1D10',
        diceCount: 1,
        diceSides: 10,
        modifier: 'NORMAL',
        dice: [{ sides: 10, value: growth.increaseRoll, selected: true }],
        result: growth.increaseRoll,
      })
      groups.push({
        label: `教育成长 ${growthIndex + 1} · 提升`,
        checkName: '1D10',
        outcomeLabel: `+${growth.increaseRoll}`,
        outcomeTone: 'none',
        success: true,
        moduleStart: increaseModuleStart,
        moduleCount: 1,
        rollResult: growth.increaseRoll,
      })
    }
  }
  const animationGroups = Array.from(
    { length: Math.ceil(modules.length / 3) },
    (_, index) => ({
      moduleStart: index * 3,
      moduleCount: Math.min(3, modules.length - index * 3),
      startDelayMs: index * 700,
    }),
  )
  const result: DiceResult = {
    formula: modules.map((module) => module.expression).join(' / '),
    modules,
    result: modules.reduce((sum, module) => sum + (module.result || 0), 0),
  }
  return {
    id: previousId + 1,
    result,
    skin: resolveDiceSkin(skin),
    reason: '生成调查员属性',
    toolName: 'characterCreationAttributes',
    mode: 'play',
    autoPlay: true,
    initialAnimation: { groups: animationGroups },
    presentation: {
      kind: 'value-roll',
      resultLabel: '属性生成',
      resultValue: '全部完成',
      formulaLabel: '标准创建法',
      formulaValue: `${groups.length} 组属性骰`,
      groups,
      groupRule: 'SEPARATE',
    },
  }
}

export function buildBackgroundPromptDicePlayback(
  prompt: CharacterCreationBackgroundPrompt,
  skin: unknown,
  previousId: number,
): DicePlaybackRequest {
  const baseLabel = BACKGROUND_LABELS[prompt.category] || prompt.category
  const modules: DiceResult['modules'] = prompt.rolls.map((result) => ({
    expression: '1D10',
    diceCount: 1,
    diceSides: 10,
    modifier: 'NORMAL',
    dice: [{ sides: 10, value: result, selected: true }],
    result,
  }))
  const groups: DicePlaybackGroupPresentation[] = prompt.rolls.map((result, index) => ({
    label: prompt.category === 'SIGNIFICANT_PEOPLE'
      ? `${baseLabel} · ${index === 0 ? '是谁' : '原因'}`
      : baseLabel,
    checkName: '1D10',
    outcomeLabel: String(result),
    outcomeTone: 'none',
    success: false,
    moduleStart: index,
    moduleCount: 1,
    rollResult: result,
  }))
  return {
    id: previousId + 1,
    result: {
      formula: modules.map((module) => module.expression).join(' / '),
      modules,
      result: prompt.rolls.reduce((sum, result) => sum + result, 0),
    },
    skin: resolveDiceSkin(skin),
    reason: `${baseLabel}随机提示`,
    toolName: 'characterCreationBackground',
    mode: 'play',
    autoPlay: true,
    initialAnimation: {
      groups: groups.map((_, index) => ({ moduleStart: index, moduleCount: 1, startDelayMs: index * 500 })),
    },
    presentation: {
      kind: 'value-roll',
      resultLabel: '背景提示',
      resultValue: '已取得提示',
      formulaLabel: '随机表',
      formulaValue: `${prompt.rolls.length} 组 D10`,
      groups,
      groupRule: 'SEPARATE',
    },
  }
}

export function buildAutoCharacterCardDicePlayback(
  rolls: AutoCharacterCreationRolls,
  skin: unknown,
  previousId: number,
): DicePlaybackRequest {
  const modules: DiceResult['modules'] = []
  const groups: DicePlaybackGroupPresentation[] = []
  const luckRolls = rolls.buildRolls?.luckRolls || []
  luckRolls.forEach((dice, index) => {
    const moduleStart = modules.length
    const result = dice.reduce((sum, value) => sum + value, 0) * 5
    modules.push({
      expression: '3D6 * 5',
      diceCount: dice.length,
      diceSides: 6,
      modifier: 'NORMAL',
      dice: dice.map((value) => ({ sides: 6, value, selected: true })),
      result,
    })
    groups.push({
      label: luckRolls.length > 1 ? `幸运 LUCK · 第 ${index + 1} 次` : '幸运 LUCK',
      checkName: '3D6 * 5',
      outcomeLabel: String(result),
      outcomeTone: 'none',
      success: result === rolls.buildRolls?.luck,
      moduleStart,
      moduleCount: 1,
      rollResult: result,
    })
  })

  const growths = rolls.buildRolls?.educationGrowths
    || (rolls.buildRolls?.educationChecks || []).map((checkRoll, index) => ({
      checkRoll,
      increaseRoll: rolls.buildRolls?.educationIncreases?.[index],
    }))
  growths.forEach((growth, index) => {
    const moduleStart = modules.length
    const percentileValue = growth.checkRoll === 100 ? 0 : growth.checkRoll
    modules.push({
      expression: '1D100',
      diceCount: 2,
      diceSides: 100,
      modifier: 'NORMAL',
      dice: [
        { sides: 10, value: Math.floor(percentileValue / 10), role: 'PERCENTILE_TENS', selected: true },
        { sides: 10, value: percentileValue % 10, role: 'PERCENTILE_ONES', selected: true },
      ],
      result: growth.checkRoll,
    })
    groups.push({
      label: `教育成长 ${index + 1} · 判定`,
      checkName: '1D100',
      outcomeLabel: String(growth.checkRoll),
      outcomeTone: 'none',
      success: growth.increaseRoll != null,
      moduleStart,
      moduleCount: 1,
      rollResult: growth.checkRoll,
    })
    if (growth.increaseRoll != null) {
      const increaseStart = modules.length
      modules.push({
        expression: '1D10',
        diceCount: 1,
        diceSides: 10,
        modifier: 'NORMAL',
        dice: [{ sides: 10, value: growth.increaseRoll, selected: true }],
        result: growth.increaseRoll,
      })
      groups.push({
        label: `教育成长 ${index + 1} · 提升`,
        checkName: '1D10',
        outcomeLabel: `+${growth.increaseRoll}`,
        outcomeTone: 'none',
        success: true,
        moduleStart: increaseStart,
        moduleCount: 1,
        rollResult: growth.increaseRoll,
      })
    }
  })

  return {
    id: previousId + 1,
    result: {
      formula: modules.map((module) => module.expression).join(' / '),
      modules,
      result: modules.reduce((sum, module) => sum + (module.result || 0), 0),
    },
    skin: resolveDiceSkin(skin),
    reason: 'AI 调查员幸运与教育成长',
    toolName: 'autoCharacterCreation',
    mode: 'play',
    autoPlay: true,
    initialAnimation: {
      groups: Array.from({ length: Math.ceil(modules.length / 3) }, (_, index) => ({
        moduleStart: index * 3,
        moduleCount: Math.min(3, modules.length - index * 3),
        startDelayMs: index * 700,
      })),
    },
    presentation: {
      kind: 'value-roll',
      resultLabel: '幸运与成长判定',
      resultValue: '骰点完成',
      formulaLabel: '生成记录',
      formulaValue: `${groups.length} 组骰点`,
      groups,
      groupRule: 'SEPARATE',
    },
  }
}
