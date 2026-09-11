import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import type { TrpgExecutionScene } from './trpgExecutionState.ts'

function styleRule(css: string, selector: string): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|\\n)${escapedSelector}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `expected a dedicated style rule for ${selector}`)
  return match[1]
}

function hexColor(rule: string, property: string): [number, number, number] {
  const match = rule.match(new RegExp(`${property}:\\s*#([0-9a-f]{6})`, 'i'))
  assert.ok(match, `expected ${property} to use a six-digit hex color`)
  const value = Number.parseInt(match[1], 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

function colorDistance(left: number[], right: number[]): number {
  return Math.hypot(...left.map((channel, index) => channel - right[index]))
}

test('gives active-turn actors a cool accent distinct from child-scene green', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const activeRule = styleRule(css, '.trpg-actor-row.running, .trpg-actor-row.waiting_input, .trpg-actor-row.waiting_dice')
  const childSceneRule = styleRule(css, '.trpg-execution-scene.child')
  const activeAccent = hexColor(activeRule, 'border-color')
  const childAccent = hexColor(childSceneRule, '--scene-accent')

  assert.ok(activeAccent[2] > activeAccent[1], 'active-turn accent should read as blue rather than green')
  assert.ok(colorDistance(activeAccent, childAccent) >= 48, 'active-turn accent should remain visibly separate from child-scene green')
  assert.match(activeRule, /inset 3px 0 0 #[0-9a-f]{6}/i)
  assert.match(activeRule, /grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto/)
})

test('limits the activity pulse to active-turn status icons', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const iconRule = styleRule(css, '.trpg-actor-row.running .trpg-status-icon svg, .trpg-actor-row.waiting_input .trpg-status-icon svg, .trpg-actor-row.waiting_dice .trpg-status-icon svg')

  assert.match(iconRule, /animation:\s*trpg-active-pulse\s+1\.8s\s+ease-in-out\s+infinite/)
})

test('uses a warm deep-orange rounded-square badge for the current user investigator', async () => {
  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const badgeRule = styleRule(css, '.trpg-player-control-badge')
  const foreground = hexColor(badgeRule, 'color')
  const background = hexColor(badgeRule, 'background')

  assert.ok(foreground[0] - foreground[1] >= 60,
    'the badge foreground should read as orange-red rather than brown or green')
  assert.ok(foreground[1] - foreground[2] >= 25,
    'the badge foreground should stay warm rather than red or gray')
  assert.ok(background[0] - background[2] >= 25,
    'the badge background should retain a visible warm-orange tint')
  assert.match(badgeRule, /border-radius:\s*6px/,
    'the player badge should be a rounded square rather than a circle')
})

test('renders an explicit event label for every active-turn actor', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const scene: TrpgExecutionScene = {
    plan: { id: 20, source: 'SCENE', displayName: '报社', items: [] },
    kind: 'child',
    status: 'current',
    statusLabel: '当前场景',
    activeActors: [
      {
        item: { order: 1, actorType: 'character', actorId: 101 },
        name: '沃尔顿',
        status: 'running',
        statusLabel: '行动中',
        genericKp: false,
      },
      {
        item: { order: 2, actorType: 'character', actorId: 102 },
        name: '红莲',
        status: 'waiting_input',
        statusLabel: '等待玩家输入',
        genericKp: false,
      },
      {
        item: { order: 3, actorType: 'kp', actorId: 103 },
        name: 'KP · 场景推进',
        status: 'waiting_dice',
        statusLabel: '等待投骰',
        genericKp: true,
      },
    ],
    waitingActors: [],
    readyActors: [],
    childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, { scene, combatOverview: [] }),
  }))

  assert.equal(html.match(/class="trpg-active-label"/g)?.length, 3)
  assert.match(html, /class="trpg-actor-row running"[^>]*>[\s\S]*?class="trpg-active-label">行动中<\/small>/)
  assert.match(html, /class="trpg-actor-row waiting_input"[^>]*>[\s\S]*?class="trpg-active-label">待输入<\/small>/)
  assert.match(html, /class="trpg-actor-row waiting_dice"[^>]*>[\s\S]*?class="trpg-active-label">待掷骰<\/small>/)
})

