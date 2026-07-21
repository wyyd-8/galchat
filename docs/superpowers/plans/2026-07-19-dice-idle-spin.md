# 骰子待机旋转 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 选择骰子后立即展示随机初始朝向的模型，并在点击掷骰前以约 9 秒一圈的速度沿世界 Y 轴持续同向旋转，点击后从当前姿态无跳变地进入现有掷骰动画。

**Architecture:** 新建独立的待机运动模块，负责随机四元数、按时间计算角增量和可停止的共享帧循环；新建正式掷骰旋转插值模块，使现有动画可以从任意当前 Euler 姿态开始。`ThreeDiceBoard` 管理准备、待机和播放状态，`main.ts` 将首次加载、示例变化和皮肤变化接到准备状态。

**Tech Stack:** TypeScript 6、Three.js 0.185、浏览器 `requestAnimationFrame`、Node.js 内置测试运行器、Vite 8。

## Global Constraints

- 待机动画只存在于“已选择、尚未掷骰”状态；掷骰完成后不恢复。
- 每颗骰子只在准备时生成一次随机三维初始朝向；所有骰子绕世界 Y 轴同向匀速旋转，周期为 `9_000ms`。
- `prefers-reduced-motion: reduce` 下只展示随机静态朝向，不启动持续旋转。
- 保留现有 3.6 秒掷骰时长、阶段划分、最终复位、1.12 倍结尾放大、结果标签、皮肤和百分骰选中效果。
- 不添加漂浮、位移、阴影变化、回弹或额外缩放。
- 使用一个共享帧循环，并在重新准备或正式掷骰前取消它。

---

## File Structure

- Create `dice-lab/src/dice/idleSpin.ts`: 随机初始四元数、待机角增量、共享帧循环及其接口。
- Create `dice-lab/test/idleSpin.test.ts`: 待机随机性、速度、同向性、启动和停止行为。
- Create `dice-lab/src/dice/rollRotation.ts`: 从任意当前姿态生成多圈正式旋转终点并插值。
- Create `dice-lab/test/rollRotation.test.ts`: 验证零进度不跳变及终点与目标姿态等价。
- Modify `dice-lab/src/dice/ThreeDice.ts`: 接入随机朝向、待机循环、准备状态和无跳变正式旋转；保留既有落点放大。
- Modify `dice-lab/src/main.ts`: 在首次加载、示例和皮肤变化时准备骰子，点击按钮时播放已准备的骰子。

### Task 1: 可测试的待机运动核心

**Files:**
- Create: `dice-lab/src/dice/idleSpin.ts`
- Test: `dice-lab/test/idleSpin.test.ts`

**Interfaces:**
- Produces: `randomIdleQuaternion(random?: () => number): QuaternionComponents`
- Produces: `idleSpinAngle(deltaMilliseconds: number): number`
- Produces: `createIdleSpinLoop(scheduler: IdleSpinScheduler): IdleSpinLoop`
- Produces: `IdleSpinTarget`，由 `ThreeDiceBoard` 适配 Three.js 模型和渲染器。

- [ ] **Step 1: 写随机朝向和角速度的失败测试**

```ts
// dice-lab/test/idleSpin.test.ts
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  IDLE_TURN_DURATION_MS,
  idleSpinAngle,
  randomIdleQuaternion,
} from '../src/dice/idleSpin.ts'

test('creates a normalized deterministic quaternion from supplied randomness', () => {
  const values = [0.25, 0.5, 0.75]
  const quaternion = randomIdleQuaternion(() => values.shift() ?? 0)
  const length = Math.hypot(quaternion.x, quaternion.y, quaternion.z, quaternion.w)

  assert.ok(Math.abs(length - 1) < 1e-12)
  assert.ok(Math.abs(quaternion.y + Math.sqrt(0.75)) < 1e-12)
  assert.ok(Math.abs(quaternion.z + 0.5) < 1e-12)
})

test('rotates only in the positive direction at one turn per nine seconds', () => {
  assert.equal(IDLE_TURN_DURATION_MS, 9_000)
  assert.ok(Math.abs(idleSpinAngle(4_500) - Math.PI) < 1e-12)
  assert.equal(idleSpinAngle(-20), 0)
})
```

