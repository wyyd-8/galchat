<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { ArrowUpRight, ChevronRight, BookOpen, Import, Plus } from '@lucide/vue'
import type { UserWorld, WorldTemplate } from '@/api/types'

defineProps<{ worlds: UserWorld[]; templates: WorldTemplate[]; loading: boolean }>()
const emit = defineEmits<{ select: [id: number]; previewTemplate: [id: number]; createWorld: []; createTemplate: []; importWorld: [file: File] }>()
const { isMobile } = useMobileViewport()
const selectedTab = ref('worlds')
const templatesTab = ref<InstanceType<typeof TabsTrigger>>()
const importInput = ref<HTMLInputElement>()
async function showTemplates() {
  selectedTab.value = 'templates'
  await nextTick()
  templatesTab.value?.$el.focus()
}
function pick(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) {
    emit('importWorld', file)
    input.value = ''
  }
}
</script>

<template>
  <main class="world-library">
    <header v-if="isMobile" class="library-mobile-header">
      <div class="library-brand"><span class="brand-glyph">✦</span><strong>GalChat</strong></div>
      <button class="icon-button" aria-label="创建世界：选择世界模板" @click="showTemplates"><Plus :size="22" /></button>
    </header>
    <div class="library-body">
      <header class="library-welcome">
        <div><span v-if="!isMobile" class="eyebrow">GALCHAT · YOUR STORIES</span><h1>故事，继续。</h1><p>回到熟悉的世界，或开启新的故事。</p></div>
        <div v-if="!isMobile" class="library-welcome-actions">
          <button class="button primary" @click="showTemplates"><Plus :size="17" />创建世界</button>
          <button class="button secondary" @click="importInput?.click()"><Import :size="17" />导入世界模板</button>
        </div>
      </header>
      <input ref="importInput" class="library-file-input" type="file" accept="application/json,.json" aria-label="选择世界模板文件" @change="pick" />
      <TabsRoot v-model="selectedTab" class="library-tabs">
        <TabsList class="library-tab-list" aria-label="世界内容">
          <TabsTrigger class="library-tab" value="worlds">已有世界 <span>{{ worlds.length }}</span></TabsTrigger>
          <TabsTrigger ref="templatesTab" class="library-tab" value="templates">世界模板 <span>{{ templates.length }}</span></TabsTrigger>
        </TabsList>
        <TabsContent value="worlds" class="library-panel">
          <div class="library-panel-heading"><p>继续你的世界</p></div>
          <p v-if="loading" class="library-notice" role="status">正在载入你的世界…</p>
          <template v-else>
            <div v-if="!worlds.length" class="library-empty"><BookOpen :size="28" /><h2>还没有自己的世界</h2><p>从一个世界模板开始新的故事。</p></div>
            <div class="library-world-grid">
              <button v-for="world in worlds" :key="world.id" class="library-world-card" @click="emit('select', world.id)">
                <span class="library-world-cover" :style="world.image ? { backgroundImage: `url(${world.image})` } : {}"><BookOpen v-if="!world.image" :size="26" /></span>
                <span class="library-world-copy"><strong>{{ world.name }}</strong><span>进入世界 <ArrowUpRight v-if="!isMobile" :size="16" /></span></span>
                <ChevronRight v-if="isMobile" class="library-world-chevron" :size="17" />
              </button>
              <button class="library-new-world" @click="showTemplates"><Plus :size="20" /><span>{{ isMobile ? '从模板开启新世界' : '开启新的世界' }}</span><small v-if="!isMobile">去世界模板挑选</small></button>
            </div>
          </template>
        </TabsContent>
        <TabsContent value="templates" class="library-panel">
          <div class="library-panel-heading">
            <p>{{ isMobile ? '挑选新的故事' : '从一个模板，开始新的故事' }}</p>
            <div class="library-template-actions">
              <button aria-label="创建世界模板" @click="emit('createTemplate')"><Plus :size="16" />{{ isMobile ? '创建' : '创建模板' }}</button>
              <button aria-label="导入世界模板" @click="importInput?.click()"><Import :size="16" />{{ isMobile ? '导入' : '导入模板' }}</button>
            </div>
          </div>
          <p v-if="loading" class="library-notice" role="status">正在载入世界模板…</p>
          <div v-else-if="templates.length" class="library-template-grid">
            <button v-for="(template, index) in templates" :key="template.id" class="library-template-card" :disabled="!template.id" @click="template.id && emit('previewTemplate', template.id)">
              <span class="library-template-cover" :class="{ sand: index % 2, 'has-image': template.image }" :style="template.image ? { backgroundImage: `linear-gradient(180deg, transparent, #142d2b99), url(${template.image})` } : {}"><span>{{ template.name }}</span></span>
              <span class="library-template-copy"><strong>{{ template.name }}</strong><span>查看背景与角色 <ArrowUpRight :size="16" /></span></span>
            </button>
          </div>
          <div v-else class="library-empty"><BookOpen :size="28" /><h2>暂无世界模板</h2><p>创建一套新设定，或导入已有的模板文件。</p><button class="button secondary" @click="emit('createWorld')">创建世界</button></div>
        </TabsContent>
      </TabsRoot>
    </div>
  </main>
