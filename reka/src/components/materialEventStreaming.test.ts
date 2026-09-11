import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import test from 'node:test'
import type { GroupMessage } from '../api/types.ts'

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

test('adds a completed material message as soon as its stream event arrives', async () => {
  const { api, streamTrpgTurn } = await import('../api/client.ts')
  const { useWorkspace } = await import('../composables/useWorkspace.ts')
  const { createRenderer, defineComponent, h } = await import('vue')
  const previousWindow = globalThis.window
  const previousLocalStorage = globalThis.localStorage
  const originalContinue = streamTrpgTurn.continue
  const originalGroupMessages = api.groupMessages
  const originalReplyPlan = api.replyPlan
  const originalCurrentTurn = api.currentTurn
  const storage = new Map<string, string>()
  Object.assign(globalThis, {
    window: { addEventListener() {}, clearTimeout, setTimeout },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      key: (index: number) => [...storage.keys()][index] ?? null,
      get length() { return storage.size },
    },
  })

  try {
    let workspace!: ReturnType<typeof useWorkspace>
    const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
      patchProp() {},
      insert(child, parent) {
        const children = (parent.children ||= []) as Array<Record<string, unknown>>
        children.push(child)
        child.parent = parent
      },
      remove() {},
      createElement: () => ({}),
      createText: (text) => ({ text }),
      createComment: (text) => ({ text }),
      setText(node, text) { node.text = text },
      setElementText(node, text) { node.text = text },
      parentNode: (node) => node.parent as Record<string, unknown> | null,
      nextSibling: () => null,
    })
    renderer.createApp(defineComponent({
      setup() {
        workspace = useWorkspace()
        return () => h('div')
      },
    })).mount({})
    workspace.conversations.value = [{
      id: 7,
      userWorldId: 3,
      worldId: 2,
      mode: 'trpg',
      title: '古树之中',
      status: 'active',
    }]
    workspace.selectedConversationId.value = 7

    const content = '{"schemaVersion":1,"materialId":13,"title":"梦唤：不眠之夜","description":"整夜未眠。","imageUrl":""}'
    let streamedMessages: GroupMessage[] = []
    streamTrpgTurn.continue = async (_id, _clientRequestId, onEvent) => {
      onEvent({
        eventType: 'material.created',
        conversationId: 7,
        turnId: 42,
        replyStepId: 9,
        messageId: 100,
        sequence: 10,
        messageKind: 'material',
        speaker: { type: 'kp', name: 'KP' },
        content,
      })
      streamedMessages = workspace.messages.value.map((message) => ({ ...message }))
    }
    api.groupMessages = async () => []
    api.replyPlan = async () => [{ source: 'USER', displayName: '群聊', items: [] }]
    api.currentTurn = async () => null

    await workspace.startTrpgTurn()

    assert.deepEqual(streamedMessages, [{
      id: 100,
      conversationId: 7,
      turnId: 42,
      replyStepId: 9,
      speakerType: 'kp',
      speakerId: undefined,
      speakerName: 'KP',
      messageKind: 'material',
      content,
      sequenceNo: 10,
      status: 'completed',
    }])
  } finally {
    streamTrpgTurn.continue = originalContinue
    api.groupMessages = originalGroupMessages
    api.replyPlan = originalReplyPlan
    api.currentTurn = originalCurrentTurn
    Object.assign(globalThis, {
      window: previousWindow,
      localStorage: previousLocalStorage,
    })
  }
})
