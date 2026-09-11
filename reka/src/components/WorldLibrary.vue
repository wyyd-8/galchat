<script setup lang="ts">
import { ref } from 'vue'
import { ARCHIVE_ACCEPT } from '@/api/archiveFiles'
import { useMobileViewport } from '@/composables/useMobileViewport'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { ArrowUpRight, ChevronRight, ArrowRight, BookOpen, Import, Plus, Sparkles } from '@lucide/vue'
import type { UserWorld, WorldTemplate } from '@/api/types'

defineProps<{ worlds: UserWorld[]; templates: WorldTemplate[]; loading: boolean; archiveBusy?: boolean }>()
const emit = defineEmits<{ select: [id: number]; previewTemplate: [id: number]; createWorld: []; createTemplate: []; importWorld: [file: File] }>()
const { isMobile } = useMobileViewport()
const menuOpen = ref(false)
function menuAction(action: 'createWorld' | 'createTemplate') { menuOpen.value = false; if (action === 'createWorld') emit('createWorld'); else emit('createTemplate') }
function pick(event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (file) { menuOpen.value = false; emit('importWorld', file); (event.target as HTMLInputElement).value = '' } }
</script>

<template>
  <main v-if="isMobile" class="mobile-v1-library">
    <header class="mobile-v1-top"><span class="brand-glyph">✦</span><div><strong>GalChat</strong><small>群像叙事</small></div><button class="icon-button" aria-label="世界操作" @click="menuOpen = true"><Plus :size="20" /></button></header>
    <div class="mobile-v1-content">
      <div class="mobile-v1-intro"><span class="eyebrow">YOUR STORIES, STILL UNFOLDING</span><h1>故事，继续。</h1><p>回到熟悉的世界，和角色再次相遇。</p></div>
      <p v-if="loading" class="mobile-v1-notice" role="status">正在载入你的世界…</p>
      <template v-else>
        <button v-if="worlds[0]" class="mobile-world-hero" :style="worlds[0].image ? { backgroundImage: `linear-gradient(135deg, #284b44aa, #142d2be8), url(${worlds[0].image})` } : {}" @click="emit('select', worlds[0].id)"><span class="mobile-cover-ornament" aria-hidden="true">◈</span><span class="eyebrow">我的世界 · {{ worlds[0].myWorld === true ? '自有模板' : worlds[0].myWorld === false ? '他人模板' : '世界' }}</span><h2>{{ worlds[0].name }}</h2><span class="mobile-cover-cta">进入世界 <ArrowUpRight :size="20" /></span></button>
        <div class="mobile-v1-section"><h2>我的世界</h2><button class="mobile-v1-link" @click="emit('createWorld')">创建世界 ＋</button></div>
        <button v-for="world in worlds.slice(1)" :key="world.id" class="mobile-v1-row" @click="emit('select', world.id)"><span class="mobile-v1-avatar" :style="world.image ? { backgroundImage: `url(${world.image})` } : {}">{{ world.image ? '' : world.name.slice(0, 1) }}</span><span><strong>{{ world.name }}</strong><small>{{ world.myWorld === true ? '自有模板' : world.myWorld === false ? '他人模板' : '世界' }}</small></span><ChevronRight :size="16" /></button>
        <p v-if="!worlds.length" class="mobile-v1-notice">还没有自己的世界。从一个模板开始，或创建全新的设定。</p>
      </template>
      <div class="mobile-v1-section"><h2>世界模板</h2><button class="mobile-v1-link" @click="emit('createTemplate')">创建模板 ＋</button></div>
      <div class="mobile-template-grid"><button v-for="(template, index) in templates" :key="template.id" class="mobile-template-tile" :class="{ sand: index % 2 }" :style="template.image ? { backgroundImage: `linear-gradient(180deg, #142d2b22, #142d2bc0), url(${template.image})`, color: '#fffefa' } : {}" :disabled="!template.id" @click="template.id && emit('previewTemplate', template.id)"><small>{{ String(index + 1).padStart(2, '0') }} / WORLD</small><strong>{{ template.name }}</strong></button></div>
    </div>
    <BaseDialog v-model="menuOpen" title="世界操作" content-class="mobile-v1-menu"><button class="mobile-v1-row" @click="menuAction('createWorld')"><Plus :size="20" /><span><strong>创建世界</strong><small>从模板开始新的故事</small></span><ChevronRight :size="16" /></button><button class="mobile-v1-row" @click="menuAction('createTemplate')"><BookOpen :size="20" /><span><strong>创建世界模板</strong><small>维护背景、设定和角色</small></span><ChevronRight :size="16" /></button><label class="mobile-v1-row file-button" :class="{ disabled: archiveBusy }"><Import :size="20" /><span><strong>导入世界模板</strong><small>ZIP 含图片，也支持旧 JSON</small></span><input type="file" :accept="ARCHIVE_ACCEPT" :disabled="archiveBusy" @change="pick" /></label></BaseDialog>
  </main>
  <main v-else class="library-page">
    <header class="library-hero">
      <div class="library-hero-copy">
        <span class="eyebrow"><Sparkles :size="14" /> GALCHAT WORKSPACE</span>
        <h1><span class="hero-title-line">让角色身处同一个世界，</span><span class="hero-title-line">让每次回应自然发生。</span></h1>
        <p>创建世界，连接角色，开始属于你的故事。</p>
      </div>
      <div class="hero-actions"><button class="button primary" @click="emit('createWorld')"><Plus :size="17" />创建世界</button><label class="button secondary file-button" :class="{ disabled: archiveBusy }"><Import :size="17" />导入 ZIP / JSON<input type="file" :accept="ARCHIVE_ACCEPT" :disabled="archiveBusy" @change="pick" /></label></div>
    </header>
    <section class="content-section">
      <div class="section-title"><div><h2>继续你的世界</h2></div><span class="count-label">{{ worlds.length }} 个世界</span></div>
      <div v-if="worlds.length" class="world-grid">
        <button v-for="world in worlds" :key="world.id" class="world-card" @click="emit('select', world.id)">
          <span class="world-cover" :style="world.image ? { backgroundImage: `linear-gradient(180deg, transparent 40%, rgba(15,18,22,.72)), url(${world.image})` } : {}"><BookOpen v-if="!world.image" :size="30" /></span>
          <span class="world-card-copy"><small>{{ world.myWorld === true ? '自有模板' : world.myWorld === false ? '他人模板' : '世界' }}</small><strong>{{ world.name }}</strong><span>进入世界 <ArrowRight :size="15" /></span></span>
        </button>
      </div>
      <div v-else class="empty-panel"><BookOpen :size="28" /><h3>还没有自己的世界</h3><p>从公开模板开始，或创建一套全新的设定。</p></div>
    </section>
    <section class="content-section muted-section">
      <div class="section-title"><div><h2>世界模板</h2></div><button class="button ghost" @click="emit('createTemplate')"><Plus :size="16" />创建模板</button></div>
      <div class="template-strip"><button v-for="template in templates.slice(0, 6)" :key="template.id" class="template-card" :disabled="!template.id" @click="template.id && emit('previewTemplate', template.id)"><div class="template-cover" :style="template.image ? { backgroundImage: `url(${template.image})` } : {}" /><small>查看世界模板</small><h3>{{ template.name }}</h3><p>查看作者与世界背景</p></button></div>
    </section>
  </main>
