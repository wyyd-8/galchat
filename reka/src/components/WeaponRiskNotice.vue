<script setup lang="ts">
import { computed, ref } from 'vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { TriangleAlert } from '@lucide/vue'
import {
  TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger,
} from 'reka-ui'
import type { CocWeapon } from '@/api/types'
import { buildWeaponRiskGuidance } from '@/components/trpgToolsState'

const props = defineProps<{ weapon: Pick<CocWeapon, 'riskTags'> }>()
const { isMobile } = useMobileViewport()
const expanded = ref(false)
const guidance = computed(() => buildWeaponRiskGuidance(props.weapon.riskTags))
</script>

<template>
  <template v-if="isMobile">
    <button class="mobile-weapon-risk" type="button" @click.stop="expanded = true"><TriangleAlert :size="13" />风险提示</button>
    <BaseDialog v-model="expanded" title="武器注意事项" mobile-presentation="sheet" layer="foreground" content-class="mobile-weapon-risk-dialog">
      <p>标签不代表固定惩罚，具体影响取决于场景与当前行动。</p>
      <article v-for="item in guidance" :key="item.tag"><h3>{{ item.tag }}</h3><p>{{ item.message }}</p></article>
      <p v-if="!guidance.length">此武器已标记为需要注意，请结合当前场景判断实际影响。</p>
      <template #footer><button class="button primary" @click="expanded = false">知道了</button></template>
    </BaseDialog>
  </template>
  <TooltipProvider v-else :delay-duration="250">
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

<style>
@media (max-width: 767px) {
.mobile-weapon-risk { display:inline-flex;align-items:center;gap:5px;min-height:32px;padding:4px 8px;border:0;border-radius:6px;color:#87602f;background:#f1e5d4;font-size:11px;font-weight:400 }
.mobile-weapon-risk-dialog p { font-size:13px;line-height:1.8;color:#777970;margin:8px 0 16px }.mobile-weapon-risk-dialog article { padding:14px 0;border-top:1px solid #dedbd2 }.mobile-weapon-risk-dialog h3 { margin:0;font-size:15px }
}
</style>
