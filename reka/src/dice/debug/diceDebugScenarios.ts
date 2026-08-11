import type { DiceResult, DiceRollAggregate, DiceRollDetail } from '../../api/types.ts'

export type DiceDebugAudience = 'single' | 'multiple'
export type DiceDebugToolName =
  | 'requestCheck'
  | 'requestGroupCheck'
  | 'requestOpposedCheck'
  | 'requestPushedCheck'
  | 'requestSanCheck'
  | 'rollSanLoss'
  | 'rollDamage'
  | 'rollHealing'

export interface DiceDebugScenario {
  id: string
  label: string
  audience: DiceDebugAudience
}

export interface DiceDebugToolGroup {
  toolName: DiceDebugToolName
  label: string
  description: string
  scenarios: DiceDebugScenario[]
}

const DEBUG_TOOLS: Array<Omit<DiceDebugToolGroup, 'scenarios'>> = [
  { toolName: 'requestCheck', label: '普通检定', description: '属性或技能百分骰检定' },
  { toolName: 'requestGroupCheck', label: '群体检定', description: '含群体通过规则的百分骰检定' },
  { toolName: 'requestOpposedCheck', label: '对抗检定', description: '展示胜者与对抗双方结果' },
  { toolName: 'requestPushedCheck', label: '孤注一掷', description: '使用孤注一掷主题的追加检定' },
  { toolName: 'requestSanCheck', label: '理智检定', description: '按当前 SAN 进行百分骰检定' },
  { toolName: 'rollSanLoss', label: '理智损失', description: '展示 SAN 数值减少结果' },
  { toolName: 'rollDamage', label: '伤害结算', description: '展示 HP 数值减少结果' },
  { toolName: 'rollHealing', label: '治疗恢复', description: '展示 HP 数值增加结果' },
]

export const DICE_DEBUG_TOOL_GROUPS: DiceDebugToolGroup[] = DEBUG_TOOLS.map((tool) => ({
  ...tool,
  scenarios: [
    { id: `${tool.toolName}-single`, label: '单人', audience: 'single' },
    { id: `${tool.toolName}-multiple`, label: '多人', audience: 'multiple' },
  ],
}))

export const DICE_DEBUG_CONSTANT_SCENARIOS: DiceDebugScenario[] = [
  { id: 'constant-value-single', label: '单人纯常量', audience: 'single' },
  { id: 'constant-value-multiple', label: '多人常量混合', audience: 'multiple' },
]

const CHARACTER_NAMES = ['林恩', '陈默']
const CHECK_VALUES = [27, 78]
const CHECK_CATEGORIES = ['SUCCESS', 'FAILURE']

