import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'

export interface DiceRollValue {
  sides: number
  value: number
  role: 'NORMAL' | 'PERCENTILE_ONES' | 'PERCENTILE_TENS'
  selected: boolean
}

export interface DiceRollModule {
  expression: string
  diceCount: number
  diceSides: number
  modifier: 'NORMAL' | 'ADVANTAGE' | 'DOUBLE_ADVANTAGE' | 'DISADVANTAGE' | 'DOUBLE_DISADVANTAGE'
  dice: DiceRollValue[]
  result: number
}

export interface DiceRollResult {
  formula: string
  modules: DiceRollModule[]
  result: number
}

type ModelKey = 'd4' | 'd6' | 'd8' | 'd10-ones' | 'd10-tens' | 'd12' | 'd20'

interface DiceModelConfig {
  key: ModelKey
  url: string
  facePattern: RegExp
  faceLabels: string[]
  displayScale?: number
}

interface RenderedDie {
  wrapper: HTMLElement
  valueLabel: HTMLElement
  renderer: THREE.WebGLRenderer
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  model: THREE.Group
  faceNormal: THREE.Vector3
  faceUp: THREE.Vector3
  frontValue: string
  turnSeed: number
  dispose: () => void
}

const FRONT = new THREE.Vector3(0, 0, 1)
const SCREEN_UP = new THREE.Vector3(0, 1, 0)
const GLYPH_UP = new THREE.Vector3(0, 0, -1)
const Z_AXIS = new THREE.Vector3(0, 0, 1)
const loader = new GLTFLoader()
const templatePromises = new Map<ModelKey, Promise<THREE.Group>>()

const MODEL_CONFIGS: Record<ModelKey, DiceModelConfig> = {
  d4: {
    key: 'd4',
    url: new URL('../../model/D4_四面骰_baked.glb', import.meta.url).href,
    facePattern: /^D4_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4'],
    displayScale: 0.65,
  },
  d6: {
    key: 'd6',
    url: new URL('../../model/D6_六面骰_baked.glb', import.meta.url).href,
    facePattern: /^D6_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4', '5', '6'],
    displayScale: 0.65,
  },
  d8: {
    key: 'd8',
    url: new URL('../../model/D8_八面骰_baked.glb', import.meta.url).href,
    facePattern: /^D8_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4', '5', '6', '7', '8'],
  },
  'd10-ones': {
    key: 'd10-ones',
    url: new URL('../../model/D10_个位骰_0-9_baked.glb', import.meta.url).href,
    facePattern: /^D10_0_9_Face_\d+_(\d)$/,
    faceLabels: ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'],
  },
  'd10-tens': {
    key: 'd10-tens',
    url: new URL('../../model/D10_百分骰_00-90_baked.glb', import.meta.url).href,
    facePattern: /^D10_00_90_Face_\d+_(\d{2})$/,
    faceLabels: ['00', '10', '20', '30', '40', '50', '60', '70', '80', '90'],
  },
  d12: {
    key: 'd12',
    url: new URL('../../model/D12_十二面骰_baked.glb', import.meta.url).href,
    facePattern: /^D12_Face_\d+_(\d+)$/,
    faceLabels: Array.from({ length: 12 }, (_, index) => String(index + 1)),
  },
  d20: {
    key: 'd20',
    url: new URL('../../model/D20_二十面骰_baked.glb', import.meta.url).href,
    facePattern: /^Face_\d+_Number_(\d+)$/,
    faceLabels: Array.from({ length: 20 }, (_, index) => String(index + 1)),
  },
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
}

function loadTemplate(config: DiceModelConfig): Promise<THREE.Group> {
  const cached = templatePromises.get(config.key)
  if (cached) return cached
  const promise = loader.loadAsync(config.url).then((gltf) => gltf.scene)
  templatePromises.set(config.key, promise)
  return promise
}

