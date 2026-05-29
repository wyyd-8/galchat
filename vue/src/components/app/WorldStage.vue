<script setup lang="ts">
import { ArrowRight, Plus } from '@element-plus/icons-vue'
import type { UserWorld, WorldTemplate } from '@/api/types'
import { firstText, imageStyle } from '@/utils/ui'

defineProps<{
  loading: { worlds: boolean }
  userWorlds: UserWorld[]
  worldTemplates: WorldTemplate[]
}>()

const emit = defineEmits<{
  selectWorld: [world: UserWorld]
  openCreateWorld: [template?: WorldTemplate]
}>()
</script>

<template>
  <section class="world-stage">
    <div class="section-panel">
      <div class="section-heading">
        <div>
          <h3>已有世界</h3>
          <p>继续你的世界、角色关系和剧情进度。</p>
        </div>
        <el-button :icon="Plus" type="primary" @click="emit('openCreateWorld')">创建世界</el-button>
      </div>

      <div class="world-grid">
        <button
          v-for="world in userWorlds"
          :key="world.id"
          class="world-card"
          @click="emit('selectWorld', world)"
        >
          <span class="world-cover" :style="imageStyle(world.image)">
            <span v-if="!world.image">{{ firstText(world.name) }}</span>
          </span>
          <span>
            <strong>{{ world.name }}</strong>
            <small>选择后进入角色列表</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <el-empty
        v-if="!loading.worlds && userWorlds.length === 0"
        description="你还没有自己的世界"
      />
    </div>

    <div class="section-panel">
      <div class="section-heading">
        <div>
          <h3>可创建世界</h3>
          <p>从后端世界模板创建用户世界。</p>
        </div>
      </div>

      <div class="template-list">
        <article v-for="template in worldTemplates" :key="template.id" class="template-row">
          <span class="avatar large" :style="imageStyle(template.image)">
            <span v-if="!template.image">{{ firstText(template.name) }}</span>
          </span>
          <div>
            <h4>{{ template.name }}</h4>
            <p>{{ template.background || '暂无背景简介，创建后可在后端补充世界详情。' }}</p>
          </div>
          <el-button type="primary" plain @click="emit('openCreateWorld', template)">
            创建
          </el-button>
        </article>
      </div>
    </div>
  </section>
</template>
