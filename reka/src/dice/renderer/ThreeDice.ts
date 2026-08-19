import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'

import {
  createIdleSpinLoop,
  createRenderCoordinator,
  randomIdleQuaternion,
  type IdleSpinTarget,
} from './idleSpin'
import { resolveNormalDiePresentation } from './normalDiePresentation'
import {
  continuousRotationTarget,
  createDiceStartDelays,
  interpolateRotation,
  type DiceAnimationGroupTiming,
} from './rollRotation'
import { settleCameraDistance, settleScaleFactor } from './settleScale'
import { formatDiceGroupLabel } from '../domain/diceGroupLabel'
import {
  createDiceRenderViewport,
  intersectDiceViewportRects,
  type DiceViewportRect,
} from '../domain/dicePlayerLayout'

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
  placeholder?: boolean
}

export interface DiceRollResult {
  formula: string
  modules: DiceRollModule[]
  result: number
}

type ModelKey = 'd4' | 'd6' | 'd8' | 'd10-ones' | 'd10-tens' | 'd12' | 'd20'
export type DiceSkin = 'classic' | 'galaxy' | 'moonwhite' | 'cinnabar'

interface DiceModelConfig {
  key: ModelKey
  url: string
  facePattern: RegExp
  faceLabels: string[]
  displayScale?: number
}

interface RenderedDie {
  wrapper: HTMLElement
  viewport: HTMLElement
  valueLabel: HTMLElement
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  model: THREE.Group
  faceNormal: THREE.Vector3
  faceUp: THREE.Vector3
  frontValue: string
  turnSeed: number
  dimOverlay?: HTMLElement
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

interface SharedDiceRenderer {
  renderer: THREE.WebGLRenderer
  owner?: object
}

let sharedDiceRenderer: SharedDiceRenderer | undefined

function acquireSharedRenderer(host: HTMLElement, owner: object): THREE.WebGLRenderer {
  if (!sharedDiceRenderer) {
    const renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: true,
      powerPreference: 'high-performance',
    })
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    renderer.setClearColor(0x000000, 0)
    renderer.autoClear = false
    renderer.domElement.className = 'dice-shared-canvas'
    renderer.domElement.setAttribute('aria-hidden', 'true')
    sharedDiceRenderer = { renderer }
  }
  sharedDiceRenderer.owner = owner
  host.append(sharedDiceRenderer.renderer.domElement)
  sharedDiceRenderer.renderer.domElement.hidden = false
  return sharedDiceRenderer.renderer
}

function releaseSharedRenderer(owner: object): void {
  if (!sharedDiceRenderer || sharedDiceRenderer.owner !== owner) return
  const { renderer } = sharedDiceRenderer
  renderer.setScissorTest(false)
  renderer.clear()
  renderer.domElement.hidden = true
  renderer.domElement.remove()
  sharedDiceRenderer.owner = undefined
}

export function disposeSharedDiceRenderer(): void {
  if (!sharedDiceRenderer) return
  sharedDiceRenderer.renderer.dispose()
  sharedDiceRenderer.renderer.forceContextLoss()
  sharedDiceRenderer.renderer.domElement.remove()
  sharedDiceRenderer = undefined
}

const MODEL_CONFIGS: Record<ModelKey, DiceModelConfig> = {
  d4: {
    key: 'd4',
    url: new URL('../assets/models/classic/D4_四面骰_baked.glb', import.meta.url).href,
    facePattern: /^D4_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4'],
    displayScale: 0.65,
  },
  d6: {
    key: 'd6',
    url: new URL('../assets/models/classic/D6_六面骰_baked.glb', import.meta.url).href,
    facePattern: /^D6_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4', '5', '6'],
    displayScale: 0.65,
  },
  d8: {
    key: 'd8',
    url: new URL('../assets/models/classic/D8_八面骰_baked.glb', import.meta.url).href,
    facePattern: /^D8_Face_\d+_(\d+)$/,
    faceLabels: ['1', '2', '3', '4', '5', '6', '7', '8'],
  },
  'd10-ones': {
    key: 'd10-ones',
    url: new URL('../assets/models/classic/D10_个位骰_0-9_baked.glb', import.meta.url).href,
    facePattern: /^D10_0_9_Face_\d+_(\d)$/,
    faceLabels: ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'],
  },
  'd10-tens': {
    key: 'd10-tens',
    url: new URL('../assets/models/classic/D10_百分骰_00-90_baked.glb', import.meta.url).href,
    facePattern: /^D10_00_90_Face_\d+_(\d{2})$/,
    faceLabels: ['00', '10', '20', '30', '40', '50', '60', '70', '80', '90'],
  },
  d12: {
    key: 'd12',
    url: new URL('../assets/models/classic/D12_十二面骰_baked.glb', import.meta.url).href,
    facePattern: /^D12_Face_\d+_(\d+)$/,
    faceLabels: Array.from({ length: 12 }, (_, index) => String(index + 1)),
  },
  d20: {
    key: 'd20',
    url: new URL('../assets/models/classic/D20_二十面骰_baked.glb', import.meta.url).href,
    facePattern: /^Face_\d+_Number_(\d+)$/,
    faceLabels: Array.from({ length: 20 }, (_, index) => String(index + 1)),
  },
}

