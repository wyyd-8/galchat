import assert from 'node:assert/strict'
import test from 'node:test'
import type { CurrentTurn, GroupChatEvent, ReplyPlan, ReplyPlanItem } from '../api/types.ts'
import { applyCurrentTurnEvent, buildTrpgExecutionState } from './trpgExecutionState.ts'

const actor = (
  order: number,
  subjectCharacterId: number,
  subjectCharacterName: string,
  participantStatus: ReplyPlanItem['participantStatus'] = 'ACTIVE',
  actorType = 'character',
): ReplyPlanItem => ({
  order,
  actorType,
  actorId: subjectCharacterId + 1000,
  subjectCharacterId,
  subjectCharacterName,
  participantStatus,
})

test('nests child scenes in execution order and removes their characters from the parent scene', () => {
  const parent: ReplyPlan = {
    id: 10, source: 'SCENE', displayName: '地下室', nextPlanId: 30,
    items: [
      actor(1, 501, '玛格丽特'),
      actor(2, 510, '爱德华'),
      actor(3, 502, '约翰', 'WAITING', 'user'),
      actor(4, 511, '沃特森', 'WAITING'),
      actor(5, 503, '安娜', 'READY'),
      actor(6, 512, '海伦', 'READY'),
      { order: 7, actorType: 'kp' },
    ],
  }
  const activeChild: ReplyPlan = {
    id: 20, source: 'SCENE', displayName: '密道', parentPlanId: 10, nextPlanId: 21,
    items: [
      actor(1, 501, '玛格丽特'),
      actor(2, 601, '食尸鬼', 'ACTIVE', 'kp'),
      actor(3, 502, '约翰', 'WAITING', 'user'),
      actor(4, 503, '安娜', 'READY'),
      { order: 5, actorType: 'kp' },
    ],
  }
  const nextChild: ReplyPlan = {
    id: 21, source: 'SCENE', displayName: '储藏间', parentPlanId: 10,
    items: [{ order: 1, actorType: 'kp' }, actor(2, 504, '亨利')],
  }
  const nextRoot: ReplyPlan = {
    id: 30, source: 'SCENE', displayName: '庭院', items: [actor(1, 505, '露西')],
  }
  const turn: CurrentTurn = {
    turnId: 100, planId: 20, planSource: 'SCENE', status: 'running',
    waitingForUser: false, sceneOptions: {},
    steps: [
      { stepId: 301, itemOrder: 1, actorType: 'character', actorId: 1501, subjectCharacterId: 501, status: 'completed' },
      { stepId: 302, itemOrder: 2, actorType: 'kp', actorId: 1601, subjectCharacterId: 601, status: 'running' },
      { stepId: 305, itemOrder: 5, actorType: 'kp', status: 'pending' },
    ],
  }

  const state = buildTrpgExecutionState(
    [activeChild, parent, nextChild, nextRoot], turn,
  )

  assert.equal(state.kind, 'exploration')
  assert.equal(state.title, '探索执行状态')
  assert.deepEqual(state.scenes.map((scene) => scene.plan.id), [10, 30])
  assert.deepEqual(state.scenes[0].childScenes.map((scene) => scene.plan.id), [20, 21])
  assert.deepEqual(
    state.scenes[0].activeActors.map((entry) => [entry.name, entry.statusLabel]),
    [['爱德华', '场景暂停']],
  )
  assert.deepEqual(
    state.scenes[0].childScenes[0].activeActors.map((entry) => [entry.name, entry.statusLabel, entry.genericKp]),
    [
      ['玛格丽特', '已完成', false],
      ['食尸鬼', '行动中', false],
      ['KP · 场景推进', '等待行动', true],
    ],
  )
  assert.deepEqual(state.scenes[0].waitingActors.map((entry) => entry.name), ['沃特森'])
  assert.deepEqual(state.scenes[0].readyActors.map((entry) => entry.name), ['海伦'])
  assert.deepEqual(state.scenes[0].childScenes[0].waitingActors.map((entry) => entry.name), ['约翰'])
  assert.deepEqual(state.scenes[0].childScenes[0].readyActors.map((entry) => entry.name), ['安娜'])
  assert.deepEqual(state.scenes[0].childScenes[1].activeActors.map((entry) => entry.name), ['亨利'])
})

