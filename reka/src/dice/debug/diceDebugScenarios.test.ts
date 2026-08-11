import assert from 'node:assert/strict'
import test from 'node:test'
import { createDiceMessagePlaybackRequest } from '../domain/dicePlayback.ts'
import {
  DICE_DEBUG_CONSTANT_SCENARIOS,
  DICE_DEBUG_TOOL_GROUPS,
  createDiceDebugAggregate,
} from './diceDebugScenarios.ts'

const backendToolNames = [
  'requestCheck',
  'requestGroupCheck',
  'requestOpposedCheck',
  'requestPushedCheck',
  'requestSanCheck',
  'rollSanLoss',
  'rollDamage',
  'rollHealing',
]

test('offers single-player and multiplayer debug scenarios for every backend dice tool', () => {
  assert.deepEqual(DICE_DEBUG_TOOL_GROUPS.map((group) => group.toolName), backendToolNames)

  for (const group of DICE_DEBUG_TOOL_GROUPS) {
    assert.deepEqual(group.scenarios.map((scenario) => scenario.audience), ['single', 'multiple'])
    for (const scenario of group.scenarios) {
      const aggregate = createDiceDebugAggregate(scenario.id)
      assert.equal(aggregate.summary.toolName, group.toolName)
      assert.equal(aggregate.results.length, scenario.audience === 'single' ? 1 : 2)
      assert.ok(aggregate.results.every((detail) => detail.summaryId === aggregate.summary.id))
      assert.ok(aggregate.results.every((detail) => detail.resultData))
    }
  }
})

test('keeps every backend-shaped debug scenario playable by the real dice window adapter', () => {
  const scenarios = [
    ...DICE_DEBUG_TOOL_GROUPS.flatMap((group) => group.scenarios),
    ...DICE_DEBUG_CONSTANT_SCENARIOS,
  ]

  for (const scenario of scenarios) {
    const aggregate = createDiceDebugAggregate(scenario.id)
    const request = createDiceMessagePlaybackRequest(0, aggregate, 'classic')

    assert.equal(request.id, 1)
    assert.equal(request.toolName, aggregate.summary.toolName)
    assert.ok(request.result.modules.length > 0)
  }
})

test('uses 1D6+1D8+2 for single-player and multiplayer damage debug scenarios', () => {
  const single = createDiceDebugAggregate('rollDamage-single')
  const multiple = createDiceDebugAggregate('rollDamage-multiple')

  assert.deepEqual(single.results.map((detail) => ({
    formula: detail.resultData?.formula,
    diceSides: detail.resultData?.modules.map((module) => module.diceSides),
    result: detail.resultData?.result,
  })), [
    { formula: '1D6+1D8+2', diceSides: [6, 8], result: 9 },
  ])
  assert.deepEqual(multiple.results.map((detail) => ({
    formula: detail.resultData?.formula,
    diceSides: detail.resultData?.modules.map((module) => module.diceSides),
    result: detail.resultData?.result,
  })), [
    { formula: '1D6+1D8+2', diceSides: [6, 8], result: 9 },
    { formula: '1D6+1D8+2', diceSides: [6, 8], result: 10 },
  ])
})

test('provides constant numeric scenarios for an all-placeholder single roll and a mixed multiplayer roll', () => {
  assert.deepEqual(DICE_DEBUG_CONSTANT_SCENARIOS.map((scenario) => scenario.id), [
    'constant-value-single',
    'constant-value-multiple',
  ])

  const single = createDiceDebugAggregate('constant-value-single')
  assert.equal(single.summary.toolName, 'rollDamage')
  assert.deepEqual(single.results.map((detail) => ({
    formula: detail.resultData?.formula,
    modules: detail.resultData?.modules.length,
    result: detail.resultData?.result,
  })), [{ formula: '4', modules: 0, result: 4 }])

  const multiple = createDiceDebugAggregate('constant-value-multiple')
  assert.deepEqual(multiple.results.map((detail) => ({
    formula: detail.resultData?.formula,
    modules: detail.resultData?.modules.length,
    result: detail.resultData?.result,
  })), [
    { formula: '3', modules: 0, result: 3 },
    { formula: '1D6 + 2', modules: 1, result: 6 },
  ])

  const singlePlayback = createDiceMessagePlaybackRequest(0, single, 'classic')
  const multiplePlayback = createDiceMessagePlaybackRequest(0, multiple, 'classic')
  assert.deepEqual(singlePlayback.result.modules.map((module) => module.placeholder), [true])
  assert.deepEqual(multiplePlayback.result.modules.map((module) => module.placeholder), [true, undefined])
})
