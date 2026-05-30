<script setup lang="ts">
import type { ComponentPublicInstance } from 'vue'
import { ref } from 'vue'
import { ChatDotRound, RefreshLeft } from '@element-plus/icons-vue'
import type { UiMessage } from '@/types/ui'

const messageInput = defineModel<string>('messageInput', { required: true })
const messageScroller = defineModel<HTMLElement | null>('messageScroller', { required: true })
const composerComposing = ref(false)

defineProps<{
  loading: { history: boolean; sending: boolean }
  messageList: UiMessage[]
  selectedCharacterName: string
  canWithdrawMessage: boolean
}>()

const emit = defineEmits<{
  handleComposerFocus: []
  handleComposerCompositionChange: [isComposing: boolean, value: string]
  sendMessage: []
  withdrawMessage: []
}>()

function bindMessageScroller(element: Element | ComponentPublicInstance | null) {
  messageScroller.value = element instanceof HTMLElement ? element : null
}

function handleCompositionChange(isComposing: boolean, event: CompositionEvent) {
  composerComposing.value = isComposing
  const target = event.target
  const value = target instanceof HTMLTextAreaElement || target instanceof HTMLInputElement
    ? target.value
    : messageInput.value
  emit('handleComposerCompositionChange', isComposing, value)
}

function handleComposerEnter(event: KeyboardEvent) {
  if (composerComposing.value || event.isComposing || event.keyCode === 229) {
    return
  }

  event.preventDefault()
  emit('sendMessage')
}
</script>

<template>
  <section class="chat-stage">
    <div class="chat-window">
      <div :ref="bindMessageScroller" class="messages" v-loading="loading.history">
        <div v-if="messageList.length === 0" class="empty-chat">
          <el-icon><ChatDotRound /></el-icon>
          <h3>和 {{ selectedCharacterName }} 开始对话</h3>
          <p>角色会结合世界背景、历史记忆、剧情事件和好感度回应。</p>
        </div>

        <article
          v-for="message in messageList"
          :key="message.id"
          class="message"
          :class="message.role"
        >
          <div v-if="message.role === 'thinking'" class="thinking-content">
            <p>{{ message.content }}</p>
          </div>
          <div v-else-if="message.role === 'tool'" class="tool-line">
            <span>{{ message.content }}</span>
          </div>
          <div v-else class="message-bubble">
            <p>{{ message.content }}</p>
            <small v-if="message.time">{{ message.time }}</small>
          </div>
        </article>
      </div>

      <div class="composer">
        <el-button
          class="withdraw-button"
          :disabled="!canWithdrawMessage"
          aria-label="撤回上一轮消息"
          title="撤回上一轮消息"
          @click="emit('withdrawMessage')"
        >
          <el-icon><RefreshLeft /></el-icon>
        </el-button>
        <el-input
          v-model="messageInput"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          resize="none"
          placeholder="输入给角色的消息"
          @focus="emit('handleComposerFocus')"
          @compositionstart="handleCompositionChange(true, $event)"
          @compositionend="handleCompositionChange(false, $event)"
          @keydown.enter.exact="handleComposerEnter"
        />
        <el-button type="primary" :loading="loading.sending" @click="emit('sendMessage')">
          发送
        </el-button>
      </div>
    </div>
  </section>
</template>
