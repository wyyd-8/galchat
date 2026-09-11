import type { CurrentTurn, CurrentTurnStep, GroupChatEvent, ReplyPlan, ReplyPlanItem } from '../api/types'

export interface TrpgExecutionActor {
  item: ReplyPlanItem
  name: string
  status: string
  statusLabel: string
  genericKp: boolean
  routedActor?: TrpgExecutionActor
}

export interface TrpgExecutionScene {
  plan: ReplyPlan
  kind: 'main' | 'child' | 'combat'
  status: 'current' | 'waiting-child' | 'waiting'
  statusLabel: string
  activeActors: TrpgExecutionActor[]
  waitingActors: TrpgExecutionActor[]
  readyActors: TrpgExecutionActor[]
  childScenes: TrpgExecutionScene[]
}

export interface TrpgExecutionState {
  kind: 'exploration' | 'combat'
  title: string
  subtitle: string
  scenes: TrpgExecutionScene[]
}

const STEP_LABELS: Record<string, string> = {
  pending: '等待行动',
  running: '行动中',
  waiting_input: '等待玩家输入',
  waiting_dice: '等待投骰',
  paused: '等待继续',
  completed: '已完成',
  failed: '失败',
  cancelled: '已跳过',
  blocked: '执行受阻',
}

export function trpgTurnActionLabel(turn: CurrentTurn | null): string {
  if (!turn) return '开始行动轮'
  if (turn.status === 'failed' || turn.status === 'blocked') {
    return '重试此行动轮'
  }
  if (turn.inputType === 'dice') return '检查投骰并继续'
  return '继续行动轮'
}

function isGenericKp(item: ReplyPlanItem): boolean {
  return item.actorType === 'kp' && item.subjectCharacterId == null
}

function actorName(item: ReplyPlanItem): string {
  if (isGenericKp(item)) return 'KP · 场景推进'
  if (item.subjectCharacterName) return item.subjectCharacterName
  if (item.subjectCharacterId != null) return `人物 #${item.subjectCharacterId}`
  if (item.actorType === 'user') return '玩家调查员'
  return `角色 #${item.actorId}`
}

function matchingSteps(
  turn: CurrentTurn | null,
  plan: ReplyPlan,
  item: ReplyPlanItem,
): CurrentTurnStep[] {
  if (!turn || turn.planId !== plan.id) return []
  if (item.subjectCharacterId != null) {
    const subjectSteps = turn.steps.filter((step) =>
      step.subjectCharacterId === item.subjectCharacterId
        && !(turn.routeContext?.targetCharacterId === item.subjectCharacterId
          && turn.stepId === step.stepId))
    if (subjectSteps.length) return subjectSteps
  }
  return turn.steps.filter((step) =>
    (item.subjectCharacterId == null || step.subjectCharacterId == null)
      && step.itemOrder === item.order
      && step.actorType === item.actorType)
}

function updateEventStep(
  steps: CurrentTurnStep[],
  event: GroupChatEvent,
  status: string,
): CurrentTurnStep[] {
  if (event.replyStepId == null) return steps
  const existing = steps.find((step) => step.stepId === event.replyStepId)
  if (!existing && event.itemOrder == null) return steps
  const next: CurrentTurnStep = {
    stepId: event.replyStepId,
    itemOrder: event.itemOrder ?? existing!.itemOrder,
    actorType: event.speaker?.type ?? existing?.actorType ?? 'character',
    actorId: event.speaker?.id ?? existing?.actorId,
    subjectCharacterId: event.routeContext?.targetCharacterId
      ?? existing?.subjectCharacterId,
    status,
    error: event.error,
  }
  return existing
    ? steps.map((step) => step.stepId === next.stepId ? next : step)
    : [...steps, next].sort((a, b) => a.itemOrder - b.itemOrder)
}

