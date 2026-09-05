import assert from 'node:assert/strict'
import test from 'node:test'
import type { CocModuleSavePayload } from '../api/types.ts'
import {
  cocModulePayloadFingerprint,
  createCocModuleSaveQueue,
  saveCocModuleIfNeeded,
  validateCocModulePayload,
} from './cocModuleAutosave.ts'

function validPayload(): CocModuleSavePayload {
  return {
    name: '雾中来客',
    introduction: '调查海边村庄的失踪事件。',
    visible: true,
    context: {},
    locations: [{ name: '旧宅', summary: '废弃宅邸', content: '门窗紧闭。' }],
    clues: [{ title: '染血信件', content: '落款日期被涂去。', important: true }],
    materials: [{ title: '旧报纸', description: '失踪事件报道', imageUrl: '/uploads/paper.png' }],
    characters: [],
  }
}

function addCharacterWithDamageBonus(payload: CocModuleSavePayload, damageBonus?: string) {
  payload.characters.push({
    character: {
      actorType: 'BOT', name: '林默', str: 50, con: 60, siz: 65, dex: 65,
      app: 55, intValue: 70, pow: 60, edu: 70, damageBonus,
    },
    skills: [], weapons: [], profile: {},
  })
}

test('rejects incomplete collection rows before autosave', () => {
  const cases: Array<[string, (payload: CocModuleSavePayload) => void, string]> = [
    ['location', (payload) => payload.locations.push({ name: '', summary: '', content: '' }), '地点名称、摘要和正文不能为空'],
    ['clue', (payload) => payload.clues.push({ title: '', content: '', important: false }), '线索标题和正文不能为空'],
    ['material', (payload) => payload.materials.push({ title: '', description: '', imageUrl: '' }), '素材标题和介绍不能为空'],
  ]

  for (const [label, mutate, expected] of cases) {
    const payload = validPayload()
    mutate(payload)
    assert.equal(validateCocModulePayload(payload), expected, label)
  }
  assert.equal(validateCocModulePayload(validPayload()), null)
})

test('allows a material without an image before autosave', () => {
  const payload = validPayload()
  payload.materials = [{ title: '梦境记录', description: '供守秘人朗读的纯文字材料', imageUrl: '' }]

  assert.equal(validateCocModulePayload(payload), null)
})

test('rejects a filled damage bonus unless it uses a legal CoC DB value', () => {
  for (const damageBonus of [undefined, '', '  ', '-2', '-1', '0', '1D4', '+1D4', ' 1d6 ', ' +1d6 ', '+2D6']) {
    const payload = validPayload()
    addCharacterWithDamageBonus(payload, damageBonus)
    assert.equal(validateCocModulePayload(payload), null, String(damageBonus))
  }

  for (const damageBonus of ['+0D6', '+1D8', '-1D4', 'DB', '火焰']) {
    const payload = validPayload()
    addCharacterWithDamageBonus(payload, damageBonus)
    assert.equal(validateCocModulePayload(payload), '模组角色卡“林默”的伤害加值（DB）格式不合法', damageBonus)
  }
})

test('serializes saves so an older request cannot finish after a newer request', async () => {
  const starts: string[] = []
  const releases: Array<() => void> = []
  const queue = createCocModuleSaveQueue(async (_moduleId, payload) => {
    starts.push(payload.name)
    await new Promise<void>((resolve) => releases.push(resolve))
  })

  const first = queue.enqueue(7, { ...validPayload(), name: '第一版' })
  await Promise.resolve()
  const second = queue.enqueue(7, { ...validPayload(), name: '第二版' })
  await Promise.resolve()

  assert.deepEqual(starts, ['第一版'])
  releases.shift()?.()
  await first
  await Promise.resolve()
  assert.deepEqual(starts, ['第一版', '第二版'])
  releases.shift()?.()
  await second
})

test('captures an immutable payload snapshot when a save is queued', async () => {
  let received = ''
  const queue = createCocModuleSaveQueue(async (_moduleId, payload) => {
    received = payload.name
  })
  const payload = validPayload()

  const saving = queue.enqueue(7, payload)
  payload.name = '排队后的修改'
  await saving

  assert.equal(received, '雾中来客')
})

test('uses normalized payload content to detect unsaved changes', () => {
  const original = validPayload()
  const unchangedCopy = structuredClone(original)
  const changed = structuredClone(original)
  changed.context.truthBackground = '村庄居民共同隐瞒了真相。'

  assert.equal(cocModulePayloadFingerprint(original), cocModulePayloadFingerprint(unchangedCopy))
  assert.notEqual(cocModulePayloadFingerprint(original), cocModulePayloadFingerprint(changed))
})

test('does not send unchanged or invalid payloads during conditional autosave', async () => {
  let calls = 0
  const queue = createCocModuleSaveQueue(async () => { calls += 1 })
  const payload = validPayload()
  const savedFingerprint = cocModulePayloadFingerprint(payload)

  assert.deepEqual(await saveCocModuleIfNeeded(queue, 7, payload, savedFingerprint), { status: 'unchanged' })
  payload.locations.push({ name: '', summary: '', content: '' })
  assert.deepEqual(await saveCocModuleIfNeeded(queue, 7, payload, savedFingerprint), {
    status: 'invalid', message: '地点名称、摘要和正文不能为空',
  })
  assert.equal(calls, 0)
})

test('returns the saved snapshot fingerprint after a successful autosave', async () => {
  const queue = createCocModuleSaveQueue(async () => undefined)
  const payload = validPayload()
  const previousFingerprint = cocModulePayloadFingerprint(payload)
  payload.context.truthBackground = '村庄居民共同隐瞒了真相。'

  const result = await saveCocModuleIfNeeded(queue, 7, payload, previousFingerprint)

  assert.deepEqual(result, { status: 'saved', fingerprint: cocModulePayloadFingerprint(payload) })
})

test('reports a failed autosave without modifying the local payload', async () => {
  const queue = createCocModuleSaveQueue(async () => { throw new Error('网络连接中断') })
  const payload = validPayload()
  const previousFingerprint = cocModulePayloadFingerprint(payload)
  payload.introduction = '本地尚未提交的新简介。'

  const result = await saveCocModuleIfNeeded(queue, 7, payload, previousFingerprint)

  assert.deepEqual(result, { status: 'error', message: '网络连接中断' })
  assert.equal(payload.introduction, '本地尚未提交的新简介。')
})