test('places the active main scene first without nesting scenes beyond one child level', () => {
  const earlierRoot: ReplyPlan = {
    id: 5, source: 'SCENE', displayName: '门厅', items: [actor(1, 500, '乔治')],
  }
  const currentRoot: ReplyPlan = {
    id: 10, source: 'SCENE', displayName: '地下室', items: [actor(1, 501, '玛格丽特')],
  }
  const activeChild: ReplyPlan = {
    id: 20, source: 'SCENE', displayName: '密道', parentPlanId: 10,
    items: [actor(1, 501, '玛格丽特')],
  }
  const malformedGrandchild: ReplyPlan = {
    id: 21, source: 'SCENE', displayName: '暗格', parentPlanId: 20,
    items: [actor(1, 502, '约翰')],
  }

  const state = buildTrpgExecutionState(
    [activeChild, earlierRoot, malformedGrandchild, currentRoot], null,
  )

  assert.deepEqual(state.scenes.map((scene) => scene.plan.id), [10, 5])
  assert.deepEqual(state.scenes[0].childScenes.map((scene) => scene.plan.id), [20, 21])
  assert.deepEqual(state.scenes[0].childScenes[1].childScenes, [])
})

test('shows only the active combat plan and maps combat step states', () => {
  const combat: ReplyPlan = {
    id: 40, source: 'COMBAT', displayName: '战斗第3轮', resumePlanId: 20,
    items: [
      actor(1, 501, '玛格丽特'),
      actor(2, 601, '食尸鬼', 'ACTIVE', 'kp'),
      { order: 3, actorType: 'kp' },
    ],
  }
  const suspendedScene: ReplyPlan = {
    id: 20, source: 'SCENE', displayName: '密道', parentPlanId: 10,
    items: [actor(1, 501, '玛格丽特')],
  }
  const turn: CurrentTurn = {
    turnId: 101, planId: 40, planSource: 'COMBAT', status: 'waiting_dice',
    waitingForUser: false, inputType: 'dice', sceneOptions: {},
    steps: [
      { stepId: 401, itemOrder: 1, actorType: 'character', subjectCharacterId: 501, status: 'completed' },
      { stepId: 402, itemOrder: 2, actorType: 'kp', subjectCharacterId: 601, status: 'waiting_dice' },
    ],
  }

  const state = buildTrpgExecutionState([combat, suspendedScene], turn)

  assert.equal(state.kind, 'combat')
  assert.equal(state.title, '战斗执行状态')
  assert.equal(state.subtitle, '战斗第3轮')
  assert.deepEqual(state.scenes.map((scene) => scene.plan.id), [40])
  assert.deepEqual(
    state.scenes[0].activeActors.map((entry) => [entry.name, entry.statusLabel]),
    [['玛格丽特', '已完成'], ['食尸鬼', '等待投骰']],
  )
})

test('aggregates every combat step related to the same character', () => {
  const combat: ReplyPlan = {
    id: 40, source: 'COMBAT', displayName: '战斗第3轮',
    items: [actor(1, 501, '玛格丽特'), actor(2, 502, '亨利')],
  }
  const turn: CurrentTurn = {
    turnId: 101, planId: 40, planSource: 'COMBAT', status: 'running',
    waitingForUser: false, sceneOptions: {},
    steps: [
      { stepId: 401, itemOrder: 1, actorType: 'character', subjectCharacterId: 501, status: 'completed' },
      { stepId: 402, itemOrder: 2, actorType: 'kp', subjectCharacterId: 501, status: 'running' },
      { stepId: 403, itemOrder: 3, actorType: 'character', subjectCharacterId: 502, status: 'completed' },
      { stepId: 404, itemOrder: 5, actorType: 'character', subjectCharacterId: 502, status: 'pending' },
    ],
  }

  const state = buildTrpgExecutionState([combat], turn)

  assert.deepEqual(
    state.scenes[0].activeActors.map((entry) => [entry.name, entry.statusLabel]),
    [['玛格丽特', '行动中'], ['亨利', '等待行动']],
  )
})

