<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Check, Close, Delete, Edit } from '@element-plus/icons-vue'
import type { ActiveStory, UserCharacter } from '@/api/types'
import { firstText, formatTime, imageStyle } from '@/utils/ui'

const props = defineProps<{
  collapsed: boolean
  selectedCharacter: UserCharacter | null
  selectedWorldName: string
  activeStory: ActiveStory | null
  promptSaving: boolean
  favorSaving: boolean
  canEditFavor: boolean
}>()

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  'update-user-info-prompt': [value: string]
  'update-favor-value': [value: number]
  'open-delete-character': []
}>()

const localCollapsed = computed({
  get: () => props.collapsed,
  set: (value) => emit('update:collapsed', value),
})

const promptEditing = ref(false)
const userInfoPromptDraft = ref('')
const favorEditing = ref(false)
const favorDraft = ref<number | undefined>(0)

watch(
  () => props.selectedCharacter,
  (character) => {
    promptEditing.value = false
    userInfoPromptDraft.value = character?.userInfoPrompt || ''
    favorEditing.value = false
    favorDraft.value = character?.favorValue ?? 0
  },
  { immediate: true },
)

watch(
  () => props.favorSaving,
  (saving, wasSaving) => {
    if (wasSaving && !saving) {
      favorEditing.value = false
      favorDraft.value = props.selectedCharacter?.favorValue ?? 0
    }
  },
)

watch(
  () => props.promptSaving,
  (saving, wasSaving) => {
    if (wasSaving && !saving) {
      promptEditing.value = false
      userInfoPromptDraft.value = props.selectedCharacter?.userInfoPrompt || ''
    }
  },
)

function startPromptEditing() {
  userInfoPromptDraft.value = props.selectedCharacter?.userInfoPrompt || ''
  promptEditing.value = true
}

function startFavorEditing() {
  favorDraft.value = props.selectedCharacter?.favorValue ?? 0
  favorEditing.value = true
}

function cancelFavorEditing() {
  favorDraft.value = props.selectedCharacter?.favorValue ?? 0
  favorEditing.value = false
}

function saveFavor() {
  if (typeof favorDraft.value === 'number') {
    emit('update-favor-value', favorDraft.value)
  }
}

function cancelPromptEditing() {
  userInfoPromptDraft.value = props.selectedCharacter?.userInfoPrompt || ''
  promptEditing.value = false
}

function savePrompt() {
  emit('update-user-info-prompt', userInfoPromptDraft.value)
}
</script>

<template>
  <aside
    v-if="selectedCharacter"
    class="character-panel"
    :class="{ collapsed: localCollapsed }"
  >
    <button class="collapse-button" @click="localCollapsed = !localCollapsed">
      <el-icon>
        <ArrowRight v-if="!localCollapsed" />
        <ArrowLeft v-else />
      </el-icon>
    </button>

    <div v-if="!localCollapsed" class="character-panel-content">
      <span class="avatar portrait" :style="imageStyle(selectedCharacter.characterImage)">
        <span v-if="!selectedCharacter.characterImage">
          {{ firstText(selectedCharacter.characterName) }}
        </span>
      </span>
      <h3>{{ selectedCharacter.characterName }}</h3>
      <p class="panel-muted">{{ selectedWorldName }}</p>

      <div class="panel-stat">
        <span>好感度</span>
        <div v-if="favorEditing" class="favor-edit-row">
          <el-input-number
            v-model="favorDraft"
            :min="0"
            :max="100"
            :step="1"
            step-strictly
            size="small"
          />
          <el-button :icon="Close" text circle @click="cancelFavorEditing" />
          <el-button
            type="primary"
            :icon="Check"
            :loading="favorSaving"
            text
            circle
            @click="saveFavor"
          />
        </div>
        <div v-else class="favor-value-row">
          <strong>{{ selectedCharacter.favorValue ?? 0 }}</strong>
          <el-button
            v-if="canEditFavor"
            :icon="Edit"
            text
            circle
            @click="startFavorEditing"
          />
        </div>
      </div>
      <el-progress
        :percentage="Math.max(0, Math.min(100, selectedCharacter.favorValue ?? 0))"
        :stroke-width="10"
      />

      <div class="info-block prompt-info-block">
        <div class="info-block-heading">
          <h4>用户信息提示词</h4>
          <el-button
            v-if="!promptEditing"
            :icon="Edit"
            text
            circle
            @click="startPromptEditing"
          />
        </div>
        <template v-if="promptEditing">
          <el-input
            v-model="userInfoPromptDraft"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            resize="none"
            placeholder="写下该角色需要记住的用户信息"
          />
          <div class="prompt-actions">
            <el-button :icon="Close" @click="cancelPromptEditing">取消</el-button>
            <el-button
              type="primary"
              :icon="Check"
              :loading="promptSaving"
              @click="savePrompt"
            >
              保存
            </el-button>
          </div>
        </template>
        <p v-else class="user-info-prompt-preview">
          {{ selectedCharacter.userInfoPrompt || '暂无用户信息提示词' }}
        </p>
      </div>

      <div class="info-block">
        <h4>最近对话</h4>
        <p class="recent-chat-content">{{ selectedCharacter.lastChatContent || '暂无最近对话' }}</p>
        <small>{{ formatTime(selectedCharacter.lastChatTime) }}</small>
      </div>

      <div class="info-block">
        <h4>当前事件</h4>
        <p>{{ activeStory?.storyEvent.title || '暂无进行中的事件' }}</p>
        <small>{{ activeStory?.storyEvent.currentScene }}</small>
      </div>

      <div class="character-danger-zone">
        <el-button
          class="danger-full-button"
          type="danger"
          :icon="Delete"
          @click="emit('open-delete-character')"
        >
          删除该角色
        </el-button>
      </div>
    </div>
  </aside>
</template>