async function buildModel(
  config: DiceModelConfig,
  faceLabel: string,
): Promise<{ model: THREE.Group; faceNormal: THREE.Vector3; faceUp: THREE.Vector3; dispose: () => void }> {
  const source = await loadTemplate(config)
  const content = source.clone(true)
  const geometries: THREE.BufferGeometry[] = []
  const materials: THREE.Material[] = []
  content.traverse((object) => {
    if (!(object instanceof THREE.Mesh)) return
    object.geometry = object.geometry.clone()
    geometries.push(object.geometry)
    const sourceMaterials = Array.isArray(object.material) ? object.material : [object.material]
    const clonedMaterials = sourceMaterials.map((material) => {
      const clone = material.clone()
      materials.push(clone)
      return clone
    })
    object.material = Array.isArray(object.material) ? clonedMaterials : clonedMaterials[0]
  })
  content.updateMatrixWorld(true)

  let bodyNode: THREE.Object3D | undefined
  content.traverse((object) => {
    if (object.name.includes('_Body_')) bodyNode = object
  })
  if (!bodyNode) throw new Error(`${config.key} GLB 缺少骰子本体`)
  const bounds = new THREE.Box3().setFromObject(bodyNode)
  const center = bounds.getCenter(new THREE.Vector3())
  const size = bounds.getSize(new THREE.Vector3())
  const scale = 2 / Math.max(size.x, size.y, size.z)
  const numberNodes = new Map<string, THREE.Object3D>()
  content.traverse((object) => {
    const match = config.facePattern.exec(object.name)
    if (match) numberNodes.set(match[1], object)
  })
  for (const label of config.faceLabels) {
    if (!numberNodes.has(label)) throw new Error(`${config.key} GLB 缺少结果面 ${label}`)
  }
  const faceNode = numberNodes.get(faceLabel)
  if (!faceNode) throw new Error(`${config.key} 不包含结果面 ${faceLabel}`)
  const faceNormal = faceNode.getWorldPosition(new THREE.Vector3()).sub(center).normalize()
  const faceUp = GLYPH_UP.clone().applyQuaternion(faceNode.getWorldQuaternion(new THREE.Quaternion()))
  faceUp.addScaledVector(faceNormal, -faceUp.dot(faceNormal)).normalize()

  const model = new THREE.Group()
  content.position.copy(center).multiplyScalar(-1)
  model.scale.setScalar(scale * (config.displayScale ?? 1))
  model.add(content)
  return {
    model,
    faceNormal,
    faceUp,
    dispose: () => {
      geometries.forEach((geometry) => geometry.dispose())
      materials.forEach((material) => material.dispose())
    },
  }
}

async function createDie(
  config: DiceModelConfig,
  faceLabel: string,
  displayValue: string,
  typeLabelText: string,
  turnSeed: number,
): Promise<RenderedDie> {
  const wrapper = document.createElement('div')
  wrapper.className = `die-slot die-slot-${config.key}`
  wrapper.setAttribute('aria-label', `${typeLabelText}，目标点数 ${displayValue}`)
  const typeLabel = document.createElement('span')
  typeLabel.className = 'die-type'
  typeLabel.textContent = typeLabelText
  const valueLabel = document.createElement('output')
  valueLabel.className = 'die-value'
  valueLabel.textContent = displayValue
  const shadow = document.createElement('div')
  shadow.className = 'die-shadow'

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(104, 104, false)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.12
  renderer.domElement.className = 'three-die-canvas'
  renderer.domElement.dataset.frontValue = ''
  renderer.domElement.dataset.modelSource = config.key

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 20)
  camera.position.set(0, 0, 4)
  scene.add(new THREE.HemisphereLight(0xffffff, 0x392552, 2.25))
  const keyLight = new THREE.DirectionalLight(0xffffff, 3.4)
  keyLight.position.set(-2.5, 4, 5)
  scene.add(keyLight)
  const rimLight = new THREE.DirectionalLight(0x9d6ee7, 2.1)
  rimLight.position.set(4, -2, 2)
  scene.add(rimLight)

  const built = await buildModel(config, faceLabel)
  scene.add(built.model)
  renderer.render(scene, camera)
  wrapper.append(typeLabel, valueLabel, shadow, renderer.domElement)
  return {
    wrapper,
    valueLabel,
    renderer,
    scene,
    camera,
    model: built.model,
    faceNormal: built.faceNormal,
    faceUp: built.faceUp,
    frontValue: displayValue,
    turnSeed,
    dispose: () => {
      built.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
    },
  }
}