</template>

<style scoped>
@media (max-width: 767px) {
  .mobile-v1-top .brand-glyph { width: 38px; height: 38px; border-radius: 11px; margin: 0 9px; font-size: 22px; }
  .mobile-world-hero { width: 100%; min-height: 218px; position: relative; overflow: hidden; border: 0; border-radius: 17px; padding: 28px 24px; text-align: left; color: #faf5e7; background: radial-gradient(ellipse at 90% 0%,#7e805875,transparent 58%),linear-gradient(135deg,#284b44,#142d2b); background-size: cover; background-position: center; }
  .mobile-world-hero .eyebrow { color: #c1d0c4; font-weight: 400; }
  .mobile-world-hero h2 { position: relative; margin: 35px 0 6px; font-size: 30px; font-weight: 550; overflow-wrap: anywhere; }
  .mobile-cover-ornament { position: absolute; right: 15px; top: 0; font: 180px Georgia; color: #b0bba118; transform: rotate(-20deg); line-height: 1; }
  .mobile-cover-cta { display: flex; align-items: center; justify-content: space-between; margin-top: 17px; font-size: 12px; }
  .mobile-template-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .mobile-template-tile { min-height: 122px; border: 0; border-radius: 12px; display: flex; flex-direction: column; justify-content: space-between; align-items: start; padding: 17px; text-align: left; color: #38574b; background: #dce3d8; background-size: cover; background-position: center; }
  .mobile-template-tile.sand { background-color: #f1e5d4; color: #7b603d; }
  .mobile-template-tile small { font: 10px var(--font-mono); letter-spacing: 1px; opacity: .65; }
  .mobile-template-tile strong { font-size: 19px; font-weight: 550; overflow-wrap: anywhere; margin-top: 20px; }
}
</style>
