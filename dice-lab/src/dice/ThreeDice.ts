import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'

import {
  createIdleSpinLoop,
  randomIdleQuaternion,
  type IdleSpinTarget,
} from './idleSpin'
import { continuousRotationTarget, interpolateRotation } from './rollRotation'
import { settleCameraDistance, settleScaleFactor } from './settleScale'

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
export type DiceSkin = 'classic' | 'galaxy' | 'moonwhite'

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
const Y_AXIS = new THREE.Vector3(0, 1, 0)
const Z_AXIS = new THREE.Vector3(0, 0, 1)
const loader = new GLTFLoader()
const templatePromises = new Map<string, Promise<THREE.Group>>()
let fogTexture: THREE.CanvasTexture | undefined

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

const GALAXY_MODEL_URLS: Record<ModelKey, string> = {
  d4: new URL('../../model/galaxy/D4_四面骰_星穹_baked.glb', import.meta.url).href,
  d6: new URL('../../model/galaxy/D6_六面骰_星穹_baked.glb', import.meta.url).href,
  d8: new URL('../../model/galaxy/D8_八面骰_星穹_baked.glb', import.meta.url).href,
  'd10-ones': new URL('../../model/galaxy/D10_个位骰_0-9_星穹_baked.glb', import.meta.url).href,
  'd10-tens': new URL('../../model/galaxy/D10_百分骰_00-90_星穹_baked.glb', import.meta.url).href,
  d12: new URL('../../model/galaxy/D12_十二面骰_星穹_baked.glb', import.meta.url).href,
  d20: new URL('../../model/galaxy/D20_二十面骰_星穹_baked.glb', import.meta.url).href,
}

const MOONWHITE_MODEL_URLS: Record<ModelKey, string> = {
  d4: new URL('../../model/moonwhite/D4_四面骰_月白冰晶_baked.glb', import.meta.url).href,
  d6: new URL('../../model/moonwhite/D6_六面骰_月白冰晶_baked.glb', import.meta.url).href,
  d8: new URL('../../model/moonwhite/D8_八面骰_月白冰晶_baked.glb', import.meta.url).href,
  'd10-ones': new URL('../../model/moonwhite/D10_个位骰_0-9_月白冰晶_baked.glb', import.meta.url).href,
  'd10-tens': new URL('../../model/moonwhite/D10_百分骰_00-90_月白冰晶_baked.glb', import.meta.url).href,
  d12: new URL('../../model/moonwhite/D12_十二面骰_月白冰晶_baked.glb', import.meta.url).href,
  d20: new URL('../../model/moonwhite/D20_二十面骰_月白冰晶_baked.glb', import.meta.url).href,
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
}

function galaxyFogTexture(): THREE.CanvasTexture {
  if (fogTexture) return fogTexture
  const canvas = document.createElement('canvas')
  canvas.width = 128
  canvas.height = 128
  const context = canvas.getContext('2d')
  if (!context) throw new Error('浏览器无法创建星云纹理')
  const gradient = context.createRadialGradient(64, 64, 2, 64, 64, 62)
  gradient.addColorStop(0, 'rgba(255,255,255,.72)')
  gradient.addColorStop(.24, 'rgba(255,255,255,.42)')
  gradient.addColorStop(.56, 'rgba(255,255,255,.16)')
  gradient.addColorStop(.78, 'rgba(255,255,255,.05)')
  gradient.addColorStop(1, 'rgba(255,255,255,0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, 128, 128)
  fogTexture = new THREE.CanvasTexture(canvas)
  fogTexture.colorSpace = THREE.SRGBColorSpace
  return fogTexture
}

function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0
    return state / 0x100000000
  }
}