test('highlights an indented routed actor instead of its owner', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const routedActor = {
    item: { order: 2, actorType: 'user', actorId: 48, subjectCharacterId: 48 },
    name: '本',
    status: 'waiting_input',
    statusLabel: '等待防守',
    genericKp: false,
  }
  const scene: TrpgExecutionScene = {
    plan: { id: 40, source: 'COMBAT', displayName: '战斗第1轮', items: [] },
    kind: 'combat', status: 'current', statusLabel: '当前战斗',
    activeActors: [{
      item: { order: 1, actorType: 'kp', subjectCharacterId: 44 },
      name: '近战测试员·阿尔法',
      status: 'waiting_interaction',
      statusLabel: '等待防守',
      genericKp: false,
      routedActor,
    }],
    waitingActors: [], readyActors: [], childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, { scene, combatOverview: [] }),
  }))

  assert.match(html, /class="trpg-actor-stack"[^>]*>[\s\S]*?近战测试员·阿尔法[\s\S]*?class="trpg-routed-actor"[\s\S]*?本/)
  assert.match(html, /class="trpg-actor-row waiting_interaction"[^>]*aria-label="近战测试员·阿尔法，等待防守"/)
  assert.match(html, /class="trpg-routed-actor"[^>]*>[\s\S]*?class="trpg-actor-row waiting_input"[^>]*aria-label="本，等待防守"[\s\S]*?class="trpg-active-label">待输入<\/small>/)

  const css = await readFile(new URL('../styles/index.css', import.meta.url), 'utf8')
  const nestedRule = styleRule(css, '.trpg-routed-actor')
  assert.match(nestedRule, /margin-left:\s*[1-9][0-9]*px/)
})

test('makes exploration investigator rows keyboard-focusable for their overview card', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const scene: TrpgExecutionScene = {
    plan: { id: 21, source: 'SCENE', displayName: '报社', items: [] },
    kind: 'main',
    status: 'current',
    statusLabel: '当前场景',
    activeActors: [{
      item: { order: 1, actorType: 'character', actorId: 101, subjectCharacterId: 501 },
      name: '沃尔顿',
      status: 'running',
      statusLabel: '行动中',
      genericKp: false,
    }],
    waitingActors: [],
    readyActors: [],
    childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, {
      scene,
      combatOverview: [],
      investigatorCards: [{
        cardId: 501,
        actorType: 'PLAYER',
        name: '沃尔顿',
        checkValues: { 侦查: 65, 聆听: 40, 图书馆使用: 55 },
      }],
    }),
  }))

  assert.match(html, /class="trpg-actor-row running[^\"]*"[^>]*tabindex="0"/)
})

test('marks player and manually controlled investigators in execution rows', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const scene: TrpgExecutionScene = {
    plan: { id: 25, source: 'COMBAT', displayName: '战斗第1轮', items: [] },
    kind: 'combat', status: 'current', statusLabel: '当前战斗',
    activeActors: [
      {
        item: { order: 1, actorType: 'user', subjectCharacterId: 501 },
        name: '威尔', status: 'waiting_input', statusLabel: '等待玩家输入', genericKp: false,
      },
      {
        item: { order: 2, actorType: 'character', actorId: 102, subjectCharacterId: 502 },
        name: '查理', status: 'pending', statusLabel: '等待行动', genericKp: false,
      },
      {
        item: { order: 3, actorType: 'character', actorId: 103, subjectCharacterId: 503 },
        name: '安娜', status: 'pending', statusLabel: '等待行动', genericKp: false,
      },
    ],
    waitingActors: [], readyActors: [], childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, {
      scene,
      combatOverview: [],
      investigatorCards: [
        { cardId: 501, actorType: 'PLAYER', name: '威尔', checkValues: {} },
        { cardId: 502, actorType: 'BOT', name: '查理', checkValues: {} },
        { cardId: 503, actorType: 'BOT', name: '安娜', checkValues: {} },
      ],
      actorRuntimes: [
        { actorType: 'character', actorId: 102, controlMode: 'MANUAL', modelApiAvailable: true },
        { actorType: 'character', actorId: 103, controlMode: 'MODEL', modelApiAvailable: true },
      ],
    }),
  }))

  assert.equal(html.match(/class="trpg-player-control-badge"/g)?.length, 2)
  assert.match(html, /威尔[\s\S]*?class="trpg-player-control-badge"[^>]*aria-label="由你控制"[^>]*>[\s\S]*?<svg/)
  assert.match(html, /查理[\s\S]*?class="trpg-player-control-badge"[^>]*aria-label="由你控制"[^>]*>[\s\S]*?<svg/)
  assert.doesNotMatch(html, /class="trpg-player-control-badge"[^>]*>你<\//)
  assert.doesNotMatch(html, /安娜[\s\S]*?class="trpg-player-control-badge"/)
})