function normalDie(value: DiceRollValue): Promise<RenderedDie> {
  if (!Number.isInteger(value.value) || value.value < 1 || value.value > value.sides) {
    throw new Error(`D${value.sides} 结果 ${value.value} 无效`)
  }
  if (value.sides === 10) {
    const faceLabel = value.value === 10 ? '0' : String(value.value)
    return createDie(MODEL_CONFIGS['d10-ones'], faceLabel, String(value.value), 'D10', value.value)
  }
  const key = `d${value.sides}` as ModelKey
  const config = MODEL_CONFIGS[key]
  if (!config) throw new Error(`暂不支持 D${value.sides} 的动画模型`)
  return createDie(config, String(value.value), String(value.value), `D${value.sides}`, value.value)
}

function uprightTarget(normal: THREE.Vector3, up: THREE.Vector3): THREE.Quaternion {
  const faceToFront = new THREE.Quaternion().setFromUnitVectors(normal, FRONT)
  const rotatedUp = up.clone().applyQuaternion(faceToFront)
  const roll = Math.atan2(rotatedUp.x, rotatedUp.y)
  return new THREE.Quaternion().setFromAxisAngle(Z_AXIS, roll).multiply(faceToFront).normalize()
}

function continuousRotation(
  normal: THREE.Vector3,
  up: THREE.Vector3,
  turnSeed: number,
): { x: number; y: number; z: number; target: THREE.Quaternion } {
  const target = uprightTarget(normal, up)
  const targetAngles = new THREE.Euler().setFromQuaternion(target, 'XYZ')
  const fullTurn = Math.PI * 2
  const unwrap = (angle: number, turns: number): number => (
    angle + fullTurn * Math.ceil(turns - angle / fullTurn)
  )
  return {
    x: unwrap(targetAngles.x, 3 + turnSeed % 2),
    y: unwrap(targetAngles.y, 4 + turnSeed % 2),
    z: unwrap(targetAngles.z, 2),
    target,
  }
}

function easeOutCubic(value: number): number {
  return 1 - (1 - value) ** 3
}

async function animateDie(die: RenderedDie, delay: number): Promise<void> {
  await wait(delay)
  die.wrapper.classList.add('is-rolling')
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const duration = reduceMotion ? 180 : 3_600
  const rotation = continuousRotation(die.faceNormal, die.faceUp, die.turnSeed)
  const startedAt = performance.now()

  await new Promise<void>((resolve) => {
    const frame = (now: number): void => {
      const progress = Math.min((now - startedAt) / duration, 1)
      const rotationProgress = easeOutCubic(progress)
      const rotationX = rotation.x * rotationProgress
      const rotationY = rotation.y * rotationProgress
      const rotationZ = rotation.z * rotationProgress
      die.model.rotation.set(rotationX, rotationY, rotationZ, 'XYZ')
      die.renderer.domElement.dataset.rotationX = rotationX.toFixed(6)
      die.renderer.domElement.dataset.rotationY = rotationY.toFixed(6)
      die.renderer.domElement.dataset.rotationZ = rotationZ.toFixed(6)
      die.renderer.render(die.scene, die.camera)
      if (progress < 1) requestAnimationFrame(frame)
      else resolve()
    }
    requestAnimationFrame(frame)
  })

  die.model.quaternion.copy(rotation.target)
  die.renderer.render(die.scene, die.camera)
  const frontDot = die.faceNormal.clone().applyQuaternion(rotation.target).dot(FRONT)
  const upDot = die.faceUp.clone().applyQuaternion(rotation.target).dot(SCREEN_UP)
  die.renderer.domElement.dataset.frontValue = die.frontValue
  die.renderer.domElement.dataset.frontDot = frontDot.toFixed(6)
  die.renderer.domElement.dataset.upDot = upDot.toFixed(6)
  die.wrapper.classList.remove('is-rolling')
  die.wrapper.classList.add('is-settled')

  await die.valueLabel.animate([
    { opacity: 0, transform: 'translateX(-50%) translateY(12px) scale(.7)' },
    { opacity: 1, transform: 'translateX(-50%) translateY(-3px) scale(1.08)', offset: 0.72 },
    { opacity: 1, transform: 'translateX(-50%) translateY(0) scale(1)' },
  ], {
    duration: reduceMotion ? 100 : 320,
    easing: 'cubic-bezier(.2,.8,.25,1.25)',
    fill: 'forwards',
  }).finished
}

