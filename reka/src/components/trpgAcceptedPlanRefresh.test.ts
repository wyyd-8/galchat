import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'

const sourceRoot = new URL('../', import.meta.url)
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}.ts`, sourceRoot).href, shortCircuit: true }
    }
    if (/^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
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

test('refreshes the combat plan as soon as a new turn is accepted', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const previousSessionStorage = globalThis.sessionStorage
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: storage(),
    sessionStorage: storage(),
  })

  const originalContinue = streamTrpgTurn.continue
  const originalMessages = api.groupMessages
  const originalPlans = api.replyPlan
  const originalTurn = api.currentTurn
  try {
    let planRequestsDuringStream = 0
    let displayedRoundDuringStream = ''
    api.replyPlan = async () => {
      planRequestsDuringStream += 1
      return [{
        id: 32,
        source: 'COMBAT',
        displayName: '战斗第2轮',
        items: [
          { order: 1, actorType: 'kp', subjectCharacterId: 44, subjectCharacterName: '近战测试员·阿尔法' },
          { order: 2, actorType: 'user', actorId: 48, subjectCharacterId: 48, subjectCharacterName: '本' },
        ],
      }]
    }
    api.groupMessages = async () => []
    api.currentTurn = async () => null
    streamTrpgTurn.continue = async (conversationId, _clientRequestId, onEvent) => {
      onEvent({ eventType: 'turn.accepted', conversationId, turnId: 115 })
      await new Promise((resolve) => setTimeout(resolve, 0))
      displayedRoundDuringStream = workspace.replyPlan.value.displayName
      assert.equal(planRequestsDuringStream, 1)
    }

    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {}, insert(child, parent) { child.parent = parent }, remove() {},
      createElement: () => ({}), createText: (text) => ({ text }),
      createComment: (text) => ({ text }), setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() { workspace = useWorkspace(); return () => h('div') },
    })).mount({})
    workspace.conversations.value = [{
      id: 4, userWorldId: 3, worldId: 2,
      mode: 'trpg', title: '调试', status: 'active',
    }]
    workspace.selectedConversationId.value = 4
    workspace.replyPlan.value = {
      id: 32,
      source: 'COMBAT',
      displayName: '战斗第1轮',
      items: [
        { order: 1, actorType: 'user', actorId: 48, subjectCharacterId: 48, subjectCharacterName: '本' },
        { order: 2, actorType: 'kp', subjectCharacterId: 44, subjectCharacterName: '近战测试员·阿尔法' },
      ],
    }
    workspace.replyPlans.value = [workspace.replyPlan.value]

    await workspace.startTrpgTurn()

    assert.equal(displayedRoundDuringStream, '战斗第2轮')
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalMessages
    api.replyPlan = originalPlans
    api.currentTurn = originalTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
      sessionStorage: previousSessionStorage,
    })
  }
})

function storage(): Storage {
  const values = new Map<string, string>()
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
    clear: () => values.clear(),
    key: (index) => [...values.keys()][index] ?? null,
    get length() { return values.size },
  }
}
