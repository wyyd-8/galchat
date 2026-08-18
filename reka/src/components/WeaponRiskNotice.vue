<script setup lang="ts">
import { computed } from 'vue'
import { TriangleAlert } from '@lucide/vue'
import {
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { CocWeapon } from '@/api/types'
import { buildWeaponRiskGuidance } from '@/components/trpgToolsState'

const props = defineProps<{ weapon: Pick<CocWeapon, 'riskTags'> }>()
const guidance = computed(() => buildWeaponRiskGuidance(props.weapon.riskTags))
</script>

<template>
  <TooltipProvider :delay-duration="250">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <span class="weapon-risk-badge" tabindex="0"><TriangleAlert :size="9" />风险提示</span>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent class="tooltip weapon-risk-tooltip" :side-offset="8">
          <header>
            <TriangleAlert :size="15" />
            <span><strong>武器注意事项</strong><small>标签不代表固定惩罚，具体影响取决于场景与当前行动。</small></span>
          </header>
          <ul v-if="guidance.length">
            <li v-for="item in guidance" :key="item.tag">
              <strong>{{ item.tag }}</strong><span>{{ item.message }}</span>
            </li>
          </ul>
          <p v-else>此武器已标记为需要注意，请结合当前场景判断实际影响。</p>
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
