<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, DocumentAdd, Plus, RefreshLeft } from '@element-plus/icons-vue'
import type { ActiveStory, StoryListItem, UserCharacter, UserWorldSave } from '@/api/types'
import { firstText, formatTime, imageStyle } from '@/utils/ui'

const props = defineProps<{
  characters: UserCharacter[]
  stories: StoryListItem[]
  activeStory: ActiveStory | null
  worldSave: UserWorldSave | null
  worldSaveLoading: boolean
  worldSaveActionLoading: boolean
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
  saveWorld: [remark: string, done?: (success: boolean) => void]
  loadWorld: [done?: (success: boolean) => void]
}>()

const saveDialogVisible = ref(false)
const loadDialogVisible = ref(false)
const saveRemark = ref('')

const saveTimeLabel = computed(() => formatTime(props.worldSave?.savedAt))
const hasWorldSave = computed(() => Boolean(props.worldSave?.savedAt))

function openSaveDialog() {
  saveRemark.value = props.worldSave?.remark || ''
  saveDialogVisible.value = true
}

function submitSave() {
  emit('saveWorld', saveRemark.value, (success) => {
    if (success) {
      saveDialogVisible.value = false
    }
  })
}

function submitLoad() {
  emit('loadWorld', (success) => {
    if (success) {
      loadDialogVisible.value = false
    }
  })
}
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
      <article class="world-save-metric" v-loading="worldSaveLoading">
        <span>存档/读档</span>
        <div class="save-summary">
          <div>
            <span>存档时间</span>
            <strong>{{ saveTimeLabel || '尚未保存' }}</strong>
          </div>
          <div>
            <span>备注</span>
            <p>{{ worldSave?.remark || '无备注' }}</p>
          </div>
        </div>
        <div class="world-save-actions">
          <el-button
            :icon="DocumentAdd"
            type="primary"
            size="small"
            :loading="worldSaveActionLoading"
            @click="openSaveDialog"
          >
            存档
          </el-button>
          <el-button
            :icon="RefreshLeft"
            size="small"
            :disabled="!hasWorldSave"
            :loading="worldSaveActionLoading"
            @click="loadDialogVisible = true"
          >
            读档
          </el-button>
        </div>
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

    <el-dialog v-model="saveDialogVisible" width="520px" :close-on-click-modal="false">
      <template #header>
        <div class="dialog-title">
          <h3>保存当前世界进度</h3>
          <p>为这次存档添加一个便于识别的备注。</p>
        </div>
      </template>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="备注">
          <el-input
            v-model="saveRemark"
            type="textarea"
            :rows="3"
            maxlength="120"
            show-word-limit
            placeholder="例如：主线推进前、角色分歧点..."
          />
        </el-form-item>
      </el-form>

      <p class="save-warning">
        注意：此功能不能还原存档后的 <strong>删除</strong> 与 <strong>新增</strong> 操作，如希望读档时某个角色被还原，切勿<strong>删除角色</strong>
      </p>
      <p v-if="hasWorldSave" class="save-warning">此操作会删除上一个存档</p>

      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="worldSaveActionLoading" @click="submitSave">确认存档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="loadDialogVisible" width="560px" :close-on-click-modal="false">
      <template #header>
        <div class="dialog-title">
          <h3>读取世界存档</h3>
          <p>{{ saveTimeLabel || '尚未保存' }} · {{ worldSave?.remark || '无备注' }}</p>
        </div>
      </template>

      <p class="save-warning">
        注意：当前操作会丢失存档内角色存档后的 <strong>所有相关内容</strong> 且不可恢复，请确认
      </p>

      <div class="save-detail-list">
        <div
          v-for="favor in worldSave?.characterFavors || []"
          :key="favor.characterId"
          class="save-detail-row"
        >
          <span>{{ favor.characterName }}</span>
          <strong>{{ favor.favorValue ?? 0 }}</strong>
        </div>
        <el-empty
          v-if="!worldSave?.characterFavors?.length"
          description="此存档暂无角色好感快照"
          :image-size="72"
        />
      </div>

      <template #footer>
        <el-button @click="loadDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="worldSaveActionLoading" @click="submitLoad">确认读档</el-button>
      </template>
    </el-dialog>
  </section>
</template>
