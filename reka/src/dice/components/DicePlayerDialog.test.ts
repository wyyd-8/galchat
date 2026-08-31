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

function fontSizePx(source: string, selector: string): number {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const rules = source.matchAll(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 'g'))
  const value = Array.from(rules)
    .map((match) => match[1]?.match(/font-size:\s*([\d.]+)px/)?.[1])
    .find(Boolean)
  assert.ok(value, `${selector} should define a pixel font size`)
  return Number(value)
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

function textContent(node: unknown): string {
  if (!node || typeof node !== 'object') return ''
  const candidate = node as { type?: number, content?: unknown, children?: unknown[] }
  if (candidate.type === NodeTypes.TEXT || candidate.type === NodeTypes.SIMPLE_EXPRESSION) {
    return typeof candidate.content === 'string' ? candidate.content : ''
  }
  if (candidate.type === NodeTypes.INTERPOLATION) return textContent(candidate.content)
  return (candidate.children || []).map(textContent).join('')
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

test('places the modifier reason notice in the upper-right stage controls', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')
  const root = baseParse(template)
  const stageBar = findElementByClass(root, 'dice-player-stage-bar')
  assert.ok(stageBar, 'the player should have upper stage controls')

  const notice = findElement(stageBar as unknown as RootNode, (element) => (
    element.tag === 'DiceModifierNotice'
  ))
  assert.ok(notice, 'explained check modifiers should use a dedicated notice')
  assert.equal(notice.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'bind'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'notice'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'modifierNotice'
  )), true)
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

test('matches the approved filter hierarchy and uses dedicated tool history cards', async () => {
  const source = await readFile(new URL('../../components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const diceTab = findElement(root, (element) => element.tag === 'TabsContent' && element.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE && prop.name === 'value' && prop.value?.content === 'dice'
  )))

  assert.ok(diceTab, 'the tools dialog should contain a dice tab')
  const toolbar = findElementByClass(diceTab as unknown as RootNode, 'dice-history-toolbar')
  assert.ok(toolbar, 'the dice tab should contain the approved filter panel')
  assert.ok(findElement(toolbar as unknown as RootNode, (element) => (
    element.tag === 'input' && element.props.some((prop) => (
      prop.type === NodeTypes.ATTRIBUTE
        && prop.name === 'class'
        && prop.value?.content.split(/\s+/).includes('dice-history-search')
    ))
  )), 'the dice tab should filter history by name')
  assert.equal(Array.from({ length: 2 }, (_, index) => findElement(
    toolbar as unknown as RootNode,
    (element) => element.tag === 'select' && element.props.some((prop) => (
      prop.type === NodeTypes.ATTRIBUTE
        && prop.name === 'class'
        && prop.value?.content.split(/\s+/).includes(index === 0
          ? 'dice-history-category-filter'
          : 'dice-history-result-filter')
    )),
  )).every(Boolean), true, 'the dice tab should filter by category and result')
  assert.match(textContent(toolbar), /名称/)
  assert.match(textContent(toolbar), /掷骰类别/)
  assert.match(textContent(toolbar), /检定结果/)

  assert.ok(findElementByClass(diceTab as unknown as RootNode, 'dice-history-filter-meta'),
    'the result count and clear action should sit below the filter panel')
  assert.ok(findElementByClass(diceTab as unknown as RootNode, 'dice-history-record'),
    'tool history should use the dedicated compact record from the approved design')
  assert.equal(findElement(diceTab as unknown as RootNode,
    (element) => element.tag === 'DiceRollMessage'), undefined,
  'the chat card structure should not dictate the tool history layout')
})

