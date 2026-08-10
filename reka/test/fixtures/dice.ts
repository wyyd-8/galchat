import type { DiceResult, DiceRollAggregate, DiceRollDetail } from '../../src/api/types.ts'

export type DiceFixturePreset =
  | 'standard' | 'group' | 'percentile' | 'normal-percentile'
  | 'advantage' | 'double-advantage' | 'disadvantage' | 'double-disadvantage' | 'custom'
export type DiceAggregateFixturePreset = 'multiplayer-check' | 'opposed-check'

type PercentilePreset = Extract<
  DiceFixturePreset,
  'percentile' | 'normal-percentile' | 'advantage' | 'double-advantage' | 'disadvantage' | 'double-disadvantage'
>

function normalModule(sides: number, values: number[]) {
  return {
    expression: `${values.length}D${sides}`,
    diceCount: values.length,
    diceSides: sides,
    modifier: 'NORMAL',
    dice: values.map((value) => ({ sides, value, role: 'NORMAL', selected: true })),
    result: values.reduce((sum, value) => sum + value, 0),
  }
}

function percentileResult(preset: PercentilePreset): DiceResult {
  const config = {
    'normal-percentile': { formula: '1D100', modifier: 'NORMAL', tens: [2], selected: 0, result: 27 },
    advantage: { formula: '1D100#', modifier: 'ADVANTAGE', tens: [8, 2], selected: 1, result: 27 },
    'double-advantage': { formula: '1D100##', modifier: 'DOUBLE_ADVANTAGE', tens: [8, 2, 5], selected: 1, result: 27 },
    disadvantage: { formula: '1D100$', modifier: 'DISADVANTAGE', tens: [8, 2], selected: 0, result: 87 },
    'double-disadvantage': { formula: '1D100$$', modifier: 'DOUBLE_DISADVANTAGE', tens: [8, 2, 5], selected: 0, result: 87 },
  }[preset === 'percentile' ? 'double-advantage' : preset]
  if (!config) throw new Error(`未知的百分骰预设：${preset}`)
  return {
    formula: config.formula,
    modules: [{
      expression: config.formula,
      diceCount: 1,
      diceSides: 100,
      modifier: config.modifier,
      dice: [
        { sides: 10, value: 7, role: 'PERCENTILE_ONES', selected: true },
        ...config.tens.map((value, index) => ({
          sides: 10, value, role: 'PERCENTILE_TENS', selected: index === config.selected,
        })),
      ],
      result: config.result,
    }],
    result: config.result,
  }
}

function resolvedPercentileResult(value: number): DiceResult {
  return {
    formula: '1D100',
    modules: [{
      expression: '1D100',
      diceCount: 1,
      diceSides: 100,
      modifier: 'NORMAL',
      dice: [
        { sides: 10, value: value % 10, role: 'PERCENTILE_ONES', selected: true },
        { sides: 10, value: Math.floor(value / 10), role: 'PERCENTILE_TENS', selected: true },
      ],
      result: value,
    }],
    result: value,
  }
}

function checkDetail(
  id: number,
  displayOrder: number,
  type: 'CHECK' | 'OPPOSED_CHECK',
  characterName: string,
  checkName: string,
  category: string,
  value: number,
): DiceRollDetail {
  return {
    id,
    summaryId: type === 'OPPOSED_CHECK' ? 9002 : 9001,
    roundNo: 1,
    displayOrder,
    displayType: type,
    reason: type === 'OPPOSED_CHECK' ? '争夺手枪' : '搜索废弃宅邸',
    resultData: resolvedPercentileResult(value),
    resolution: { type, outcome: { characterName, checkName, category } },
    resolvedAt: '2026-08-09T12:00:00',
  }
}

export function createDiceAggregateFixture(preset: DiceAggregateFixturePreset): DiceRollAggregate {
  if (preset === 'multiplayer-check') {
    return {
      summary: {
        id: 9001,
        conversationId: 1,
        reason: '搜索废弃宅邸',
        totalResult: '林恩侦查成功；陈默侦查失败；苏婉侦查大成功',
        roundCount: 1,
        status: 'COMPLETED',
      },
      results: [
        checkDetail(9101, 1, 'CHECK', '林恩', '侦查', 'SUCCESS', 27),
        checkDetail(9102, 2, 'CHECK', '陈默', '侦查', 'FAILURE', 78),
        checkDetail(9103, 3, 'CHECK', '苏婉', '侦查', 'CRITICAL_SUCCESS', 1),
      ],
      semanticResult: '林恩侦查成功；陈默侦查失败；苏婉侦查大成功',
    }
  }
  return {
    summary: {
      id: 9002,
      conversationId: 1,
      reason: '争夺手枪',
      totalResult: '林恩获胜',
      roundCount: 1,
      status: 'COMPLETED',
    },
    results: [
      checkDetail(9201, 1, 'OPPOSED_CHECK', '林恩', '格斗', 'SUCCESS', 35),
      checkDetail(9202, 2, 'OPPOSED_CHECK', '陈默', '闪避', 'SUCCESS', 40),
    ],
    semanticResult: '林恩获胜',
  }
}

export function createDiceResultFixture(preset: DiceFixturePreset): DiceResult {
  if (preset === 'standard') {
    return {
      formula: '1D4 + 1D6 + 1D8 + 1D10 + 1D12 + 1D20',
      modules: [
        normalModule(4, [3]), normalModule(6, [5]), normalModule(8, [7]),
        normalModule(10, [9]), normalModule(12, [11]), normalModule(20, [17]),
      ],
      result: 52,
    }
  }
  if (preset === 'group') {
    return {
      formula: '3D6 + 2D8',
      modules: [normalModule(6, [4, 5, 6]), normalModule(8, [3, 7])],
      result: 25,
    }
  }
  if (['percentile', 'normal-percentile', 'advantage', 'double-advantage', 'disadvantage', 'double-disadvantage'].includes(preset)) {
    return percentileResult(preset as PercentilePreset)
  }
  return {
    formula: '1D20',
    modules: [normalModule(20, [12])],
    result: 12,
  }
}
