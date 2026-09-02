import assert from 'node:assert/strict'
import test from 'node:test'

test('parses Chinese and abbreviated attributes, matches Chinese skills, and derives missing combat values', async () => {
  const module = await import('./cocModuleCharacterImport.ts').catch(() => ({})) as {
    parseModuleCharacterText?: (text: string, skillNames: string[]) => unknown
  }

  const draft = module.parseModuleCharacterText?.(`
阿利斯泰尔·罗森与布莱恩·霍尔
力量50 CON60 体型65 敏捷65 智力65
外貌60 POW70 教育70 SAN70 HP12
伤害加值：+1D4
体格：1
移动：8
格斗（斗殴）25%（12/5）伤害 1D3+DB
潜行 40%
射击（.30-06 栓动式步枪）35%（17/7）伤害 2D6+4
  `, ['斗殴', '潜行', '射击:步枪/霰弹枪']) as {
    character?: Record<string, unknown>
    skills?: Array<{ displayName: string, value: number }>
    unresolvedLines?: string[]
  } | undefined

  assert.deepEqual(draft?.character, {
    name: '阿利斯泰尔·罗森与布莱恩·霍尔',
    str: 50,
    con: 60,
    siz: 65,
    dex: 65,
    app: 60,
    intValue: 65,
    pow: 70,
    edu: 70,
    hpMax: 12,
    hpCurrent: 12,
    sanMax: 70,
    sanCurrent: 70,
    mpMax: 14,
    mpCurrent: 14,
    damageBonus: '+1D4',
    build: 1,
    mov: 8,
  })
  assert.deepEqual(draft?.skills, [
    { displayName: '斗殴', value: 25 },
    { displayName: '潜行', value: 40 },
  ])
  assert.deepEqual(draft?.unresolvedLines, [
    '格斗（斗殴）25%（12/5）伤害 1D3+DB',
    '射击（.30-06 栓动式步枪）35%（17/7）伤害 2D6+4',
  ])
})

test('derives HP, SAN, MP, damage bonus, build and MOV when they are omitted', async () => {
  const module = await import('./cocModuleCharacterImport.ts').catch(() => ({})) as {
    parseModuleCharacterText?: (text: string, skillNames: string[]) => unknown
  }

  const draft = module.parseModuleCharacterText?.(`
林默
STR 40 CON 50 SIZ 60 DEX 70 APP 55 INT 65 POW 60 EDU 70
侦查 55%
  `, ['侦查']) as { character?: Record<string, unknown> } | undefined

  assert.deepEqual(draft?.character, {
    name: '林默',
    str: 40,
    con: 50,
    siz: 60,
    dex: 70,
    app: 55,
    intValue: 65,
    pow: 60,
    edu: 70,
    hpMax: 11,
    hpCurrent: 11,
    sanMax: 60,
    sanCurrent: 60,
    mpMax: 12,
    mpCurrent: 12,
    damageBonus: '0',
    build: 0,
    mov: 8,
  })
})

test('matches every skill-value pair when several skills share one line', async () => {
  const module = await import('./cocModuleCharacterImport.ts').catch(() => ({})) as {
    parseModuleCharacterText?: (text: string, skillNames: string[]) => unknown
  }

  const draft = module.parseModuleCharacterText?.(
    '说服 39% 话术 31% 潜行 25% 侦查 41% 医学 27%',
    ['说服', '话术', '潜行', '侦查', '医学'],
  ) as { skills?: Array<{ displayName: string, value: number }> } | undefined

  assert.deepEqual(draft?.skills, [
    { displayName: '说服', value: 39 },
    { displayName: '话术', value: 31 },
    { displayName: '潜行', value: 25 },
    { displayName: '侦查', value: 41 },
    { displayName: '医学', value: 27 },
  ])
})

test('rejects unsupported damage syntax and restricts three-range damage to shotguns', async () => {
  const module = await import('./cocModuleCharacterImport.ts').catch(() => ({})) as {
    validateWeaponDamage?: (damage: string, skillName: string) => string | null
  }

  assert.equal(module.validateWeaponDamage?.('1D8+DB', '斗殴'), null)
  assert.equal(module.validateWeaponDamage?.('晕眩', '斗殴'), null)
  assert.equal(module.validateWeaponDamage?.('4D6/2D6/1D6', '射击:步枪/霰弹枪'), null)
  assert.equal(module.validateWeaponDamage?.('4D6/2D6/1D6', '射击:手枪'), '只有霰弹枪可以填写近/中/远三级伤害')
  assert.equal(module.validateWeaponDamage?.('1D8+火焰', '斗殴'), '伤害仅支持数字、掷骰、DB、半DB或晕眩')
})