function createGalaxyFog(
  center: THREE.Vector3,
  size: THREE.Vector3,
  seed: number,
  materials: THREE.Material[],
): THREE.Group {
  const fog = new THREE.Group()
  fog.name = 'Galaxy_Runtime_Diffuse_Fog'
  const random = seededRandom(seed)
  const radius = Math.min(size.x, size.y, size.z)
  const layerColors = [0x176dff, 0x4b2cff, 0x9c36ee, 0xf13f9d]
  const layerSpread = [0.28, 0.20, 0.12]
  const layerOpacity = [0.10, 0.14, 0.21]
  const layerScale = [0.54, 0.42, 0.30]

  for (let index = 0; index < 18; index += 1) {
    const layer = index % 3
    const colorIndex = layer === 0 ? index % 2 : Math.min(layer + 1, layerColors.length - 1)
    const material = new THREE.SpriteMaterial({
      map: galaxyFogTexture(),
      color: layerColors[colorIndex],
      transparent: true,
      opacity: layerOpacity[layer] * (0.72 + random() * 0.48),
      depthWrite: false,
      depthTest: true,
      blending: THREE.AdditiveBlending,
      rotation: random() * Math.PI,
    })
    materials.push(material)
    const sprite = new THREE.Sprite(material)
    const spread = radius * layerSpread[layer]
    sprite.position.set(
      center.x + (random() * 2 - 1) * spread,
      center.y + (random() * 2 - 1) * spread,
      center.z + (random() * 2 - 1) * spread,
    )
    const scale = radius * layerScale[layer] * (0.72 + random() * 0.56)
    sprite.scale.set(scale * (0.72 + random() * 0.48), scale, 1)
    fog.add(sprite)
  }
  return fog
}

function createMoonwhiteMist(
  center: THREE.Vector3,
  size: THREE.Vector3,
  seed: number,
  materials: THREE.Material[],
): THREE.Group {
  const mist = new THREE.Group()
  mist.name = 'Moonwhite_Runtime_Ice_Mist'
  const random = seededRandom(seed)
  const radius = Math.min(size.x, size.y, size.z)
  const colors = [0x8fd8ff, 0xc7ecff, 0xc8baff, 0xffffff]

  for (let index = 0; index < 12; index += 1) {
    const layer = index % 3
    const material = new THREE.SpriteMaterial({
      map: galaxyFogTexture(),
      color: colors[index % colors.length],
      transparent: true,
      opacity: [0.045, 0.07, 0.105][layer] * (0.75 + random() * 0.45),
      depthWrite: false,
      depthTest: true,
      blending: THREE.AdditiveBlending,
      rotation: random() * Math.PI,
    })
    materials.push(material)
    const sprite = new THREE.Sprite(material)
    const spread = radius * [0.18, 0.12, 0.065][layer]
    sprite.position.set(
      center.x + (random() * 2 - 1) * spread,
      center.y + (random() * 2 - 1) * spread,
      center.z + (random() * 2 - 1) * spread,
    )
    const scale = radius * [0.42, 0.31, 0.22][layer] * (0.78 + random() * 0.4)
    sprite.scale.set(scale * (0.8 + random() * 0.35), scale, 1)
    mist.add(sprite)
  }
  return mist
}

function modelUrl(config: DiceModelConfig, skin: DiceSkin): string {
  if (skin === 'galaxy') return GALAXY_MODEL_URLS[config.key]
  if (skin === 'moonwhite') return MOONWHITE_MODEL_URLS[config.key]
  return config.url
}

function loadTemplate(config: DiceModelConfig, skin: DiceSkin): Promise<THREE.Group> {
  const cacheKey = `${skin}:${config.key}`
  const cached = templatePromises.get(cacheKey)
  if (cached) return cached
  const promise = loader.loadAsync(modelUrl(config, skin)).then((gltf) => gltf.scene)
  templatePromises.set(cacheKey, promise)
  return promise
}

