<script setup lang="ts">
import { BookOpen, ChevronLeft, LogOut, MessageCircle, MoreHorizontal, Plus, Settings, Sparkles, UserRound } from '@lucide/vue'
import {
  DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuSeparator, DropdownMenuTrigger,
  ScrollAreaRoot, ScrollAreaScrollbar, ScrollAreaThumb, ScrollAreaViewport,
} from 'reka-ui'
import type { Conversation, Session, UserWorld } from '@/api/types'

defineProps<{ session: Session; worlds: UserWorld[]; conversations: Conversation[]; selectedWorldId: number | null; selectedConversationId: number | null; loading: boolean }>()
const emit = defineEmits<{ selectWorld: [id: number]; selectConversation: [id: number]; home: []; newWorld: []; newConversation: []; account: []; password: []; logout: [] }>()
</script>

<template>
  <aside class="sidebar">
    <button class="brand" @click="emit('home')"><span class="brand-glyph"><Sparkles :size="18" /></span><span><strong>GalChat</strong><small>群像叙事工作台</small></span></button>
    <div class="sidebar-heading"><span>我的世界</span><button class="icon-button subtle" title="新建世界" @click="emit('newWorld')"><Plus :size="16" /></button></div>
    <ScrollAreaRoot class="sidebar-scroll">
      <ScrollAreaViewport class="sidebar-viewport">
        <button v-for="world in worlds" :key="world.id" class="sidebar-item" :class="{ active: selectedWorldId === world.id }" @click="emit('selectWorld', world.id)">
          <span class="mini-cover" :style="world.image ? { backgroundImage: `url(${world.image})` } : {}"><BookOpen v-if="!world.image" :size="16" /></span>
          <span class="sidebar-item-copy"><strong>{{ world.name }}</strong><small>{{ world.myWorld ? '原创世界' : '收藏世界' }}</small></span>
        </button>
        <template v-if="selectedWorldId">
          <div class="sidebar-heading conversation-heading"><span>群聊</span><button class="icon-button subtle" title="新建群聊" @click="emit('newConversation')"><Plus :size="16" /></button></div>
          <button v-for="conversation in conversations" :key="conversation.id" class="sidebar-item conversation-item" :class="{ active: selectedConversationId === conversation.id }" @click="emit('selectConversation', conversation.id)">
            <span class="status-dot" :class="conversation.status" /><span class="sidebar-item-copy"><strong>{{ conversation.title }}</strong><small>{{ conversation.mode === 'trpg' ? '跑团' : '群聊' }} · {{ conversation.status === 'active' ? '进行中' : '已结束' }}</small></span>
          </button>
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
        <DropdownMenuSeparator class="menu-separator" />
        <DropdownMenuItem class="menu-item danger" @select="emit('logout')"><LogOut :size="16" />退出登录</DropdownMenuItem>
      </DropdownMenuContent></DropdownMenuPortal>
    </DropdownMenuRoot>
  </aside>
</template>
