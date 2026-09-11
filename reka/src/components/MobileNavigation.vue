<script setup lang="ts">
import { BookOpen, Library, UserRound } from '@lucide/vue'
defineProps<{ current: 'library' | 'modules' | 'profile' }>()
const emit = defineEmits<{ navigate: [destination: 'library' | 'modules' | 'profile'] }>()
</script>

<template>
  <nav class="mobile-navigation" aria-label="主要导航">
    <button :aria-current="current === 'library' ? 'page' : undefined" @click="emit('navigate', 'library')"><Library :size="21" /><span>世界</span></button>
    <button :aria-current="current === 'modules' ? 'page' : undefined" @click="emit('navigate', 'modules')"><BookOpen :size="21" /><span>模组</span></button>
    <button :aria-current="current === 'profile' ? 'page' : undefined" @click="emit('navigate', 'profile')"><UserRound :size="21" /><span>我的</span></button>
  </nav>
</template>
