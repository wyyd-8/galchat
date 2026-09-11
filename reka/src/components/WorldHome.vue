<script setup lang="ts">
import { computed, ref } from 'vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { ArrowLeft, Ellipsis, Archive, ChevronRight, Dices, MessageCircleMore, Pencil, Plus, Save, Settings2, UsersRound } from '@lucide/vue'
import type { Character, Conversation, UserWorld, WorldSave } from '@/api/types'

type DialogueKind = 'direct' | 'group' | 'trpg'
type DialogueFilter = 'all' | DialogueKind

interface DialogueEntry {
  key: string
  id: number
  kind: DialogueKind
  title: string
  preview: string
  time?: string
  image?: string
  favor?: number
  status?: Conversation['status']
  completionStatus?: Conversation['completionStatus']
  archivedAt?: string
}

const props = defineProps<{ world: UserWorld; characters: Character[]; conversations: Conversation[]; worldSave: WorldSave | null }>()
const emit = defineEmits<{ openCharacter: [id: number]; openConversation: [id: number]; newConversation: []; addCharacter: []; editCharacter: [id: number]; save: []; load: []; settings: []; back: [] }>()
const dialogueFilter = ref<DialogueFilter>('all')
const { isMobile } = useMobileViewport()
const menuOpen = ref(false)
const saveOpen = ref(false)
function mobileAction(action: 'settings' | 'addCharacter' | 'newConversation') { menuOpen.value = false; if (action === 'settings') emit('settings'); else if (action === 'addCharacter') emit('addCharacter'); else emit('newConversation') }

const dialogueEntries = computed<DialogueEntry[]>(() => [
  ...props.characters.map((character) => ({
    key: `direct-${character.characterId}`,
    id: character.characterId,
    kind: 'direct' as const,
    title: character.characterName,
    preview: character.lastChatContent || '还没有单聊记录',
    time: character.lastChatTime,
    image: character.characterImage,
    favor: character.favorValue ?? 0,
  })),
  ...props.conversations.map((conversation) => ({
    key: `conversation-${conversation.id}`,
    id: conversation.id,
    kind: conversation.mode === 'trpg' ? 'trpg' as const : 'group' as const,
    title: conversation.title,
    preview: (conversation.completionStatus === 'ready' ? conversation.summary : conversation.lastChatContent) || conversation.summary || (conversation.status === 'active' ? '等待下一次互动' : '会话已结束'),
    time: conversation.lastChatTime || conversation.updatedAt,
    status: conversation.status,
    completionStatus: conversation.completionStatus,
    archivedAt: conversation.archivedAt,
  })),
].sort((left, right) => activityTime(right.time) - activityTime(left.time)))

const visibleDialogueEntries = computed(() => dialogueFilter.value === 'all'
  ? dialogueEntries.value
  : dialogueEntries.value.filter((entry) => entry.kind === dialogueFilter.value))

const filterOptions = computed(() => [
  { value: 'all' as const, label: '全部', count: dialogueEntries.value.length },
  { value: 'direct' as const, label: '单聊', count: props.characters.length },
  { value: 'group' as const, label: '群聊', count: props.conversations.filter((item) => item.mode === 'chat').length },
  { value: 'trpg' as const, label: '跑团', count: props.conversations.filter((item) => item.mode === 'trpg').length },
])

function activityTime(value?: string) {
  if (!value) return 0
  const timestamp = Date.parse(value)
  return Number.isNaN(timestamp) ? 0 : timestamp
}