const GALAXY_MODEL_URLS: Record<ModelKey, string> = {
  d4: new URL('../assets/models/galaxy/D4_四面骰_星穹_baked.glb', import.meta.url).href,
  d6: new URL('../assets/models/galaxy/D6_六面骰_星穹_baked.glb', import.meta.url).href,
  d8: new URL('../assets/models/galaxy/D8_八面骰_星穹_baked.glb', import.meta.url).href,
  'd10-ones': new URL('../assets/models/galaxy/D10_个位骰_0-9_星穹_baked.glb', import.meta.url).href,
  'd10-tens': new URL('../assets/models/galaxy/D10_百分骰_00-90_星穹_baked.glb', import.meta.url).href,
  d12: new URL('../assets/models/galaxy/D12_十二面骰_星穹_baked.glb', import.meta.url).href,
  d20: new URL('../assets/models/galaxy/D20_二十面骰_星穹_baked.glb', import.meta.url).href,
}

const MOONWHITE_MODEL_URLS: Record<ModelKey, string> = {
  d4: new URL('../assets/models/moonwhite/D4_四面骰_月白冰晶_baked.glb', import.meta.url).href,
  d6: new URL('../assets/models/moonwhite/D6_六面骰_月白冰晶_baked.glb', import.meta.url).href,
  d8: new URL('../assets/models/moonwhite/D8_八面骰_月白冰晶_baked.glb', import.meta.url).href,
  'd10-ones': new URL('../assets/models/moonwhite/D10_个位骰_0-9_月白冰晶_baked.glb', import.meta.url).href,
  'd10-tens': new URL('../assets/models/moonwhite/D10_百分骰_00-90_月白冰晶_baked.glb', import.meta.url).href,
  d12: new URL('../assets/models/moonwhite/D12_十二面骰_月白冰晶_baked.glb', import.meta.url).href,
  d20: new URL('../assets/models/moonwhite/D20_二十面骰_月白冰晶_baked.glb', import.meta.url).href,
}

const CINNABAR_MODEL_URLS: Record<ModelKey, string> = {
  d4: new URL('../assets/models/cinnabar/D4_四面骰_朱砂鎏金_baked.glb', import.meta.url).href,
  d6: new URL('../assets/models/cinnabar/D6_六面骰_朱砂鎏金_baked.glb', import.meta.url).href,
  d8: new URL('../assets/models/cinnabar/D8_八面骰_朱砂鎏金_baked.glb', import.meta.url).href,
  'd10-ones': new URL('../assets/models/cinnabar/D10_个位骰_0-9_朱砂鎏金_baked.glb', import.meta.url).href,
  'd10-tens': new URL('../assets/models/cinnabar/D10_百分骰_00-90_朱砂鎏金_baked.glb', import.meta.url).href,
  d12: new URL('../assets/models/cinnabar/D12_十二面骰_朱砂鎏金_baked.glb', import.meta.url).href,
  d20: new URL('../assets/models/cinnabar/D20_二十面骰_朱砂鎏金_baked.glb', import.meta.url).href,
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
  if (skin === 'cinnabar') return CINNABAR_MODEL_URLS[config.key]
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
  const viewport = document.createElement('div')
  viewport.className = 'three-die-viewport'
  viewport.dataset.frontValue = ''
  viewport.dataset.modelSource = config.key
  viewport.dataset.diceSkin = skin
  viewport.setAttribute('aria-hidden', 'true')

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 20)
  camera.position.set(0, 0, settleCameraDistance(4))
  const groundColor = skin === 'moonwhite' ? 0x244d73 : skin === 'cinnabar' ? 0x5b261c : 0x392552
  scene.add(new THREE.HemisphereLight(0xffffff, groundColor, 2.25))
  const keyLight = new THREE.DirectionalLight(skin === 'moonwhite' ? 0xdaf5ff : skin === 'cinnabar' ? 0xffe1a6 : 0xffffff, 3.4)
  keyLight.position.set(-2.5, 4, 5)
  scene.add(keyLight)
  const rimLight = new THREE.DirectionalLight(skin === 'moonwhite' ? 0xa8d8ff : skin === 'cinnabar' ? 0xffc15c : 0x9d6ee7, 2.1)
  rimLight.position.set(4, -2, 2)
  scene.add(rimLight)

  const built = await buildModel(config, faceLabel, skin)
  scene.add(built.model)
  wrapper.append(typeLabel, valueLabel, shadow, viewport)
  return {
    wrapper,
    viewport,
    valueLabel,
    scene,
    camera,
    model: built.model,
    faceNormal: built.faceNormal,
    faceUp: built.faceUp,
    frontValue: displayValue,
    turnSeed,
    dispose: () => {
      viewport.remove()
      built.dispose()
    },
  }
}