async function buildModel(
  config: DiceModelConfig,
  faceLabel: string,
  skin: DiceSkin,
): Promise<{ model: THREE.Group; faceNormal: THREE.Vector3; faceUp: THREE.Vector3; dispose: () => void }> {
  const source = await loadTemplate(config, skin)
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
      if (skin === 'galaxy' && object.name.includes('_Body_') && clone instanceof THREE.MeshStandardMaterial) {
        clone.roughness = 0.16
        clone.metalness = 0.06
        clone.emissive.set(0x071748)
        clone.emissiveIntensity = 0.32
        clone.emissiveMap = clone.map
      }
      if (skin === 'moonwhite' && object.name.includes('_Body_') && clone instanceof THREE.MeshStandardMaterial) {
        clone.roughness = 0.15
        clone.metalness = 0.025
        clone.emissive.set(0x102f58)
        clone.emissiveIntensity = 0.18
        clone.emissiveMap = clone.map
        if (clone instanceof THREE.MeshPhysicalMaterial) {
          clone.transmission = 0.34
          clone.thickness = 0.22
          clone.ior = 1.455
        }
      }
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
  if (skin === 'galaxy') {
    const faceSeed = Number.parseInt(faceLabel, 10) || faceLabel.length
    content.add(createGalaxyFog(center, size, config.faceLabels.length * 101 + faceSeed, materials))
  }
  if (skin === 'moonwhite') {
    const faceSeed = Number.parseInt(faceLabel, 10) || faceLabel.length
    content.add(createMoonwhiteMist(center, size, config.faceLabels.length * 149 + faceSeed, materials))
  }
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
  skin: DiceSkin,
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
  renderer.setSize(168, 168, false)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = skin === 'galaxy' ? 1.26 : skin === 'moonwhite' ? 1.18 : 1.12
  renderer.domElement.className = 'three-die-canvas'
  renderer.domElement.dataset.frontValue = ''
  renderer.domElement.dataset.modelSource = config.key
  renderer.domElement.dataset.diceSkin = skin

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 20)
  camera.position.set(0, 0, settleCameraDistance(4))
  const groundColor = skin === 'moonwhite' ? 0x244d73 : 0x392552
  scene.add(new THREE.HemisphereLight(0xffffff, groundColor, 2.25))
  const keyLight = new THREE.DirectionalLight(skin === 'moonwhite' ? 0xdaf5ff : 0xffffff, 3.4)
  keyLight.position.set(-2.5, 4, 5)
  scene.add(keyLight)
  const rimLight = new THREE.DirectionalLight(skin === 'moonwhite' ? 0xa8d8ff : 0x9d6ee7, 2.1)
  rimLight.position.set(4, -2, 2)
  scene.add(rimLight)

  const built = await buildModel(config, faceLabel, skin)
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

function normalDie(value: DiceRollValue, skin: DiceSkin): Promise<RenderedDie> {
  if (!Number.isInteger(value.value) || value.value < 1 || value.value > value.sides) {
    throw new Error(`D${value.sides} 结果 ${value.value} 无效`)
  }
  if (value.sides === 10) {
    const faceLabel = value.value === 10 ? '0' : String(value.value)
    return createDie(MODEL_CONFIGS['d10-ones'], faceLabel, String(value.value), 'D10', value.value, skin)
  }
  const key = `d${value.sides}` as ModelKey
  const config = MODEL_CONFIGS[key]
  if (!config) throw new Error(`暂不支持 D${value.sides} 的动画模型`)
  return createDie(config, String(value.value), String(value.value), `D${value.sides}`, value.value, skin)
}

function uprightTarget(normal: THREE.Vector3, up: THREE.Vector3): THREE.Quaternion {
  const faceToFront = new THREE.Quaternion().setFromUnitVectors(normal, FRONT)
  const rotatedUp = up.clone().applyQuaternion(faceToFront)
  const roll = Math.atan2(rotatedUp.x, rotatedUp.y)
  return new THREE.Quaternion().setFromAxisAngle(Z_AXIS, roll).multiply(faceToFront).normalize()
}

function easeOutCubic(value: number): number {
  return 1 - (1 - value) ** 3
}

