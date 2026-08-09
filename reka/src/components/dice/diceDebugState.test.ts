import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import type { DiceResult } from '../../api/types.ts'
import {
  createDiceAggregatePlaybackRequest,
  createDiceDebugAggregatePreset,
  createDicePlaybackRequest,
  createDiceDebugPreset,
  createDicePlayerSummary,
  createDicePlayerStatus,
  createGroupOutcomeVisibility,
  parseDiceResultJson,
  validatePlayableDiceResult,
} from './diceDebugState.ts'

test('creates a multiplayer check playback from backend-shaped roll details', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createDiceAggregatePlaybackRequest(6, aggregate, 'galaxy')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.id, 7)
  assert.equal(request.reason, '搜索废弃宅邸')
  assert.ok(aggregate.results.every((detail) => detail.summaryId === aggregate.summary.id))
  assert.equal(request.result.modules.length, 3)
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '侦查', result: '27 · 成功', diceCount: 2 },
    { label: '陈默', expression: '侦查', result: '78 · 失败', diceCount: 2 },
    { label: '苏婉', expression: '侦查', result: '1 · 大成功', diceCount: 2 },
  ])
  assert.deepEqual(request.presentation?.groups.map((group) => group.success), [true, false, true])
  assert.equal(summary.resultLabel, '任一成功')
  assert.equal(summary.resultValue, '成功')
  assert.equal(summary.formulaLabel, '检定项目')
  assert.equal(summary.formulaValue, '3 人参与 · 侦查 · 任一成功即通过')
})

test('fails an all-success group check when any participant fails', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic', 'ALL_SUCCESS')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.equal(request.presentation?.groupRule, 'ALL_SUCCESS')
  assert.equal(summary.resultLabel, '全部成功')
  assert.equal(summary.resultValue, '失败')
  assert.equal(summary.formulaValue, '3 人参与 · 侦查 · 全部成功才通过')
})

test('passes an all-success group check when every participant succeeds', () => {
  const aggregate = createDiceDebugAggregatePreset('multiplayer-check')
  aggregate.results.forEach((detail) => {
    if (detail.resolution?.outcome) detail.resolution.outcome.category = 'SUCCESS'
  })

  const request = createDiceAggregatePlaybackRequest(0, aggregate, 'classic', 'ALL_SUCCESS')

  assert.equal(request.presentation?.resultValue, '成功')
})

test('shows participant boxes without outcomes before the roll', () => {
  assert.deepEqual(createGroupOutcomeVisibility('concealed'), {
    showIndividuals: true,
    revealIndividualResults: false,
    showTransition: false,
    showFinal: false,
    highlightWinner: false,
  })
})

test('keeps individual group results beside the final result after the merge', () => {
  assert.deepEqual(createGroupOutcomeVisibility('individual'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: false,
    showFinal: false,
    highlightWinner: false,
  })
  assert.deepEqual(createGroupOutcomeVisibility('merging'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: true,
    showFinal: true,
    highlightWinner: true,
  })
  assert.deepEqual(createGroupOutcomeVisibility('merged'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: true,
    showFinal: true,
    highlightWinner: true,
  })
})

test('keeps opposed results visible while the winner is highlighted', () => {
  assert.deepEqual(createGroupOutcomeVisibility('highlighted'), {
    showIndividuals: true,
    revealIndividualResults: true,
    showTransition: false,
    showFinal: false,
    highlightWinner: true,
  })
})

test('creates an opposed check playback that reveals the winner after both rolls', () => {
  const aggregate = createDiceDebugAggregatePreset('opposed-check')

  const request = createDiceAggregatePlaybackRequest(2, aggregate, 'classic')
  const summary = createDicePlayerSummary(request.result, request.skin, request.presentation)

  assert.ok(aggregate.results.every((detail) => detail.summaryId === aggregate.summary.id))
  assert.equal(request.result.modules.length, 2)
  assert.deepEqual(summary.groups, [
    { label: '林恩', expression: '格斗', result: '35 · 成功', diceCount: 2 },
    { label: '陈默', expression: '闪避', result: '40 · 成功', diceCount: 2 },
  ])
  assert.deepEqual(request.presentation?.groups.map((group) => group.winner), [true, false])
  assert.equal(summary.modifierLabel, '对抗检定')
  assert.equal(summary.resultLabel, '对抗结果')
  assert.equal(summary.resultValue, '林恩获胜')
  assert.equal(summary.formulaValue, '林恩（格斗） vs 陈默（闪避）')
})