function normalDie(value: DiceRollValue, skin: DiceSkin): Promise<RenderedDie> {
  if (!Number.isInteger(value.value) || value.value < 1 || value.value > value.sides) {
    throw new Error(`D${value.sides} 结果 ${value.value} 无效`)
  }
  const presentation = resolveNormalDiePresentation(value.sides, value.value)
  const config = MODEL_CONFIGS[presentation.modelKey as ModelKey]
  if (!config) throw new Error(`暂不支持 D${value.sides} 的动画模型`)
  return createDie(
    config,
    presentation.faceLabel,
    presentation.displayValue,
    presentation.typeLabelText,
    value.value,
    skin,
  )
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

interface DieAnimationState {
  die: RenderedDie
  delay: number
  complete: boolean
  finalTarget?: THREE.Quaternion
  landingTarget?: THREE.Quaternion
  startRotation?: THREE.Euler
  rotation?: ReturnType<typeof continuousRotationTarget>
  initialScale?: THREE.Vector3
  valueAnimation?: Animation
}

async function animateDice(
  dice: RenderedDie[],
  signal: AbortSignal,
  renderAll: () => void,
  startDelays?: number[],
): Promise<void> {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const duration = reduceMotion ? 180 : 3_600
  const rollEnd = 0.84
  const pauseEnd = 0.875
  const sequenceStartedAt = performance.now()
  const states: DieAnimationState[] = dice.map((die, index) => ({
    die,
    delay: startDelays?.[index] ?? index * 90,
    complete: false,
  }))

  const initialize = (state: DieAnimationState): void => {
    const { die } = state
    die.wrapper.classList.add('is-rolling')
    state.finalTarget = uprightTarget(die.faceNormal, die.faceUp)
    const randomRoll = (Math.random() * 2 - 1) * Math.PI
    const landingRoll = Math.abs(randomRoll) < Math.PI / 6
      ? Math.sign(randomRoll || 1) * Math.PI / 6
      : randomRoll
    state.landingTarget = new THREE.Quaternion()
      .setFromAxisAngle(Z_AXIS, landingRoll)
      .multiply(state.finalTarget)
      .normalize()
    state.startRotation = new THREE.Euler().setFromQuaternion(die.model.quaternion, 'XYZ')
    const targetAngles = new THREE.Euler().setFromQuaternion(
      reduceMotion ? state.finalTarget : state.landingTarget,
      'XYZ',
    )
    state.rotation = continuousRotationTarget(
      state.startRotation,
      targetAngles,
      { x: 3 + die.turnSeed % 2, y: 4 + die.turnSeed % 2, z: 2 },
    )
    state.initialScale = die.model.scale.clone()
  }

  let frameHandle: number | undefined
  await new Promise<void>((resolve) => {
    const finish = (): void => {
      if (frameHandle !== undefined) window.cancelAnimationFrame(frameHandle)
      frameHandle = undefined
      signal.removeEventListener('abort', abort)
      resolve()
    }
    const abort = (): void => {
      states.forEach((state) => state.die.wrapper.classList.remove('is-rolling'))
      finish()
    }
    const frame = (now: number): void => {
      if (signal.aborted) {
        finish()
        return
      }
      let changed = false
      let allComplete = true
      for (const state of states) {
        if (state.complete) continue
        const elapsed = now - sequenceStartedAt - state.delay
        if (elapsed < 0) {
          allComplete = false
          continue
        }
        if (!state.finalTarget) initialize(state)
        const { die, finalTarget, landingTarget, startRotation, rotation, initialScale } = state
        if (!finalTarget || !landingTarget || !startRotation || !rotation || !initialScale) continue
        const progress = Math.min(elapsed / duration, 1)
        if (reduceMotion || progress < rollEnd) {
          const rotationProgress = easeOutCubic(reduceMotion ? progress : progress / rollEnd)
          const currentRotation = interpolateRotation(startRotation, rotation, rotationProgress)
          die.model.rotation.set(currentRotation.x, currentRotation.y, currentRotation.z, 'XYZ')
        } else if (progress < pauseEnd) {
          die.model.quaternion.copy(landingTarget)
        } else {
          const uprightProgress = easeOutCubic((progress - pauseEnd) / (1 - pauseEnd))
          die.model.quaternion.slerpQuaternions(landingTarget, finalTarget, uprightProgress)
          die.model.scale.copy(initialScale).multiplyScalar(settleScaleFactor(uprightProgress))
        }
        changed = true
        if (progress < 1) {
          allComplete = false
          continue
        }

        die.model.quaternion.copy(finalTarget)
        if (!reduceMotion) die.model.scale.copy(initialScale).multiplyScalar(settleScaleFactor(1))
        const frontDot = die.faceNormal.clone().applyQuaternion(finalTarget).dot(FRONT)
        const upDot = die.faceUp.clone().applyQuaternion(finalTarget).dot(SCREEN_UP)
        die.viewport.dataset.frontValue = die.frontValue
        die.viewport.dataset.frontDot = frontDot.toFixed(6)
        die.viewport.dataset.upDot = upDot.toFixed(6)
        die.wrapper.classList.remove('is-rolling')
        die.wrapper.classList.add('is-settled')
        state.valueAnimation = die.valueLabel.animate([
          { opacity: 0, transform: 'translateX(-50%) translateY(12px) scale(.7)' },
          { opacity: 1, transform: 'translateX(-50%) translateY(-3px) scale(1.08)', offset: 0.72 },
          { opacity: 1, transform: 'translateX(-50%) translateY(0) scale(1)' },
        ], {
          duration: reduceMotion ? 100 : 320,
          easing: 'cubic-bezier(.2,.8,.25,1.25)',
          fill: 'forwards',
        })
        state.complete = true
      }
      if (changed) renderAll()
      if (allComplete) finish()
      else frameHandle = window.requestAnimationFrame(frame)
    }
    signal.addEventListener('abort', abort, { once: true })
    frameHandle = window.requestAnimationFrame(frame)
  })
  if (signal.aborted) return
  await Promise.all(states.map(async (state) => {
    if (!state.valueAnimation) return
    const cancel = (): void => state.valueAnimation?.cancel()
    signal.addEventListener('abort', cancel, { once: true })
    try {
      await state.valueAnimation.finished
    } catch {
      // Cancelling the board also rejects the Web Animation promise.
    } finally {
      signal.removeEventListener('abort', cancel)
    }
  }))
}

function moduleHeading(
  module: DiceRollModule,
  moduleIndex: number,
  showDetails = true,
): HTMLElement {
  const heading = document.createElement('header')
  heading.className = 'module-heading'
  const groupLabel = document.createElement('small')
  groupLabel.className = 'dice-module-group-label'
  groupLabel.textContent = formatDiceGroupLabel(moduleIndex, 1)
  heading.append(groupLabel)
  if (!showDetails) return heading
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
  moduleIndex: number,
): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
  const ones = module.dice.find((die) => die.role === 'PERCENTILE_ONES')
  const tens = module.dice.filter((die) => die.role === 'PERCENTILE_TENS')
  if (!ones || tens.length === 0) throw new Error(`${module.expression} 缺少百分骰的十位或个位数据`)
  if (tens.filter((die) => die.selected).length !== 1) throw new Error(`${module.expression} 必须选中一个十位骰`)

  const element = document.createElement('section')
  element.className = 'dice-module'
  element.append(moduleHeading(module, moduleIndex))
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
  moduleIndex: number,
): Promise<{ element: HTMLElement; dice: RenderedDie[] }> {
  if (module.placeholder) {
    const element = document.createElement('section')
    element.className = 'dice-module is-value-placeholder'
    const heading = moduleHeading(module, moduleIndex, false)
    const row = document.createElement('div')
    row.className = 'dice-row'
    const slot = document.createElement('div')
    slot.className = 'die-slot die-slot-placeholder is-settled'
    slot.setAttribute('aria-label', `数值结果 ${module.result}`)
    const valueLabel = document.createElement('output')
    valueLabel.className = 'die-value'
    valueLabel.textContent = String(module.result)
    slot.append(valueLabel)
    row.append(slot)
    element.append(heading, row)
    return { element, dice: [] }
  }
  if (module.diceSides === 100) return createPercentileModule(module, skin, moduleIndex)
  const element = document.createElement('section')
  element.className = 'dice-module'
  element.append(moduleHeading(module, moduleIndex))
  const row = document.createElement('div')
  row.className = 'dice-row'
  const dice = await Promise.all(module.dice.map((die) => normalDie(die, skin)))
  row.append(...dice.map((die) => die.wrapper))
  element.append(row)
  return { element, dice }
}

