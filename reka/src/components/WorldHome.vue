<script setup lang="ts">
import { Archive, ChevronRight, MessageCircleMore, Pencil, Plus, Save, Settings2, UsersRound } from '@lucide/vue'
import type { Character, Conversation, UserWorld, WorldSave } from '@/api/types'

defineProps<{ world: UserWorld; characters: Character[]; conversations: Conversation[]; worldSave: WorldSave | null }>()
const emit = defineEmits<{ openCharacter: [id: number]; openConversation: [id: number]; newConversation: []; addCharacter: []; editCharacter: [id: number]; save: []; load: []; settings: [] }>()
</script>

<template>
  <main class="world-home">
    <header class="workspace-banner" :style="world.image ? { backgroundImage: `linear-gradient(90deg, rgba(25,27,29,.92), rgba(25,27,29,.38)), url(${world.image})` } : {}">
      <div><span class="eyebrow light">CURRENT WORLD</span><h1>{{ world.name }}</h1><p>{{ characters.length }} 位角色 · {{ conversations.filter((item) => item.status === 'active').length }} 个进行中的群聊</p></div>
      <button class="button glass" @click="emit('settings')"><Settings2 :size="17" />世界设置</button>
    </header>
    <div class="world-dashboard">
      <section class="dashboard-main">
        <div class="section-title"><div><span class="eyebrow">CAST</span><h2>世界角色</h2></div><button class="button secondary" @click="emit('addCharacter')"><Plus :size="16" />添加角色</button></div>
        <div v-if="characters.length" class="character-grid">
          <article v-for="character in characters" :key="character.characterId" class="character-card">
            <button class="character-card-main" @click="emit('openCharacter', character.characterId)"><span class="character-avatar" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span><span><strong>{{ character.characterName }}</strong><small>{{ character.lastChatContent || `好感 ${character.favorValue ?? 0}` }}</small></span><ChevronRight :size="17" /></button>
            <button class="icon-button character-edit-button" title="编辑角色资料" @click="emit('editCharacter', character.characterId)"><Pencil :size="14" /></button>
          </article>
        </div><div v-else class="empty-panel compact"><UsersRound :size="24" /><p>添加角色后即可建立群聊。</p></div>
        <div class="section-title conversation-title"><div><span class="eyebrow">CONVERSATIONS</span><h2>群聊记录</h2></div><button class="button primary" :disabled="characters.length === 0" @click="emit('newConversation')"><Plus :size="16" />新建群聊</button></div>
        <div class="conversation-list"><button v-for="conversation in conversations" :key="conversation.id" class="conversation-row" @click="emit('openConversation', conversation.id)"><span class="conversation-icon"><MessageCircleMore :size="19" /></span><span><strong>{{ conversation.title }}</strong><small>{{ conversation.mode === 'trpg' ? '跑团模式' : '普通群聊' }} · {{ conversation.status === 'active' ? '进行中' : '已结束' }}</small></span><ChevronRight :size="18" /></button></div>
      </section>
      <aside class="snapshot-card"><span class="snapshot-icon"><Archive :size="22" /></span><span class="eyebrow">WORLD SNAPSHOT</span><h3>世界存档</h3><p v-if="worldSave">{{ worldSave.remark || '未填写备注' }}<small>{{ worldSave.savedAt || '最近存档' }}</small></p><p v-else>尚未建立存档。保存后可回到角色好感与世界状态。</p><div><button class="button secondary" @click="emit('save')"><Save :size="16" />保存</button><button class="button ghost" :disabled="!worldSave" @click="emit('load')">读取</button></div></aside>
    </div>
  </main>
</template>
