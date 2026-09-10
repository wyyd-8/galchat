<script setup lang="ts">
import { computed, watch } from 'vue'
import type { StyleValue } from 'vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { useMobileDialogHistory } from '@/composables/useMobileDialogHistory'
import { ArrowLeft, X } from '@lucide/vue'
import { DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'

const open = defineModel<boolean>({ required: true })
const props = withDefaults(defineProps<{
  title: string
  description?: string
  size?: 'sm' | 'md' | 'lg'
  layer?: 'default' | 'foreground'
  contentClass?: string
  contentStyle?: StyleValue
  mobilePresentation?: 'sheet' | 'page'
  mobileBack?: () => void
}>(), { description: '', size: 'md', layer: 'default', contentClass: '', contentStyle: undefined, mobilePresentation: 'sheet' })
const { isMobile } = useMobileViewport()
useMobileDialogHistory(open, isMobile, () => {
  if (props.mobileBack) props.mobileBack()
  else open.value = false
})
const layerClass = computed(() => `dialog-layer-${props.layer}`)
let returnFocus: HTMLElement | null = null
watch(open, (visible) => {
  if (visible && typeof document !== 'undefined') returnFocus = document.activeElement as HTMLElement | null
}, { flush: 'sync', immediate: true })
function restoreFocus(event: Event) {
  if (!returnFocus?.isConnected) return
  event.preventDefault()
  returnFocus.focus({ preventScroll: true })
}
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" :class="layerClass" />
      <DialogContent class="dialog-content" :class="[`dialog-${size}`, layerClass, contentClass]" :style="contentStyle" v-bind="description ? {} : { 'aria-describedby': undefined }" :data-mobile-presentation="mobilePresentation" @close-auto-focus="restoreFocus">
        <header class="dialog-header">
          <button v-if="isMobile && mobilePresentation === 'page' && mobileBack" class="icon-button mobile-dialog-back" aria-label="返回" @click="mobileBack"><ArrowLeft :size="22" /></button>
          <DialogClose v-else-if="isMobile && mobilePresentation === 'page'" class="icon-button mobile-dialog-back" aria-label="返回"><ArrowLeft :size="22" /></DialogClose>
          <div>
            <DialogTitle class="dialog-title">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="dialog-description">{{ description }}</DialogDescription>
          </div>
          <slot name="header-actions" />
          <DialogClose v-if="!isMobile || mobilePresentation !== 'page'" class="icon-button" aria-label="关闭"><X :size="18" /></DialogClose>
        </header>
        <div class="dialog-body"><slot /></div>
        <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer" /></footer>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