export class ThreeDiceBoard {
  private activeDice: RenderedDie[] = []
  private activeModules: RenderedDie[][] = []
  private skin: DiceSkin = 'classic'
  private preparationGeneration = 0
  private preparedResult: DiceRollResult | undefined
  private readonly rendererOwner = {}
  private readonly surface: HTMLElement
  private readonly renderLayer: HTMLElement
  private readonly scrollHost?: HTMLElement
  private readonly renderer: THREE.WebGLRenderer
  private readonly renderCoordinator: ReturnType<typeof createRenderCoordinator>
  private readonly idleSpin: ReturnType<typeof createIdleSpinLoop>
  private resizeObserver?: ResizeObserver
  private rollController?: AbortController
  private layoutFrameHandle?: number
  private layoutDeadline = 0
  private canvasWidth = 0
  private canvasHeight = 0
  private canvasPixelRatio = 0
  private disposed = false

  constructor(private readonly diceTray: HTMLElement, renderLayer?: HTMLElement) {
    const surface = diceTray.closest<HTMLElement>('.dice-player-surface') || diceTray.parentElement
    if (!surface) throw new Error('骰子托盘缺少播放器表面')
    this.surface = surface
    this.renderLayer = renderLayer || surface
    this.scrollHost = diceTray.closest<HTMLElement>('.dialog-body') || undefined
    this.renderer = acquireSharedRenderer(this.renderLayer, this.rendererOwner)
    const scheduler = {
      request: (callback: (now: number) => void) => window.requestAnimationFrame(callback),
      cancel: (handle: number) => window.cancelAnimationFrame(handle),
    }
    this.renderCoordinator = createRenderCoordinator(scheduler, () => this.renderAll())
    this.idleSpin = createIdleSpinLoop(scheduler, () => this.renderAll())
    this.scrollHost?.addEventListener('scroll', this.handleLayoutChange, { passive: true })
    this.diceTray.addEventListener('scroll', this.handleLayoutChange, { passive: true })
    window.addEventListener('resize', this.handleLayoutChange, { passive: true })
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(this.handleLayoutChange)
      this.resizeObserver.observe(this.surface)
      this.resizeObserver.observe(this.diceTray)
    }
    this.renderWaitingDice()
  }

  setSkin(skin: DiceSkin): void {
    this.skin = skin
  }

  async prepareResult(result: DiceRollResult): Promise<void> {
    if (!result.modules.length) throw new Error('后端掷骰结果不包含骰子模块')
    const generation = ++this.preparationGeneration
    this.cancelRoll()
    this.idleSpin.stop()
    this.preparedResult = undefined
    const modules = await Promise.all(result.modules.map((module, moduleIndex) => (
      createModule(module, this.skin, moduleIndex)
    )))
    const nextDice = modules.flatMap((module) => module.dice)
    if (generation !== this.preparationGeneration) {
      nextDice.forEach((die) => die.dispose())
      return
    }

    this.activeDice.forEach((die) => this.disposeDie(die))
    this.activeModules = modules.map((module) => module.dice)
    this.activeDice = nextDice
    this.diceTray.replaceChildren(...modules.map((module) => module.element))
    for (const die of this.activeDice) {
      const orientation = randomIdleQuaternion()
      die.model.quaternion.set(orientation.x, orientation.y, orientation.z, orientation.w)
    }
    this.renderAll()
    this.preparedResult = result

    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      const targets: IdleSpinTarget[] = this.activeDice.map((die) => {
        const spinStep = new THREE.Quaternion()
        return {
          rotateBy(angleRadians) {
            spinStep.setFromAxisAngle(Y_AXIS, angleRadians)
            die.model.quaternion.premultiply(spinStep)
          },
        }
      })
      this.idleSpin.start(targets)
    }
  }

  async playResult(
    result: DiceRollResult,
    animationGroups?: DiceAnimationGroupTiming[],
  ): Promise<void> {
    if (this.preparedResult !== result || this.activeDice.length === 0) {
      await this.prepareResult(result)
    }
    if (this.preparedResult !== result) return
    this.idleSpin.stop()
    this.preparedResult = undefined
    this.cancelRoll()
    const controller = new AbortController()
    this.rollController = controller
    const startDelays = createDiceStartDelays(
      this.activeModules.map((module) => module.length),
      animationGroups,
    )
    await animateDice(this.activeDice, controller.signal, () => this.renderAll(), startDelays)
    if (controller.signal.aborted || this.disposed) return
    this.rollController = undefined
    for (const die of this.activeDice) {
      const outcome = die.wrapper.dataset.percentileOutcome
      if (!outcome) continue
      die.wrapper.classList.add(outcome === 'selected' ? 'is-selected' : 'is-dimmed')
      if (outcome === 'selected') die.wrapper.setAttribute('aria-current', 'true')
      if (outcome === 'dimmed') this.addDimOverlay(die)
    }
    this.trackLayoutFor(420)
  }

  async showResult(result: DiceRollResult): Promise<void> {
    if (this.preparedResult !== result || this.activeDice.length === 0) {
      await this.prepareResult(result)
    }
    if (this.preparedResult !== result) return
    this.cancelRoll()
    this.idleSpin.stop()
    for (const die of this.activeDice) {
      const finalTarget = uprightTarget(die.faceNormal, die.faceUp)
      die.model.quaternion.copy(finalTarget)
      die.model.scale.multiplyScalar(settleScaleFactor(1))
      die.wrapper.classList.add('is-settled')
      die.valueLabel.style.opacity = '1'
      die.valueLabel.style.transform = 'translateX(-50%) translateY(0) scale(1)'
      const outcome = die.wrapper.dataset.percentileOutcome
      if (outcome) {
        die.wrapper.classList.add(outcome === 'selected' ? 'is-selected' : 'is-dimmed')
        if (outcome === 'selected') die.wrapper.setAttribute('aria-current', 'true')
        if (outcome === 'dimmed') this.addDimOverlay(die)
      }
    }
    this.renderAll()
    this.trackLayoutFor(420)
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.preparationGeneration += 1
    this.cancelRoll()
    this.idleSpin.stop()
    this.renderCoordinator.stop()
    if (this.layoutFrameHandle !== undefined) window.cancelAnimationFrame(this.layoutFrameHandle)
    this.layoutFrameHandle = undefined
    this.resizeObserver?.disconnect()
    this.scrollHost?.removeEventListener('scroll', this.handleLayoutChange)
    this.diceTray.removeEventListener('scroll', this.handleLayoutChange)
    window.removeEventListener('resize', this.handleLayoutChange)
    this.activeDice.forEach((die) => this.disposeDie(die))
    this.activeDice = []
    this.activeModules = []
    this.preparedResult = undefined
    this.diceTray.replaceChildren()
    releaseSharedRenderer(this.rendererOwner)
  }

  refreshLayout(): void {
    this.renderCoordinator.request()
    this.trackLayoutFor(300)
  }

  private renderWaitingDice(): void {
    const placeholder = document.createElement('p')
    placeholder.className = 'empty-tray'
    placeholder.textContent = '选择示例并点击“播放掷骰”'
    this.diceTray.replaceChildren(placeholder)
  }

  private readonly handleLayoutChange = (): void => {
    this.renderCoordinator.request()
  }

  private cancelRoll(): void {
    this.rollController?.abort()
    this.rollController = undefined
  }

  private disposeDie(die: RenderedDie): void {
    die.dimOverlay?.remove()
    die.dispose()
  }

  private addDimOverlay(die: RenderedDie): void {
    if (die.dimOverlay) return
    const overlay = document.createElement('div')
    overlay.className = 'dice-shared-dim-overlay'
    overlay.setAttribute('aria-hidden', 'true')
    die.dimOverlay = overlay
    this.renderLayer.append(overlay)
  }

  private trackLayoutFor(milliseconds: number): void {
    this.layoutDeadline = Math.max(this.layoutDeadline, performance.now() + milliseconds)
    if (this.layoutFrameHandle !== undefined) return
    const frame = (now: number): void => {
      this.layoutFrameHandle = undefined
      if (this.disposed) return
      this.renderAll()
      if (now < this.layoutDeadline) this.layoutFrameHandle = window.requestAnimationFrame(frame)
    }
    this.layoutFrameHandle = window.requestAnimationFrame(frame)
  }

  private renderAll(): void {
    if (this.disposed || sharedDiceRenderer?.owner !== this.rendererOwner) return
    const surfaceRect = this.surface.getBoundingClientRect()
    const clippingRects: DiceViewportRect[] = [surfaceRect, {
      left: 0,
      top: 0,
      width: window.innerWidth,
      height: window.innerHeight,
    }]
    if (this.scrollHost) clippingRects.push(this.scrollHost.getBoundingClientRect())
    const canvasRect = intersectDiceViewportRects(clippingRects)
    const canvas = this.renderer.domElement
    if (!canvasRect || canvasRect.width < 1 || canvasRect.height < 1) {
      canvas.hidden = true
      return
    }

    canvas.hidden = false
    canvas.style.left = `${canvasRect.left - surfaceRect.left}px`
    canvas.style.top = `${canvasRect.top - surfaceRect.top}px`
    canvas.style.width = `${canvasRect.width}px`
    canvas.style.height = `${canvasRect.height}px`
    const width = Math.max(1, Math.round(canvasRect.width))
    const height = Math.max(1, Math.round(canvasRect.height))
    const pixelRatio = Math.min(window.devicePixelRatio, 2)
    if (
      width !== this.canvasWidth
      || height !== this.canvasHeight
      || pixelRatio !== this.canvasPixelRatio
    ) {
      this.canvasWidth = width
      this.canvasHeight = height
      this.canvasPixelRatio = pixelRatio
      this.renderer.setPixelRatio(pixelRatio)
      this.renderer.setSize(width, height, false)
    }
    this.renderer.toneMappingExposure = this.skin === 'galaxy'
      ? 1.26
      : this.skin === 'moonwhite'
        ? 1.18
        : this.skin === 'cinnabar'
          ? 1.16
          : 1.12
    this.renderer.setScissorTest(false)
    this.renderer.setViewport(0, 0, width, height)
    this.renderer.clear(true, true, true)
    this.renderer.setScissorTest(true)

    for (const die of this.activeDice) {
      const slotRect = die.viewport.getBoundingClientRect()
      const placement = createDiceRenderViewport(slotRect, canvasRect)
      if (!placement || slotRect.width < 1 || slotRect.height < 1) {
        if (die.dimOverlay) die.dimOverlay.hidden = true
        continue
      }
      const { viewport, scissor } = placement
      die.camera.aspect = slotRect.width / slotRect.height
      die.camera.updateProjectionMatrix()
      this.renderer.setViewport(viewport.x, viewport.y, viewport.width, viewport.height)
      this.renderer.setScissor(scissor.x, scissor.y, scissor.width, scissor.height)
      this.renderer.render(die.scene, die.camera)
      if (die.dimOverlay) {
        die.dimOverlay.hidden = false
        die.dimOverlay.style.left = `${slotRect.left - surfaceRect.left}px`
        die.dimOverlay.style.top = `${slotRect.top - surfaceRect.top}px`
        die.dimOverlay.style.width = `${slotRect.width}px`
        die.dimOverlay.style.height = `${slotRect.height}px`
      }
    }
    this.renderer.setScissorTest(false)
  }
}