- [ ] **Step 2: 运行测试并确认因模块缺失而失败**

Run: `cd dice-lab && node --test test/idleSpin.test.ts`

Expected: FAIL，错误包含 `Cannot find module '../src/dice/idleSpin.ts'`。

- [ ] **Step 3: 实现最小随机朝向与角增量**

```ts
// dice-lab/src/dice/idleSpin.ts
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
```

- [ ] **Step 4: 运行测试并确认基础数学通过**

Run: `cd dice-lab && node --test test/idleSpin.test.ts`

Expected: 2 tests PASS，0 FAIL。

- [ ] **Step 5: 为共享帧循环补充失败测试**

在同一测试文件追加：

```ts
import { createIdleSpinLoop, type IdleSpinScheduler } from '../src/dice/idleSpin.ts'

test('uses one stoppable frame loop and advances every target by the same angle', () => {
  let nextHandle = 0
  const callbacks = new Map<number, (now: number) => void>()
  const cancelled: number[] = []
  const scheduler: IdleSpinScheduler = {
    request(callback) {
      nextHandle += 1
      callbacks.set(nextHandle, callback)
      return nextHandle
    },
    cancel(handle) {
      cancelled.push(handle)
      callbacks.delete(handle)
    },
  }
  const angles: number[][] = [[], []]
  const renders = [0, 0]
  const loop = createIdleSpinLoop(scheduler)
  loop.start(angles.map((targetAngles, index) => ({
    rotateBy(angleRadians) {
      targetAngles.push(angleRadians)
    },
    render() {
      renders[index] += 1
    },
  })))

  callbacks.get(1)?.(1_000)
  callbacks.get(2)?.(5_500)
  loop.stop()

  assert.deepEqual(renders, [2, 2])
  assert.ok(Math.abs(angles[0][0] - Math.PI) < 1e-12)
  assert.equal(angles[0][0], angles[1][0])
  assert.deepEqual(cancelled, [3])
})
```

- [ ] **Step 6: 运行测试并确认循环接口缺失导致失败**

Run: `cd dice-lab && node --test test/idleSpin.test.ts`

Expected: FAIL，错误指出 `createIdleSpinLoop` 或 `IdleSpinScheduler` 尚未导出。

- [ ] **Step 7: 实现共享帧循环**

在 `idleSpin.ts` 追加：

```ts
export interface IdleSpinTarget {
  rotateBy: (angleRadians: number) => void
  render: () => void
}

export interface IdleSpinScheduler {
  request: (callback: (now: number) => void) => number
  cancel: (handle: number) => void
}

export interface IdleSpinLoop {
  start: (targets: IdleSpinTarget[]) => void
  stop: () => void
}

export function createIdleSpinLoop(scheduler: IdleSpinScheduler): IdleSpinLoop {
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
      target.render()
    }
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
```

- [ ] **Step 8: 运行待机测试并提交**

Run: `cd dice-lab && node --test test/idleSpin.test.ts`

Expected: 3 tests PASS，0 FAIL。

```bash
git add dice-lab/src/dice/idleSpin.ts dice-lab/test/idleSpin.test.ts
git commit -m "feat: add dice idle spin core"
```

### Task 2: 从待机姿态无跳变进入正式掷骰

**Files:**
- Create: `dice-lab/src/dice/rollRotation.ts`
- Test: `dice-lab/test/rollRotation.test.ts`
- Modify: `dice-lab/src/dice/ThreeDice.ts:430-505`

**Interfaces:**
- Consumes: Three.js 将当前和目标四元数转换为 `XYZ` Euler 角。
- Produces: `continuousRotationTarget(start, target, turns): EulerRotation`
- Produces: `interpolateRotation(start, end, progress): EulerRotation`

- [ ] **Step 1: 写无跳变和目标等价的失败测试**

