import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { baseParse, NodeTypes, type ElementNode, type RootNode } from '@vue/compiler-dom'

function findElementByClass(root: RootNode, className: string): ElementNode | undefined {
  const visit = (node: unknown): ElementNode | undefined => {
    if (!node || typeof node !== 'object') return undefined
    const candidate = node as { type?: number, children?: unknown[] }

    if (candidate.type === NodeTypes.ELEMENT) {
      const element = node as ElementNode
      const classAttribute = element.props.find((prop) => (
        prop.type === NodeTypes.ATTRIBUTE && prop.name === 'class'
      ))
      if (
        classAttribute?.type === NodeTypes.ATTRIBUTE
        && classAttribute.value?.content.split(/\s+/).includes(className)
      ) {
        return element
      }
    }

    if (Array.isArray(candidate.children)) {
      for (const child of candidate.children) {
        const match = visit(child)
        if (match) return match
      }
    }
    return undefined
  }

  return visit(root)
}

function cssRule(source: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))?.[1] || ''
}

function findElement(root: RootNode, predicate: (element: ElementNode) => boolean): ElementNode | undefined {
  const visit = (node: unknown): ElementNode | undefined => {
    if (!node || typeof node !== 'object') return undefined
    const candidate = node as { type?: number, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT) {
      const element = node as ElementNode
      if (predicate(element)) return element
    }
    if (Array.isArray(candidate.children)) {
      for (const child of candidate.children) {
        const match = visit(child)
        if (match) return match
      }
    }
    return undefined
  }
  return visit(root)
}

test('renders the opposed-check separator as an accessible crossed-swords icon', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const marker = findElementByClass(baseParse(template), 'dice-opposed-versus')

  assert.ok(marker, 'opposed checks should render a central separator')
  assert.equal(marker.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'aria-label'
      && prop.value?.content === '对抗'
  )), true)
  assert.equal(marker.children.some((child) => (
    child.type === NodeTypes.ELEMENT && child.tag === 'Swords'
  )), true)
  assert.equal(marker.children.some((child) => (
    child.type === NodeTypes.ELEMENT
      && child.children.some((grandchild) => (
        grandchild.type === NodeTypes.TEXT && grandchild.content.trim() === 'VS'
      ))
  )), false)
})

test('renders structured dice messages with the dedicated compact component', async () => {
  const source = await readFile(new URL('../../components/GroupChatStage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'GroupChatStage should contain a template')

  const root = baseParse(template)
  const visit = (node: unknown): boolean => {
    if (!node || typeof node !== 'object') return false
    const candidate = node as { type?: number, tag?: string, children?: unknown[] }
    if (candidate.type === NodeTypes.ELEMENT && candidate.tag === 'DiceRollMessage') return true
    return candidate.children?.some(visit) || false
  }

  assert.equal(visit(root), true)
})

test('reuses iconless chat dice cards for the tool dice history', async () => {
  const source = await readFile(new URL('../../components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const diceTab = findElement(root, (element) => element.tag === 'TabsContent' && element.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE && prop.name === 'value' && prop.value?.content === 'dice'
  )))

  assert.ok(diceTab, 'the tools dialog should contain a dice tab')
  assert.equal(Boolean(findElement(diceTab as unknown as RootNode, (element) => element.tag === 'input')), false)
  const card = findElement(diceTab as unknown as RootNode, (element) => element.tag === 'DiceRollMessage')
  assert.ok(card, 'the dice tab should reuse the chat dice message component')
  assert.equal(card.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'bind'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'show-icon'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'false'
  )), true)
})

test('shows a dedicated empty state when the current chat has no dice messages', async () => {
  const source = await readFile(new URL('../../components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')

  const empty = findElementByClass(baseParse(template), 'dice-history-empty')

  assert.ok(empty, 'the dice history should explain that the current chat has no rolls')
  assert.equal(empty.children.some((child) => child.type === NodeTypes.ELEMENT && child.tag === 'strong'), true)
  assert.equal(empty.children.some((child) => child.type === NodeTypes.ELEMENT && child.tag === 'p'), true)
})

test('adds a separate locate control to each tool dice card', async () => {
  const source = await readFile(new URL('../../components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const item = findElementByClass(root, 'dice-history-item')
  const locate = findElementByClass(root, 'dice-history-locate')

  assert.ok(item, 'each dice history entry should reserve a locate action area')
  assert.ok(locate, 'each dice history entry should offer a locate action')
  assert.equal(locate.tag, 'button')
  assert.equal(locate.children.some((child) => child.type === NodeTypes.ELEMENT && child.tag === 'LocateFixed'), true)
  assert.equal(locate.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'aria-label'
      && prop.value?.content === '定位到聊天记录'
  )), true)
  assert.equal(locate.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'on'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'click'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content.includes("emit('locateDice', message.id)")
  )), true)
})

test('wires tool dice location requests to the chat navigator', async () => {
  const source = await readFile(new URL('../../App.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'App should contain a template')
  const tools = findElement(baseParse(template), (element) => element.tag === 'TrpgToolsDialog')

  assert.ok(tools, 'App should render the TRPG tools dialog')
  assert.equal(tools.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'on'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'locate-dice'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'locateDiceMessage'
  )), true)
})

