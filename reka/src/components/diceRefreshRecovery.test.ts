import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test, { type TestContext } from 'node:test'
import type { DiceRollAggregate, GroupMessage } from '../api/types.ts'

const sourceRoot = new URL('../', import.meta.url)
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}.ts`, sourceRoot).href, shortCircuit: true }
    }
    if (context.parentURL?.startsWith(sourceRoot.href)
      && /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
      return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (!url.endsWith('/api/client.ts')) return nextLoad(url, context)
    const source = readFileSync(new URL(url), 'utf8')
    return {
      format: 'module-typescript',
      shortCircuit: true,
      source: source.replace('import.meta.env.VITE_API_BASE_URL', 'undefined'),
    }
  },
})

async function fixture(t: TestContext) {
  const { api } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previous = { window: globalThis.window, localStorage: globalThis.localStorage, sessionStorage: globalThis.sessionStorage }
  const storage = { getItem: () => null, setItem() {}, removeItem() {} }
  Object.assign(globalThis, { window: { addEventListener() {}, clearTimeout, setTimeout }, localStorage: storage, sessionStorage: storage })
  t.after(() => Object.assign(globalThis, previous))
  let workspace!: ReturnType<typeof useWorkspace>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: node => node.parent as Record<string, unknown> | null,
    nextSibling: () => null,
  })
  const app = renderer.createApp(defineComponent({ setup() { workspace = useWorkspace(); return () => h('div') } }))
  app.mount({})
  t.after(() => app.unmount())
  workspace.selectedConversationId.value = 7
  const aggregate: DiceRollAggregate = {
    summary: { id: 501, conversationId: 7, roundCount: 2, status: 'PENDING' },
    results: [
      { id: 601, summaryId: 501, roundNo: 1, resolvedAt: '2026-09-30T10:00:00', resolution: { type: 'SAN_CHECK' } },
      { id: 602, summaryId: 501, roundNo: 2, resolution: { type: 'SAN_LOSS' } },
    ],
  }
  const persisted: GroupMessage = {
    id: 100, conversationId: 7, speakerType: 'kp', messageKind: 'dice_roll', status: 'completed', sequenceNo: 10,
    content: JSON.stringify({ summaryId: 501, roundNos: [1, 2] }),
  }
  workspace.messages.value = [{ ...persisted, content: '', diceRoundNos: [1],
    diceRoll: { ...aggregate, results: [aggregate.results[0]!] } }]
  t.mock.method(api, 'diceSummary', async () => structuredClone(aggregate.summary))
  t.mock.method(api, 'diceResults', async () => structuredClone(aggregate.results))
  t.mock.method(api, 'groupMessages', async () => [structuredClone(persisted)])
  return { api, workspace, aggregate, persisted }
}

test('a retry with no newly created dice recovers persisted follow-up cards without enqueueing playback', async t => {
  const { api, workspace, aggregate } = await fixture(t)
  t.mock.method(api, 'rollDiceResult', async () => ({ summary: aggregate.summary, rolledResult: aggregate.results[0]!, createdResults: [] }))
  const progress = await api.rollDiceResult(601)
  const refreshed = await workspace.refreshDiceRoll(progress.summary.id)
  assert.deepEqual(workspace.messages.value[0]?.diceRoundNos, [1, 2])
  assert.deepEqual(workspace.messages.value[0]?.diceRoll?.results.map(r => r.id), [601, 602])
  assert.deepEqual(refreshed.results.map(r => r.id), [601, 602])
  assert.deepEqual(workspace.incomingDiceRolls.value, [])
})

test('a failed refresh leaves the message intact and a later retry recovers all rounds', async t => {
  const { api, workspace, aggregate } = await fixture(t)
  let fail = true
  t.mock.method(api, 'diceResults', async () => { if (fail) throw new Error('connection lost'); return aggregate.results })
  await assert.rejects(workspace.refreshDiceRoll(501), /connection lost/)
  assert.deepEqual(workspace.messages.value[0]?.diceRoundNos, [1])
  fail = false
  await workspace.refreshDiceRoll(501)
  assert.deepEqual(workspace.messages.value[0]?.diceRoundNos, [1, 2])
})

test('refresh keeps legacy message ownership and ignores unrelated streaming messages', async t => {
  const { api, workspace, aggregate, persisted } = await fixture(t)
  workspace.messages.value.push({ ...persisted, id: 101, sequenceNo: 11, content: '', diceRoundNos: [2],
    diceRoll: { ...aggregate, results: [aggregate.results[1]!] } },
    { ...persisted, id: 102, messageKind: 'dialogue', content: '正在生成', status: 'streaming' })
  aggregate.results.push({ id: 603, summaryId: 501, roundNo: 3 })
  t.mock.method(api, 'groupMessages', async () => [
    { ...persisted, content: JSON.stringify({ summaryId: 501, roundNos: [1] }) },
    { ...persisted, id: 101, content: JSON.stringify({ summaryId: 501, roundNos: [2, 3] }) },
  ])
  await workspace.refreshDiceRoll(501)
  await workspace.refreshDiceRoll(501)
  assert.deepEqual(workspace.messages.value.slice(0, 2).map(m => m.diceRoll?.results.map(r => r.id)), [[601], [602, 603]])
  assert.equal(workspace.messages.value[2]?.content, '正在生成')
  assert.equal(workspace.messages.value[2]?.status, 'streaming')
  assert.deepEqual(workspace.incomingDiceRolls.value, [])
})

test('refresh finds a dice message outside the latest history page', async t => {
  const { api, workspace, persisted } = await fixture(t)
  t.mock.method(api, 'groupMessages', async (conversationId: number, beforeId?: number) => {
    assert.equal(conversationId, 7)
    if (beforeId == null) return Array.from({ length: 50 }, (_, i) => ({ ...persisted, id: 200 + i, messageKind: 'dialogue', content: '后续消息' }))
    assert.equal(beforeId, 200)
    return [persisted]
  })
  await workspace.refreshDiceRoll(501)
  assert.deepEqual(workspace.messages.value[0]?.diceRoundNos, [1, 2])
  assert.equal(workspace.messages.value.length, 1)
})

test('a refresh finishing after switching conversation does not publish stale dice state', async t => {
  const { api, workspace, persisted } = await fixture(t)
  let finish!: (messages: GroupMessage[]) => void
  t.mock.method(api, 'groupMessages', () => new Promise<GroupMessage[]>(resolve => { finish = resolve }))
  const request = workspace.refreshDiceRoll(501)
  workspace.selectedConversationId.value = 8
  workspace.messages.value = []
  finish([persisted])
  await request
  assert.deepEqual(workspace.messages.value, [])
  assert.equal(workspace.latestDiceRoll.value, null)
})

// Execute the actual App handlers with a real workspace and controlled HTTP results.
// Rendering/WebGL is outside this test; request transitions and queues are observable here.
async function appDiceHandlers(workspace: Awaited<ReturnType<typeof fixture>>['workspace'], api: Awaited<ReturnType<typeof fixture>>['api']) {
  const ts = await import('typescript')
  const dice = await import('../dice/domain/dicePlayback.ts')
  const { ref, watch } = await import('vue')
  const script = readFileSync(new URL('../App.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]!
  const parsed = ts.createSourceFile('App.ts', script, ts.ScriptTarget.Latest, true)
  const names = ['createMessagePlaybackRequest', 'openDiceMessage', 'navigateDiceRound', 'rollDiceMessage', 'completeDiceMessageRoll', 'continueAfterDice']
  const handlers = parsed.statements.filter(node => ts.isFunctionDeclaration(node) && node.name && names.includes(node.name.text))
  assert.equal(handlers.length, names.length)
  const state = {
    dicePlayerOpen: ref(false), dicePlaybackRequest: ref<import('../dice/domain/dicePlayback.ts').DicePlaybackRequest | null>(null),
    diceMessageAggregate: ref<DiceRollAggregate | null>(null), queuedDiceAggregates: ref<DiceRollAggregate[]>([]),
    diceShowContinue: ref(false), autoAdvanceDiceSummaryIds: ref(new Set<number>()),
    diceRoundNavigation: { previous: ref<DiceRollAggregate | null>(null), next: ref<DiceRollAggregate | null>(null) },
  }
  const notices: string[] = []
  const dependencies = { ...dice, ...state, workspace, api, watch, notify(title: string) { notices.push(title) }, errorMessage: String }
  const javascript = ts.transpileModule(handlers.map(node => node.getText(parsed)).join('\n'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText
  const actions = new Function(...Object.keys(dependencies), `${javascript}\nreturn { ${names.join(',')} }`)(...Object.values(dependencies)) as {
    openDiceMessage: (aggregate: DiceRollAggregate) => void
    navigateDiceRound: (direction: -1 | 1) => void
    rollDiceMessage: () => Promise<void>
    completeDiceMessageRoll: () => void
    continueAfterDice: () => Promise<void>
  }
  return { ...state, ...actions, notices }
}

test('round browsing shows settled results without autoplay and preserves pending rolls and the Continue queue', async t => {
  const { workspace, api, aggregate } = await fixture(t)
  const dice = await import('../dice/domain/dicePlayback.ts')
  const { createDiceResultFixture } = await import('../../test/fixtures/dice.ts')
  aggregate.results.forEach(detail => { detail.resultData = createDiceResultFixture('normal-percentile') })
  aggregate.results[0]!.reason = '第一轮'
  aggregate.results[1]!.reason = '第二轮'
  const [previous, pending] = dice.splitDiceAggregateByRound(aggregate)
  const handlers = await appDiceHandlers(workspace, api)
  handlers.openDiceMessage(pending!)
  handlers.queuedDiceAggregates.value = [pending!]
  handlers.diceRoundNavigation.previous.value = previous!
  handlers.diceRoundNavigation.next.value = pending!
  const requestId = handlers.dicePlaybackRequest.value!.id

  handlers.navigateDiceRound(-1)
  assert.equal(handlers.dicePlaybackRequest.value?.reason, '第一轮')
  assert.equal(handlers.dicePlaybackRequest.value?.mode, 'settled')
  assert.equal(handlers.dicePlaybackRequest.value?.autoPlay, false)
  assert.ok(handlers.dicePlaybackRequest.value!.id > requestId)
  handlers.completeDiceMessageRoll()
  assert.equal(handlers.diceShowContinue.value, false, 'replaying history does not advance the live queue')
  assert.deepEqual(handlers.queuedDiceAggregates.value, [pending])

  handlers.navigateDiceRound(1)
  assert.equal(handlers.dicePlaybackRequest.value?.reason, '第二轮')
  assert.equal(handlers.dicePlaybackRequest.value?.mode, 'pending')
  assert.equal(handlers.dicePlaybackRequest.value?.autoPlay, false)
  assert.equal(handlers.dicePlaybackRequest.value?.offerContinueAfterComplete, true)
  assert.equal(handlers.diceMessageAggregate.value?.results[0]?.id, 602)
  assert.equal(handlers.diceShowContinue.value, false)

  const lastRequest = handlers.dicePlaybackRequest.value
  handlers.diceRoundNavigation.next.value = null
  handlers.navigateDiceRound(1)
  assert.equal(handlers.dicePlaybackRequest.value, lastRequest)
  handlers.diceShowContinue.value = true
  handlers.navigateDiceRound(-1)
  assert.equal(handlers.dicePlaybackRequest.value, lastRequest)
  handlers.diceShowContinue.value = false
  const rolledIds: number[] = []
  t.mock.method(api, 'rollDiceResult', async (id: number) => {
    rolledIds.push(id)
    aggregate.results[1]!.resolvedAt = '2026-09-30T10:01:00'
    aggregate.summary.status = 'COMPLETED'
    return { summary: aggregate.summary, rolledResult: aggregate.results[1]!, createdResults: [] }
  })
  await handlers.rollDiceMessage()
  assert.deepEqual(rolledIds, [602], 'rolling after browsing still submits the selected pending round')
  handlers.completeDiceMessageRoll()
  assert.equal(handlers.diceShowContinue.value, true, 'the live Continue flow resumes after rolling')
  assert.equal(handlers.queuedDiceAggregates.value.length, 0, 'the displayed round is not duplicated in the queue')
  assert.deepEqual(handlers.notices, [])
})

for (const kind of ['OPPOSED_CHECK', 'DAMAGE'] as const) {
  for (const mixedNpc of [false, true]) {
    for (const failure of ['none', 'response', 'refresh'] as const) {
    test(`${kind}: three participants${mixedNpc ? ' including NPCs' : ''} complete in one playback without participant hops (${failure})`, async t => {
      const { api, workspace, aggregate, persisted } = await fixture(t)
      const { createDiceResultFixture } = await import('../../test/fixtures/dice.ts')
      aggregate.summary = { ...aggregate.summary, status: 'COMPLETED', roundCount: 1,
        toolName: kind === 'DAMAGE' ? 'rollDamage' : 'requestOpposedCheck' }
      aggregate.results = ['林恩', '周晴', '守卫'].map((name, index) => ({
        id: 601 + index, summaryId: 501, roundNo: 1, displayOrder: index + 1,
        characterId: mixedNpc && index > 0 ? 11 + index : undefined,
        resolvedAt: '2026-09-30T10:00:00', displayType: kind,
        resultData: createDiceResultFixture(kind === 'DAMAGE' ? 'standard' : 'normal-percentile'),
        resolution: { type: kind, characterName: name, checkName: '侦查',
          outcome: { characterName: name, checkName: '侦查', category: 'SUCCESS', winner: index === 0 } },
      }))
      persisted.content = JSON.stringify({ summaryId: 501, roundNos: [1] })
      const pending = structuredClone(aggregate)
      pending.summary.status = 'PENDING'
      pending.results.forEach(result => { if (result.characterId == null) {
        delete result.resolvedAt
        delete result.resultData!.result
        delete result.resolution!.outcome
      } })
      workspace.messages.value[0]!.diceRoll = pending
      const settled = structuredClone(aggregate.results)
      aggregate.results = structuredClone(pending.results)
      aggregate.summary.status = 'PENDING'
      let rolls = 0
      let failed = false
      if (failure === 'refresh') t.mock.method(api, 'groupMessages', async () => {
        if (!failed) { failed = true; throw new Error('history response lost') }
        return [persisted]
      })
      t.mock.method(api, 'rollDiceResult', async (id: number) => {
        rolls++
        // Opposed checks roll the whole player bundle; damage uses one bundle per target.
        aggregate.results = aggregate.results.map(result => kind === 'OPPOSED_CHECK' || result.id === id
          ? settled.find(done => done.id === result.id)! : result)
        aggregate.summary.status = aggregate.results.some(result => !result.resolvedAt) ? 'PENDING' : 'COMPLETED'
        if (failure === 'response' && !failed && (kind !== 'DAMAGE' || mixedNpc || id === 602)) {
          failed = true
          throw new Error('roll committed but response lost')
        }
        return { summary: aggregate.summary, rolledResult: aggregate.results.find(result => result.id === id)!, createdResults: [] }
      })
      let continued = 0
      t.mock.method(workspace, 'startTrpgTurn', async () => { continued++; return true })
      const app = await appDiceHandlers(workspace, api)
      app.openDiceMessage(pending)
      const initialId = app.dicePlaybackRequest.value!.id
      const initialGroups = app.dicePlaybackRequest.value!.presentation!.groups.map(({ label, checkName }) => ({ label, checkName }))
      assert.deepEqual(initialGroups.map(group => group.label), ['林恩', '周晴', '守卫'])
      await app.rollDiceMessage()
      if (failure !== 'none') {
        assert.equal(app.dicePlaybackRequest.value!.mode, 'pending')
        assert.deepEqual(app.queuedDiceAggregates.value, [])
        await app.rollDiceMessage()
      }
      const successfulRequestId = initialId + (failure === 'none' ? 1 : 2)
      assert.equal(rolls, (kind === 'DAMAGE' && !mixedNpc ? 3 : 1) + (failure === 'none' ? 0 : 1))
      assert.equal(app.dicePlaybackRequest.value!.id, successfulRequestId, 'one pending-to-play transition')
      assert.equal(app.dicePlaybackRequest.value!.presentation?.groups.length, 3)
      assert.deepEqual(app.dicePlaybackRequest.value!.presentation!.groups.map(({ label, checkName }) => ({ label, checkName })), initialGroups, 'participant names and check labels stay stable after rolling')
      assert.deepEqual(app.diceMessageAggregate.value!.results.map(result => result.id), [601, 602, 603])
      assert.deepEqual(app.queuedDiceAggregates.value, [], 'same-round participants must not become separate windows')
      assert.equal(app.dicePlayerOpen.value, true)
      await workspace.refreshDiceRoll(501)
      assert.equal(app.dicePlaybackRequest.value!.id, successfulRequestId, 'card refresh must not restart playback')
      app.completeDiceMessageRoll()
      await app.continueAfterDice()
      assert.equal(app.dicePlayerOpen.value, false)
      assert.equal(continued, 1, 'one continue returns to the game')
    })
  }
}

}

test('three damage targets queue one real CON follow-up round without rolling it early', async t => {
  const { api, workspace, aggregate, persisted } = await fixture(t)
  const { createDiceResultFixture } = await import('../../test/fixtures/dice.ts')
  aggregate.summary = { ...aggregate.summary, roundCount: 1, toolName: 'rollDamage' }
  aggregate.results = [601, 602, 603].map((id, index) => ({
    id, summaryId: 501, roundNo: 1, displayOrder: index + 1, displayType: 'DAMAGE',
    resultData: { ...createDiceResultFixture('standard'), result: undefined },
    resolution: { type: 'DAMAGE', characterName: `目标${index + 1}` },
  }))
  persisted.content = JSON.stringify({ summaryId: 501, roundNos: [1] })
  const pending = structuredClone(aggregate)
  workspace.messages.value[0]!.diceRoll = pending
  const submitted: number[] = []
  t.mock.method(api, 'rollDiceResult', async (id: number) => {
    submitted.push(id)
    const result = aggregate.results.find(r => r.id === id)!
    result.resolvedAt = '2026-09-30T10:00:00'
    result.resultData!.result = 3
    if (id === 603) {
      aggregate.results.push({ id: 604, summaryId: 501, roundNo: 2, displayType: 'MAJOR_WOUND_CON',
        resultData: { ...createDiceResultFixture('normal-percentile'), result: undefined },
        resolution: { type: 'MAJOR_WOUND_CON', characterName: '目标3' } })
      aggregate.summary.roundCount = 2
      persisted.content = JSON.stringify({ summaryId: 501, roundNos: [1, 2] })
    }
    return { summary: aggregate.summary, rolledResult: result, createdResults: id === 603 ? [aggregate.results[3]!] : [] }
  })
  const app = await appDiceHandlers(workspace, api)
  app.openDiceMessage(pending)
  await app.rollDiceMessage()
  assert.deepEqual(submitted, [601, 602, 603])
  assert.deepEqual(app.diceMessageAggregate.value!.results.map(r => r.id), [601, 602, 603])
  assert.deepEqual(app.queuedDiceAggregates.value.map(a => a.results.map(r => r.id)), [[604]])
  app.completeDiceMessageRoll()
  await app.continueAfterDice()
  assert.equal(app.dicePlayerOpen.value, true)
  assert.equal(app.dicePlaybackRequest.value!.mode, 'pending')
  assert.deepEqual(app.diceMessageAggregate.value!.results.map(r => r.id), [604])
  assert.deepEqual(app.queuedDiceAggregates.value, [])
  assert.deepEqual(submitted, [601, 602, 603], 'opening the next round must still wait for the player')
})


for (const outcome of ['success', 'failure'] as const) {
  for (const navigation of ['close', 'other-dice', 'reopen-same', 'other-conversation', 'leave-and-return'] as const) {
    test(`late roll ${outcome} cannot change playback after ${navigation}`, async t => {
      const { api, workspace, aggregate } = await fixture(t)
      const { createDiceResultFixture } = await import('../../test/fixtures/dice.ts')
      const { nextTick } = await import('vue')
      const pending = structuredClone(aggregate)
      pending.summary.roundCount = 1
      pending.results = [{ id: 601, summaryId: 501, roundNo: 1, displayType: 'DAMAGE',
        resultData: { ...createDiceResultFixture('standard'), result: undefined },
        resolution: { type: 'DAMAGE', characterName: '林恩' } }]
      let release!: () => void
      const submitted: number[] = []
      t.mock.method(api, 'rollDiceResult', async (id: number) => {
        submitted.push(id)
        await new Promise<void>(resolve => { release = resolve })
        if (outcome === 'failure') throw new Error('late network error')
        return { summary: aggregate.summary, rolledResult: aggregate.results[0]!, createdResults: [] }
      })
      const app = await appDiceHandlers(workspace, api)
      app.openDiceMessage(pending)
      const inFlight = app.rollDiceMessage()
      if (navigation === 'close' || navigation === 'reopen-same') {
        app.dicePlayerOpen.value = false
        // No tick between close/reopen: invalidation must be synchronous and sticky.
        if (navigation === 'reopen-same') app.dicePlayerOpen.value = true
      } else if (navigation === 'other-dice') {
        const newer = structuredClone(pending)
        newer.summary.id = 777
        app.openDiceMessage(newer)
      } else {
        workspace.selectedConversationId.value = 8
        if (navigation === 'leave-and-return') workspace.selectedConversationId.value = 7
      }
      const requestBeforeResponse = app.dicePlaybackRequest.value
      const aggregateBeforeResponse = app.diceMessageAggregate.value
      release()
      await inFlight
      await nextTick()
      assert.equal(app.dicePlaybackRequest.value, requestBeforeResponse, 'no new request that could reopen or reset the player')
      assert.equal(app.diceMessageAggregate.value, aggregateBeforeResponse)
      assert.deepEqual(app.queuedDiceAggregates.value, [])
      assert.deepEqual(app.notices, [], 'an obsolete failure must not interrupt the current view')
      assert.deepEqual(submitted, [601], 'an obsolete action must not submit more bundles')
      if (navigation === 'other-conversation') assert.equal(workspace.latestDiceRoll.value, null)
      if (navigation === 'close') assert.equal(app.dicePlayerOpen.value, false)
    })
  }
}

test('closing while a same-round damage bundle is in flight stops remaining submissions', async t => {
  const { api, workspace, aggregate, persisted } = await fixture(t)
  const { createDiceResultFixture } = await import('../../test/fixtures/dice.ts')
  aggregate.summary = { ...aggregate.summary, roundCount: 1, toolName: 'rollDamage' }
  aggregate.results = [601, 602, 603].map(id => ({ id, summaryId: 501, roundNo: 1,
    resultData: { ...createDiceResultFixture('standard'), result: undefined },
    resolution: { type: 'DAMAGE', characterName: `目标${id}` } }))
  persisted.content = JSON.stringify({ summaryId: 501, roundNos: [1] })
  let release!: () => void
  let secondStarted!: () => void
  const second = new Promise<void>(resolve => { secondStarted = resolve })
  const submitted: number[] = []
  t.mock.method(api, 'rollDiceResult', async (id: number) => {
    submitted.push(id)
    if (id === 602) {
      secondStarted()
      await new Promise<void>(resolve => { release = resolve })
    }
    const result = aggregate.results.find(r => r.id === id)!
    result.resolvedAt = '2026-09-30T10:00:00'
    result.resultData!.result = 3
    return { summary: aggregate.summary, rolledResult: result, createdResults: [] }
  })
  const app = await appDiceHandlers(workspace, api)
  app.openDiceMessage(structuredClone(aggregate))
  const inFlight = app.rollDiceMessage()
  await second
  app.dicePlayerOpen.value = false
  const requestBeforeResponse = app.dicePlaybackRequest.value
  release()
  await inFlight
  assert.deepEqual(submitted, [601, 602])
  assert.equal(app.dicePlaybackRequest.value, requestBeforeResponse)
  assert.deepEqual(workspace.messages.value[0]!.diceRoll!.results.map(r => Boolean(r.resolvedAt)), [true, true, false])
})
