import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, nextTick, ref } from 'vue'
import type { DiceRollAggregate } from '../../api/types.ts'
import { splitDiceAggregateByRound } from '../domain/dicePlayback.ts'
import { createDiceAggregateFixture } from '../../../test/fixtures/dice.ts'

function group(): DiceRollAggregate {
  const aggregate = createDiceAggregateFixture('multiplayer-check')
  aggregate.summary.roundCount = 3
  aggregate.results.forEach((detail, index) => {
    detail.roundNo = index + 1
    detail.reason = `轮次 ${index + 1}`
  })
  return aggregate
}

async function setup(load: (id: number) => Promise<DiceRollAggregate>) {
  const { useDiceRoundNavigation } = await import('./useDiceRoundNavigation.ts')
  const scope = effectScope()
  const full = group()
  const aggregate = ref<DiceRollAggregate | null>(splitDiceAggregateByRound(full)[1]!)
  const open = ref(true)
  const context = ref('conversation-1')
  const errors: unknown[] = []
  const navigation = scope.run(() => useDiceRoundNavigation({
    aggregate, open, context: () => context.value, load,
    onError: error => errors.push(error),
  }))!
  return { full, aggregate, open, context, errors, navigation, scope }
}

test('round navigation loads the whole group even when the card only contains the middle round', async t => {
  const full = group()
  const state = await setup(async id => {
    assert.equal(id, full.summary.id)
    return full
  })
  t.after(() => state.scope.stop())
  await nextTick()
  assert.equal(state.navigation.roundPosition.value, 2)
  assert.equal(state.navigation.roundCount.value, 3)
  assert.deepEqual(state.navigation.previous.value?.results.map(r => r.roundNo), [1])
  assert.deepEqual(state.navigation.next.value?.results.map(r => r.roundNo), [3])
  state.aggregate.value = state.navigation.previous.value
  await nextTick()
  assert.equal(state.navigation.roundPosition.value, 1)
  assert.equal(Boolean(state.navigation.previous.value), false)
  assert.deepEqual(state.navigation.next.value?.results.map(r => r.roundNo), [2])
  state.aggregate.value = splitDiceAggregateByRound(full)[2]!
  await nextTick()
  assert.equal(state.navigation.roundPosition.value, 3)
  assert.deepEqual(state.navigation.previous.value?.results.map(r => r.roundNo), [2])
  assert.equal(state.navigation.next.value, null)
})

for (const invalidate of ['close', 'replace', 'context', 'dispose'] as const) {
  test(`round navigation ignores a late load after ${invalidate}`, async t => {
    let resolve!: (value: DiceRollAggregate) => void
    const state = await setup(() => new Promise(done => { resolve = done }))
    t.after(() => state.scope.stop())
    if (invalidate === 'close') state.open.value = false
    if (invalidate === 'replace') state.aggregate.value = null
    if (invalidate === 'context') state.context.value = 'conversation-2'
    if (invalidate === 'dispose') state.scope.stop()
    resolve(state.full)
    await nextTick()
    assert.equal(state.navigation.previous.value, null)
    assert.equal(state.navigation.next.value, null)
  })
}

test('round navigation rejects data from another group', async t => {
  const full = group()
  full.summary.id++
  const state = await setup(async () => full)
  t.after(() => state.scope.stop())
  await nextTick()
  assert.equal(state.navigation.previous.value, null)
  assert.equal(state.navigation.next.value, null)
})

test('a failed navigation load leaves the current roll usable and reports the failure', async t => {
  const failure = new Error('offline')
  const state = await setup(async () => { throw failure })
  t.after(() => state.scope.stop())
  await nextTick()
  assert.equal(state.aggregate.value?.results[0]?.roundNo, 2)
  assert.equal(state.navigation.previous.value, null)
  assert.equal(state.navigation.next.value, null)
  assert.deepEqual(state.errors, [failure])
})
