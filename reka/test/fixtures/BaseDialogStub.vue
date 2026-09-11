<script setup lang="ts">
defineModel<boolean>({ required: true })
defineProps<{ title: string, description?: string }>()
</script>

<template>
  <section>
    <slot />
    <slot name="footer" />
  </section>
</template>