test('keeps the load-earlier action at the bottom of the scrollable dice record flow', async () => {
  const source = await readFile(new URL('../../components/TrpgToolsDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'TrpgToolsDialog should contain a template')
  const root = baseParse(template)
  const flow = findElementByClass(root, 'dice-history-scroll')
  assert.ok(flow, 'dice records should have one scrollable flow')
  const loadZone = findElementByClass(flow as unknown as RootNode, 'dice-history-load-zone')
  assert.ok(loadZone, 'the load action should have the approved divider and explanatory hint')

  const loadEarlier = findElement(loadZone as unknown as RootNode, (element) => (
    element.tag === 'button' && element.props.some((prop) => (
      prop.type === NodeTypes.ATTRIBUTE
        && prop.name === 'class'
        && prop.value?.content.split(/\s+/).includes('dice-history-load-more')
    ))
  ))
  assert.ok(loadEarlier, 'the load-earlier button should be inside the dice record flow')
  assert.ok(findElement(loadZone as unknown as RootNode, (element) => element.tag === 'p'),
    'the load zone should explain whether older chat can still be searched')
  assert.equal(loadEarlier.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'on'
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.arg.content === 'click'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content.includes("emit('loadEarlier')")
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
      && prop.exp.content.includes("emit('locateDice', entry.messageId)")
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

test('wires the tools history loader to the existing group-chat pagination state', async () => {
  const source = await readFile(new URL('../../App.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'App should contain a template')
  const tools = findElement(baseParse(template), (element) => element.tag === 'TrpgToolsDialog')
  assert.ok(tools, 'App should render the TRPG tools dialog')

  const bindings = new Map(tools.props.flatMap((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.arg?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      ? [[`${prop.name}:${prop.arg.content}`, prop.exp.content] as const]
      : []
  )))
  assert.equal(bindings.get('bind:has-older-messages'), 'workspace.hasOlderGroupMessages.value')
  assert.equal(bindings.get('bind:loading-older-messages'), 'workspace.loading.chat')
  assert.equal(bindings.get('on:load-earlier'), 'workspace.loadOlderGroupMessages')
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

test('matches the approved compact filter, record, category pill, and load-zone proportions', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const toolbar = cssRule(styles, '.dice-history-toolbar')
  const filterLabel = cssRule(styles, '.dice-history-toolbar label > span:first-child')
  const filterMeta = cssRule(styles, '.dice-history-filter-meta')
  const record = cssRule(styles, '.dice-history-record')
  const recordTitle = cssRule(styles, '.dice-history-record-main strong')
  const category = cssRule(styles, '.dice-history-category')
  const locate = cssRule(styles, '.dice-history-locate')
  const loadZone = cssRule(styles, '.dice-history-load-zone')

  assert.match(toolbar, /padding:\s*13px/)
  assert.match(toolbar, /grid-template-columns:\s*minmax\(210px,\s*1fr\)\s+145px\s+135px/)
  assert.match(toolbar, /background:\s*#f7f5ef/)
  assert.match(filterLabel, /display:\s*block/)
  assert.match(filterLabel, /margin:\s*0 0 6px 2px/)
  assert.match(filterMeta, /min-height:\s*30px/)
  assert.match(record, /min-height:\s*57px/)
  assert.match(recordTitle, /font-size:\s*11px/)
  assert.match(category, /border-radius:\s*999px/)
  assert.match(category, /background:\s*#e4ece8/)
  assert.doesNotMatch(locate, /position:\s*absolute/)
  assert.match(loadZone, /margin-top:\s*13px/)
  assert.match(loadZone, /padding-top:\s*13px/)
  assert.match(loadZone, /border-top:\s*1px solid/)
})

test('keeps keyboard focus on tool tabs visible without the boxed browser outline', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const focus = cssRule(styles, '.trpg-tools .tabs-list button:focus-visible')

  assert.match(focus, /outline:\s*none/)
  assert.match(focus, /background:\s*rgba\(41,79,73,\.06\)/)
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

test('colors special dice module borders and merged totals with their outcome tone', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const criticalModule = cssRule(styles, '.dice-player-surface .dice-module[data-outcome-tone="critical-success"]')
  const criticalTotal = cssRule(styles, '.dice-player-surface .dice-module[data-outcome-tone="critical-success"]::after')
  const fumbleModule = cssRule(styles, '.dice-player-surface .dice-module[data-outcome-tone="fumble"]')
  const fumbleTotal = cssRule(styles, '.dice-player-surface .dice-module[data-outcome-tone="fumble"]::after')

  assert.match(criticalModule, /border-color:\s*rgba\(184,137,46/)
  assert.match(criticalTotal, /color:\s*#ffe29a/)
  assert.match(fumbleModule, /border-color:\s*rgba\(150,42,59/)
  assert.match(fumbleTotal, /color:\s*#ff9cac/)
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

test('renders a styled difficulty badge before each check name', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')
  const check = findElementByClass(baseParse(template), 'dice-group-check')
  const badge = check && findElement(check as unknown as RootNode, (element) => (
    element.tag === 'em' && element.props.some((prop) => (
      prop.type === NodeTypes.ATTRIBUTE
        && prop.name === 'class'
        && prop.value?.content === 'dice-check-difficulty'
    ))
  ))

  assert.ok(check, 'participant result cards should group difficulty with the check name')
  assert.ok(badge, 'checks with a difficulty should render a dedicated badge')
  assert.ok(
    check.children.indexOf(badge) < check.children.findIndex((child) => (
      child.type === NodeTypes.ELEMENT && child.tag === 'span'
    )),
    'the difficulty badge should appear before the check name',
  )

  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  assert.match(cssRule(styles, '.dice-check-difficulty'), /border-radius:\s*999px/)
  assert.match(cssRule(styles, '.dice-check-difficulty.is-hard'), /color:\s*#8a621d/)
  assert.match(cssRule(styles, '.dice-check-difficulty.is-extreme'), /color:\s*#8b3543/)
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

test('keeps the result footer visible while an oversized dice stage scrolls', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const root = baseParse(template)
  const playerDialog = findElement(root, (element) => element.tag === 'BaseDialog')
  const stageScroller = findElementByClass(root, 'dice-player-stage-scroll')
  const surface = stageScroller && findElementByClass(stageScroller as unknown as RootNode, 'dice-player-surface')
  const footer = findElementByClass(root, 'dice-player-result')

  assert.ok(playerDialog, 'DicePlayerDialog should render a BaseDialog')
  assert.ok(stageScroller, 'the dice stage should have its own scroll region')
  assert.ok(surface, 'the scroll region should contain the animated dice stage')
  assert.ok(footer, 'the player should render its result footer')
  assert.ok(
    playerDialog.children.indexOf(stageScroller) < playerDialog.children.indexOf(footer),
    'the independently scrolling stage should precede the fixed result footer',
  )

  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const body = cssRule(styles, '.dice-player-window .dialog-body')
  const scrollRegion = cssRule(styles, '.dice-player-stage-scroll')

  assert.match(body, /display:\s*grid/)
  assert.match(body, /grid-template-rows:\s*minmax\(0,\s*1fr\)\s+auto/)
  assert.match(body, /overflow:\s*hidden/)
  assert.match(scrollRegion, /min-height:\s*0/)
  assert.match(scrollRegion, /overflow-y:\s*auto/)
})

test('leaves calm breathing room above the dice player heading', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const header = cssRule(styles, '.dice-player-window .dialog-header')
  const minHeight = Number(header.match(/min-height:\s*([\d.]+)px/)?.[1])
  const paddingTop = Number(header.match(/padding:\s*([\d.]+)px/)?.[1])

  assert.ok(minHeight >= 82 && minHeight <= 86, 'the heading should gain vertical room without becoming oversized')
  assert.ok(paddingTop >= 20 && paddingTop <= 22, 'the title should keep a comfortable top inset')
})

test('reveals each lower result only after its dice group merge completes', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')
  const root = baseParse(template)
  const stageScroller = findElementByClass(root, 'dice-player-stage-scroll')
  const resultBox = findElementByClass(root, 'dice-group-result-box')
  const resultValue = resultBox && findElement(resultBox as unknown as RootNode, (element) => element.tag === 'b')
  const score = findElementByClass(root, 'dice-player-score')
  const finalValue = score && findElement(score as unknown as RootNode, (element) => element.tag === 'strong')

  assert.ok(stageScroller, 'the animated stage needs a dedicated scroll element')
  assert.equal(stageScroller.props.some((prop) => (
    prop.type === NodeTypes.ATTRIBUTE
      && prop.name === 'ref'
      && prop.value?.content === 'stageScroll'
  )), true, 'the merge sequence should be able to scroll the stage')
  assert.ok(resultValue, 'group result boxes should contain a result value')
  assert.equal(resultValue.props.some((prop) => (
    prop.type === NodeTypes.DIRECTIVE
      && prop.name === 'if'
      && prop.exp?.type === NodeTypes.SIMPLE_EXPRESSION
      && prop.exp.content === 'isDiceGroupResultRevealed(index)'
  )), true, 'each group value should wait for its own merge completion')
  assert.ok(finalValue, 'single-result rolls should contain a final value')
  assert.equal(finalValue.children.some((child) => (
    child.type === NodeTypes.INTERPOLATION
      && child.content.type === NodeTypes.SIMPLE_EXPRESSION
      && child.content.content.includes('isFinalDiceResultRevealed')
  )), true, 'a single final result should wait for the dice merge to finish')
})

test('stacks each revealed participant result above its outcome label', async () => {
  const source = await readFile(new URL('./DicePlayerDialog.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DicePlayerDialog should contain a template')

  const root = baseParse(template)
  const outcome = findElementByClass(root, 'dice-group-outcome')
  const value = outcome && findElement(outcome as unknown as RootNode, (element) => element.tag === 'strong')
  const label = outcome && findElement(outcome as unknown as RootNode, (element) => element.tag === 'small')

  assert.ok(outcome, 'revealed participant results should use a dedicated stacked layout')
  assert.ok(value, 'the roll value should occupy its own line')
  assert.ok(label, 'the outcome label should occupy its own line')

  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const outcomeRule = cssRule(styles, '.dice-group-result-box > .dice-group-outcome')
  assert.match(outcomeRule, /display:\s*grid/)
  assert.match(outcomeRule, /justify-items:\s*end/)
})

test('keeps the result footer typography within a compact readable scale', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const sizeRanges = [
    ['.dice-group-outcome-heading span', 8, 9],
    ['.dice-group-outcome-heading strong', 10, 11],
    ['.dice-group-result-box > span strong', 10, 11],
    ['.dice-group-result-box > span small', 8, 9],
    ['.dice-check-difficulty', 7, 8],
    ['.dice-group-result-box > .dice-group-outcome strong', 15, 16],
    ['.dice-group-result-box > .dice-group-outcome small', 7, 8],
    ['.dice-group-result-box > .dice-group-target strong', 15, 16],
    ['.dice-group-result-box > .dice-group-target small', 7, 8],
  ] as const

  for (const [selector, minimum, maximum] of sizeRanges) {
    const actual = fontSizePx(styles, selector)
    assert.ok(actual >= minimum, `${selector} should remain at least ${minimum}px`)
    assert.ok(actual <= maximum, `${selector} should remain at most ${maximum}px`)
  }
})

test('stretches each dice message card across the chat message row', async () => {
  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')

  assert.match(card, /width:\s*100%/)
  assert.match(card, /box-sizing:\s*border-box/)
})

test('truncates long dice summaries to the width available in each card host', async () => {
  const source = await readFile(new URL('./DiceRollMessage.vue', import.meta.url), 'utf8')
  const template = source.match(/<template>([\s\S]*)<\/template>/)?.[1]
  assert.ok(template, 'DiceRollMessage should contain a template')

  const titleText = findElementByClass(baseParse(template), 'dice-message-title-text')
  assert.ok(titleText, 'the summary text needs its own shrinkable box for ellipsis rendering')

  const styles = await readFile(new URL('../../styles/index.css', import.meta.url), 'utf8')
  const card = cssRule(styles, '.dice-message-card')
  const title = cssRule(styles, '.dice-message-title')
  const titleTextRule = cssRule(styles, '.dice-message-title-text')
  const chatHost = cssRule(styles, '.chat-message.dice_roll')
  const toolHost = cssRule(styles, '.dice-history-item')

  assert.match(card, /max-width:\s*100%/)
  assert.match(title, /flex:\s*1\s+1\s+auto/)
  assert.match(titleTextRule, /min-width:\s*0/)
  assert.match(titleTextRule, /overflow:\s*hidden/)
  assert.match(titleTextRule, /text-overflow:\s*ellipsis/)
  assert.match(titleTextRule, /white-space:\s*nowrap/)
  assert.match(chatHost, /min-width:\s*0/)
  assert.match(toolHost, /min-width:\s*0/)
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