test('creates a percentile preset that preserves every backend dice field', () => {
  const result = createDiceDebugPreset('percentile')

  assert.deepEqual(result, {
    formula: '1D100##',
    modules: [{
      expression: '1D100##',
      diceCount: 1,
      diceSides: 100,
      modifier: 'DOUBLE_ADVANTAGE',
      dice: [
        { sides: 10, value: 7, role: 'PERCENTILE_ONES', selected: true },
        { sides: 10, value: 8, role: 'PERCENTILE_TENS', selected: false },
        { sides: 10, value: 2, role: 'PERCENTILE_TENS', selected: true },
        { sides: 10, value: 5, role: 'PERCENTILE_TENS', selected: false },
      ],
      result: 27,
    }],
    result: 27,
  })
})

test('creates a double-disadvantage preset with the selected highest tens die', () => {
  const result = createDiceDebugPreset('double-disadvantage')

  assert.equal(result.formula, '1D100$$')
  assert.equal(result.modules[0].modifier, 'DOUBLE_DISADVANTAGE')
  assert.deepEqual(result.modules[0].dice.map((die) => die.selected), [true, true, false, false])
  assert.equal(result.result, 87)
})

test('accepts a complete multi-module backend result', () => {
  const result: DiceResult = {
    formula: '2D6 + 1D8',
    modules: [
      {
        expression: '2D6', diceCount: 2, diceSides: 6, modifier: 'NORMAL', result: 9,
        dice: [
          { sides: 6, value: 4, role: 'NORMAL', selected: true },
          { sides: 6, value: 5, role: 'NORMAL', selected: true },
        ],
      },
      {
        expression: '1D8', diceCount: 1, diceSides: 8, modifier: 'NORMAL', result: 7,
        dice: [{ sides: 8, value: 7, role: 'NORMAL', selected: true }],
      },
    ],
    result: 16,
  }

  assert.deepEqual(validatePlayableDiceResult(result), [])
})

test('reports missing results and invalid percentile selections before opening the player', () => {
  const result: DiceResult = {
    formula: '1D100#',
    modules: [{
      expression: '1D100#', diceCount: 1, diceSides: 100, modifier: 'ADVANTAGE',
      dice: [
        { sides: 10, value: 3, role: 'PERCENTILE_ONES', selected: true },
        { sides: 10, value: 1, role: 'PERCENTILE_TENS', selected: false },
        { sides: 10, value: 7, role: 'PERCENTILE_TENS', selected: false },
      ],
    }],
  }

  assert.deepEqual(validatePlayableDiceResult(result), [
    '缺少总结果',
    '模块 1 缺少模块结果',
    '模块 1 必须且只能选中一个十位骰',
  ])
})

test('rejects values that cannot be represented by an available 3D model', () => {
  const result: DiceResult = {
    formula: '1D7',
    modules: [{
      expression: '1D7', diceCount: 1, diceSides: 7, modifier: 'NORMAL', result: 7,
      dice: [{ sides: 7, value: 7, role: 'NORMAL', selected: true }],
    }],
    result: 7,
  }

  assert.deepEqual(validatePlayableDiceResult(result), ['模块 1 的骰子 1 暂不支持 D7 动画'])
})

test('creates a new immutable playback request when the same result is replayed', () => {
  const result = createDiceDebugPreset('group')

  const first = createDicePlaybackRequest(0, result, 'galaxy')
  const second = createDicePlaybackRequest(first.id, result, 'galaxy')
  result.modules[0].dice[0].value = 1

  assert.equal(first.id, 1)
  assert.equal(second.id, 2)
  assert.equal(first.skin, 'galaxy')
  assert.equal(first.result.modules[0].dice[0].value, 4)
})

test('creates a playback request from a Vue reactive debug form', () => {
  const formResult = reactive(createDiceDebugPreset('normal-percentile'))

  const request = createDicePlaybackRequest(4, formResult, 'classic')

  assert.equal(request.id, 5)
  assert.equal(request.result.formula, '1D100')
  assert.equal(request.result.modules[0].dice[0].value, 7)
})

test('keeps the production roll reason in an immutable playback request', () => {
  const result = createDiceDebugPreset('normal-percentile')

  const request = createDicePlaybackRequest(8, result, 'classic', '侦查检定')
  result.formula = 'changed after playback'

  assert.equal(request.id, 9)
  assert.equal(request.reason, '侦查检定')
  assert.equal(request.result.formula, '1D100')
})