test('opens each new backend dice roll after its chat card is rendered', async () => {
  const appSource = await readFile(new URL('../../App.vue', import.meta.url), 'utf8')
  const workspaceSource = await readFile(new URL('../../composables/useWorkspace.ts', import.meta.url), 'utf8')

  assert.match(workspaceSource, /event\.eventType === 'dice_roll\.created'[\s\S]*incomingDiceRoll\.value = aggregate/)
  assert.match(appSource, /watch\(\s*\(\) => workspace\.incomingDiceRoll\.value/)
  assert.match(appSource, /if \(aggregate\) openIncomingDiceMessage\(aggregate\)/)
  assert.match(appSource, /\{ flush: 'post' \}/)
})

test('marks each chat message as a scroll target for tool navigation', async () => {
  const source = await readFile(new URL('../../components/GroupChatStage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'GroupChatStage should contain a template')
  const message = findElementByClass(baseParse(template), 'chat-message')

  assert.ok(message, 'the chat should render message articles')
  assert.equal(message.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'bind'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'data-message-id'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'message.id'
  )), true)
})

test('reserves the card right edge for the tool locate control', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const item = cssRule(styles, '.dice-history-item')
  const card = cssRule(styles, '.dice-history-item .dice-message-card')
  const locate = cssRule(styles, '.dice-history-locate')

  assert.match(item, /position:\s*relative/)
  assert.match(card, /padding-right:\s*58px/)
  assert.match(locate, /position:\s*absolute/)
  assert.match(locate, /right:\s*14px/)
})

test('offers an explicit continue action below replay after the first player roll', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const action = findElementByClass(baseParse(template), 'dice-player-continue')

  assert.ok(action, 'the player should render a continue action')
  assert.equal(action.children.some((child) => (
    child.type === NodeTypes.TEXT && child.content.trim() === '继续'
  )), true)
})

test('removes the replay action after an all-placeholder result completes', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const replay = findElementByClass(baseParse(template), 'dice-player-replay')
  assert.ok(replay, 'the player should render a conditional roll action')
  assert.equal(replay.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'if'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'showRollAction'
  )), true)
})

test('uses a neutral value state instead of success or failure for numeric cards', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const resultBox = findElementByClass(baseParse(template), 'dice-group-result-box')
  assert.ok(resultBox, 'the player should render participant result cards')
  assert.equal(resultBox.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'bind'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'class'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content.includes("isValueRoll ? 'is-value'")
  )), true)
})

test('shows the corresponding dice group number below every participant name', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const groupNumber = findElementByClass(baseParse(template), 'dice-group-result-number')
  assert.ok(groupNumber, 'participant result cards should show their dice group number')
  assert.match(
    groupNumber.children.map((child) => child.loc.source).join(''),
    /formatDiceGroupLabel\(request\.presentation\.groups\[index\]!\.moduleStart, request\.presentation\.groups\[index\]!\.moduleCount\)/,
  )
})

test('places the dice player and its overlay on a foreground dialog layer', async () => {
  const playerSource = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const playerTemplate = playerSource.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(playerTemplate, 'DicePlayerDialog should contain a template')
  const playerDialog = findElement(baseParse(playerTemplate), (element) => element.tag === 'BaseDialog')
  assert.ok(playerDialog, 'DicePlayerDialog should render a BaseDialog')
  assert.equal(playerDialog.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'layer'
      && prop.value?.content === 'foreground'
  )), true)

  const baseSource = await readFile(new URL('../../components/ui/BaseDialog.vue', import.meta.url), 'utf8')
  const baseTemplate = baseSource.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(baseTemplate, 'BaseDialog should contain a template')
  const baseRoot = baseParse(baseTemplate)
  for (const tag of ['DialogOverlay', 'DialogContent']) {
    const element = findElement(baseRoot, (candidate) => candidate.tag === tag)
    assert.ok(element, `BaseDialog should render ${tag}`)
    assert.equal(element.props.some((prop) => (
      prop.type === NodeTypes.DIRECTIVE
        && prop.name === 'bind'
        && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
        && prop.arg.content === 'class'
        && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
        && prop.exp.content.includes('layerClass')
    )), true)
  }

  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  assert.match(cssRule(styles, '.dialog-overlay.dialog-layer-foreground'), /z-index:\s*60/)
  assert.match(cssRule(styles, '.dialog-content.dialog-layer-foreground'), /z-index:\s*61/)
})

test('stretches each dice message card across the chat message row', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')

  assert.match(card, /width:\s*100%/)
  assert.match(card, /box-sizing:\s*border-box/)
})

test('uses the semantic accent for the dice card border, icon, underline, and status', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')
  const title = cssRule(styles, '.dice-message-title')
  const icon = cssRule(styles, '.dice-message-title svg')
  const status = cssRule(styles, '.dice-message-status')

  assert.match(card, /border:\s*2px solid var\(--dice-message-accent\)/)
  assert.match(title, /text-decoration-line:\s*underline/)
  assert.match(title, /text-decoration-color:\s*var\(--dice-message-accent\)/)
  assert.match(icon, /color:\s*var\(--dice-message-accent\)/)
  assert.match(status, /color:\s*var\(--dice-message-accent\)/)
})
