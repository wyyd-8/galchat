<script setup lang="ts">
import type { StyleValue } from 'vue'
import { X } from '@lucide/vue'
import { DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'

const open = defineModel<boolean>({ required: true })
withDefaults(defineProps<{
  title: string
  description?: string
  size?: 'sm' | 'md' | 'lg'
  contentClass?: string
  contentStyle?: StyleValue
}>(), { description: '', size: 'md', contentClass: '', contentStyle: undefined })
</script>

<template>
  <DialogRoot v-model:open="open">
    <DialogPortal>
      <DialogOverlay class="dialog-overlay" />
      <DialogContent class="dialog-content" :class="[`dialog-${size}`, contentClass]" :style="contentStyle">
        <header class="dialog-header">
          <div>
            <DialogTitle class="dialog-title">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="dialog-description">{{ description }}</DialogDescription>
          </div>
          <DialogClose class="icon-button" aria-label="关闭"><X :size="18" /></DialogClose>
        </header>
        <div class="dialog-body"><slot /></div>
        <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer" /></footer>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