test('summarizes a playback result for the player window', () => {
  const summary = createDicePlayerSummary(createDiceDebugPreset('group'), 'moonwhite')

  assert.deepEqual(summary, {
    skinLabel: '月白冰晶',
    moduleLabel: '2 组判定',
    diceLabel: '5 颗骰子',
    modifierLabel: '常规判定',
    selectionLabel: '5 颗全部计入',
    groups: [
      { label: '第 1 组', expression: '3D6', result: 15, diceCount: 3 },
      { label: '第 2 组', expression: '2D8', result: 10, diceCount: 2 },
    ],
    equation: '3D6 + 2D8 = 25',
    resultLabel: '最终结果',
    resultValue: 25,
    formulaLabel: '判定公式',
    formulaValue: '3D6 + 2D8',
  })
})

test('explains percentile modifiers and discarded dice in the player window', () => {
  const summary = createDicePlayerSummary(createDiceDebugPreset('double-advantage'), 'classic')

  assert.equal(summary.modifierLabel, '双奖励骰')
  assert.equal(summary.selectionLabel, '2 颗计入 · 2 颗舍弃')
})

test('uses singular labels and a pending equation when a result has not settled', () => {
  const result: DiceResult = {
    formula: '1D20',
    modules: [{
      expression: '1D20', diceCount: 1, diceSides: 20, modifier: 'NORMAL',
      dice: [{ sides: 20, value: 12, role: 'NORMAL', selected: true }],
    }],
  }

  assert.deepEqual(createDicePlayerSummary(result, 'classic'), {
    skinLabel: '经典',
    moduleLabel: '1 组判定',
    diceLabel: '1 颗骰子',
    modifierLabel: '常规判定',
    selectionLabel: '1 颗全部计入',
    groups: [{ label: '第 1 组', expression: '1D20', result: '—', diceCount: 1 }],
    equation: '1D20 = —',
    resultLabel: '最终结果',
    resultValue: '—',
    formulaLabel: '判定公式',
    formulaValue: '1D20',
  })
})

test('keeps the final result concealed until the dice finish rolling', () => {
  assert.deepEqual(createDicePlayerStatus('loading'), {
    label: '正在准备',
    hint: '加载骰子与判定桌面',
    revealResult: false,
    showDieValues: false,
    actionLabel: '准备骰子',
    actionDisabled: true,
  })
  assert.deepEqual(createDicePlayerStatus('playing'), {
    label: '正在投掷',
    hint: '结果将在骰子停稳后揭晓',
    revealResult: false,
    showDieValues: false,
    actionLabel: '正在掷骰',
    actionDisabled: true,
  })
  assert.deepEqual(createDicePlayerStatus('complete'), {
    label: '判定完成',
    hint: '最终点数已锁定',
    revealResult: true,
    showDieValues: true,
    actionLabel: '重放动画',
    actionDisabled: false,
  })
  assert.equal(createDicePlayerStatus('error').revealResult, true)
  assert.equal(createDicePlayerStatus('error').showDieValues, false)
})

test('shows a concealed ready state after every die has been preloaded', () => {
  const ready = createDicePlayerStatus('ready')

  assert.deepEqual(ready, {
    label: '准备就绪',
    hint: '骰子正在待机，点击掷骰开始判定',
    revealResult: false,
    showDieValues: false,
    actionLabel: '掷骰',
    actionDisabled: false,
  })
})

test('locks the roll control only while dice are loading or rolling', () => {
  assert.deepEqual(
    ['loading', 'playing', 'complete'].map((phase) => {
      const state = createDicePlayerStatus(phase as 'loading' | 'playing' | 'complete') as ReturnType<typeof createDicePlayerStatus> & {
        actionLabel: string
        actionDisabled: boolean
      }
      return [state.actionLabel, state.actionDisabled]
    }),
    [
      ['准备骰子', true],
      ['正在掷骰', true],
      ['重放动画', false],
    ],
  )
})

test('imports the full backend JSON payload without dropping additional fields', () => {
  const result = parseDiceResultJson(JSON.stringify({
    formula: '1D6',
    modules: [{
      expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL', result: 5,
      dice: [{ sides: 6, value: 5, role: 'NORMAL', selected: true, trace: 'server-die' }],
      trace: 'server-module',
    }],
    result: 5,
    trace: 'server-result',
  })) as DiceResult & { trace: string }

  assert.equal(result.trace, 'server-result')
  assert.equal((result.modules[0] as unknown as { trace: string }).trace, 'server-module')
  assert.equal((result.modules[0].dice[0] as unknown as { trace: string }).trace, 'server-die')
})

test('rejects JSON that is not a DiceResult object', () => {
  assert.throws(() => parseDiceResultJson('[1, 2, 3]'), /JSON 根节点必须是对象/)
  assert.throws(() => parseDiceResultJson('{}'), /JSON 缺少 formula 或 modules/)
  assert.throws(
    () => parseDiceResultJson('{"formula":"1D6","modules":[{}]}'),
    /模块 1 缺少 expression 或 dice/,
  )
})
