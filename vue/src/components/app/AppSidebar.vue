<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, Compass, Refresh, Setting, User } from '@element-plus/icons-vue'
import type { UserCharacter, UserWorld } from '@/api/types'
import type { SidebarMode } from '@/types/ui'
import { favorTone, firstText, imageStyle } from '@/utils/ui'

const props = defineProps<{
  sidebarMode: SidebarMode
  session: { username: string }
  isLoggedIn: boolean
  hasSelectedWorld: boolean
  selectedWorldName: string
  loading: { worlds: boolean; characters: boolean }
  userWorlds: UserWorld[]
  characters: UserCharacter[]
  isWorldActive: (world: UserWorld) => boolean
  isCharacterActive: (character: UserCharacter) => boolean
}>()

const emit = defineEmits<{
  'update:sidebarMode': [value: SidebarMode]
  openWorldOverview: []
  loadWorlds: []
  refreshCurrentWorld: []
  selectWorld: [world: UserWorld]
  selectCharacter: [character: UserCharacter]
  openAccountSettings: []
  openPasswordSettings: []
  logout: []
  login: []
}>()

const localSidebarMode = computed({
  get: () => props.sidebarMode,
  set: (value) => emit('update:sidebarMode', value),
})
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark">G</div>
      <div>
        <h1>GalChat</h1>
        <p>角色世界会话台</p>
      </div>
    </div>

    <el-radio-group v-model="localSidebarMode" class="mode-switch" size="large">
      <el-radio-button value="worlds">
        <el-icon><Compass /></el-icon>
        世界
      </el-radio-button>
      <el-radio-button value="characters" :disabled="!hasSelectedWorld" @click="emit('openWorldOverview')">
        <el-icon><User /></el-icon>
        角色
      </el-radio-button>
    </el-radio-group>

    <div class="sidebar-scroll">
      <template v-if="sidebarMode === 'worlds'">
        <div class="sidebar-title">
          <span>已有世界</span>
          <el-button :icon="Refresh" text circle @click="emit('loadWorlds')" />
        </div>

        <button
          v-for="world in userWorlds"
          :key="world.id"
          class="nav-item"
          :class="{ active: isWorldActive(world) }"
          @click="emit('selectWorld', world)"
        >
          <span class="avatar" :style="imageStyle(world.image)">
            <span v-if="!world.image">{{ firstText(world.name) }}</span>
          </span>
          <span class="nav-copy">
            <strong>{{ world.name }}</strong>
            <small>进入角色选择</small>
          </span>
        </button>

        <el-empty
          v-if="!loading.worlds && userWorlds.length === 0"
          description="还没有创建世界"
          :image-size="72"
        />
      </template>

      <template v-else>
        <button class="back-row" @click="emit('update:sidebarMode', 'worlds')">
          <el-icon><ArrowLeft /></el-icon>
          {{ selectedWorldName }}
        </button>

        <div class="sidebar-title">
          <span>选择角色</span>
          <el-button :icon="Refresh" text circle @click="emit('refreshCurrentWorld')" />
        </div>

        <button
          v-for="character in characters"
          :key="character.characterId"
          class="nav-item character-nav"
          :class="{ active: isCharacterActive(character) }"
          @click="emit('selectCharacter', character)"
        >
          <span class="avatar" :style="imageStyle(character.characterImage)">
            <span v-if="!character.characterImage">{{ firstText(character.characterName) }}</span>
          </span>
          <span class="nav-copy">
            <strong>{{ character.characterName }}</strong>
            <small>{{ character.lastChatContent || '尚未开始对话' }}</small>
          </span>
          <el-tag :type="favorTone(character.favorValue)" size="small" round>
            {{ character.favorValue ?? 0 }}
          </el-tag>
        </button>

        <el-empty
          v-if="!loading.characters && characters.length === 0"
          description="该世界还没有角色"
          :image-size="72"
        />
      </template>
    </div>

    <div class="user-dock">
      <button v-if="isLoggedIn" class="user-card" @click="emit('openAccountSettings')">
        <span class="avatar user-avatar">{{ firstText(session.username) }}</span>
        <span>
          <strong>{{ session.username || '已登录用户' }}</strong>
          <small>账号设置</small>
        </span>
      </button>
      <el-button v-else type="primary" class="login-button" @click="emit('login')">
        登录 / 注册
      </el-button>
      <el-dropdown v-if="isLoggedIn" trigger="click">
        <el-button :icon="Setting" circle />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="emit('openAccountSettings')">账号设置</el-dropdown-item>
            <el-dropdown-item @click="emit('openPasswordSettings')">修改密码</el-dropdown-item>
            <el-dropdown-item divided @click="emit('logout')">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </aside>
</template>