```ts
// dice-lab/test/rollRotation.test.ts
import assert from 'node:assert/strict'
import test from 'node:test'

import { continuousRotationTarget, interpolateRotation } from '../src/dice/rollRotation.ts'

const FULL_TURN = Math.PI * 2
const moduloTurn = (value: number): number => ((value % FULL_TURN) + FULL_TURN) % FULL_TURN

test('keeps the current visible rotation at zero progress', () => {
  const start = { x: 0.8, y: -1.1, z: 2.4 }
  const target = continuousRotationTarget(
    start,
    { x: -0.3, y: 0.4, z: -2 },
    { x: 3, y: 4, z: 2 },
  )
  assert.deepEqual(interpolateRotation(start, target, 0), start)
})

test('ends at an equivalent target after positive full turns', () => {
  const start = { x: 0.8, y: -1.1, z: 2.4 }
  const desired = { x: -0.3, y: 0.4, z: -2 }
  const end = continuousRotationTarget(start, desired, { x: 3, y: 4, z: 2 })

  assert.ok(end.x > start.x && end.y > start.y && end.z > start.z)
  assert.ok(Math.abs(moduloTurn(end.x) - moduloTurn(desired.x)) < 1e-12)
  assert.ok(Math.abs(moduloTurn(end.y) - moduloTurn(desired.y)) < 1e-12)
  assert.ok(Math.abs(moduloTurn(end.z) - moduloTurn(desired.z)) < 1e-12)
})
```

- [ ] **Step 2: 运行测试并确认模块缺失导致失败**

Run: `cd dice-lab && node --test test/rollRotation.test.ts`

Expected: FAIL，错误包含 `Cannot find module '../src/dice/rollRotation.ts'`。

- [ ] **Step 3: 实现多圈终点和插值**

```ts
// dice-lab/src/dice/rollRotation.ts
const FULL_TURN = Math.PI * 2

export interface EulerRotation {
  x: number
  y: number
  z: number
}

function unwrapFrom(start: number, target: number, turns: number): number {
  const positiveDelta = ((target - start) % FULL_TURN + FULL_TURN) % FULL_TURN
  return start + positiveDelta + turns * FULL_TURN
}

export function continuousRotationTarget(
  start: EulerRotation,
  target: EulerRotation,
  turns: EulerRotation,
): EulerRotation {
  return {
    x: unwrapFrom(start.x, target.x, turns.x),
    y: unwrapFrom(start.y, target.y, turns.y),
    z: unwrapFrom(start.z, target.z, turns.z),
  }
}

export function interpolateRotation(
  start: EulerRotation,
  end: EulerRotation,
  progress: number,
): EulerRotation {
  return {
    x: start.x + (end.x - start.x) * progress,
    y: start.y + (end.y - start.y) * progress,
    z: start.z + (end.z - start.z) * progress,
  }
}
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `cd dice-lab && node --test test/rollRotation.test.ts`

Expected: 2 tests PASS，0 FAIL。

- [ ] **Step 5: 将 `animateDie` 接到当前姿态，保留原有末段效果**

在 `ThreeDice.ts` 导入：

```ts
import { continuousRotationTarget, interpolateRotation } from './rollRotation'
```

删除原有 `continuousRotation`，在 `animateDie` 创建目标时改为：

```ts
const startRotation = new THREE.Euler().setFromQuaternion(die.model.quaternion, 'XYZ')
const targetAngles = new THREE.Euler().setFromQuaternion(
  reduceMotion ? finalTarget : landingTarget,
  'XYZ',
)
const rotation = continuousRotationTarget(
  startRotation,
  targetAngles,
  { x: 3 + die.turnSeed % 2, y: 4 + die.turnSeed % 2, z: 2 },
)
```

把正式旋转阶段的绝对零起点设置替换为：

```ts
const currentRotation = interpolateRotation(startRotation, rotation, rotationProgress)
die.model.rotation.set(
  currentRotation.x,
  currentRotation.y,
  currentRotation.z,
  'XYZ',
)
```

不要修改 `rollEnd = 0.84`、`pauseEnd = 0.875`、`settleScaleFactor` 调用、标签动画或最终四元数。

- [ ] **Step 6: 运行运动测试、缩放回归和构建并提交**

Run: `cd dice-lab && node --test test/rollRotation.test.ts test/settleScale.test.ts`

Expected: 4 tests PASS，0 FAIL。

Run: `cd dice-lab && npm run build`

Expected: TypeScript 和 Vite 构建成功；允许现有的 chunk size 警告。

```bash
git add dice-lab/src/dice/rollRotation.ts dice-lab/test/rollRotation.test.ts dice-lab/src/dice/ThreeDice.ts
git commit -m "feat: continue dice roll from idle pose"
```

### Task 3: 选择后准备骰子并管理待机生命周期

**Files:**
- Modify: `dice-lab/src/dice/ThreeDice.ts:603-637`
- Modify: `dice-lab/src/main.ts:15-132`
- Test: `dice-lab/test/idleSpin.test.ts`

**Interfaces:**
- Consumes: `randomIdleQuaternion`、`createIdleSpinLoop` 和 `IdleSpinTarget`。
- Produces: `ThreeDiceBoard.prepareResult(result: DiceRollResult): Promise<void>`。
- Preserves: `ThreeDiceBoard.playResult(result)` 和 `window.playDiceResult(result)` 的直接播放能力。

- [ ] **Step 1: 补充重新启动循环会取消旧循环的失败测试**

在 `idleSpin.test.ts` 追加：

```ts
test('cancels the previous frame before restarting', () => {
  let handle = 0
  const cancelled: number[] = []
  const loop = createIdleSpinLoop({
    request() {
      handle += 1
      return handle
    },
    cancel(frameHandle) {
      cancelled.push(frameHandle)
    },
  })

  loop.start([])
  loop.start([])

  assert.deepEqual(cancelled, [1])
})
```

- [ ] **Step 2: 临时运行测试并确认若实现不满足重启清理则失败；若当前核心已满足则保留为回归测试**

Run: `cd dice-lab && node --test test/idleSpin.test.ts`

Expected: 测试证明第二次 `start` 会取消句柄 `1`；若 Task 1 的最小实现已经通过，则记录为已覆盖行为，不改生产代码。

- [ ] **Step 3: 在棋盘中接入准备状态和共享待机循环**

在 `ThreeDice.ts` 导入：

```ts
import {
  createIdleSpinLoop,
  randomIdleQuaternion,
  type IdleSpinTarget,
} from './idleSpin'
```

增加世界 Y 轴常量：

```ts
const Y_AXIS = new THREE.Vector3(0, 1, 0)
```

把 `ThreeDiceBoard` 改为以下状态和方法结构；既有百分骰结果收尾循环保持原样：

```ts
export class ThreeDiceBoard {
  private activeDice: RenderedDie[] = []
  private skin: DiceSkin = 'classic'
  private preparationGeneration = 0
  private preparedResult: DiceRollResult | undefined
  private readonly idleSpin = createIdleSpinLoop({
    request: (callback) => window.requestAnimationFrame(callback),
    cancel: (handle) => window.cancelAnimationFrame(handle),
  })