function moduleHeading(module: DiceRollModule): HTMLElement {
  const heading = document.createElement('header')
  heading.className = 'module-heading'
  const expression = document.createElement('strong')
  expression.textContent = module.expression
  const total = document.createElement('span')
  total.textContent = `模块结果 ${module.result}`
  heading.append(expression, total)
  return heading
}

async function createPercentileModule(module: DiceRollModule): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
  const ones = module.dice.find((die) => die.role === 'PERCENTILE_ONES')
  const tens = module.dice.filter((die) => die.role === 'PERCENTILE_TENS')
  if (!ones || tens.length === 0) throw new Error(`${module.expression} 缺少百分骰的十位或个位数据`)
  if (tens.filter((die) => die.selected).length !== 1) throw new Error(`${module.expression} 必须选中一个十位骰`)

  const element = document.createElement('section')
  element.className = 'dice-module'
  element.append(moduleHeading(module))
  const roll = document.createElement('div')
  roll.className = 'percentile-roll'
  const tensGroup = document.createElement('div')
  tensGroup.className = 'percentile-tens'
  const rendered: RenderedDie[] = []

  for (const tensDie of tens) {
    const tensLabel = tensDie.value === 0 ? '00' : String(tensDie.value * 10)
    const tensModel = await createDie(MODEL_CONFIGS['d10-tens'], tensLabel, tensLabel, '十位', tensDie.value)
    tensModel.wrapper.classList.add('percentile-tens-die')
    tensModel.wrapper.dataset.percentileOutcome = tensDie.selected ? 'selected' : 'dimmed'
    tensGroup.append(tensModel.wrapper)
    rendered.push(tensModel)
  }

  const plus = document.createElement('span')
  plus.className = 'percentile-plus'
  plus.textContent = '+'
  plus.setAttribute('aria-hidden', 'true')
  const onesModel = await createDie(
    MODEL_CONFIGS['d10-ones'],
    String(ones.value),
    String(ones.value),
    '个位',
    ones.value,
  )
  onesModel.wrapper.classList.add('percentile-ones-die')
  onesModel.wrapper.dataset.percentileOutcome = 'selected'
  rendered.push(onesModel)
  roll.append(tensGroup, plus, onesModel.wrapper)
  element.append(roll)
  return { element, dice: rendered }
}

async function createModule(module: DiceRollModule): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
  if (module.diceSides === 100) return createPercentileModule(module)
  const element = document.createElement('section')
  element.className = 'dice-module'
  element.append(moduleHeading(module))
  const row = document.createElement('div')
  row.className = 'dice-row'
  const dice = await Promise.all(module.dice.map(normalDie))
  row.append(...dice.map((die) => die.wrapper))
  element.append(row)
  return { element, dice }
}

export class ThreeDiceBoard {
  private activeDice: RenderedDie[] = []

  constructor(private readonly diceTray: HTMLElement) {
    this.renderWaitingDice()
  }

  async playResult(result: DiceRollResult): Promise<void> {
    if (!result.modules.length) throw new Error('后端掷骰结果不包含骰子模块')
    this.activeDice.forEach((die) => die.dispose())
    const modules = await Promise.all(result.modules.map(createModule))
    this.activeDice = modules.flatMap((module) => module.dice)
    this.diceTray.replaceChildren(...modules.map((module) => module.element))
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
