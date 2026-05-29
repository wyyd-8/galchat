<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Check, Close, Edit } from '@element-plus/icons-vue'
import type { ActiveStory, UserCharacter } from '@/api/types'
import { firstText, formatTime, imageStyle } from '@/utils/ui'

const props = defineProps<{
  collapsed: boolean
  selectedCharacter: UserCharacter | null
  selectedWorldName: string
  activeStory: ActiveStory | null
  promptSaving: boolean
}>()

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  'update-user-info-prompt': [value: string]
}>()

const localCollapsed = computed({
  get: () => props.collapsed,
  set: (value) => emit('update:collapsed', value),
})

const promptEditing = ref(false)
const userInfoPromptDraft = ref('')

watch(
  () => props.selectedCharacter,
  (character) => {
    promptEditing.value = false
    userInfoPromptDraft.value = character?.userInfoPrompt || ''
  },
  { immediate: true },
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

    <template v-if="!localCollapsed">
      <span class="avatar portrait" :style="imageStyle(selectedCharacter.characterImage)">
        <span v-if="!selectedCharacter.characterImage">
          {{ firstText(selectedCharacter.characterName) }}
        </span>
      </span>
      <h3>{{ selectedCharacter.characterName }}</h3>
      <p class="panel-muted">{{ selectedWorldName }}</p>

      <div class="panel-stat">
        <span>好感度</span>
        <strong>{{ selectedCharacter.favorValue ?? 0 }}</strong>
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
    </template>
  </aside>
</template>
