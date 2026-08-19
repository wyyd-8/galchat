const FULL_TURN = Math.PI * 2

export const IDLE_TURN_DURATION_MS = 9_000

export interface QuaternionComponents {
  x: number
  y: number
  z: number
  w: number
}

export function randomIdleQuaternion(random: () => number = Math.random): QuaternionComponents {
  const first = random()
  const second = random()
  const third = random()
  const lowerRadius = Math.sqrt(1 - first)
  const upperRadius = Math.sqrt(first)

  return {
    x: lowerRadius * Math.sin(FULL_TURN * second),
    y: lowerRadius * Math.cos(FULL_TURN * second),
    z: upperRadius * Math.sin(FULL_TURN * third),
    w: upperRadius * Math.cos(FULL_TURN * third),
  }
}

export function idleSpinAngle(deltaMilliseconds: number): number {
  return FULL_TURN * Math.max(deltaMilliseconds, 0) / IDLE_TURN_DURATION_MS
}

export interface IdleSpinTarget {
  rotateBy: (angleRadians: number) => void
}

export interface IdleSpinScheduler {
  request: (callback: (now: number) => void) => number
  cancel: (handle: number) => void
}

export interface IdleSpinLoop {
  start: (targets: IdleSpinTarget[]) => void
  stop: () => void
}

export interface RenderCoordinator {
  request: () => void
  stop: () => void
}

export function createRenderCoordinator(
  scheduler: IdleSpinScheduler,
  render: () => void,
): RenderCoordinator {
  let frameHandle: number | undefined

  return {
    request() {
      if (frameHandle !== undefined) return
      frameHandle = scheduler.request(() => {
        frameHandle = undefined
        render()
      })
    },
    stop() {
      if (frameHandle !== undefined) scheduler.cancel(frameHandle)
      frameHandle = undefined
    },
  }
}

export function createIdleSpinLoop(
  scheduler: IdleSpinScheduler,
  renderAll?: () => void,
): IdleSpinLoop {
  let activeTargets: IdleSpinTarget[] = []
  let frameHandle: number | undefined
  let previousTime: number | undefined

  const stop = (): void => {
    if (frameHandle !== undefined) scheduler.cancel(frameHandle)
    frameHandle = undefined
    previousTime = undefined
    activeTargets = []
  }

  const frame = (now: number): void => {
    if (frameHandle === undefined) return
    const angle = previousTime === undefined ? 0 : idleSpinAngle(now - previousTime)
    previousTime = now
    for (const target of activeTargets) {
      if (angle > 0) target.rotateBy(angle)
    }
    renderAll?.()
    frameHandle = scheduler.request(frame)
  }

  return {
    start(targets) {
      stop()
      activeTargets = targets
      frameHandle = scheduler.request(frame)
    },
    stop,
  }
}
