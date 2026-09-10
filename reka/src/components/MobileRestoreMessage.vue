<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount, useId } from 'vue'
import type { GroupMessage } from '@/api/types'
import { formatRollbackPreviewMessage } from '@/components/trpgToolsState'
const props = defineProps<{ message: GroupMessage; boundary?: boolean }>()
const boundaryText = computed(() => formatRollbackPreviewMessage(props.message))
const expanded = ref(false)
const boundaryParagraph = ref<HTMLElement | null>(null)
const boundaryId = useId()
const measuredOverflow = ref<boolean | null>(null)
const canExpand = computed(() => measuredOverflow.value ?? (boundaryText.value.length > 30 || boundaryText.value.split('\n').length > 3))
function measureBoundary() {
  const element = boundaryParagraph.value
  if (!element) return
  const lineHeight = Number.parseFloat(getComputedStyle(element).lineHeight)
  measuredOverflow.value = element.scrollHeight > lineHeight * 3 + 1
}
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(measureBoundary)
  if (boundaryParagraph.value) observer.observe(boundaryParagraph.value)
  measureBoundary()
})
watch([boundaryText, boundaryParagraph], async () => {
  expanded.value = false
  measuredOverflow.value = null
  await nextTick()
  observer?.disconnect()
  if (boundaryParagraph.value) observer?.observe(boundaryParagraph.value)
  measureBoundary()
})
onBeforeUnmount(() => observer?.disconnect())
const speaker = (message: GroupMessage) => message.speakerType === 'user' ? '你' : message.speakerType === 'narrator' ? '叙事' : message.speakerName || (message.speakerType === 'kp' ? 'KP' : '角色')
</script>
<template>
<article class="restore-message"><span class="restore-avatar small">{{ speaker(message).slice(0, 1) }}</span><div><header>{{ speaker(message) }}<small v-if="boundary">恢复后保留的最后一条消息</small></header><p :id="boundaryId" ref="boundaryParagraph" :class="{ 'restore-boundary-collapsed': !expanded }">{{ boundaryText }}</p><button v-if="canExpand" class="restore-expand" type="button" :aria-expanded="expanded" :aria-controls="boundaryId" @click="expanded = !expanded">{{ expanded ? '收起' : '展开完整消息' }}</button></div></article>
</template>
<style scoped>
.restore-message { display:flex;align-items:flex-start;gap:9px;margin:22px 0;min-width:0; }.restore-message>div { min-width:0;flex:1; }.restore-message header { display:flex;justify-content:space-between;gap:8px;font-size:12px;color:#4e594f;line-height:1.5;margin-bottom:8px; }.restore-message header small { font-size:10px;color:#929488; }.restore-message p { margin:0;font-size:16px;line-height:1.85;color:#313b32;white-space:pre-wrap;overflow-wrap:anywhere; }
.restore-message p.restore-boundary-collapsed { display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden; }
.restore-expand { border:0;background:transparent;color:#294f49;font-size:12px;line-height:1.6;padding:8px 0;min-height:36px;cursor:pointer; }
.restore-avatar { width:46px;height:46px;border-radius:14px;background:#e4ece8;color:#294f49;display:inline-grid;place-items:center;font:19px Georgia,'Songti SC',serif;flex-shrink:0; }.restore-avatar.small { width:32px;height:32px;border-radius:10px;font-size:15px; }

</style>