async function animateDie(die: RenderedDie, delay: number): Promise<void> {
  await wait(delay)
  die.wrapper.classList.add('is-rolling')
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const duration = reduceMotion ? 180 : 3_600
  const finalTarget = uprightTarget(die.faceNormal, die.faceUp)
  const randomRoll = (Math.random() * 2 - 1) * Math.PI
  const landingRoll = Math.abs(randomRoll) < Math.PI / 6
    ? Math.sign(randomRoll || 1) * Math.PI / 6
    : randomRoll
  const landingTarget = new THREE.Quaternion()
    .setFromAxisAngle(Z_AXIS, landingRoll)
    .multiply(finalTarget)
    .normalize()
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
  const initialScale = die.model.scale.clone()
  const rollEnd = 0.84
  const pauseEnd = 0.875
  const startedAt = performance.now()

  await new Promise<void>((resolve) => {
    const frame = (now: number): void => {
      const progress = Math.min((now - startedAt) / duration, 1)
      if (reduceMotion || progress < rollEnd) {
        const rotationProgress = easeOutCubic(reduceMotion ? progress : progress / rollEnd)
        const currentRotation = interpolateRotation(startRotation, rotation, rotationProgress)
        die.model.rotation.set(
          currentRotation.x,
          currentRotation.y,
          currentRotation.z,
          'XYZ',
        )
      } else if (progress < pauseEnd) {
        die.model.quaternion.copy(landingTarget)
      } else {
        const uprightProgress = easeOutCubic((progress - pauseEnd) / (1 - pauseEnd))
        die.model.quaternion.slerpQuaternions(landingTarget, finalTarget, uprightProgress)
        die.model.scale.copy(initialScale).multiplyScalar(settleScaleFactor(uprightProgress))
      }
      die.renderer.domElement.dataset.rotationX = die.model.rotation.x.toFixed(6)
      die.renderer.domElement.dataset.rotationY = die.model.rotation.y.toFixed(6)
      die.renderer.domElement.dataset.rotationZ = die.model.rotation.z.toFixed(6)
      die.renderer.render(die.scene, die.camera)
      if (progress < 1) requestAnimationFrame(frame)
      else resolve()
    }
    requestAnimationFrame(frame)
  })

  die.model.quaternion.copy(finalTarget)
  if (!reduceMotion) die.model.scale.copy(initialScale).multiplyScalar(settleScaleFactor(1))
  die.renderer.render(die.scene, die.camera)
  const frontDot = die.faceNormal.clone().applyQuaternion(finalTarget).dot(FRONT)
  const upDot = die.faceUp.clone().applyQuaternion(finalTarget).dot(SCREEN_UP)
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

async function createPercentileModule(
  module: DiceRollModule,
  skin: DiceSkin,
): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
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
    const tensModel = await createDie(
      MODEL_CONFIGS['d10-tens'],
      tensLabel,
      tensLabel,
      '十位',
      tensDie.value,
      skin,
    )
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
    skin,
  )
  onesModel.wrapper.classList.add('percentile-ones-die')
  onesModel.wrapper.dataset.percentileOutcome = 'selected'
  rendered.push(onesModel)
  roll.append(tensGroup, plus, onesModel.wrapper)
  element.append(roll)
  return { element, dice: rendered }
}

async function createModule(
  module: DiceRollModule,
  skin: DiceSkin,
): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
  if (module.diceSides === 100) return createPercentileModule(module, skin)
  const element = document.createElement('section')
  element.className = 'dice-module'
  element.append(moduleHeading(module))
  const row = document.createElement('div')
  row.className = 'dice-row'
  const dice = await Promise.all(module.dice.map((die) => normalDie(die, skin)))
  row.append(...dice.map((die) => die.wrapper))
  element.append(row)
  return { element, dice }
}

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

  dispose(): void {
    this.preparationGeneration += 1
    this.idleSpin.stop()
    this.activeDice.forEach((die) => die.dispose())
    this.activeDice = []
    this.preparedResult = undefined
    this.diceTray.replaceChildren()
  }

  private renderWaitingDice(): void {
    const placeholder = document.createElement('p')
    placeholder.className = 'empty-tray'
    placeholder.textContent = '选择示例并点击“播放掷骰”'
    this.diceTray.replaceChildren(placeholder)
  }
}