</template>

<style scoped>
.world-library { max-width: 1440px; margin: 0 auto; padding: 42px clamp(28px, 5vw, 72px) 64px; }
.library-welcome { display: flex; justify-content: space-between; align-items: center; gap: 24px; margin-bottom: 30px; }
.library-welcome h1 { margin: 9px 0; font-family: var(--font-display); font-size: 32px; font-weight: 600; letter-spacing: -.5px; }
.library-welcome p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.8; }
.library-welcome-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; flex-shrink: 0; }
.library-tab-list { display: flex; gap: 28px; border-bottom: 1px solid var(--line); margin-bottom: 20px; }
.library-tab { min-height: 48px; padding: 10px 2px 14px; border: 0; border-bottom: 3px solid transparent; display: inline-flex; align-items: center; justify-content: center; gap: 8px; background: transparent; color: var(--muted); font-size: 16px; cursor: pointer; }
.library-tab[data-state="active"] { color: var(--pine); border-bottom-color: var(--pine); font-weight: 600; }
.library-tab > span { min-width: 22px; padding: 2px 6px; border-radius: 5px; background: var(--surface); font-size: 11px; font-weight: 400; }
.library-tab[data-state="active"] > span { background: var(--pine-soft); }
.library-panel-heading { min-height: 44px; margin-bottom: 12px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; }
.library-panel-heading p { margin: 0; color: var(--muted); font-size: 12px; }
.library-template-actions { display: flex; align-items: center; gap: 18px; }
.library-template-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 44px; padding: 0; border: 0; background: transparent; color: var(--pine); font-size: 12px; cursor: pointer; }
.library-file-input { display: none; }
.library-world-grid, .library-template-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.library-world-card, .library-template-card { min-width: 0; padding: 0; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: var(--surface); text-align: left; cursor: pointer; transition: border-color 150ms ease, box-shadow 150ms ease; }
.library-world-card:hover, .library-template-card:not(:disabled):hover { border-color: var(--line-strong); box-shadow: 0 8px 24px rgba(49,48,41,.08); }
.library-world-cover { height: 145px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #e6e9df, #c7d4c7); background-size: cover; background-position: center; color: var(--pine); }
.library-world-copy, .library-template-copy { display: block; padding: 16px; }
.library-world-copy strong, .library-template-copy strong { display: block; font-size: 15px; font-weight: 550; overflow-wrap: anywhere; }
.library-world-copy > span, .library-template-copy > span { margin-top: 9px; display: flex; align-items: center; justify-content: space-between; gap: 6px; color: var(--muted); font-size: 11px; }
.library-world-copy svg, .library-template-copy svg { flex-shrink: 0; color: var(--pine); }
.library-new-world { min-height: 225px; padding: 16px; border: 1px dashed var(--line-strong); border-radius: 12px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; background: transparent; color: var(--pine); font-size: 13px; cursor: pointer; }
.library-new-world:hover { background: var(--surface); }
.library-new-world small { color: var(--muted); font-size: 11px; }
.library-template-cover { min-height: 180px; padding: 20px; display: flex; align-items: flex-end; color: var(--pine); background: linear-gradient(135deg, #e6e9df, #c7d4c7); background-size: cover; background-position: center; }
.library-template-cover.sand { color: #7b603d; background-color: #f1e5d4; background-image: linear-gradient(135deg, #f5ecdf, #ddceb4); }
.library-template-cover.has-image { color: #fffefa; }
.library-template-cover > span { font-size: 23px; font-weight: 550; letter-spacing: 1px; overflow-wrap: anywhere; }
.library-empty { padding: 40px 16px; text-align: center; color: var(--muted); }
.library-empty h2 { margin: 14px 0 8px; font-size: 17px; color: var(--ink); }
.library-empty p, .library-notice { font-size: 13px; line-height: 1.8; color: var(--muted); }
.library-empty .button { margin-top: 10px; }
@media (min-width: 768px) {
  .library-welcome { flex-wrap: wrap; padding: 24px 28px; border: 1px solid #344b43; border-radius: 14px; color: #f7f4ed; background: radial-gradient(circle at 80% 20%, rgba(191,137,66,.25), transparent 35%), linear-gradient(125deg, #223d39, #182c2a 60%, #302c26); }
  .library-welcome .eyebrow { color: #c0cbc2; }
  .library-welcome p { color: #cbd2c9; }
  .library-welcome .button.primary { color: var(--pine); background: #f7f4ed; border-color: #f7f4ed; box-shadow: none; }
  .library-welcome .button.primary:hover { background: #e8e8dd; border-color: #e8e8dd; }
  .library-welcome .button.secondary { color: #f7f4ed; border-color: rgba(255,255,255,.3); background: rgba(255,255,255,.08); }
  .library-welcome .button.secondary:hover { background: rgba(255,255,255,.16); }
  .library-welcome .button:focus-visible { outline-color: #f7f4ed; }
  .library-world-grid, .library-template-grid { grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; }
  .library-world-cover { height: 105px; }
  .library-world-copy, .library-template-copy { padding: 12px; }
  .library-world-copy > span, .library-template-copy > span { margin-top: 7px; }
  .library-new-world { min-height: 178px; gap: 8px; }
  .library-template-cover { min-height: 120px; padding: 14px; }
  .library-template-cover > span { font-size: 20px; }
}
@media (max-width: 767px) {
  .world-library { width: 100%; height: 100%; min-height: 0; padding: 0; display: flex; flex: 1; flex-direction: column; }
  .library-mobile-header { flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: max(10px, env(safe-area-inset-top)) 18px 5px; }
  .library-brand { display: flex; align-items: center; gap: 9px; }
  .library-brand strong { font-size: 17px; font-weight: 600; }
  .library-mobile-header .icon-button { width: 44px; height: 44px; color: var(--pine); }
  .library-body { width: 100%; max-width: 640px; margin: 0 auto; flex: 1; min-height: 0; padding: 0 18px 24px; overflow-y: auto; overscroll-behavior: contain; }
  .library-welcome { padding: 17px 2px 22px; margin: 0; }
  .library-welcome h1 { font-size: 27px; margin: 0 0 7px; }
  .library-welcome p { font-size: 12px; }
  .library-tab-list { position: sticky; top: 0; z-index: 1; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px; padding: 4px; border: 0; border-radius: 12px; background: var(--pine-soft); margin-bottom: 12px; }
  .library-tab { min-height: 44px; padding: 8px 4px; border: 0; border-radius: 9px; font-size: 14px; }
  .library-tab[data-state="active"] { background: var(--surface-strong); box-shadow: 0 2px 6px rgba(40,61,41,.06); }
  .library-tab > span, .library-tab[data-state="active"] > span { min-width: 0; padding: 0; background: transparent; }
  .library-panel-heading { margin-bottom: 8px; }
  .library-template-actions { gap: 14px; }
  .library-world-grid { display: flex; flex-direction: column; gap: 0; }
  .library-world-card { display: flex; align-items: center; gap: 13px; padding: 12px 0; border: 0; border-bottom: 1px solid var(--line); border-radius: 0; background: transparent; }
  .library-world-card:first-child { padding-top: 0; }
  .library-world-card:hover { box-shadow: none; }
  .library-world-cover { width: 55px; height: 55px; flex-shrink: 0; border-radius: 9px; }
  .library-world-copy { min-width: 0; flex: 1; padding: 0; }
  .library-world-copy > span { margin-top: 5px; }
  .library-world-chevron { flex-shrink: 0; color: var(--muted); }
  .library-new-world { min-height: 45px; flex-direction: row; gap: 8px; margin-top: 18px; padding: 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); }
  .library-template-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .library-template-cover { min-height: 137px; padding: 15px; }
  .library-template-cover > span { font-size: 21px; }
  .library-template-copy { padding: 12px; }
  .library-template-copy strong { font-size: 14px; }
  .library-template-copy > span { gap: 3px; }
}
@media (max-width: 360px) {
  .library-body { padding-inline: 14px; }
  .library-template-actions { gap: 10px; }
  .library-template-cover { padding: 12px; min-height: 120px; }
  .library-template-cover > span { font-size: 19px; }
  .library-template-copy { padding: 10px; }
}
</style>
