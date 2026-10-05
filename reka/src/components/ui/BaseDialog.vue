<script setup lang="ts">
import { computed, watch } from 'vue'
import type { StyleValue } from 'vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { useMobileDialogHistory } from '@/composables/useMobileDialogHistory'
import { useDialogPresence } from '@/composables/useDialogPresence'
import { ArrowLeft, X } from '@lucide/vue'
import { DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'

const visible = defineModel<boolean>({ required: true })
const props = withDefaults(defineProps<{
  title: string
  description?: string
  size?: 'sm' | 'md' | 'lg'
  layer?: 'default' | 'foreground'
  contentClass?: string
  contentStyle?: StyleValue
  mobilePresentation?: 'sheet' | 'page'
  mobileBack?: () => void
  embedded?: boolean
  closeDisabled?: boolean
}>(), { description: '', size: 'md', layer: 'default', contentClass: '', contentStyle: undefined, mobilePresentation: 'sheet' })
const open = computed({
  get: () => visible.value,
  set: value => { if (value || !props.closeDisabled) visible.value = value },
})
function preventLockedClose(event: Event) {
  if (props.closeDisabled) event.preventDefault()
}
const { isMobile } = useMobileViewport()
useDialogPresence(computed(() => open.value && !props.embedded))
useMobileDialogHistory(open, computed(() => isMobile.value && !props.embedded), async () => {
  if (props.closeDisabled) return
  if (props.mobileBack) await props.mobileBack()
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
function initialFocus(event: Event) {
  // Focusing a text field on entry opens the phone keyboard before the page is read.
  // Keep focus inside the dialog without starting text input.
  if (!isMobile.value) return
  event.preventDefault()
  const content = event.target as HTMLElement | null
  content?.focus({ preventScroll: true })
}
</script>

<template>
  <section v-if="embedded" class="dialog-content dialog-embedded" :class="contentClass" :aria-label="title" :data-mobile-presentation="mobilePresentation"
    style="position: relative; inset: auto; transform: none; width: 100%; max-width: 100%; height: auto; max-height: none; margin: 0; border: 0; border-radius: 12px; box-shadow: none; animation: none; z-index: auto;">
    <div class="dialog-body" style="padding: 0; display: block; overflow: visible;"><slot /></div>
  </section>
  <DialogRoot v-else v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" :class="layerClass" />
      <DialogContent class="dialog-content" :class="[`dialog-${size}`, layerClass, contentClass]" :style="contentStyle" v-bind="description ? {} : { 'aria-describedby': undefined }" :data-mobile-presentation="mobilePresentation" @open-auto-focus="initialFocus" @close-auto-focus="restoreFocus" @escape-key-down="preventLockedClose" @interact-outside="preventLockedClose">
        <header class="dialog-header">
          <button v-if="isMobile && mobilePresentation === 'page' && mobileBack" class="icon-button mobile-dialog-back" aria-label="返回" :disabled="closeDisabled" @click="mobileBack"><ArrowLeft :size="22" /></button>
          <DialogClose v-else-if="isMobile && mobilePresentation === 'page'" class="icon-button mobile-dialog-back" aria-label="返回" :disabled="closeDisabled"><ArrowLeft :size="22" /></DialogClose>
          <div>
            <DialogTitle class="dialog-title">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="dialog-description">{{ description }}</DialogDescription>
          </div>
          <slot name="header-actions" />
          <DialogClose v-if="!isMobile || mobilePresentation !== 'page'" class="icon-button" aria-label="关闭" :disabled="closeDisabled"><X :size="18" /></DialogClose>
        </header>
        <div class="dialog-body"><slot /></div>
        <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer" /></footer>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.dialog-content.dialog-embedded[data-mobile-presentation]::before { display: none; }
</style>