test('renders an investigator execution row as an explicit character-card action', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const scene: TrpgExecutionScene = {
    plan: { id: 23, source: 'SCENE', displayName: '报社', items: [] },
    kind: 'main', status: 'current', statusLabel: '当前场景',
    activeActors: [{
      item: { order: 1, actorType: 'character', actorId: 101, subjectCharacterId: 501 },
      name: '沃尔顿', status: 'running', statusLabel: '行动中', genericKp: false,
    }],
    waitingActors: [], readyActors: [], childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, {
      scene,
      combatOverview: [],
      investigatorCards: [{
        cardId: 501,
        actorType: 'PLAYER',
        name: '沃尔顿',
        checkValues: {},
      }],
    }),
  }))

  assert.match(html, /<button[^>]*class="trpg-actor-row running[^\"]*"[^>]*aria-label="沃尔顿，行动中，打开人物卡"/)
})

test('renders waiting and finished investigators as labeled overview rows', async (context) => {
  const vite = await createServer({
    appType: 'custom',
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  context.after(() => vite.close())
  const { default: TrpgActorRoster } = await vite.ssrLoadModule('/src/components/TrpgActorRoster.vue')
  const scene: TrpgExecutionScene = {
    plan: { id: 22, source: 'SCENE', displayName: '林线入口', items: [] },
    kind: 'main',
    status: 'current',
    statusLabel: '当前场景',
    activeActors: [{
      item: { order: 1, actorType: 'character', actorId: 101, subjectCharacterId: 501 },
      name: '威尔',
      status: 'running',
      statusLabel: '行动中',
      genericKp: false,
    }],
    waitingActors: [{
      item: { order: 2, actorType: 'character', actorId: 102, subjectCharacterId: 502 },
      name: '查理',
      status: 'waiting',
      statusLabel: '暂不参与',
      genericKp: false,
    }],
    readyActors: [{
      item: { order: 3, actorType: 'character', actorId: 103, subjectCharacterId: 503 },
      name: '安娜',
      status: 'ready',
      statusLabel: '已完成本场景探索',
      genericKp: false,
    }],
    childScenes: [],
  }

  const html = await renderToString(createSSRApp({
    render: () => h(TrpgActorRoster, {
      scene,
      combatOverview: [],
      investigatorCards: [
        { cardId: 501, actorType: 'PLAYER', name: '威尔', checkValues: {} },
        { cardId: 502, actorType: 'BOT', name: '查理', checkValues: {} },
        { cardId: 503, actorType: 'BOT', name: '安娜', checkValues: {} },
      ],
    }),
  }))

  assert.match(html, /class="trpg-participant-label"[^>]*>[\s\S]*?暂不参与[\s\S]*?1 人/)
  assert.match(html, /class="trpg-participant-label"[^>]*>[\s\S]*?已完成[\s\S]*?1 人/)
  assert.equal(html.match(/tabindex="0"/g)?.length, 3)
  assert.match(html, /class="trpg-row-state-label">暂缓<\/small>/)
  assert.match(html, /class="trpg-row-state-label">已完成<\/small>/)
})
