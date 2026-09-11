<script setup lang="ts">
import { Check, CircleAlert, Info, X } from '@lucide/vue'
import { ToastClose, ToastDescription, ToastProvider, ToastRoot, ToastTitle, ToastViewport } from 'reka-ui'
import { notice } from '@/composables/useNotice'
</script>

<template>
  <ToastProvider :duration="3600" swipe-direction="right">
    <ToastRoot v-model:open="notice.open" class="toast" :class="`toast-${notice.tone}`">
      <Check v-if="notice.tone === 'success'" :size="18" />
      <CircleAlert v-else-if="notice.tone === 'danger'" :size="18" />
      <Info v-else :size="18" />
      <div><ToastTitle class="toast-title">{{ notice.title }}</ToastTitle><ToastDescription v-if="notice.message" class="toast-description">{{ notice.message }}</ToastDescription></div>
      <ToastClose class="toast-close"><X :size="16" /></ToastClose>
    </ToastRoot>
    <ToastViewport class="toast-viewport" />
  </ToastProvider>
</template>