function activityLabel(value?: string) {
  if (!value) return '尚未开始'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const now = new Date()
  if (date.toDateString() === now.toDateString()) return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (date.getFullYear() === now.getFullYear()) return `${date.getMonth() + 1}月${date.getDate()}日`
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`
}

function kindLabel(kind: DialogueKind) {
  return { direct: '单聊', group: '群聊', trpg: 'CoC 跑团' }[kind]
}

function openDialogue(entry: DialogueEntry) {
  if (entry.kind === 'direct') emit('openCharacter', entry.id)
  else emit('openConversation', entry.id)
}
</script>

<template>
  <main v-if="isMobile" class="mobile-v1-world">
    <header class="mobile-v1-top"><button class="icon-button" aria-label="返回世界列表" @click="emit('back')"><ArrowLeft :size="22" /></button><div><strong>我的世界</strong></div><button class="icon-button" aria-label="世界操作" @click="menuOpen = true"><Ellipsis :size="20" /></button></header>
    <div class="mobile-v1-content">
      <div class="mobile-world-cover" :style="world.image ? { backgroundImage: `linear-gradient(115deg,#24433dd9,#3f594ccc),url(${world.image})` } : {}"><span class="eyebrow">WORLD / {{ String(world.id).padStart(2, '0') }}</span><h1>{{ world.name }}</h1><p>{{ characters.length }} 位角色 · {{ conversations.filter(item => item.status === 'active').length }} 个进行中的会话</p></div>
      <button class="mobile-v1-row" @click="saveOpen = true"><span class="mobile-v1-avatar"><Archive :size="22" /></span><span><strong>世界存档</strong><small>{{ worldSave ? `${activityLabel(worldSave.savedAt)} · ${worldSave.remark || '未填写备注'}` : '尚未创建存档' }}</small></span><ChevronRight :size="16" /></button>
      <div class="mobile-v1-section"><h2>对话中心</h2></div>
      <div class="mobile-world-create-actions"><button class="button secondary" @click="emit('addCharacter')"><Plus :size="16" />添加角色</button><button class="button primary" @click="emit('newConversation')"><Plus :size="16" />新建群聊/跑团</button></div>
      <div class="mobile-world-filters" role="tablist" aria-label="对话类型"><button v-for="option in filterOptions" :key="option.value" role="tab" :aria-selected="dialogueFilter === option.value" @click="dialogueFilter = option.value">{{ option.label }}</button></div>
      <button v-for="entry in visibleDialogueEntries" :key="entry.key" class="mobile-v1-row" @click="openDialogue(entry)"><span class="mobile-v1-avatar" :class="entry.kind" :style="entry.image ? { backgroundImage: `url(${entry.image})` } : {}"><template v-if="entry.kind === 'direct'">{{ entry.image ? '' : entry.title.slice(0, 1) }}</template><Dices v-else-if="entry.kind === 'trpg'" :size="22" /><MessageCircleMore v-else :size="22" /></span><span><strong>{{ entry.title }}</strong><small class="mobile-dialogue-preview">{{ kindLabel(entry.kind) }} · {{ entry.preview }}</small></span><time>{{ activityLabel(entry.time) }}</time></button>
      <p v-if="!visibleDialogueEntries.length" class="mobile-v1-notice">还没有{{ dialogueFilter === 'all' ? '对话' : kindLabel(dialogueFilter) }}，添加角色或新建会话后显示在这里。</p>
    </div>
    <BaseDialog v-model="menuOpen" title="世界操作" content-class="mobile-v1-menu"><button class="mobile-v1-row" @click="mobileAction('newConversation')"><Plus :size="20" /><span><strong>新建群聊或跑团</strong></span><ChevronRight :size="16" /></button><button class="mobile-v1-row" @click="mobileAction('addCharacter')"><UsersRound :size="20" /><span><strong>添加角色</strong></span><ChevronRight :size="16" /></button><button class="mobile-v1-row" @click="mobileAction('settings')"><Settings2 :size="20" /><span><strong>世界设置</strong><small>常规、设定与数据</small></span><ChevronRight :size="16" /></button></BaseDialog>
    <BaseDialog v-model="saveOpen" title="世界存档" mobile-presentation="page"><div class="mobile-v1-intro"><h1>保存这一刻</h1><p>一个世界，一个存档槽。</p></div><section v-if="worldSave" class="mobile-v1-card"><span class="eyebrow">{{ activityLabel(worldSave.savedAt) }}</span><h3>{{ worldSave.remark || '未填写备注' }}</h3><p class="mobile-v1-prose">包含当时的角色状态、聊天与世界事件。</p><button class="button secondary mobile-v1-wide" @click="emit('load')">查看读取预览</button></section><p v-else class="mobile-v1-notice">当前还没有存档。保存后可以回到这一刻。</p><p class="mobile-v1-notice">再次保存会覆盖现有存档。读取前可核对时间、备注与角色好感。</p><template #footer><button class="button primary" @click="emit('save')"><Save :size="16" />{{ worldSave ? '覆盖当前存档' : '创建存档' }}</button></template></BaseDialog>
  </main>
  <main v-else class="world-home">
    <header class="workspace-banner" :style="world.image ? { backgroundImage: `linear-gradient(90deg, rgba(25,27,29,.92), rgba(25,27,29,.38)), url(${world.image})` } : {}">
      <div><h1>{{ world.name }}</h1><p>{{ characters.length }} 位角色 · {{ conversations.filter((item) => item.status === 'active').length }} 个进行中的会话</p></div>
      <button class="button glass" @click="emit('settings')"><Settings2 :size="17" />世界设置</button>
    </header>
    <div class="world-dashboard">
      <section class="dashboard-main dialogue-hub">
        <header class="dialogue-hub-heading">
          <div><h2>对话中心</h2><p>单聊、群聊和跑团按最近互动排列。</p></div>
          <div class="dialogue-hub-actions"><button class="button secondary" @click="emit('addCharacter')"><Plus :size="16" />添加角色</button><button class="button primary" @click="emit('newConversation')"><Plus :size="16" />新建群聊或跑团</button></div>
        </header>

        <div class="dialogue-filter" role="tablist" aria-label="对话类型">
          <button v-for="option in filterOptions" :key="option.value" role="tab" :aria-selected="dialogueFilter === option.value" :class="{ active: dialogueFilter === option.value }" @click="dialogueFilter = option.value"><span>{{ option.label }}</span><em>{{ option.count }}</em></button>
        </div>

        <div v-if="visibleDialogueEntries.length" class="dialogue-entry-grid">
          <article v-for="entry in visibleDialogueEntries" :key="entry.key" class="dialogue-entry-card" :class="`dialogue-entry-${entry.kind}`">
            <button class="dialogue-entry-main" @click="openDialogue(entry)">
              <span class="dialogue-entry-visual" :style="entry.kind === 'direct' && entry.image ? { backgroundImage: `url(${entry.image})` } : {}">
                <template v-if="entry.kind === 'direct'">{{ entry.image ? '' : entry.title.slice(0, 1) }}</template>
                <MessageCircleMore v-else-if="entry.kind === 'group'" :size="20" />
                <Dices v-else :size="20" />
              </span>
              <span class="dialogue-entry-copy">
                <span class="dialogue-entry-overline"><em>{{ kindLabel(entry.kind) }}</em><small>{{ activityLabel(entry.time) }}</small></span>
                <strong>{{ entry.title }}</strong>
                <p>{{ entry.preview }}</p>
                <span class="dialogue-entry-meta"><small v-if="entry.kind === 'direct'">好感 {{ entry.favor }}</small><small v-else :class="{ closed: entry.status === 'closed' }">{{ entry.archivedAt ? '已归档 · 可重读' : entry.completionStatus === 'ready' ? '已归档 · 查看回顾' : entry.completionStatus ? '待生成总结' : entry.status === 'active' ? '进行中' : '已结束' }}</small></span>
              </span>
              <ChevronRight :size="17" />
            </button>
            <button v-if="entry.kind === 'direct'" class="icon-button dialogue-entry-edit" title="编辑角色设置" aria-label="编辑角色设置" @click="emit('editCharacter', entry.id)"><Pencil :size="14" /></button>
          </article>
        </div>
        <div v-else class="dialogue-hub-empty">
          <UsersRound :size="25" />
          <strong>{{ dialogueFilter === 'all' ? '还没有对话入口' : `还没有${filterOptions.find((item) => item.value === dialogueFilter)?.label}` }}</strong>
          <p>{{ dialogueFilter === 'direct' ? '添加角色后即可开始单聊。' : dialogueFilter === 'group' || dialogueFilter === 'trpg' ? '建立会话后会显示在这里。' : '添加角色后即可开始单聊并建立会话。' }}</p>
        </div>
      </section>
      <aside class="snapshot-card"><span class="snapshot-icon"><Archive :size="22" /></span><h3>世界存档</h3><p v-if="worldSave">{{ worldSave.remark || '未填写备注' }}<small>{{ worldSave.savedAt || '存档时间未知' }}</small></p><p v-else>尚未建立存档。存档包含角色状态、单聊、群聊和世界事件。</p><div><button class="button secondary" @click="emit('save')"><Save :size="16" />{{ worldSave ? '覆盖存档' : '创建存档' }}</button><button class="button ghost" :disabled="!worldSave" @click="emit('load')">读取存档</button></div></aside>
    </div>
  </main>
</template>

<style scoped>
@media (max-width: 767px) {
  .mobile-world-cover { margin: -18px -18px 7px; padding: 28px 22px 24px; color: #fffefa; background: linear-gradient(115deg,#24433d,#3f594c); background-size: cover; background-position: center; }
  .mobile-world-cover h1 { font-size: 28px; line-height: 1.35; font-weight: 600; margin: 12px 0 6px; overflow-wrap: anywhere; }
  .mobile-world-cover p, .mobile-world-cover .eyebrow { color: #c8d3c6; font-size: 12px; font-weight: 400; }
  .mobile-world-cover p { line-height: 1.8; margin: 8px 0 0; }
  .mobile-world-filters { display: flex; gap: 3px; padding: 3px; background: #e8e7df; border-radius: 10px; margin: 12px 0 16px; }
  .mobile-world-filters button { flex: 1; min-height: 40px; border: 0; border-radius: 8px; background: transparent; color: #72786e; font-size: 13px; }
  .mobile-world-filters button[aria-selected=true] { background: var(--surface-strong); color: var(--pine); font-weight: 600; }
  .mobile-v1-row time { max-width: 65px; flex-shrink: 0; font-size: 11px; color: var(--muted); }
  .mobile-v1-row .mobile-dialogue-preview { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .mobile-v1-avatar.trpg { background: #f0e0e2; color: var(--wine); }
  .mobile-v1-avatar.group { background: #f1e5d4; color: #87602f; }
  .mobile-add-character { margin-top: 20px; }
}
</style>
