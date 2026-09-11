<script setup lang="ts">
import { computed } from 'vue'
import { BookOpen, Cpu, Dices, LogOut, MessageCircle, MessagesSquare, MoreHorizontal, Plus, Settings, Sparkles, UserRound } from '@lucide/vue'
import {
  DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuSeparator, DropdownMenuTrigger,
  ScrollAreaRoot, ScrollAreaScrollbar, ScrollAreaThumb, ScrollAreaViewport,
} from 'reka-ui'
import type { Character, Conversation, Session, UserWorld } from '@/api/types'

const props = defineProps<{ session: Session; worlds: UserWorld[]; characters: Character[]; conversations: Conversation[]; selectedWorldId: number | null; selectedCharacterId: number | null; selectedConversationId: number | null; moduleLibraryActive?: boolean; loading: boolean }>()
const emit = defineEmits<{ selectWorld: [id: number]; selectDirect: [id: number]; selectConversation: [id: number]; home: []; modules: []; newWorld: []; account: []; password: []; models: []; logout: [] }>()

const recentDirect = computed(() => latestItem(props.characters, (item) => item.lastChatTime))
const recentGroup = computed(() => latestItem(props.conversations.filter((item) => item.mode === 'chat'), (item) => item.lastChatTime || item.updatedAt))
const recentTrpg = computed(() => latestItem(props.conversations.filter((item) => item.mode === 'trpg'), (item) => item.lastChatTime || item.updatedAt))

function latestItem<T>(items: T[], timeOf: (item: T) => string | undefined) {
  return [...items].sort((left, right) => timestamp(timeOf(right)) - timestamp(timeOf(left)))[0] || null
}

function timestamp(value?: string) {
  if (!value) return 0
  const result = Date.parse(value)
  return Number.isNaN(result) ? 0 : result
}

function activityLabel(value?: string) {
  if (!value) return '尚未开始'
  const time = timestamp(value)
  if (!time) return value
  const minutes = Math.floor((Date.now() - time) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  const date = new Date(time)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}
</script>

<template>
  <aside class="sidebar">
    <button class="brand" @click="emit('home')"><span class="brand-glyph"><Sparkles :size="18" /></span><span><strong>GalChat</strong><small>群像叙事工作台</small></span></button>
    <button class="sidebar-module-entry" :class="{ active: moduleLibraryActive }" @click="emit('modules')"><span class="recent-entry-icon recent-trpg"><BookOpen :size="16" /></span><span><strong>模组库</strong><small>管理 CoC 跑团内容</small></span></button>
    <div class="sidebar-heading"><span>我的世界</span><button class="icon-button subtle" title="新建世界" @click="emit('newWorld')"><Plus :size="16" /></button></div>
    <ScrollAreaRoot class="sidebar-scroll">
      <ScrollAreaViewport class="sidebar-viewport">
        <button v-for="world in worlds" :key="world.id" class="sidebar-item" :class="{ active: selectedWorldId === world.id }" @click="emit('selectWorld', world.id)">
          <span class="mini-cover" :style="world.image ? { backgroundImage: `url(${world.image})` } : {}"><BookOpen v-if="!world.image" :size="16" /></span>
          <span class="sidebar-item-copy"><strong>{{ world.name }}</strong><small>{{ world.myWorld === true ? '自有模板' : world.myWorld === false ? '他人模板' : '世界' }}</small></span>
        </button>
        <template v-if="selectedWorldId">
          <div class="sidebar-heading recent-heading"><span>最近互动</span></div>
          <div class="sidebar-recent-list">
            <button v-if="recentDirect" class="sidebar-recent-item recent-direct" :class="{ active: selectedCharacterId === recentDirect.characterId }" @click="emit('selectDirect', recentDirect.characterId)">
              <span class="recent-entry-icon recent-character" :style="recentDirect.characterImage ? { backgroundImage: `url(${recentDirect.characterImage})` } : {}"><UserRound v-if="!recentDirect.characterImage" :size="16" /></span>
              <span class="sidebar-item-copy"><strong>{{ recentDirect.characterName }}</strong><small>单聊 · {{ activityLabel(recentDirect.lastChatTime) }}</small></span>
            </button>
            <div v-else class="sidebar-recent-item recent-empty"><span class="recent-entry-icon recent-character"><UserRound :size="16" /></span><span class="sidebar-item-copy"><strong>暂无最近单聊</strong><small>选择角色后开始互动</small></span></div>

            <button v-if="recentGroup" class="sidebar-recent-item recent-group" :class="{ active: selectedConversationId === recentGroup.id }" @click="emit('selectConversation', recentGroup.id)">
              <span class="recent-entry-icon"><MessagesSquare :size="16" /></span><span class="sidebar-item-copy"><strong>{{ recentGroup.title }}</strong><small>群聊 · {{ activityLabel(recentGroup.lastChatTime || recentGroup.updatedAt) }}</small></span>
            </button>
            <div v-else class="sidebar-recent-item recent-empty recent-group"><span class="recent-entry-icon"><MessagesSquare :size="16" /></span><span class="sidebar-item-copy"><strong>暂无最近群聊</strong><small>在主界面建立会话</small></span></div>

            <button v-if="recentTrpg" class="sidebar-recent-item recent-trpg" :class="{ active: selectedConversationId === recentTrpg.id }" @click="emit('selectConversation', recentTrpg.id)">
              <span class="recent-entry-icon"><Dices :size="16" /></span><span class="sidebar-item-copy"><strong>{{ recentTrpg.title }}</strong><small>跑团 · {{ activityLabel(recentTrpg.lastChatTime || recentTrpg.updatedAt) }}</small></span>
            </button>
            <div v-else class="sidebar-recent-item recent-empty recent-trpg"><span class="recent-entry-icon"><Dices :size="16" /></span><span class="sidebar-item-copy"><strong>暂无最近跑团</strong><small>在主界面建立跑团</small></span></div>
          </div>
        </template>
      </ScrollAreaViewport>
      <ScrollAreaScrollbar orientation="vertical" class="scrollbar"><ScrollAreaThumb class="scrollbar-thumb" /></ScrollAreaScrollbar>
    </ScrollAreaRoot>
    <DropdownMenuRoot>
      <DropdownMenuTrigger class="profile-dock">
        <span class="avatar avatar-user"><UserRound :size="18" /></span><span><strong>{{ session.username || '旅人' }}</strong><small>管理账号</small></span><MoreHorizontal :size="18" />
      </DropdownMenuTrigger>
      <DropdownMenuPortal><DropdownMenuContent class="menu-content" :side-offset="8" align="start">
        <DropdownMenuItem class="menu-item" @select="emit('account')"><Settings :size="16" />账号资料</DropdownMenuItem>
        <DropdownMenuItem class="menu-item" @select="emit('password')"><MessageCircle :size="16" />修改密码</DropdownMenuItem>
        <DropdownMenuItem class="menu-item" @select="emit('models')"><Cpu :size="16" />模型管理</DropdownMenuItem>
        <DropdownMenuSeparator class="menu-separator" />
        <DropdownMenuItem class="menu-item danger" @select="emit('logout')"><LogOut :size="16" />退出登录</DropdownMenuItem>
      </DropdownMenuContent></DropdownMenuPortal>
    </DropdownMenuRoot>
  </aside>
</template>
