<script setup lang="ts">
import { Download, LoaderCircle } from '@lucide/vue'
import type { CardBackground, CardFont } from './characterCardExport'
const background = defineModel<CardBackground | ''>('background', { required: true })
const fontIndex = defineModel<CardFont>('fontIndex', { required: true })
defineProps<{ name: string; exporting: boolean; error: string; hideActions?: boolean }>()
defineEmits<{ download: []; cancel: [] }>()
</script>

<template>
  <form class="card-export-form" :aria-busy="exporting" @submit.prevent="$emit('download')">
    <header><strong>导出人物卡</strong><p>{{ name }} · 双页 PDF</p></header>
    <label>卡面模板
      <select v-model="background" :disabled="exporting" required>
        <option disabled value="">请选择角色卡时代</option>
        <option value="1920s">1920 年代</option>
        <option value="modern">现代</option>
      </select>
    </label>
    <label>字体
      <select v-model="fontIndex" :disabled="exporting">
        <option :value="0">小薇 · 清晰易读</option>
        <option :value="1">志莽行 · 随性手写</option>
        <option :value="2">马善政 · 毛笔风格</option>
      </select>
    </label>
    <p class="card-export-hint">技能仅填写与基础值不同的项目；固定两页，超出卡面容量的内容将省略。</p>
    <p v-if="error" class="card-export-error" role="alert">{{ error }}</p>
    <footer v-if="!hideActions">
      <button class="button ghost" type="button" @click="$emit('cancel')">取消</button>
      <button class="button primary" type="submit" :disabled="!background || exporting">
        <LoaderCircle v-if="exporting" class="spin" :size="14" /><Download v-else :size="14" />
        {{ exporting ? '正在生成…' : '生成并下载 PDF' }}
      </button>
    </footer>
  </form>
</template>