export function applyCurrentTurnEvent(
  current: CurrentTurn | null,
  event: GroupChatEvent,
  activePlan: ReplyPlan,
): CurrentTurn | null {
  if (event.eventType === 'turn.completed') return null
  if (event.eventType === 'turn.accepted' && event.turnId != null) {
    const steps = current?.turnId === event.turnId
      ? updateEventStep(current.steps, event, 'completed')
      : []
    return {
      turnId: event.turnId,
      planId: activePlan.id,
      planSource: activePlan.source,
      status: 'running',
      waitingForUser: false,
      sceneOptions: {},
      steps,
    }
  }
  if (!current || event.turnId !== current.turnId) return current
  if (event.eventType === 'reply.started') {
    return {
      ...current,
      status: 'running',
      waitingForUser: false,
      steps: updateEventStep(current.steps, event, 'running'),
    }
  }
  if (event.eventType === 'message.completed') {
    return {
      ...current,
      steps: updateEventStep(current.steps, event, 'completed'),
    }
  }
  if (event.eventType === 'turn.waiting_input') {
    return {
      ...current,
      status: 'waiting_input',
      stepId: event.replyStepId,
      actionType: event.actionType,
      itemOrder: event.itemOrder,
      inputType: event.actionType === 'trpg_scene_selection'
        ? 'selection'
        : event.actionType === 'trpg_interaction_response'
          ? 'clarification'
          : 'message',
      sceneName: event.groupName,
      promptMessageId: event.promptMessageId,
      interactionType: event.interactionType,
      interactionSeq: event.interactionSeq,
      waitingForUser: true,
      sceneOptions: event.sceneOptions ?? {},
      routeContext: event.routeContext,
      steps: updateEventStep(current.steps, event, 'waiting_input'),
    }
  }
  if (event.eventType === 'turn.paused') {
    return {
      ...current,
      status: 'paused',
      inputType: 'continue',
      waitingForUser: false,
    }
  }
  if (event.eventType === 'reply.failed'
    || event.eventType === 'generation.failed') {
    return {
      ...current,
      status: 'failed',
      steps: updateEventStep(current.steps, event, 'failed'),
    }
  }
  return current
}

function actorStatus(
  item: ReplyPlanItem,
  plan: ReplyPlan,
  sceneStatus: TrpgExecutionScene['status'],
  turn: CurrentTurn | null,
): Pick<TrpgExecutionActor, 'status' | 'statusLabel'> {
  if (sceneStatus === 'waiting-child') {
    return { status: 'scene-paused', statusLabel: '场景暂停' }
  }
  if (sceneStatus === 'waiting') {
    return { status: 'waiting-scene', statusLabel: '等待进入' }
  }
  if (!turn || turn.planId !== plan.id) {
    return { status: 'waiting-round', statusLabel: '等待下一轮' }
  }
  const steps = matchingSteps(turn, plan, item)
  const status = [
    'failed', 'blocked', 'waiting_input', 'waiting_dice',
    'running', 'paused', 'pending', 'completed', 'cancelled',
  ].find((candidate) => steps.some((step) => step.status === candidate))
  return status
    ? { status, statusLabel: STEP_LABELS[status] ?? status }
    : { status: 'pending', statusLabel: '等待行动' }
}

function orderLinkedPlans(plans: ReplyPlan[]): ReplyPlan[] {
  const byId = new Map(plans.flatMap((plan) => plan.id == null ? [] : [[plan.id, plan] as const]))
  const referenced = new Set(plans.flatMap((plan) =>
    plan.nextPlanId != null && byId.has(plan.nextPlanId) ? [plan.nextPlanId] : []))
  const ordered: ReplyPlan[] = []
  const visited = new Set<number>()
  const appendChain = (head: ReplyPlan) => {
    let plan: ReplyPlan | undefined = head
    while (plan && plan.id != null && !visited.has(plan.id)) {
      ordered.push(plan)
      visited.add(plan.id)
      plan = plan.nextPlanId == null ? undefined : byId.get(plan.nextPlanId)
    }
  }
  plans.filter((plan) => plan.id != null && !referenced.has(plan.id)).forEach(appendChain)
  plans.filter((plan) => plan.id != null && !visited.has(plan.id)).forEach(appendChain)
  return ordered
}