  constructor(private readonly diceTray: HTMLElement) {
    this.renderWaitingDice()
  }

  setSkin(skin: DiceSkin): void {
    this.skin = skin
  }

  async prepareResult(result: DiceRollResult): Promise<void> {
    if (!result.modules.length) throw new Error('后端掷骰结果不包含骰子模块')
    const generation = ++this.preparationGeneration
    this.idleSpin.stop()
    this.preparedResult = undefined
    const modules = await Promise.all(result.modules.map((module) => createModule(module, this.skin)))
    const nextDice = modules.flatMap((module) => module.dice)
    if (generation !== this.preparationGeneration) {
      nextDice.forEach((die) => die.dispose())
      return
    }

    this.activeDice.forEach((die) => die.dispose())
    this.activeDice = nextDice
    this.diceTray.replaceChildren(...modules.map((module) => module.element))
    for (const die of this.activeDice) {
      const orientation = randomIdleQuaternion()
      die.model.quaternion.set(orientation.x, orientation.y, orientation.z, orientation.w)
      die.renderer.render(die.scene, die.camera)
    }
    this.preparedResult = result

    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      const targets: IdleSpinTarget[] = this.activeDice.map((die) => {
        const spinStep = new THREE.Quaternion()
        return {
          rotateBy(angleRadians) {
            spinStep.setFromAxisAngle(Y_AXIS, angleRadians)
            die.model.quaternion.premultiply(spinStep)
          },
          render() {
            die.renderer.render(die.scene, die.camera)
          },
        }
      })
      this.idleSpin.start(targets)
    }
  }

  async playResult(result: DiceRollResult): Promise<void> {
    if (this.preparedResult !== result || this.activeDice.length === 0) {
      await this.prepareResult(result)
    }
    if (this.preparedResult !== result) return
    this.idleSpin.stop()
    this.preparedResult = undefined
    await Promise.all(this.activeDice.map((die, index) => animateDie(die, index * 90)))
    for (const die of this.activeDice) {
      const outcome = die.wrapper.dataset.percentileOutcome
      if (!outcome) continue
      die.wrapper.classList.add(outcome === 'selected' ? 'is-selected' : 'is-dimmed')
      if (outcome === 'selected') die.wrapper.setAttribute('aria-current', 'true')
    }
  }

  private renderWaitingDice(): void {
    const placeholder = document.createElement('p')
    placeholder.className = 'empty-tray'
    placeholder.textContent = '选择示例并点击“播放掷骰”'
    this.diceTray.replaceChildren(placeholder)
  }
}
```

- [ ] **Step 4: 将页面选择事件接到准备状态**

在 `main.ts` 增加准备请求代次和函数：

```ts
let prepareGeneration = 0

