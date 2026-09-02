import assert from 'node:assert/strict'
import test from 'node:test'

test('builds a three-wave 3D playback that preserves every attribute die', async () => {
  const diceModule = await import('./characterCardCreationDice.ts').catch(() => ({})) as {
    buildAttributeDicePlayback?: (input: unknown, skin: string, previousId: number) => unknown
  }
  const buildPlayback = diceModule.buildAttributeDicePlayback
  const rolls = [
    { code: 'STR', formula: '3D6 * 5', dice: [4, 5, 3], result: 60 },
    { code: 'CON', formula: '3D6 * 5', dice: [2, 6, 4], result: 60 },
    { code: 'SIZ', formula: '(2D6 + 6) * 5', dice: [5, 3], result: 70 },
    { code: 'DEX', formula: '3D6 * 5', dice: [3, 3, 4], result: 50 },
    { code: 'APP', formula: '3D6 * 5', dice: [5, 4, 3], result: 60 },
    { code: 'INT', formula: '(2D6 + 6) * 5', dice: [4, 5], result: 75 },
    { code: 'POW', formula: '3D6 * 5', dice: [5, 5, 4], result: 70 },
    { code: 'EDU', formula: '(2D6 + 6) * 5', dice: [5, 4], result: 75 },
  ]
  const luckRolls = [{ code: 'LUCK', formula: '3D6 * 5', dice: [1, 6, 6], result: 65 }]

  const request = buildPlayback?.({ rolls, luckRolls }, 'cinnabar', 40) as {
    id?: number
    skin?: string
    result?: { modules: Array<{ expression: string, dice: Array<{ sides: number, value: number, selected: boolean }> }> }
    presentation?: { groups: Array<{ label: string, outcomeLabel: string }> }
    initialAnimation?: { groups: Array<{ moduleStart: number, moduleCount: number, startDelayMs: number }> }
  } | undefined

  assert.equal(request?.id, 41)
  assert.equal(request?.skin, 'cinnabar')
  assert.equal(request?.result?.modules.length, 9)
  assert.deepEqual(request?.result?.modules[0], {
    expression: '3D6 * 5',
    diceCount: 3,
    diceSides: 6,
    modifier: 'NORMAL',
    dice: [
      { sides: 6, value: 4, selected: true },
      { sides: 6, value: 5, selected: true },
      { sides: 6, value: 3, selected: true },
    ],
    result: 60,
  })
  assert.equal(request?.presentation?.groups[0]?.label, '力量 STR')
  assert.equal(request?.presentation?.groups[2]?.label, '体型 SIZ')
  assert.deepEqual(request?.presentation?.groups[8], {
    label: '幸运 LUCK',
    checkName: '3D6 * 5',
    outcomeLabel: '65',
    outcomeTone: 'none',
    success: false,
    moduleStart: 8,
    moduleCount: 1,
    rollResult: 65,
  })
  assert.deepEqual(request?.initialAnimation?.groups, [
    { moduleStart: 0, moduleCount: 3, startDelayMs: 0 },
    { moduleStart: 3, moduleCount: 3, startDelayMs: 700 },
    { moduleStart: 6, moduleCount: 3, startDelayMs: 1400 },
  ])
})

test('shows both D10 rolls for a significant-people background prompt', async () => {
  const diceModule = await import('./characterCardCreationDice.ts') as {
    buildBackgroundPromptDicePlayback?: (input: unknown, skin: string, previousId: number) => unknown
  }

  const request = diceModule.buildBackgroundPromptDicePlayback?.({
    category: 'SIGNIFICANT_PEOPLE',
    rolls: [7, 3],
    promptCodes: ['WHO_07', 'REASON_03'],
    prompts: ['一位旧友', '曾在困境中救过你'],
  }, 'classic', 8) as {
    id?: number
    result?: { modules: Array<{ diceSides: number, dice: Array<{ value: number }> }> }
    presentation?: { groups: Array<{ label: string, outcomeLabel: string }> }
  } | undefined

  assert.equal(request?.id, 9)
  assert.deepEqual(request?.result?.modules.map((module) => module.dice[0]?.value), [7, 3])
  assert.deepEqual(request?.result?.modules.map((module) => module.diceSides), [10, 10])
  assert.deepEqual(request?.presentation?.groups.map((group) => group.label), ['重要之人 · 是谁', '重要之人 · 原因'])
  assert.deepEqual(request?.presentation?.groups.map((group) => group.outcomeLabel), ['7', '3'])
})

test('includes percentile checks and conditional D10 increases from education growth', async () => {
  const { buildAttributeDicePlayback } = await import('./characterCardCreationDice.ts')

  const request = buildAttributeDicePlayback({
    rolls: [],
    luckRolls: [],
    educationGrowths: [
      { checkRoll: 83, increaseRoll: 7, eduBefore: 75, eduAfter: 82 },
      { checkRoll: 40, eduBefore: 82, eduAfter: 82 },
    ],
  }, 'classic', 3)

  assert.equal(request.result.modules.length, 3)
  assert.deepEqual(request.result.modules[0], {
    expression: '1D100',
    diceCount: 2,
    diceSides: 100,
    modifier: 'NORMAL',
    dice: [
      { sides: 10, value: 8, role: 'PERCENTILE_TENS', selected: true },
      { sides: 10, value: 3, role: 'PERCENTILE_ONES', selected: true },
    ],
    result: 83,
  })
  assert.deepEqual(request.result.modules[1]?.dice, [
    { sides: 10, value: 7, selected: true },
  ])
  assert.deepEqual(request.presentation?.groups.map((group) => group.label), [
    '教育成长 1 · 判定', '教育成长 1 · 提升', '教育成长 2 · 判定',
  ])
})

