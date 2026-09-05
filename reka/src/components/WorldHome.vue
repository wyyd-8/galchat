<script setup lang="ts">
import { computed, ref } from 'vue'
import { Archive, ChevronRight, Dices, MessageCircleMore, Pencil, Plus, Save, Settings2, UsersRound } from '@lucide/vue'
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
}

const props = defineProps<{ world: UserWorld; characters: Character[]; conversations: Conversation[]; worldSave: WorldSave | null }>()
const emit = defineEmits<{ openCharacter: [id: number]; openConversation: [id: number]; newConversation: []; addCharacter: []; editCharacter: [id: number]; save: []; load: []; settings: [] }>()
const dialogueFilter = ref<DialogueFilter>('all')

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
    preview: conversation.lastChatContent || conversation.summary || (conversation.status === 'active' ? '等待下一次互动' : '会话已结束'),
    time: conversation.lastChatTime || conversation.updatedAt,
    status: conversation.status,
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
  <main class="world-home">
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
                <span class="dialogue-entry-meta"><small v-if="entry.kind === 'direct'">好感 {{ entry.favor }}</small><small v-else :class="{ closed: entry.status === 'closed' }">{{ entry.status === 'active' ? '进行中' : '已关闭' }}</small></span>
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
