<script setup lang="ts">
import { computed, ref } from 'vue'
import { Braces, Play, Plus, Trash2 } from '@lucide/vue'
import type { DiceModule, DiceResult, DiceRollAggregate } from '@/api/types'
import {
  createDiceAggregatePlaybackRequest,
  createDiceDebugAggregatePreset,
  createDiceDebugPreset,
  parseDiceResultJson,
  validatePlayableDiceResult,
  type DiceDebugAggregatePreset,
  type DiceDebugPreset,
  type DiceGroupRule,
  type DiceSkin,
} from './diceDebugState'

type PanelPreset = DiceDebugPreset | DiceDebugAggregatePreset

const emit = defineEmits<{
  play: [result: DiceResult, skin: DiceSkin]
  playAggregate: [aggregate: DiceRollAggregate, skin: DiceSkin, groupRule: DiceGroupRule]
}>()

const preset = ref<PanelPreset>('percentile')
const skin = ref<DiceSkin>('classic')
const result = ref<DiceResult>(createDiceDebugPreset('percentile'))
const aggregate = ref<DiceRollAggregate | null>(null)
const groupRule = ref<DiceGroupRule>('ANY_SUCCESS')
const jsonSource = ref('')
const jsonError = ref('')
const attempted = ref(false)
const errors = computed(() => aggregate.value
  ? aggregate.value.results.flatMap((detail) => detail.resultData
      ? validatePlayableDiceResult(detail.resultData)
      : [`${detail.reason || `骰位 #${detail.id}`} 缺少掷骰结果`])
  : validatePlayableDiceResult(result.value))
const outcomeLabels: Record<string, string> = {
  CRITICAL_SUCCESS: '大成功',
  SUCCESS: '成功',
  FAILURE: '失败',
  FUMBLE: '大失败',
}
const aggregateGroups = computed(() => aggregate.value?.results.map((detail) => ({
  id: detail.id,
  characterName: String(detail.resolution?.outcome?.characterName || detail.reason || `参与者 ${detail.displayOrder || detail.id}`),
  checkName: String(detail.resolution?.outcome?.checkName || detail.displayType || '检定'),
  category: outcomeLabels[String(detail.resolution?.outcome?.category || '')] || '待结算',
  result: detail.resultData?.result ?? '—',
})) || [])
const isMultiplayerCheck = computed(() => aggregate.value?.results.every(
  (detail) => detail.resolution?.type === 'CHECK',
) || false)
const aggregatePresentation = computed(() => aggregate.value
  ? createDiceAggregatePlaybackRequest(0, aggregate.value, skin.value, groupRule.value).presentation
  : null)

function applyPreset() {
  if (preset.value === 'multiplayer-check' || preset.value === 'opposed-check') {
    aggregate.value = createDiceDebugAggregatePreset(preset.value)
  } else {
    aggregate.value = null
    result.value = createDiceDebugPreset(preset.value)
  }
  attempted.value = false
  jsonError.value = ''
  jsonSource.value = ''
}

function addModule() {
  preset.value = 'custom'
  aggregate.value = null
  result.value.modules.push({
    expression: '1D6', diceCount: 1, diceSides: 6, modifier: 'NORMAL', result: 4,
    dice: [{ sides: 6, value: 4, role: 'NORMAL', selected: true }],
  })
}

function removeModule(index: number) {
  preset.value = 'custom'
  aggregate.value = null
  result.value.modules.splice(index, 1)
}

function addDie(module: DiceModule) {
  preset.value = 'custom'
  aggregate.value = null
  const percentile = module.diceSides === 100
  module.dice.push({
    sides: percentile ? 10 : module.diceSides,
    value: percentile ? 0 : 1,
    role: percentile ? 'PERCENTILE_TENS' : 'NORMAL',
    selected: !percentile,
  })
}

function removeDie(module: DiceModule, index: number) {
  preset.value = 'custom'
  aggregate.value = null
  module.dice.splice(index, 1)
}

function exportJson() {
  jsonSource.value = JSON.stringify(aggregate.value || result.value, null, 2)
  jsonError.value = ''
}

function importJson() {
  try {
    result.value = parseDiceResultJson(jsonSource.value)
    preset.value = 'custom'
    aggregate.value = null
    attempted.value = false
    jsonError.value = ''
  } catch (cause) {
    jsonError.value = cause instanceof Error ? cause.message : '无法解析 JSON'
  }
}

function play() {
  attempted.value = true
  if (errors.value.length) return
  if (aggregate.value) emit('playAggregate', aggregate.value, skin.value, groupRule.value)
  else emit('play', result.value, skin.value)
}
</script>

<template>
  <div class="dice-debug-grid">
    <div class="dice-debug-toolbar">
      <label class="field"><span>骰子皮肤</span><select v-model="skin"><option value="classic">经典</option><option value="galaxy">星穹</option><option value="moonwhite">月白冰晶</option></select></label>
      <label class="field"><span>掷骰种类</span><select v-model="preset" @change="applyPreset"><optgroup label="检定场景"><option value="multiplayer-check">多人检定</option><option value="opposed-check">对抗检定</option></optgroup><optgroup label="骰子与数值"><option value="standard">全部标准骰</option><option value="group">骰子组 / 数值计算</option><option value="normal-percentile">普通百分骰</option><option value="advantage">奖励骰</option><option value="double-advantage">双奖励骰</option><option value="disadvantage">惩罚骰</option><option value="double-disadvantage">双惩罚骰</option><option value="custom">自定义</option></optgroup></select></label>
      <label v-if="isMultiplayerCheck" class="field"><span>群体通过规则</span><select v-model="groupRule"><option value="ANY_SUCCESS">任一成功</option><option value="ALL_SUCCESS">全部成功</option></select></label>
      <label v-if="!aggregate" class="field"><span>完整公式</span><input v-model="result.formula" @input="preset = 'custom'" /></label>
      <label v-if="!aggregate" class="field"><span>总结果</span><input v-model.number="result.result" type="number" /></label>
    </div>

    <section v-if="aggregate" class="dice-debug-scenario">
      <header><span><strong>{{ aggregate.summary.reason }}</strong><small>模拟真实 DiceRollAggregate · {{ aggregateGroups.length }} 名参与者</small></span><b>{{ aggregatePresentation?.resultLabel }}：{{ aggregatePresentation?.resultValue }}</b></header>
      <div>
        <article v-for="group in aggregateGroups" :key="group.id">
          <span><strong>{{ group.characterName }}</strong><small>{{ group.checkName }}</small></span>
          <b>{{ group.result }}</b>
          <em>{{ group.category }}</em>
        </article>
      </div>
    </section>

    <article v-for="(module, moduleIndex) in aggregate ? [] : result.modules" :key="moduleIndex" class="dice-debug-module">
      <header class="dice-debug-heading"><strong>模块 {{ moduleIndex + 1 }}</strong><button class="icon-button subtle" title="删除模块" @click="removeModule(moduleIndex)"><Trash2 :size="14" /></button></header>
      <div class="dice-debug-module-fields">
        <label class="field"><span>表达式</span><input v-model="module.expression" /></label>
        <label class="field"><span>公式骰数</span><input v-model.number="module.diceCount" type="number" min="1" /></label>
        <label class="field"><span>公式面数</span><select v-model.number="module.diceSides"><option v-for="sides in [4, 6, 8, 10, 12, 20, 100]" :key="sides" :value="sides">D{{ sides }}</option></select></label>
        <label class="field"><span>修正类型</span><input v-model="module.modifier" list="dice-modifiers" /></label>
        <label class="field"><span>模块结果</span><input v-model.number="module.result" type="number" /></label>
      </div>
      <div class="dice-debug-dice">
        <div v-for="(die, dieIndex) in module.dice" :key="dieIndex" class="dice-debug-die">
          <b>#{{ dieIndex + 1 }}</b>
          <label class="field"><span>骰子种类</span><select v-model.number="die.sides"><option v-for="sides in [4, 6, 8, 10, 12, 20]" :key="sides" :value="sides">D{{ sides }}</option></select></label>
          <label class="field"><span>具体值</span><input v-model.number="die.value" type="number" /></label>
          <label class="field"><span>后端角色</span><input v-model="die.role" list="dice-roles" /></label>
          <label class="dice-debug-check"><input v-model="die.selected" type="checkbox" />参与结果</label>
          <button class="icon-button subtle" title="删除骰子" @click="removeDie(module, dieIndex)"><Trash2 :size="13" /></button>
        </div>
      </div>
      <button class="button ghost" @click="addDie(module)"><Plus :size="14" />添加骰子</button>
    </article>

    <datalist id="dice-modifiers"><option value="NORMAL" /><option value="ADVANTAGE" /><option value="DOUBLE_ADVANTAGE" /><option value="DISADVANTAGE" /><option value="DOUBLE_DISADVANTAGE" /></datalist>
    <datalist id="dice-roles"><option value="NORMAL" /><option value="PERCENTILE_ONES" /><option value="PERCENTILE_TENS" /></datalist>

    <p v-if="attempted && errors.length" class="dice-debug-errors">{{ errors.join('；') }}</p>
    <div class="dice-debug-actions">
      <button v-if="!aggregate" class="button secondary" @click="addModule"><Plus :size="15" />添加模块</button>
      <button class="button primary" @click="play"><Play :size="15" />在窗口中播放</button>
    </div>

    <details class="dice-json-editor" @toggle="!jsonSource && exportJson()">
      <summary><Braces :size="13" />{{ aggregate ? '完整聚合检定 JSON' : '完整后端 JSON（可粘贴真实响应，额外字段不会被丢弃）' }}</summary>
      <textarea v-model="jsonSource" spellcheck="false" :readonly="Boolean(aggregate)" />
      <p v-if="jsonError" class="dice-debug-errors">{{ jsonError }}</p>
      <div class="dice-debug-actions"><button class="button ghost" @click="exportJson">从当前配置刷新 JSON</button><button v-if="!aggregate" class="button secondary" @click="importJson">载入这份 JSON</button></div>
    </details>
  </div>
</template>
