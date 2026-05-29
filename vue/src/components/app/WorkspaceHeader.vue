<script setup lang="ts">
import { EditPen, House, Plus, Refresh, Setting } from '@element-plus/icons-vue'

defineProps<{
  isWorldSelectionMode: boolean
  hasSelectedWorld: boolean
  hasSelectedCharacter: boolean
  canEditSelectedWorld: boolean
  selectedWorldName: string
  selectedCharacterName: string
}>()

const emit = defineEmits<{
  openWorldDetails: []
  openCreateCharacterTemplate: []
  openCreateCharacter: []
  openWorldSettings: []
  refreshWorkspace: []
}>()
</script>

<template>
  <header class="workspace-header">
    <div>
      <p class="eyebrow">
        <span v-if="isWorldSelectionMode || !hasSelectedWorld">世界入口</span>
        <span v-else-if="!hasSelectedCharacter">世界概览</span>
        <span v-else>实时聊天</span>
      </p>
      <h2>
        <span v-if="isWorldSelectionMode || !hasSelectedWorld">选择或创建一个世界</span>
        <span v-else-if="!hasSelectedCharacter">{{ selectedWorldName }}</span>
        <span v-else>{{ selectedCharacterName }}</span>
      </h2>
    </div>

    <div class="header-actions">
      <el-tag v-if="!isWorldSelectionMode && hasSelectedWorld" effect="plain" round>
        <el-icon><House /></el-icon>
        {{ selectedWorldName }}
      </el-tag>
      <el-button
        v-if="!isWorldSelectionMode && hasSelectedWorld && canEditSelectedWorld"
        :icon="EditPen"
        @click="emit('openWorldDetails')"
      >
        修改世界设定
      </el-button>
      <el-button
        v-if="!isWorldSelectionMode && hasSelectedWorld && canEditSelectedWorld"
        :icon="Plus"
        @click="emit('openCreateCharacterTemplate')"
      >
        创建角色模板
      </el-button>
      <el-button
        v-if="!isWorldSelectionMode && hasSelectedWorld"
        :icon="Plus"
        type="primary"
        @click="emit('openCreateCharacter')"
      >
        创建新角色
      </el-button>
      <el-tooltip
        v-if="!isWorldSelectionMode && hasSelectedWorld && !hasSelectedCharacter"
        content="世界设置"
        placement="bottom"
      >
        <el-button :icon="Setting" circle @click="emit('openWorldSettings')" />
      </el-tooltip>
      <el-button v-else :icon="Refresh" circle @click="emit('refreshWorkspace')" />
    </div>
  </header>
</template>
