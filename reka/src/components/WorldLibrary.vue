<script setup lang="ts">
import { ArrowRight, BookOpen, Import, Plus, Sparkles } from '@lucide/vue'
import type { UserWorld, WorldTemplate } from '@/api/types'

defineProps<{ worlds: UserWorld[]; templates: WorldTemplate[]; loading: boolean }>()
const emit = defineEmits<{ select: [id: number]; previewTemplate: [id: number]; createWorld: []; createTemplate: []; importWorld: [file: File] }>()
function pick(event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (file) emit('importWorld', file) }
</script>

<template>
  <main class="library-page">
    <header class="library-hero">
      <div><span class="eyebrow"><Sparkles :size="14" /> GALCHAT WORKSPACE</span><h1>让角色在同一个世界里<br />真正彼此回应。</h1><p>创建群聊、安排回复次序，并为后续跑团演出保留一块安静的舞台。</p></div>
      <div class="hero-actions"><button class="button primary" @click="emit('createWorld')"><Plus :size="17" />创建世界</button><label class="button secondary file-button"><Import :size="17" />导入<input type="file" accept="application/json" @change="pick" /></label></div>
    </header>
    <section class="content-section">
      <div class="section-title"><div><span class="eyebrow">CONTINUE</span><h2>继续你的世界</h2></div><span class="count-label">{{ worlds.length }} 个世界</span></div>
      <div v-if="worlds.length" class="world-grid">
        <button v-for="world in worlds" :key="world.id" class="world-card" @click="emit('select', world.id)">
          <span class="world-cover" :style="world.image ? { backgroundImage: `linear-gradient(180deg, transparent 40%, rgba(15,18,22,.72)), url(${world.image})` } : {}"><BookOpen v-if="!world.image" :size="30" /></span>
          <span class="world-card-copy"><small>{{ world.myWorld ? '原创世界' : '收藏世界' }}</small><strong>{{ world.name }}</strong><span>进入世界 <ArrowRight :size="15" /></span></span>
        </button>
      </div>
      <div v-else class="empty-panel"><BookOpen :size="28" /><h3>还没有自己的世界</h3><p>从公开模板开始，或创建一套全新的设定。</p></div>
    </section>
    <section class="content-section muted-section">
      <div class="section-title"><div><span class="eyebrow">DISCOVER</span><h2>世界模板</h2></div><button class="button ghost" @click="emit('createTemplate')"><Plus :size="16" />创建模板</button></div>
      <div class="template-strip"><button v-for="template in templates.slice(0, 6)" :key="template.id" class="template-card" :disabled="!template.id" @click="template.id && emit('previewTemplate', template.id)"><div class="template-cover" :style="template.image ? { backgroundImage: `url(${template.image})` } : {}" /><small>查看世界模板</small><h3>{{ template.name }}</h3><p>查看作者、背景与公开信息</p></button></div>
    </section>
  </main>
</template>
