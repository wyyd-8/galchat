import './style.css'
import {
  ThreeDiceBoard,
  type DiceRollModule,
  type DiceRollResult,
  type DiceSkin,
} from './dice/ThreeDice'

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector)
  if (!element) throw new Error(`页面缺少元素 ${selector}`)
  return element
}

const diceTray = requiredElement<HTMLElement>('#dice-tray')
const rollButton = requiredElement<HTMLButtonElement>('#roll-button')
const skinButton = requiredElement<HTMLButtonElement>('#skin-button')
const skinLabel = requiredElement<HTMLElement>('#skin-label')
const demoSelect = requiredElement<HTMLSelectElement>('#demo-select')
const resultValues = requiredElement<HTMLElement>('#result-values')
const resultTotal = requiredElement<HTMLElement>('#result-total')

const diceBoard = new ThreeDiceBoard(diceTray)
let activeSkin: DiceSkin = 'classic'
const SKIN_ORDER: DiceSkin[] = ['classic', 'galaxy', 'moonwhite']
const SKIN_LABELS: Record<DiceSkin, string> = {
  classic: '经典皮肤',
  galaxy: '星穹皮肤',
  moonwhite: '月白冰晶',
}

function setSkin(skin: DiceSkin): void {
  activeSkin = skin
  diceBoard.setSkin(skin)
  document.body.dataset.diceSkin = skin
  skinButton.setAttribute('aria-pressed', String(skin !== 'classic'))
  skinButton.setAttribute('aria-label', `当前为${SKIN_LABELS[skin]}，点击切换皮肤`)
  skinLabel.textContent = SKIN_LABELS[skin]
}

function normalModule(sides: number, values: number[]): DiceRollModule {
  return {
    expression: `${values.length}D${sides}`,
    diceCount: values.length,
    diceSides: sides,
    modifier: 'NORMAL',
    dice: values.map((value) => ({ sides, value, role: 'NORMAL', selected: true })),
    result: values.reduce((sum, value) => sum + value, 0),
  }
}

const DEMOS: Record<string, DiceRollResult> = {
  standard: {
    formula: '1D4 + 1D6 + 1D8 + 1D10 + 1D12 + 1D20',
    modules: [
      normalModule(4, [3]),
      normalModule(6, [5]),
      normalModule(8, [7]),
      normalModule(10, [9]),
      normalModule(12, [11]),
      normalModule(20, [17]),
    ],
    result: 52,
  },
  group: {
    formula: '3D6 + 2D8',
    modules: [normalModule(6, [4, 5, 6]), normalModule(8, [3, 7])],
    result: 25,
  },
  percentile: {
    formula: '1D100##',
    modules: [{
      expression: '1D100##',
      diceCount: 1,
      diceSides: 100,
      modifier: 'DOUBLE_ADVANTAGE',
      dice: [
        { sides: 10, value: 7, role: 'PERCENTILE_ONES', selected: true },
        { sides: 10, value: 8, role: 'PERCENTILE_TENS', selected: false },
        { sides: 10, value: 2, role: 'PERCENTILE_TENS', selected: true },
        { sides: 10, value: 5, role: 'PERCENTILE_TENS', selected: false },
      ],
      result: 27,
    }],
    result: 27,
  },
}

async function play(result: DiceRollResult): Promise<void> {
  rollButton.disabled = true
  skinButton.disabled = true
  demoSelect.disabled = true
  resultValues.textContent = result.formula
  resultTotal.textContent = '正在播放后端结果…'

  try {
    await diceBoard.playResult(result)
    resultValues.textContent = String(result.result)
    resultTotal.textContent = `${result.formula} = ${result.result}`
  } catch (error) {
    resultValues.textContent = '无法播放'
    resultTotal.textContent = error instanceof Error ? error.message : '未知错误'
  } finally {
    rollButton.disabled = false
    skinButton.disabled = false
    demoSelect.disabled = false
  }
}

rollButton.addEventListener('click', () => play(DEMOS[demoSelect.value] ?? DEMOS.standard))
skinButton.addEventListener('click', async () => {
  const currentIndex = SKIN_ORDER.indexOf(activeSkin)
  setSkin(SKIN_ORDER[(currentIndex + 1) % SKIN_ORDER.length])
  await play(DEMOS[demoSelect.value] ?? DEMOS.standard)
})

// 正式接入时可直接调用 window.playDiceResult(await response.json())。
declare global {
  interface Window {
    playDiceResult: (result: DiceRollResult) => Promise<void>
  }
}
window.playDiceResult = play

const selectedDemo = new URLSearchParams(window.location.search).get('demo')
if (selectedDemo && DEMOS[selectedDemo]) {
  demoSelect.value = selectedDemo
}
const selectedSkin = new URLSearchParams(window.location.search).get('skin')
setSkin(SKIN_ORDER.includes(selectedSkin as DiceSkin) ? selectedSkin as DiceSkin : 'classic')
