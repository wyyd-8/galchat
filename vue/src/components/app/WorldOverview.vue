<script setup lang="ts">
import { ArrowRight, Plus } from '@element-plus/icons-vue'
import type { ActiveStory, StoryListItem, UserCharacter } from '@/api/types'
import { firstText, imageStyle } from '@/utils/ui'

defineProps<{
  characters: UserCharacter[]
  stories: StoryListItem[]
  activeStory: ActiveStory | null
  averageFavor: number
  activeStoryTitle: string
  selectedStoryCharacterIds: Set<number>
}>()

const emit = defineEmits<{
  selectCharacter: [character: UserCharacter]
  openStartStory: []
  openAdvanceStory: []
  openEndStory: []
  openStoryDetail: [storyEventId: number]
}>()
</script>

<template>
  <section class="overview-stage">
    <div class="metric-strip">
      <article>
        <span>角色数</span>
        <strong>{{ characters.length }}</strong>
      </article>
      <article>
        <span>平均好感</span>
        <strong>{{ averageFavor }}</strong>
      </article>
      <article>
        <span>故事事件</span>
        <strong>{{ stories.length }}</strong>
      </article>
      <article>
        <span>进行中</span>
        <strong>{{ activeStory ? 1 : 0 }}</strong>
      </article>
    </div>

    <div class="overview-grid">
      <div class="section-panel">
        <div class="section-heading">
          <div>
            <h3>角色好感度概况</h3>
            <p>选择右侧角色后进入聊天主窗口。</p>
          </div>
        </div>

        <div class="favor-list">
          <button
            v-for="character in characters"
            :key="character.characterId"
            class="favor-row"
            @click="emit('selectCharacter', character)"
          >
            <span class="avatar" :style="imageStyle(character.characterImage)">
              <span v-if="!character.characterImage">{{ firstText(character.characterName) }}</span>
            </span>
            <span class="favor-copy">
              <strong>{{ character.characterName }}</strong>
              <el-progress
                :percentage="Math.max(0, Math.min(100, character.favorValue ?? 0))"
                :stroke-width="8"
                :show-text="false"
              />
            </span>
            <span class="favor-number">{{ character.favorValue ?? 0 }}</span>
          </button>
        </div>
      </div>

      <div class="section-panel">
        <div class="section-heading">
          <div>
            <h3>事件总览</h3>
            <p>{{ activeStoryTitle }}</p>
          </div>
          <div class="story-actions">
            <el-button :icon="Plus" type="primary" @click="emit('openStartStory')">开启新事件</el-button>
            <el-button :icon="ArrowRight" :disabled="!activeStory" @click="emit('openAdvanceStory')">
              推进事件
            </el-button>
          </div>
        </div>

        <article
          v-if="activeStory"
          class="active-story"
          role="button"
          tabindex="0"
          @click="emit('openStoryDetail', activeStory.storyEvent.id)"
          @keydown.enter.prevent="emit('openStoryDetail', activeStory.storyEvent.id)"
        >
          <div class="active-story-header">
            <el-tag type="success" effect="dark" round>进行中</el-tag>
            <el-button type="danger" plain size="small" @click.stop="emit('openEndStory')">结束事件</el-button>
          </div>
          <h4>{{ activeStory.storyEvent.title }}</h4>
          <p>{{ activeStory.storyEvent.currentScene || activeStory.storyEvent.opening }}</p>
          <div class="story-characters">
            <el-tag
              v-for="character in characters.filter((item) => selectedStoryCharacterIds.has(item.characterId))"
              :key="character.characterId"
              round
            >
              {{ character.characterName }}
            </el-tag>
          </div>
        </article>

        <div class="story-list">
          <button
            v-for="story in stories"
            :key="story.id"
            class="story-row"
            @click="emit('openStoryDetail', story.id)"
          >
            <span>{{ story.title }}</span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