test('highlights a routed defender instead of the action owner', () => {
  const combat: ReplyPlan = {
    id: 40, source: 'COMBAT', displayName: '战斗第1轮',
    items: [
      actor(1, 44, '近战测试员·阿尔法', 'ACTIVE', 'kp'),
      actor(2, 48, '本', 'ACTIVE', 'user'),
    ],
  }
  const turn: CurrentTurn = {
    turnId: 115, planId: 40, planSource: 'COMBAT', status: 'waiting_input',
    stepId: 404, actionType: 'combat_defense', itemOrder: 2,
    waitingForUser: true, sceneOptions: {},
    routeContext: { ownerCharacterId: 44, targetCharacterId: 48 },
    steps: [
      { stepId: 401, itemOrder: 1, actorType: 'kp', subjectCharacterId: 44, status: 'completed' },
      { stepId: 402, itemOrder: 2, actorType: 'kp', subjectCharacterId: 44, status: 'waiting_interaction' },
      { stepId: 403, itemOrder: 2, actorType: 'kp', subjectCharacterId: 44, status: 'completed' },
      { stepId: 404, itemOrder: 2, actorType: 'user', actorId: 48, subjectCharacterId: 48, status: 'waiting_input' },
      { stepId: 405, itemOrder: 3, actorType: 'user', actorId: 48, subjectCharacterId: 48, status: 'pending' },
      { stepId: 406, itemOrder: 4, actorType: 'kp', subjectCharacterId: 48, status: 'pending' },
    ],
  }

  const actors = buildTrpgExecutionState([combat], turn).scenes[0].activeActors

  assert.deepEqual(
    actors.map((entry) => [
      entry.name,
      entry.status,
      entry.routedActor?.name,
      entry.routedActor?.status,
    ]),
    [
      ['近战测试员·阿尔法', 'waiting_interaction', '本', 'waiting_input'],
      ['本', 'pending', undefined, undefined],
    ],
  )
  assert.equal(actors[0].routedActor?.statusLabel, '等待防守')
})

test('uses a next-round state when the active scene has no turn yet', () => {
  const scene: ReplyPlan = {
    id: 50, source: 'SCENE', displayName: '阁楼', items: [actor(1, 501, '玛格丽特')],
  }

  const state = buildTrpgExecutionState([scene], null)

  assert.equal(state.scenes[0].activeActors[0].statusLabel, '等待下一轮')
})

test('advances current step state from streaming events without losing plan identity', () => {
  const activePlan: ReplyPlan = {
    id: 20, source: 'SCENE', displayName: '密道', items: [],
  }
  const events: GroupChatEvent[] = [
    { eventType: 'turn.accepted', turnId: 100 },
    { eventType: 'reply.started', turnId: 100, replyStepId: 301, itemOrder: 1, speaker: { type: 'character', id: 11 } },
    { eventType: 'message.completed', turnId: 100, replyStepId: 301, itemOrder: 1, speaker: { type: 'character', id: 11 } },
    { eventType: 'turn.waiting_input', turnId: 100, replyStepId: 302, itemOrder: 2, actionType: 'trpg_scene', groupName: '密道', speaker: { type: 'user', id: 22 } },
  ]

  const turn = events.reduce<CurrentTurn | null>(
    (current, event) => applyCurrentTurnEvent(current, event, activePlan),
    null,
  )

  assert.equal(turn?.planId, 20)
  assert.equal(turn?.planSource, 'SCENE')
  assert.equal(turn?.stepId, 302)
  assert.equal(turn?.waitingForUser, true)
  assert.deepEqual(turn?.steps.map((step) => [step.stepId, step.itemOrder, step.status]), [
    [301, 1, 'completed'],
    [302, 2, 'waiting_input'],
  ])
  assert.equal(
    applyCurrentTurnEvent(turn, { eventType: 'turn.completed', turnId: 100 }, activePlan),
    null,
  )
})

test('uses a dedicated clarification input state for interaction children', () => {
  const activePlan: ReplyPlan = {
    id: 20, source: 'SCENE', displayName: '密道', items: [],
  }
  const accepted = applyCurrentTurnEvent(
    null,
    { eventType: 'turn.accepted', turnId: 100 },
    activePlan,
  )

  const turn = applyCurrentTurnEvent(accepted, {
    eventType: 'turn.waiting_input',
    turnId: 100,
    replyStepId: 302,
    actionType: 'trpg_interaction_response',
    groupName: '密道',
    speaker: { type: 'user', id: 22 },
    routeContext: { ownerCharacterId: 501, targetCharacterId: 502 },
  }, activePlan)

  assert.equal(turn?.inputType, 'clarification')
  assert.equal(turn?.waitingForUser, true)
  assert.deepEqual(turn?.routeContext, {
    ownerCharacterId: 501,
    targetCharacterId: 502,
  })
})