function selectedResult(): DiceRollResult {
  return DEMOS[demoSelect.value] ?? DEMOS.standard
}

async function prepare(result: DiceRollResult): Promise<void> {
  const generation = ++prepareGeneration
  rollButton.disabled = true
  resultValues.textContent = result.formula
  resultTotal.textContent = '等待掷骰'
  try {
    await diceBoard.prepareResult(result)
  } catch (error) {
    if (generation !== prepareGeneration) return
    resultValues.textContent = '无法加载'
    resultTotal.textContent = error instanceof Error ? error.message : '未知错误'
  } finally {
    if (generation === prepareGeneration) rollButton.disabled = false
  }
}
```

保留 `play` 中现有禁用和结果展示逻辑，但在开头增加 `prepareGeneration += 1`。替换事件监听：

```ts
rollButton.addEventListener('click', () => play(selectedResult()))
demoSelect.addEventListener('change', () => {
  void prepare(selectedResult())
})
skinButton.addEventListener('click', () => {
  const currentIndex = SKIN_ORDER.indexOf(activeSkin)
  setSkin(SKIN_ORDER[(currentIndex + 1) % SKIN_ORDER.length])
  void prepare(selectedResult())
})
```

在 URL 参数和皮肤初始化完成后启动首次准备：

```ts
void prepare(selectedResult())
```

- [ ] **Step 5: 运行完整单元测试和构建**

Run: `cd dice-lab && node --test test/*.test.ts`

Expected: 8 tests PASS，0 FAIL。

Run: `cd dice-lab && npm run build`

Expected: TypeScript 与 Vite 构建成功；允许现有 chunk size 警告。

- [ ] **Step 6: 在浏览器验证状态与视觉连续性**

Run: `cd dice-lab && npm run dev`

在 `http://127.0.0.1:5173/` 验证：

- 首次加载即展示标准骰，且每颗初始朝向不同。
- 所有骰子围绕世界 Y 轴同向匀速旋转，约 9 秒一圈，无漂浮、缩放或回弹。
- 切换“骰子组”和“百分骰”后展示对应模型并重新进入随机待机。
- 切换皮肤只重建待机骰子，不自动播放正式掷骰。
- 点击“播放掷骰”时起始姿态无跳变；现有 3.6 秒节奏、复位与 1.12 倍单调放大保持不变。
- 掷骰结束后保持最终姿态，不恢复待机。
- 快速切换示例和皮肤时不出现旧骰子覆盖或多个待机循环。
- 浏览器控制台无异常。

- [ ] **Step 7: 检查差异并提交**

Run: `git diff --check`

Expected: 无输出。

Run: `git status --short`

Expected: 只包含本计划涉及的待机动画文件，以及实施前已经存在的用户文件。

```bash
git add dice-lab/src/dice/ThreeDice.ts dice-lab/src/main.ts dice-lab/test/idleSpin.test.ts
git commit -m "feat: show idle spinning dice before roll"
```