function buildScene(
  plan: ReplyPlan,
  kind: TrpgExecutionScene['kind'],
  activePlan: ReplyPlan,
  ancestorIds: Set<number>,
  turn: CurrentTurn | null,
  combat: boolean,
  excludedCharacterIds = new Set<number>(),
  childScenes: TrpgExecutionScene[] = [],
): TrpgExecutionScene {
  const status: TrpgExecutionScene['status'] = plan.id === activePlan.id
    ? 'current'
    : plan.id != null && ancestorIds.has(plan.id) ? 'waiting-child'
    : 'waiting'
  const statusLabel = combat && status === 'current'
    ? '当前战斗'
    : status === 'current' ? '当前场景'
    : status === 'waiting-child' ? '等待子场景返回'
    : '等待进入'
  const activeItems = plan.items
    .filter((item) => item.subjectCharacterId == null || !excludedCharacterIds.has(item.subjectCharacterId))
    .filter((item) => (item.participantStatus ?? 'ACTIVE') === 'ACTIVE')
    .filter((item) => !isGenericKp(item) || (!combat && status === 'current'))
    .sort((a, b) => a.order - b.order)
  const activeActors: TrpgExecutionActor[] = activeItems.map((item) => ({
      item,
      name: actorName(item),
      ...actorStatus(item, plan, status, turn),
      genericKp: isGenericKp(item),
    }))
  const route = status === 'current' ? turn?.routeContext : undefined
  if (route) {
    const owner = activeActors.find((actor) =>
      actor.item.subjectCharacterId === route.ownerCharacterId)
    const targetItem = plan.items.find((item) =>
      item.subjectCharacterId === route.targetCharacterId)
    if (owner && targetItem && owner.item !== targetItem) {
      const routeLabel = turn?.actionType === 'combat_defense'
        ? '等待防守'
        : turn?.actionType === 'trpg_interaction_response'
          ? '等待回复'
          : '等待响应'
      owner.status = 'waiting_interaction'
      owner.statusLabel = routeLabel
      owner.routedActor = {
        item: targetItem,
        name: actorName(targetItem),
        status: 'waiting_input',
        statusLabel: routeLabel,
        genericKp: isGenericKp(targetItem),
      }
    }
  }
  const bucket = (participantStatus: 'WAITING' | 'READY') => plan.items
    .filter((item) => item.subjectCharacterId == null || !excludedCharacterIds.has(item.subjectCharacterId))
    .filter((item) => item.participantStatus === participantStatus && !isGenericKp(item))
    .sort((a, b) => a.order - b.order)
    .map((item) => ({
      item,
      name: actorName(item),
      status: participantStatus.toLowerCase(),
      statusLabel: participantStatus === 'WAITING' ? '暂不参与' : '已完成本场景探索',
      genericKp: false,
    }))
  return {
    plan, kind, status, statusLabel,
    activeActors,
    waitingActors: bucket('WAITING'),
    readyActors: bucket('READY'),
    childScenes,
  }
}

function sceneRoot(plan: ReplyPlan, byId: Map<number, ReplyPlan>): ReplyPlan {
  let root = plan
  const visited = new Set<number>()
  while (root.parentPlanId != null && !visited.has(root.parentPlanId)) {
    visited.add(root.parentPlanId)
    const parent = byId.get(root.parentPlanId)
    if (!parent) break
    root = parent
  }
  return root
}

function moveFirst(plans: ReplyPlan[], planId?: number): ReplyPlan[] {
  if (planId == null) return plans
  const index = plans.findIndex((plan) => plan.id === planId)
  if (index <= 0) return plans
  return [plans[index], ...plans.slice(0, index), ...plans.slice(index + 1)]
}

export function buildTrpgExecutionState(
  plans: ReplyPlan[],
  turn: CurrentTurn | null,
): TrpgExecutionState {
  const activePlan = plans[0]
  if (!activePlan) {
    return { kind: 'exploration', title: '当前场景与行动顺序', subtitle: '暂无场景计划', scenes: [] }
  }
  if (activePlan.source === 'COMBAT') {
    return {
      kind: 'combat',
      title: '当前战斗与行动顺序',
      subtitle: activePlan.displayName,
      scenes: [buildScene(activePlan, 'combat', activePlan, new Set(), turn, true)],
    }
  }
  const scenePlans = plans.filter((plan) => plan.source === 'SCENE')
  const byId = new Map(scenePlans.flatMap((plan) => plan.id == null ? [] : [[plan.id, plan] as const]))
  const ancestorIds = new Set<number>()
  let parentId = activePlan.parentPlanId
  while (parentId != null && !ancestorIds.has(parentId)) {
    ancestorIds.add(parentId)
    parentId = byId.get(parentId)?.parentPlanId
  }
  const activeRoot = sceneRoot(activePlan, byId)
  const roots = orderLinkedPlans(scenePlans.filter((plan) => sceneRoot(plan, byId) === plan))
  const childrenByRoot = new Map<number, ReplyPlan[]>()
  scenePlans.forEach((plan) => {
    const root = sceneRoot(plan, byId)
    if (plan === root || root.id == null) return
    const children = childrenByRoot.get(root.id) ?? []
    children.push(plan)
    childrenByRoot.set(root.id, children)
  })
  return {
    kind: 'exploration',
    title: '当前场景与行动顺序',
    subtitle: activePlan.displayName,
    scenes: moveFirst(roots, activeRoot.id).map((root) => {
      const childPlans = orderLinkedPlans(root.id == null ? [] : childrenByRoot.get(root.id) ?? [])
      const childScenes = childPlans.map((child) =>
        buildScene(child, 'child', activePlan, ancestorIds, turn, false))
      const childCharacterIds = new Set(childPlans.flatMap((child) => child.items.flatMap((item) =>
        item.subjectCharacterId == null ? [] : [item.subjectCharacterId])))
      return buildScene(root, 'main', activePlan, ancestorIds, turn, false, childCharacterIds, childScenes)
    }),
  }
}
