<script setup lang="ts">
import { Braces, Play } from '@lucide/vue'
import type { DiceRollAggregate } from '@/api/types'
import {
  DICE_DEBUG_CONSTANT_SCENARIOS,
  DICE_DEBUG_TOOL_GROUPS,
  createDiceDebugAggregate,
} from '@/dice/debug/diceDebugScenarios'

const emit = defineEmits<{ play: [aggregate: DiceRollAggregate] }>()

function play(id: string) {
  emit('play', createDiceDebugAggregate(id))
}
</script>

<template>
  <div class="dice-debug-panel">
    <header class="dice-debug-intro">
      <Braces :size="20" />
      <span>
        <strong>后端响应场景</strong>
        <small>使用固定的后端数据结构播放窗口，不会写入聊天记录或修改人物状态。</small>
      </span>
    </header>

    <div class="dice-debug-tool-grid">
      <article v-for="group in DICE_DEBUG_TOOL_GROUPS" :key="group.toolName" class="dice-debug-tool-card">
        <span>
          <strong>{{ group.label }}</strong>
          <code>{{ group.toolName }}</code>
          <small>{{ group.description }}</small>
        </span>
        <div>
          <button
            v-for="scenario in group.scenarios"
            :key="scenario.id"
            type="button"
            class="button ghost"
            @click="play(scenario.id)"
          >
            <Play :size="13" />{{ scenario.label }}
          </button>
        </div>
      </article>
    </div>

    <section class="dice-debug-constant-card">
      <span>
        <strong>含常量的数值掷骰</strong>
        <small>验证纯常量占位，以及多人结果中常量与实体骰并存的布局。</small>
      </span>
      <div>
        <button
          v-for="scenario in DICE_DEBUG_CONSTANT_SCENARIOS"
          :key="scenario.id"
          type="button"
          class="button secondary"
          @click="play(scenario.id)"
        >
          <Play :size="13" />{{ scenario.label }}
        </button>
      </div>
    </section>
  </div>
</template>