test('replays only luck and education growth dice from automatic creation', async () => {
  const diceModule = await import('./characterCardCreationDice.ts') as {
    buildAutoCharacterCardDicePlayback?: (input: unknown, skin: string, previousId: number) => unknown
  }

  const request = diceModule.buildAutoCharacterCardDicePlayback?.({
    buildRolls: {
      luck: 65,
      luckRolls: [[4, 4, 5]],
      educationChecks: [82, 41],
      educationIncreases: [7],
    },
    backgroundRolls: {
      ideology: 2,
      significantPersonWho: 7,
      significantPersonReason: 3,
      meaningfulLocation: 8,
      treasuredPossession: 4,
      trait: 10,
      directions: {},
    },
  }, 'cinnabar', 12) as {
    id?: number
    result?: { modules: Array<{ expression: string, dice: Array<{ value: number, role?: string }> }> }
    presentation?: { groups: Array<{ label: string, outcomeLabel: string }> }
  } | undefined

  assert.equal(request?.id, 13)
  assert.equal(request?.result?.modules.length, 4)
  assert.deepEqual(request?.result?.modules[0]?.dice.map((die) => die.value), [4, 4, 5])
  assert.deepEqual(request?.result?.modules[1]?.dice, [
    { sides: 10, value: 8, role: 'PERCENTILE_TENS', selected: true },
    { sides: 10, value: 2, role: 'PERCENTILE_ONES', selected: true },
  ])
  assert.deepEqual(request?.presentation?.groups.map((group) => group.label), [
    '幸运 LUCK',
    '教育成长 1 · 判定',
    '教育成长 1 · 提升',
    '教育成长 2 · 判定',
  ])
})

test('builds autoplay playback from the luck result returned after text import', async () => {
  const diceModule = await import('./characterCardCreationDice.ts') as {
    buildImportedCharacterLuckDicePlayback?: (result: unknown, name: string, skin: string, previousId: number) => unknown
  }

  const request = diceModule.buildImportedCharacterLuckDicePlayback?.({
    formula: '3D6 * 5',
    modules: [{
      expression: '3D6 * 5',
      diceCount: 3,
      diceSides: 6,
      modifier: 'NORMAL',
      dice: [
        { sides: 6, value: 4, selected: true },
        { sides: 6, value: 5, selected: true },
        { sides: 6, value: 3, selected: true },
      ],
      result: 60,
    }],
    result: 60,
  }, '周宁', 'cinnabar', 20) as {
    id?: number
    autoPlay?: boolean
    reason?: string
    result?: { result?: number }
    initialAnimation?: { groups: Array<{ moduleStart: number, moduleCount: number, startDelayMs: number }> }
    presentation?: { resultLabel?: string, resultValue?: string, groups?: Array<{ label: string, outcomeLabel: string }> }
  } | undefined

  assert.equal(request?.id, 21)
  assert.equal(request?.autoPlay, true)
  assert.equal(request?.reason, '周宁的幸运')
  assert.equal(request?.result?.result, 60)
  assert.deepEqual(request?.initialAnimation?.groups, [{ moduleStart: 0, moduleCount: 1, startDelayMs: 0 }])
  assert.equal(request?.presentation?.resultLabel, '幸运生成')
  assert.equal(request?.presentation?.resultValue, '60')
  assert.deepEqual(request?.presentation?.groups, [{
    label: '幸运 LUCK',
    checkName: '3D6 * 5',
    outcomeLabel: '60',
    outcomeTone: 'none',
    success: false,
    moduleStart: 0,
    moduleCount: 1,
    rollResult: 60,
  }])
})

test('closes instead of offering replay after every character-card roll', async () => {
  const diceModule = await import('./characterCardCreationDice.ts') as Record<string, (...args: any[]) => {
    completionAction?: string
  }>
  const diceResult = {
    formula: '3D6 * 5',
    modules: [{
      expression: '3D6 * 5', diceCount: 3, diceSides: 6, modifier: 'NORMAL',
      dice: [
        { sides: 6, value: 3, selected: true },
        { sides: 6, value: 4, selected: true },
        { sides: 6, value: 5, selected: true },
      ],
      result: 60,
    }],
    result: 60,
  }
  const requests = [
    diceModule.buildAttributeDicePlayback({
      rolls: [{ code: 'STR', formula: '3D6 * 5', dice: [3, 4, 5], result: 60 }],
      luckRolls: [],
    }, 'classic', 0),
    diceModule.buildBackgroundPromptDicePlayback({
      category: 'IDEOLOGY', rolls: [4], promptCodes: ['IDEOLOGY_04'], prompts: ['信念'],
    }, 'classic', 1),
    diceModule.buildImportedCharacterLuckDicePlayback(diceResult, '周宁', 'classic', 2),
    diceModule.buildAutoCharacterCardDicePlayback({
      buildRolls: { luck: 60, luckRolls: [[3, 4, 5]] },
    }, 'classic', 3),
  ]

  assert.deepEqual(requests.map((request) => request.completionAction), [
    'CLOSE', 'CLOSE', 'CLOSE', 'CLOSE',
  ])
})