function percentileResult(value: number): DiceResult {
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

function numericResult(value: number): DiceResult {
  const dieValue = Math.max(1, Math.min(6, value - 2))
  return {
    formula: '1D6 + 2',
    modules: [{
      expression: '1D6',
      diceCount: 1,
      diceSides: 6,
      modifier: 'NORMAL',
      dice: [{ sides: 6, value: dieValue, role: 'NORMAL', selected: true }],
      result: dieValue,
    }],
    result: value,
  }
}

function damageResult(index: number): DiceResult {
  const d6Value = index === 0 ? 3 : 2
  const d8Value = index === 0 ? 4 : 6
  return {
    formula: '1D6+1D8+2',
    modules: [
      {
        expression: '1D6',
        diceCount: 1,
        diceSides: 6,
        modifier: 'NORMAL',
        dice: [{ sides: 6, value: d6Value, role: 'NORMAL', selected: true }],
        result: d6Value,
      },
      {
        expression: '1D8',
        diceCount: 1,
        diceSides: 8,
        modifier: 'NORMAL',
        dice: [{ sides: 8, value: d8Value, role: 'NORMAL', selected: true }],
        result: d8Value,
      },
    ],
    result: d6Value + d8Value + 2,
  }
}

function displayType(toolName: DiceDebugToolName): string {
  if (toolName === 'requestOpposedCheck') return 'OPPOSED_CHECK'
  if (toolName === 'requestSanCheck') return 'SAN_CHECK'
  if (toolName === 'rollSanLoss') return 'SAN_LOSS'
  if (toolName === 'rollDamage') return 'DAMAGE'
  if (toolName === 'rollHealing') return 'HEALING'
  return 'CHECK'
}

function toolLabel(toolName: DiceDebugToolName): string {
  return DEBUG_TOOLS.find((tool) => tool.toolName === toolName)?.label || toolName
}

function toolFromScenario(id: string): DiceDebugToolName | undefined {
  return DEBUG_TOOLS.find((tool) => id.startsWith(`${tool.toolName}-`))?.toolName
}

function checkDetail(
  summaryId: number,
  index: number,
  toolName: DiceDebugToolName,
  reason: string,
): DiceRollDetail {
  const type = displayType(toolName)
  const groupRule = toolName === 'requestGroupCheck' ? 'ANY_SUCCESS' : 'SEPARATE'
  return {
    id: summaryId * 10 + index + 1,
    summaryId,
    roundNo: 1,
    displayOrder: index + 1,
    displayType: type,
    reason,
    resultData: percentileResult(CHECK_VALUES[index]!),
    resolution: {
      type,
      groupRule,
      outcome: {
        characterName: CHARACTER_NAMES[index],
        checkName: toolName === 'requestSanCheck' ? '理智' : index === 0 ? '侦查' : '聆听',
        category: CHECK_CATEGORIES[index],
        rank: index === 0 ? 'REGULAR' : undefined,
      },
    },
    resolvedAt: '2026-08-12T12:00:00',
  }
}

function valueDetail(
  summaryId: number,
  index: number,
  toolName: DiceDebugToolName,
  reason: string,
  resultData = numericResult(index === 0 ? 6 : 4),
): DiceRollDetail {
  const type = displayType(toolName)
  return {
    id: summaryId * 10 + index + 1,
    summaryId,
    roundNo: 1,
    displayOrder: index + 1,
    displayType: type,
    reason,
    resultData,
    resolution: {
      type,
      outcome: { characterName: CHARACTER_NAMES[index] },
    },
    resolvedAt: '2026-08-12T12:00:00',
  }
}

function scenarioIndex(id: string): number {
  const ids = [
    ...DICE_DEBUG_TOOL_GROUPS.flatMap((group) => group.scenarios.map((scenario) => scenario.id)),
    ...DICE_DEBUG_CONSTANT_SCENARIOS.map((scenario) => scenario.id),
  ]
  return ids.indexOf(id)
}

export function createDiceDebugAggregate(id: string): DiceRollAggregate {
  const index = scenarioIndex(id)
  if (index < 0) throw new Error(`未知的骰子调试场景：${id}`)
  const summaryId = 9_700 + index

  if (id === 'constant-value-single') {
    const reason = '固定伤害调试'
    return {
      summary: {
        id: summaryId, conversationId: 1, reason, totalResult: '林恩受到4点伤害',
        roundCount: 1, status: 'COMPLETED', toolName: 'rollDamage',
      },
      results: [valueDetail(
        summaryId,
        0,
        'rollDamage',
        reason,
        { formula: '4', modules: [], result: 4 },
      )],
      semanticResult: '林恩受到4点伤害',
    }
  }

  if (id === 'constant-value-multiple') {
    const reason = '多人伤害调试'
    return {
      summary: {
        id: summaryId, conversationId: 1, reason, totalResult: '分别结算伤害',
        roundCount: 1, status: 'COMPLETED', toolName: 'rollDamage',
      },
      results: [
        valueDetail(summaryId, 0, 'rollDamage', reason, { formula: '3', modules: [], result: 3 }),
        valueDetail(summaryId, 1, 'rollDamage', reason, numericResult(6)),
      ],
      semanticResult: '分别结算伤害',
    }
  }

  const toolName = toolFromScenario(id)!
  const audience: DiceDebugAudience = id.endsWith('-single') ? 'single' : 'multiple'
  const count = audience === 'single' ? 1 : 2
  const reason = `${toolLabel(toolName)}调试`
  const type = displayType(toolName)
  const valueRoll = type === 'SAN_LOSS' || type === 'DAMAGE' || type === 'HEALING'
  const results = Array.from({ length: count }, (_, resultIndex) => valueRoll
    ? valueDetail(
        summaryId,
        resultIndex,
        toolName,
        reason,
        toolName === 'rollDamage' ? damageResult(resultIndex) : numericResult(resultIndex === 0 ? 6 : 4),
      )
    : checkDetail(summaryId, resultIndex, toolName, reason))
  const semanticResult = toolName === 'requestOpposedCheck'
    ? `${CHARACTER_NAMES[0]}获胜`
    : audience === 'single' ? `${CHARACTER_NAMES[0]}：已完成` : '分别展示'

  return {
    summary: {
      id: summaryId,
      conversationId: 1,
      reason,
      totalResult: semanticResult,
      roundCount: 1,
      status: 'COMPLETED',
      toolName,
    },
    results,
    semanticResult,
  }
}
