<script setup lang="ts">
import {
  Delete,
  Message,
  Plus,
  Upload,
  WarningFilled,
} from '@element-plus/icons-vue'
import AppSidebar from '@/components/app/AppSidebar.vue'
import CharacterPanel from '@/components/app/CharacterPanel.vue'
import ChatStage from '@/components/app/ChatStage.vue'
import WorkspaceHeader from '@/components/app/WorkspaceHeader.vue'
import WorldOverview from '@/components/app/WorldOverview.vue'
import WorldStage from '@/components/app/WorldStage.vue'
import { useGalchatApp } from '@/composables/useGalchatApp'
import {
  firstText,
  formatTime,
  imageStyle,
  storyStatusLabel,
  storyStatusType,
} from '@/utils/ui'

const {
  session,
  sidebarMode,
  authDialogVisible,
  authMode,
  authForm,
  authLoading,
  authCodeLoading,
  authCodeCooldown,
  accountDialogVisible,
  accountLoading,
  passwordDialogVisible,
  passwordLoading,
  passwordCodeLoading,
  passwordCodeCooldown,
  accountForm,
  passwordForm,
  loading,
  worldTemplates,
  userWorlds,
  characters,
  selectedCharacter,
  stories,
  activeStory,
  storyDetailDialogVisible,
  storyDetailLoading,
  selectedStoryDetail,
  characterPanelCollapsed,
  createWorldDialogVisible,
  createWorldStep,
  createWorldForm,
  createTemplateDialogVisible,
  templateImageUploading,
  createTemplateImageFileName,
  createTemplateForm,
  createCharacterDialogVisible,
  createCharacterTemplateDialogVisible,
  characterDeleteDialogVisible,
  characterImageUploading,
  createCharacterImageFileName,
  characterCreating,
  characterPromptSaving,
  characterDeleting,
  characterTemplateLoading,
  addCharacterForm,
  createCharacterForm,
  worldDetailDialogVisible,
  worldDetailLoading,
  worldDetails,
  worldDetailForm,
  worldSettingsDialogVisible,
  worldSettingsLoading,
  worldDeleteDialogVisible,
  worldDeleting,
  worldSettingsForm,
  startStoryDialogVisible,
  advanceStoryDialogVisible,
  endStoryDialogVisible,
  storyActionLoading,
  storyForm,
  storyAdvanceForm,
  storyEndForm,
  messageInput,
  messageList,
  messageScroller,
  isLoggedIn,
  hasSelectedWorld,
  hasSelectedCharacter,
  isWorldSelectionMode,
  canEditSelectedWorld,
  selectedWorldName,
  selectedCharacterName,
  createWorldStepIsLast,
  availableCharacterTemplates,
  availableCharacterTemplateIds,
  selectedAddCharacterTemplate,
  selectedCreateTemplate,
  averageFavor,
  activeStoryTitle,
  selectedStoryCharacterIds,
  canWithdrawLatestMessage,
  isWorldActive,
  isCharacterActive,
  submitAuth,
  switchAuthMode,
  sendRegisterEmailCode,
  sendPasswordEmailCode,
  openAccountSettings,
  openPasswordSettings,
  submitAccountProfile,
  submitPassword,
  loadWorlds,
  refreshCurrentWorld,
  refreshWorkspace,
  selectWorld,
  openWorldOverview,
  openStoryDetail,
  selectCharacter,
  openCreateWorld,
  openCreateTemplate,
  handleTemplateImageChange,
  handleCharacterImageChange,
  submitCreateTemplate,
  openCreateCharacter,
  characterTemplateLabel,
  openCreateCharacterTemplate,
  addFavorabilityRow,
  removeFavorabilityRow,
  submitCreateCharacter,
  updateCharacterPrompt,
  openDeleteCharacterConfirm,
  submitDeleteCharacter,
  submitCreateCharacterTemplate,
  openWorldDetails,
  openWorldSettings,
  submitWorldSettings,
  openDeleteWorldConfirm,
  submitDeleteWorld,
  submitWorldDetail,
  deleteWorldDetail,
  nextCreateWorldStep,
  previousCreateWorldStep,
  submitCreateWorld,
  openStartStory,
  submitStartStory,
  openAdvanceStory,
  submitAdvanceStory,
  openEndStory,
  submitEndStory,
  handleComposerFocus,
  handleComposerCompositionChange,
  sendMessage,
  withdrawLatestMessage,
  logout,
} = useGalchatApp()
</script>

<template>
  <div class="app-shell">
    <AppSidebar
      v-model:sidebar-mode="sidebarMode"
      :session="session"
      :is-logged-in="isLoggedIn"
      :has-selected-world="hasSelectedWorld"
      :selected-world-name="selectedWorldName"
      :loading="loading"
      :user-worlds="userWorlds"
      :characters="characters"
      :is-world-active="isWorldActive"
      :is-character-active="isCharacterActive"
      @open-world-overview="openWorldOverview"
      @load-worlds="loadWorlds"
      @refresh-current-world="refreshCurrentWorld"
      @select-world="selectWorld"
      @select-character="selectCharacter"
      @open-account-settings="openAccountSettings"
      @open-password-settings="openPasswordSettings"
      @logout="logout"
      @login="authDialogVisible = true"
    />

    <main class="workspace" v-loading="loading.app">
      <WorkspaceHeader
        :is-world-selection-mode="isWorldSelectionMode"
        :has-selected-world="hasSelectedWorld"
        :has-selected-character="hasSelectedCharacter"
        :can-edit-selected-world="canEditSelectedWorld"
        :selected-world-name="selectedWorldName"
        :selected-character-name="selectedCharacterName"
        @open-world-details="openWorldDetails"
        @open-world-settings="openWorldSettings"
        @open-create-character-template="openCreateCharacterTemplate"
        @open-create-character="openCreateCharacter"
        @refresh-workspace="refreshWorkspace"
      />

      <WorldStage
        v-if="isWorldSelectionMode || !hasSelectedWorld"
        :loading="loading"
        :user-worlds="userWorlds"
        :world-templates="worldTemplates"
        @select-world="selectWorld"
        @open-create-world="openCreateWorld"
      />

      <WorldOverview
        v-else-if="!hasSelectedCharacter"
        :characters="characters"
        :stories="stories"
        :active-story="activeStory"
        :average-favor="averageFavor"
        :active-story-title="activeStoryTitle"
        :selected-story-character-ids="selectedStoryCharacterIds"
        @select-character="selectCharacter"
        @open-start-story="openStartStory"
        @open-advance-story="openAdvanceStory"
        @open-end-story="openEndStory"
        @open-story-detail="openStoryDetail"
      />

      <ChatStage
        v-else
        v-model:message-input="messageInput"
        v-model:message-scroller="messageScroller"
        :loading="loading"
        :message-list="messageList"
        :selected-character-name="selectedCharacterName"
        :can-withdraw-message="canWithdrawLatestMessage"
        @handle-composer-focus="handleComposerFocus"
        @handle-composer-composition-change="handleComposerCompositionChange"
        @send-message="sendMessage"
        @withdraw-message="withdrawLatestMessage"
      />
    </main>

    <CharacterPanel
      v-if="!isWorldSelectionMode && hasSelectedCharacter"
      v-model:collapsed="characterPanelCollapsed"
      :selected-character="selectedCharacter"
      :selected-world-name="selectedWorldName"
      :active-story="activeStory"
      :prompt-saving="characterPromptSaving"
      @update-user-info-prompt="updateCharacterPrompt"
      @open-delete-character="openDeleteCharacterConfirm"
    />

    <el-dialog v-model="authDialogVisible" width="420px" :close-on-click-modal="false">
      <template #header>
        <div class="dialog-title">
          <h3>{{ authMode === 'login' ? '登录 GalChat' : '注册 GalChat' }}</h3>
          <p>登录后即可读取你的世界、角色和聊天历史。</p>
        </div>
      </template>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="邮箱">
          <el-input
            v-model="authForm.email"
            autocomplete="email"
            inputmode="email"
            placeholder="请输入邮箱"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="authForm.password"
            type="password"
            :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'"
            show-password
            @keydown.enter="submitAuth"
          />
        </el-form-item>
        <el-form-item v-if="authMode === 'register'" label="确认密码">
          <el-input
            v-model="authForm.confirmPassword"
            type="password"
            autocomplete="new-password"
            show-password
            @keydown.enter="submitAuth"
          />
        </el-form-item>
        <el-form-item v-if="authMode === 'register'" label="邮箱验证码">
          <div class="auth-code-row">
            <el-input
              v-model="authForm.verificationCode"
              autocomplete="one-time-code"
              inputmode="numeric"
              maxlength="6"
              placeholder="6位数字验证码"
              @keydown.enter="submitAuth"
            />
            <el-button
              :icon="Message"
              :loading="authCodeLoading"
              :disabled="authCodeCooldown > 0 || authCodeLoading || !authForm.email.trim()"
              @click="sendRegisterEmailCode"
            >
              {{ authCodeCooldown > 0 ? `${authCodeCooldown}s` : '发送' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="switchAuthMode">
          {{ authMode === 'login' ? '去注册' : '去登录' }}
        </el-button>
        <el-button type="primary" :loading="authLoading" @click="submitAuth">
          {{ authMode === 'login' ? '登录' : '注册' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="accountDialogVisible" title="账号设置" width="560px">
      <el-form label-position="top" v-loading="accountLoading">
        <div class="account-form-grid">
          <el-form-item label="用户名">
            <el-input v-model="accountForm.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="accountForm.email" autocomplete="email" disabled />
          </el-form-item>
        </div>
        <el-form-item label="生日">
          <el-date-picker
            v-model="accountForm.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="accountDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="accountLoading" @click="submitAccountProfile">
          保存账号
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="520px">
      <el-form label-position="top" v-loading="passwordLoading">
        <el-form-item label="邮箱">
          <el-input v-model="passwordForm.email" autocomplete="email" disabled />
        </el-form-item>
        <el-form-item label="邮箱验证码">
          <div class="auth-code-row">
            <el-input
              v-model="passwordForm.verificationCode"
              autocomplete="one-time-code"
              inputmode="numeric"
              maxlength="6"
              placeholder="6位数字验证码"
            />
            <el-button
              :icon="Message"
              :loading="passwordCodeLoading"
              :disabled="passwordCodeCooldown > 0 || passwordCodeLoading || !passwordForm.email.trim()"
              @click="sendPasswordEmailCode"
            >
              {{ passwordCodeCooldown > 0 ? `${passwordCodeCooldown}s` : '发送' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordLoading" @click="submitPassword">
          更新密码
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createWorldDialogVisible" title="创建世界" width="620px">
      <el-steps :active="createWorldStep" finish-status="success" simple class="create-steps">
        <el-step title="模板" />
        <el-step title="好感" />
        <el-step title="主动" />
        <el-step title="思考" />
        <el-step title="输入" />
      </el-steps>

      <el-form label-position="top" class="create-world-form">
        <section v-if="createWorldStep === 0" class="create-step-panel">
          <el-form-item label="世界模板">
            <el-select v-model="createWorldForm.worldId" filterable placeholder="选择模板">
              <el-option
                v-for="template in worldTemplates"
                :key="template.id"
                :label="template.name"
                :value="template.id"
              />
            </el-select>
            <p class="field-help">
              世界模板提供世界背景、封面和默认角色来源；创建世界时会记录所选模板 ID，并用于后续聊天上下文构建。
            </p>
          </el-form-item>

          <el-button class="create-template-button" plain :icon="Plus" @click="openCreateTemplate">
            创建新的模板
          </el-button>

          <el-form-item label="世界名称">
            <el-input v-model="createWorldForm.name" placeholder="留空则使用模板名称" />
            <p class="field-help">
              名称只用于你的世界列表展示，不会改变原始模板内容。
            </p>
          </el-form-item>

          <div v-if="selectedCreateTemplate" class="selected-template">
            <span class="avatar large" :style="imageStyle(selectedCreateTemplate.image)">
              <span v-if="!selectedCreateTemplate.image">
                {{ firstText(selectedCreateTemplate.name) }}
              </span>
            </span>
            <div>
              <strong>{{ selectedCreateTemplate.name }}</strong>
              <p>{{ selectedCreateTemplate.background || '暂无背景简介' }}</p>
            </div>
          </div>
        </section>

        <section v-else-if="createWorldStep === 1" class="create-step-panel">
          <el-form-item label="好感度提升难度">
            <el-radio-group v-model="createWorldForm.favorSystemStatus" class="option-stack">
              <el-radio value="EASY" border>
                简单
                <span>面对陌生人，人们总是倾向于信任，而非怀疑。角色更容易被善意、陪伴和选择打动。</span>
              </el-radio>
              <el-radio value="NORMAL" border>
                标准
                <span>关系会随着稳定互动自然推进。好感变化克制但可感知，适合大多数日常和剧情向世界。</span>
              </el-radio>
              <el-radio value="HARD" border>
                困难
                <span>信任需要更长时间建立。角色会更看重持续行动、关键承诺和明确选择。</span>
              </el-radio>
            </el-radio-group>
            <p class="field-help">
              难度只影响角色关系系统中的好感变化速度，不会改变聊天原文。
            </p>
          </el-form-item>
        </section>

        <section v-else-if="createWorldStep === 2" class="create-step-panel">
          <el-form-item label="主动提醒功能">
            <el-switch v-model="createWorldForm.acitvePushStatus" active-text="开启" inactive-text="关闭" />
            <p class="field-help">
              开启后，系统会记录聊天中提及的现实世界里用户发生的事件，并用于定时触发后续主动提醒与关怀。
            </p>
            <p class="field-help danger-help">
              注意：本功能不适用于希望进行沉浸式角色扮演的世界，适用于指定背景、人物的日常对话世界；不正确的选择可能导致定时生成的聊天记录异常，此时请忽略对应消息。
            </p>
          </el-form-item>
        </section>

        <section v-else-if="createWorldStep === 3" class="create-step-panel">
          <el-form-item label="思考模式">
            <el-switch v-model="createWorldForm.thinkStatus" active-text="开启" inactive-text="关闭" />
            <p class="field-help">
              开启后会以流式方式展示思考与回复过程；此模式不支持多条对话合并，优化输入会自动关闭。
            </p>
          </el-form-item>
          <el-form-item label="角色沉浸思考">
            <el-switch
              v-model="createWorldForm.addSpecialPrompt"
              :disabled="!createWorldForm.thinkStatus"
              active-text="开启"
              inactive-text="关闭"
            />
            <p class="field-help">
              开启后，思考过程会更偏向角色第一人称内心独白；仅在思考模式下生效。
            </p>
            <p class="field-help danger-help">
              注意：此功能会导致角色难以主动调用工具，从而发生记忆与世界观详细缺失、事件检索失效、好感度增加困难等问题，请谨慎开启。
            </p>
          </el-form-item>
        </section>

        <section v-else class="create-step-panel">
          <el-form-item label="优化输入">
            <el-switch
              v-model="createWorldForm.eotDetectionStatus"
              :disabled="createWorldForm.thinkStatus"
              active-text="开启"
              inactive-text="关闭"
            />
            <p class="field-help">
              开启后会判断用户输入是否完成，能更快得到响应，但可能出现“抢答”的情况；如果开启思考模式，该字段会固定为 false。
            </p>
          </el-form-item>
        </section>
      </el-form>

      <template #footer>
        <el-button @click="createWorldDialogVisible = false">取消</el-button>
        <el-button :disabled="createWorldStep === 0" @click="previousCreateWorldStep">上一步</el-button>
        <el-button v-if="!createWorldStepIsLast" type="primary" @click="nextCreateWorldStep">
          下一步
        </el-button>
        <el-button v-else type="primary" @click="submitCreateWorld">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="worldSettingsDialogVisible" title="世界设置" width="560px">
      <el-form label-position="top" class="world-settings-form" @submit.prevent>
        <el-form-item label="世界名称">
          <el-input v-model="worldSettingsForm.name" maxlength="30" show-word-limit placeholder="请输入世界名称" />
        </el-form-item>

        <el-form-item label="主动提醒功能">
          <el-switch v-model="worldSettingsForm.acitvePushStatus" active-text="开启" inactive-text="关闭" />
          <p class="field-help">
            开启后，系统会记录聊天中提及的现实世界里用户发生的事件，并用于定时触发后续主动提醒与关怀。
          </p>
        </el-form-item>

        <el-form-item label="好感度提升难度">
          <el-radio-group v-model="worldSettingsForm.favorSystemStatus" class="option-stack">
            <el-radio value="EASY" border>
              简单
              <span>面对陌生人，人们总是倾向于信任，而非怀疑。角色更容易被善意、陪伴和选择打动。</span>
            </el-radio>
            <el-radio value="NORMAL" border>
              标准
              <span>关系会随着稳定互动自然推进。好感变化克制但可感知，适合大多数日常和剧情向世界。</span>
            </el-radio>
            <el-radio value="HARD" border>
              困难
              <span>信任需要更长时间建立。角色会更看重持续行动、关键承诺和明确选择。</span>
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="优化输入">
          <el-switch v-model="worldSettingsForm.eotDetectionStatus" active-text="开启" inactive-text="关闭" />
          <p class="field-help">
            开启后会判断用户输入是否完成，能更快得到响应，但可能出现“抢答”的情况。
          </p>
        </el-form-item>

        <div class="settings-danger-zone">
          <el-button
            class="danger-full-button"
            type="danger"
            :icon="Delete"
            @click="openDeleteWorldConfirm"
          >
            删除该世界
          </el-button>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="worldSettingsDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="worldSettingsLoading" @click="submitWorldSettings">
          保存设置
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="worldDeleteDialogVisible"
      title="确认删除世界"
      width="460px"
      :close-on-click-modal="!worldDeleting"
      :close-on-press-escape="!worldDeleting"
    >
      <div class="delete-confirm">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <h4>此操作不可恢复</h4>
          <p>
            在删除世界前，你需要手动删除所有当前世界下的角色
          </p>
          <p>
            删除世界「{{ selectedWorldName }}」会同时删除当前世界下的所有事件与个性化设置。
          </p>
        </div>
      </div>

      <template #footer>
        <el-button :disabled="worldDeleting" @click="worldDeleteDialogVisible = false">取消</el-button>
        <el-button type="danger" :icon="Delete" :loading="worldDeleting" @click="submitDeleteWorld">
          删除该世界
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="characterDeleteDialogVisible"
      title="确认删除角色"
      width="460px"
      :close-on-click-modal="!characterDeleting"
      :close-on-press-escape="!characterDeleting"
    >
      <div class="delete-confirm">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <h4>此操作不可恢复</h4>
          <p>删除角色「{{ selectedCharacterName }}」会清除该角色相关聊天、好感与记忆数据。</p>
        </div>
      </div>

      <template #footer>
        <el-button :disabled="characterDeleting" @click="characterDeleteDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :icon="Delete"
          :loading="characterDeleting"
          @click="submitDeleteCharacter"
        >
          删除该角色
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createTemplateDialogVisible" title="创建新的世界模板" width="760px">
      <el-form label-position="top" class="template-form">
        <el-form-item label="可见性">
          <el-switch v-model="createTemplateForm.visible" active-text="公开" inactive-text="私有" />
          <p class="field-help">
            公开模板可在模板列表中被看见，私有模板仅用于当前用户可访问范围。角色模板会在世界创建完成后添加，并由系统自动绑定到对应世界模板。
          </p>
        </el-form-item>

        <div class="template-form-grid">
          <el-form-item label="模板名称">
            <el-input v-model="createTemplateForm.name" placeholder="例如：雾港学院" />
            <p class="field-help">
              模板名称会出现在可创建世界列表中，并作为模板基础信息保存。
            </p>
          </el-form-item>

          <el-form-item label="作者">
            <el-input v-model="createTemplateForm.author" placeholder="留空也可以创建" />
            <p class="field-help">
              作者用于标记模板来源，该信息会随模板一同保存。
            </p>
          </el-form-item>
        </div>

        <el-form-item label="封面图片">
          <div class="upload-row">
            <el-input
              :model-value="createTemplateImageFileName"
              readonly
              placeholder="上传成功后显示原文件名"
            />
            <label class="upload-button" :class="{ 'is-disabled': templateImageUploading }">
              <input
                type="file"
                accept=".jpg,.jpeg,.png,.gif,.webp,.bmp,image/jpeg,image/png,image/gif,image/webp,image/bmp"
                :disabled="templateImageUploading"
                @change="handleTemplateImageChange"
              />
              <el-icon><Upload /></el-icon>
              <span>{{ templateImageUploading ? '上传中' : '上传图片' }}</span>
            </label>
          </div>
          <p class="field-help">
            图片会通过后端上传接口保存，提交时使用上传接口返回的图片地址。
          </p>
        </el-form-item>

        <el-form-item label="世界背景">
          <el-input
            v-model="createTemplateForm.background"
            type="textarea"
            :autosize="{ minRows: 5, maxRows: 8 }"
            placeholder="描述世界观、时代背景、核心冲突和关键规则"
          />
          <p class="field-help">
            世界背景会进入角色聊天上下文，请不要写入不希望角色长期参考的隐私信息。
          </p>
        </el-form-item>

      </el-form>

      <template #footer>
        <el-button @click="createTemplateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateImageUploading" @click="submitCreateTemplate">
          创建模板
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createCharacterDialogVisible" title="创建新角色" width="560px">
      <el-form label-position="top" v-loading="characterTemplateLoading">
        <el-form-item label="角色模板">
          <el-select
            v-model="addCharacterForm.characterId"
            filterable
            placeholder="选择当前世界的角色模板"
          >
            <el-option
              v-for="character in availableCharacterTemplates"
              :key="character.id"
              :label="characterTemplateLabel(character.id)"
              :value="character.id"
            >
              <div class="character-template-option">
                <div class="character-template-option-image" :style="imageStyle(character.image)">
                  <span v-if="!character.image">{{ firstText(character.name) }}</span>
                </div>
                <span>{{ character.name || characterTemplateLabel(character.id) }}</span>
              </div>
            </el-option>
          </el-select>
          <p class="field-help">
            新角色只能从当前世界模板已有的角色模板创建；已经添加到该世界的角色不会再次显示。
          </p>
        </el-form-item>

        <div v-if="selectedAddCharacterTemplate" class="selected-character-template">
          <div class="selected-character-template-image" :style="imageStyle(selectedAddCharacterTemplate.image)">
            <span v-if="!selectedAddCharacterTemplate.image">
              {{ firstText(selectedAddCharacterTemplate.name) }}
            </span>
          </div>
          <div>
            <strong>{{ selectedAddCharacterTemplate.name || characterTemplateLabel(selectedAddCharacterTemplate.id) }}</strong>
          </div>
        </div>

        <el-form-item v-if="selectedAddCharacterTemplate" label="用户信息提示词">
          <el-input
            v-model="addCharacterForm.userInfoPrompt"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            placeholder="写下这个角色需要记住的用户信息"
          />
        </el-form-item>

        <el-empty
          v-if="!characterTemplateLoading && availableCharacterTemplates.length === 0"
          description="当前世界没有可添加的角色模板"
          :image-size="72"
        />
      </el-form>

      <template #footer>
        <el-button @click="createCharacterDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="availableCharacterTemplateIds.length === 0 || !addCharacterForm.characterId"
          :loading="characterCreating || characterTemplateLoading"
          @click="submitCreateCharacter"
        >
          添加角色
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createCharacterTemplateDialogVisible" title="创建角色模板" width="720px">
      <el-form label-position="top" class="template-form">
        <div class="template-form-grid">
          <el-form-item label="模板名称">
            <el-input v-model="createCharacterForm.name" placeholder="例如：林澈" />
          </el-form-item>
          <el-form-item label="初始好感">
            <el-input-number v-model="createCharacterForm.initFavor" :min="0" :max="100" />
          </el-form-item>
        </div>

        <el-form-item label="角色图片">
          <div class="upload-row">
            <el-input
              :model-value="createCharacterImageFileName"
              readonly
              placeholder="上传成功后显示原文件名"
            />
            <label class="upload-button" :class="{ 'is-disabled': characterImageUploading }">
              <input
                type="file"
                accept=".jpg,.jpeg,.png,.gif,.webp,.bmp,image/jpeg,image/png,image/gif,image/webp,image/bmp"
                :disabled="characterImageUploading"
                @change="handleCharacterImageChange"
              />
              <el-icon><Upload /></el-icon>
              <span>{{ characterImageUploading ? '上传中' : '上传图片' }}</span>
            </label>
          </div>
        </el-form-item>

        <el-form-item label="角色背景">
          <el-input
            v-model="createCharacterForm.background"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="角色经历、身份、与世界的关系"
          />
        </el-form-item>

        <el-form-item label="性格设定">
          <el-input
            v-model="createCharacterForm.personality"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="角色说话方式、价值观、行为倾向"
          />
        </el-form-item>

        <el-form-item label="好感阶段">
          <div class="favorability-editor">
            <el-button plain :icon="Plus" @click="addFavorabilityRow">
              添加好感度提示词
            </el-button>
            <div class="favorability-list">
              <div
                v-for="row in createCharacterForm.favorabilityRows"
                :key="row.id"
                class="favorability-row"
              >
                <el-input-number
                  v-model="row.threshold"
                  :min="0"
                  :max="100"
                  placeholder="值"
                />
                <el-input
                  v-model="row.prompt"
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 5 }"
                  resize="none"
                  placeholder="该好感度下的角色提示词"
                />
                <el-button :icon="Delete" text type="danger" circle @click="removeFavorabilityRow(row.id)" />
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createCharacterTemplateDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="characterCreating || characterImageUploading"
          @click="submitCreateCharacterTemplate"
        >
          创建模板
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="worldDetailDialogVisible" title="修改世界设定" width="760px">
      <div class="world-detail-dialog" v-loading="worldDetailLoading">
        <div class="detail-list">
          <article v-for="detail in worldDetails" :key="detail.id" class="detail-row">
            <div>
              <h4>{{ detail.about || '未命名设定' }}</h4>
              <p>{{ detail.details }}</p>
            </div>
            <el-button :icon="Delete" text type="danger" circle @click="deleteWorldDetail(detail)" />
          </article>
          <el-empty
            v-if="!worldDetailLoading && worldDetails.length === 0"
            description="还没有世界设定"
            :image-size="72"
          />
        </div>

        <div class="dialog-section">
          <div class="dialog-section-title">
            <h4>添加设定</h4>
            <p>新增内容会进入该世界模板的详情库。</p>
          </div>
          <el-form label-position="top">
            <el-form-item label="设定主题">
              <el-input v-model="worldDetailForm.about" placeholder="例如：学院禁区" />
            </el-form-item>
            <el-form-item label="设定内容">
              <el-input
                v-model="worldDetailForm.details"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 7 }"
                placeholder="写下这个设定的规则、事实或背景"
              />
            </el-form-item>
          </el-form>
        </div>
      </div>

      <template #footer>
        <el-button @click="worldDetailDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" :loading="worldDetailLoading" @click="submitWorldDetail">
          添加设定
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="storyDetailDialogVisible" title="事件详情" width="620px">
      <div v-loading="storyDetailLoading" class="story-detail-dialog">
        <template v-if="selectedStoryDetail">
          <div class="story-detail-title">
            <div>
              <h3>{{ selectedStoryDetail.title }}</h3>
              <p>{{ selectedStoryDetail.theme || '暂无主题' }}</p>
            </div>
            <el-tag :type="storyStatusType(selectedStoryDetail.status)" round>
              {{ storyStatusLabel(selectedStoryDetail.status) }}
            </el-tag>
          </div>

          <el-descriptions :column="2" border>
            <el-descriptions-item label="开始时间">
              {{ formatTime(selectedStoryDetail.startedAt) || '未记录' }}
            </el-descriptions-item>
            <el-descriptions-item label="结束时间">
              {{ formatTime(selectedStoryDetail.endedAt) || '未结束' }}
            </el-descriptions-item>
            <el-descriptions-item label="参与角色" :span="2">
              <div class="story-characters">
                <el-tag
                  v-for="name in selectedStoryDetail.characterNames"
                  :key="name"
                  round
                >
                  {{ name }}
                </el-tag>
                <span v-if="selectedStoryDetail.characterNames.length === 0" class="muted-text">
                  暂无角色
                </span>
              </div>
            </el-descriptions-item>
          </el-descriptions>

          <div class="detail-text-block">
            <h4>当前场景</h4>
            <p>{{ selectedStoryDetail.currentScene || '暂无当前场景' }}</p>
          </div>
          <div class="detail-text-block">
            <h4>开场描述</h4>
            <p>{{ selectedStoryDetail.opening || '暂无开场描述' }}</p>
          </div>
          <div class="detail-text-block">
            <h4>事件总结</h4>
            <p>{{ selectedStoryDetail.summary || '事件尚未结束，暂无总结' }}</p>
          </div>
        </template>
      </div>

      <template #footer>
        <el-button @click="storyDetailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="startStoryDialogVisible" title="开启新事件" width="560px">
      <el-form label-position="top">
        <el-form-item label="事件标题">
          <el-input v-model="storyForm.title" placeholder="例如：雨夜旧约" />
        </el-form-item>
        <el-form-item label="主题">
          <el-input v-model="storyForm.theme" placeholder="事件的情绪或目标" />
        </el-form-item>
        <el-form-item label="当前场景">
          <el-input
            v-model="storyForm.currentScene"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="角色正在面对的地点、状态和冲突"
          />
        </el-form-item>
        <el-form-item label="开场描述">
          <el-input
            v-model="storyForm.opening"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="可选，留空时后端会生成开场"
          />
        </el-form-item>
        <el-form-item label="参与角色">
          <el-select
            v-model="storyForm.characterIds"
            multiple
            placeholder="选择参与事件的角色"
          >
            <el-option
              v-for="character in characters"
              :key="character.characterId"
              :label="character.characterName"
              :value="character.characterId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startStoryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStartStory">开启事件</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="advanceStoryDialogVisible" title="推进事件" width="520px">
      <el-form label-position="top">
        <el-form-item label="推进说明">
          <el-input
            v-model="storyAdvanceForm.transition"
            type="textarea"
            :rows="4"
            placeholder="描述接下来发生的转折、线索或场景变化"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="advanceStoryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="storyActionLoading" @click="submitAdvanceStory">
          推进事件
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="endStoryDialogVisible" title="结束事件" width="520px">
      <el-form label-position="top">
        <el-form-item label="结束说明">
          <el-input
            v-model="storyEndForm.ending"
            type="textarea"
            :rows="4"
            placeholder="可选：描述事件如何收束，留空则由系统根据历史总结"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="endStoryDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="storyActionLoading" @click="submitEndStory">
          结束事件
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style>
.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 284px minmax(0, 1fr) auto;
  background:
    linear-gradient(135deg, rgba(24, 91, 116, 0.08), transparent 34%),
    linear-gradient(315deg, rgba(189, 74, 94, 0.08), transparent 36%),
    #f5f7fb;
  color: #202734;
}

.sidebar {
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 16px;
  border-right: 1px solid rgba(42, 52, 71, 0.1);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(18px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, #285c74, #c6576a);
}

.brand h1,
.brand p,
.workspace-header h2,
.workspace-header p,
.section-heading h3,
.section-heading p {
  margin: 0;
}

.brand h1 {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0;
}

.brand p,
.sidebar-title,
.nav-copy small,
.panel-muted,
.section-heading p,
.world-card small,
.template-row p,
.dialog-title p {
  color: #697386;
}

.mode-switch {
  width: 100%;
  margin-bottom: 18px;
}

.mode-switch .el-radio-button {
  flex: 1;
}

.mode-switch .el-radio-button__inner {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.sidebar-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding-right: 3px;
}

.sidebar-title,
.section-heading,
.header-actions,
.user-dock,
.back-row,
.story-row,
.favor-row,
.world-card,
.template-row {
  display: flex;
  align-items: center;
}

.sidebar-title {
  justify-content: space-between;
  font-size: 13px;
  margin: 8px 4px 10px;
}

.nav-item,
.back-row {
  width: 100%;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.nav-item {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  text-align: left;
  margin-bottom: 6px;
}

.nav-item:hover,
.nav-item.active,
.back-row:hover {
  background: #edf4f6;
}

.nav-item.active {
  outline: 1px solid rgba(40, 92, 116, 0.28);
}

.character-nav .nav-copy small {
  display: block;
  max-width: 126px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-copy {
  min-width: 0;
}

.nav-copy strong {
  display: block;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.avatar {
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background:
    linear-gradient(135deg, rgba(40, 92, 116, 0.22), rgba(198, 87, 106, 0.2)),
    #e7edf2;
  background-size: cover;
  background-position: center;
  color: #285c74;
  font-weight: 800;
}

.avatar.large {
  width: 52px;
  height: 52px;
}

.avatar.portrait {
  width: 88px;
  height: 88px;
  margin: 6px auto 14px;
  font-size: 28px;
}

.user-avatar {
  width: 34px;
  height: 34px;
}

.back-row {
  gap: 8px;
  padding: 10px;
  text-align: left;
  font-weight: 700;
}

.user-dock {
  gap: 8px;
  padding-top: 14px;
  border-top: 1px solid rgba(42, 52, 71, 0.08);
}

.user-card {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  padding: 8px;
  border-radius: 8px;
  background: #f3f6f8;
  text-align: left;
}

.user-card strong,
.user-card small {
  display: block;
}

.user-card strong {
  font-weight: 700;
}

.user-card small {
  color: #7a8494;
}

.login-button {
  flex: 1;
}

.workspace {
  min-width: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 24px;
  overflow: hidden;
}

.workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.eyebrow {
  color: #c6576a;
  font-size: 13px;
  font-weight: 700;
}

.workspace-header h2 {
  margin-top: 2px;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 0;
}

.header-actions {
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.world-stage,
.overview-stage,
.chat-stage {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.world-stage,
.overview-stage {
  display: grid;
  gap: 18px;
}

.section-panel {
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.84);
  padding: 18px;
  box-shadow: 0 18px 45px rgba(33, 43, 54, 0.06);
}

.section-heading {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-heading h3 {
  font-size: 18px;
  font-weight: 800;
}

.world-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.world-card,
.template-row,
.favor-row,
.story-row {
  width: 100%;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
}

.world-card {
  min-height: 94px;
  gap: 12px;
  padding: 12px;
  text-align: left;
  cursor: pointer;
}

.world-card:hover,
.template-row:hover,
.favor-row:hover,
.story-row:hover {
  border-color: rgba(40, 92, 116, 0.34);
  box-shadow: 0 12px 28px rgba(33, 43, 54, 0.08);
}

.world-cover {
  width: 70px;
  height: 70px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(40, 92, 116, 0.18), rgba(198, 87, 106, 0.22)),
    #e7edf2;
  background-size: cover;
  background-position: center;
  font-size: 22px;
  font-weight: 800;
  color: #285c74;
}

.world-card strong,
.world-card small {
  display: block;
}

.world-card strong,
.template-row h4 {
  font-weight: 800;
}

.world-card .el-icon {
  margin-left: auto;
}

.template-list {
  display: grid;
  gap: 10px;
}

.template-row {
  gap: 14px;
  padding: 14px;
}

.template-row div {
  min-width: 0;
  flex: 1;
}

.template-row h4,
.template-row p {
  margin: 0;
}

.template-row p {
  margin-top: 4px;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-strip article {
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
  padding: 16px;
}

.metric-strip span,
.panel-stat span {
  display: block;
  color: #697386;
  font-size: 13px;
}

.metric-strip strong,
.panel-stat strong {
  display: block;
  margin-top: 4px;
  font-size: 28px;
  font-weight: 800;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
  gap: 18px;
}

.favor-list {
  display: grid;
  gap: 10px;
}

.favor-row {
  gap: 12px;
  padding: 12px;
  cursor: pointer;
  text-align: left;
}

.favor-copy {
  min-width: 0;
  flex: 1;
}

.favor-copy strong {
  display: block;
  margin-bottom: 8px;
  font-weight: 800;
}

.favor-number {
  font-weight: 800;
  color: #285c74;
}

.active-story {
  padding: 16px;
  border-radius: 8px;
  background: #f0f7f3;
  cursor: pointer;
}

.active-story:hover {
  box-shadow: 0 12px 28px rgba(33, 43, 54, 0.08);
}

.story-actions,
.active-story-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.story-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.active-story-header {
  justify-content: space-between;
}

.active-story h4,
.active-story p {
  margin: 10px 0 0;
}

.active-story h4 {
  font-size: 18px;
  font-weight: 800;
}

.active-story p {
  color: #4d5968;
}

.story-characters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
}

.story-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.story-row {
  justify-content: space-between;
  padding: 12px;
  cursor: pointer;
}

.story-detail-dialog {
  min-height: 220px;
}

.story-detail-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.story-detail-title h3,
.story-detail-title p,
.detail-text-block h4,
.detail-text-block p {
  margin: 0;
}

.story-detail-title h3,
.detail-text-block h4 {
  font-weight: 800;
}

.story-detail-title p,
.muted-text {
  color: #697386;
}

.detail-text-block {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #f8fafc;
}

.detail-text-block p {
  margin-top: 8px;
  color: #3f4958;
  white-space: pre-wrap;
}

.chat-stage {
  display: flex;
}

.chat-window {
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  overflow: hidden;
}

.messages {
  min-height: 0;
  overflow: auto;
  padding: 22px;
}

.empty-chat {
  height: 100%;
  display: grid;
  place-items: center;
  align-content: center;
  color: #697386;
  text-align: center;
}

.empty-chat .el-icon {
  width: 56px;
  height: 56px;
  margin-bottom: 12px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #edf4f6;
  color: #285c74;
  font-size: 28px;
}

.empty-chat h3,
.empty-chat p {
  margin: 0;
}

.empty-chat h3 {
  color: #202734;
  font-weight: 800;
}

.empty-chat p {
  margin-top: 6px;
}

.message {
  display: flex;
  margin-bottom: 12px;
}

.message.user {
  justify-content: flex-end;
}

.message.thinking {
  margin: 4px 0 8px;
}

.message.tool {
  margin: 12px 0;
}

.message-bubble {
  max-width: min(680px, 76%);
  border-radius: 8px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid rgba(42, 52, 71, 0.1);
}

.message.user .message-bubble {
  color: #fff;
  background: #285c74;
  border-color: #285c74;
}

.message.story .message-bubble {
  max-width: 560px;
  color: #586273;
  background: #f5f7f9;
  font-size: 13px;
}

.thinking-content {
  max-width: min(680px, 76%);
  padding: 0 4px;
  color: #9aa3af;
  font-size: 12px;
  line-height: 1.55;
}

.tool-line {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #7a8494;
  font-size: 12px;
}

.tool-line::before,
.tool-line::after {
  content: '';
  height: 1px;
  flex: 1;
  background: rgba(122, 132, 148, 0.2);
}

.message-bubble p,
.message-bubble small,
.thinking-content p {
  margin: 0;
  white-space: pre-wrap;
}

.message-bubble small {
  display: block;
  margin-top: 6px;
  opacity: 0.7;
}

.composer {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  padding: 14px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
  background: #fff;
}

.withdraw-button {
  width: 40px;
  min-width: 40px;
  padding: 0;
}

.character-panel {
  width: 292px;
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 22px 18px;
  border-left: 1px solid rgba(42, 52, 71, 0.1);
  background: rgba(255, 255, 255, 0.9);
  transition: width 0.2s ease;
}

.character-panel.collapsed {
  width: 54px;
  padding: 18px 8px;
}

.collapse-button {
  width: 34px;
  min-width: 34px;
  height: 34px;
  min-height: 34px;
  flex: 0 0 34px;
  border: 1px solid rgba(42, 52, 71, 0.12);
  border-radius: 8px;
  display: grid;
  place-items: center;
  align-self: flex-end;
  margin-left: auto;
  padding: 0;
  background: #fff;
  cursor: pointer;
  line-height: 1;
}

.character-panel h3,
.panel-muted {
  margin: 0;
  text-align: center;
}

.character-panel h3 {
  font-size: 20px;
  font-weight: 800;
}

.character-panel-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding-right: 4px;
}

.panel-stat {
  margin: 22px 0 8px;
}

.info-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
}

.info-block h4,
.info-block p,
.info-block small {
  margin: 0;
}

.info-block h4 {
  font-size: 14px;
  font-weight: 800;
}

.info-block-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.info-block p {
  margin-top: 8px;
  color: #3f4958;
}

.prompt-info-block .el-textarea {
  margin-top: 10px;
}

.prompt-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.user-info-prompt-preview {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.recent-chat-content {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 5;
}

.info-block small {
  display: block;
  margin-top: 6px;
  color: #7a8494;
}

.character-danger-zone,
.settings-danger-zone {
  border-top: 1px solid rgba(192, 53, 53, 0.18);
}

.character-danger-zone {
  margin-top: auto;
  padding-top: 18px;
}

.settings-danger-zone {
  margin-top: 6px;
  padding-top: 16px;
}

.danger-full-button {
  width: 100%;
}

.delete-confirm {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(192, 53, 53, 0.18);
  border-radius: 8px;
  background: #fff7f7;
}

.delete-confirm .el-icon {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  color: #c03535;
  background: #ffe3e3;
  font-size: 22px;
}

.delete-confirm h4,
.delete-confirm p {
  margin: 0;
}

.delete-confirm h4 {
  color: #9f2d2d;
  font-weight: 800;
}

.delete-confirm p {
  margin-top: 6px;
  color: #5f3030;
  line-height: 1.6;
}

.dialog-title h3,
.dialog-title p {
  margin: 0;
}

.dialog-title h3 {
  font-weight: 800;
}

.dialog-title p {
  margin-top: 4px;
}

.auth-code-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px;
  gap: 10px;
}

.auth-code-row .el-button {
  min-width: 0;
}

.create-steps {
  margin-bottom: 18px;
}

.create-world-form,
.template-form,
.world-settings-form {
  max-height: 58vh;
  overflow: auto;
  padding-right: 4px;
}

.create-step-panel {
  min-height: 260px;
}

.field-help {
  width: 100%;
  margin: 8px 0 0;
  color: #697386;
  font-size: 13px;
  line-height: 1.55;
}

.danger-help {
  color: #c03535;
}

.create-template-button {
  width: 100%;
  margin-bottom: 18px;
}

.selected-template {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #f7fafc;
}

.selected-template strong,
.selected-template p {
  margin: 0;
}

.selected-template strong {
  font-weight: 800;
}

.selected-template p {
  margin-top: 4px;
  color: #697386;
}

.character-template-option {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.character-template-option-image,
.selected-character-template-image {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #dfe8ec;
  background-size: cover;
  background-position: center;
  color: #285c74;
  font-weight: 800;
}

.character-template-option-image {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  font-size: 12px;
}

.selected-character-template {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: -2px 0 18px;
  padding: 12px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #f7fafc;
}

.selected-character-template strong {
  font-weight: 800;
}

.selected-character-template-image {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  font-size: 22px;
}

.option-stack {
  width: 100%;
  display: grid;
  gap: 10px;
}

.option-stack .el-radio {
  width: 100%;
  height: auto;
  align-items: flex-start;
  padding: 12px;
  margin-right: 0;
  white-space: normal;
}

.option-stack .el-radio__label {
  display: grid;
  gap: 4px;
  color: #202734;
  font-weight: 800;
}

.option-stack .el-radio__label span {
  color: #697386;
  font-size: 13px;
  font-weight: 400;
}

.template-form-grid,
.account-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.account-form-grid {
  align-items: start;
}

.account-form-grid .el-date-editor,
.el-form-item .el-date-editor {
  width: 100%;
}

.dialog-section {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid rgba(42, 52, 71, 0.1);
}

.dialog-section-title h4,
.dialog-section-title p,
.detail-row h4,
.detail-row p {
  margin: 0;
}

.dialog-section-title {
  margin-bottom: 14px;
}

.dialog-section-title h4,
.detail-row h4 {
  font-size: 15px;
  font-weight: 800;
}

.dialog-section-title p,
.detail-row p {
  margin-top: 4px;
  color: #697386;
}

.world-detail-dialog {
  max-height: 62vh;
  overflow: auto;
  padding-right: 4px;
}

.detail-list {
  display: grid;
  gap: 10px;
}

.detail-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 12px;
  border: 1px solid rgba(42, 52, 71, 0.1);
  border-radius: 8px;
  background: #fff;
}

.detail-row p {
  white-space: pre-wrap;
}

.favorability-editor {
  width: 100%;
  display: grid;
  gap: 10px;
}

.favorability-list {
  display: grid;
  gap: 8px;
}

.favorability-row {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
}

.favorability-row .el-input-number {
  width: 100%;
}

.upload-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}

.upload-button {
  position: relative;
  min-width: 92px;
  height: 32px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #285c74;
  background: #fff;
  cursor: pointer;
}

.upload-button.is-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.upload-button.is-disabled input {
  cursor: not-allowed;
}

@media (max-width: 1100px) {
  .app-shell {
    grid-template-columns: 244px minmax(0, 1fr);
  }

  .character-panel {
    position: fixed;
    right: 0;
    z-index: 10;
    box-shadow: -18px 0 40px rgba(33, 43, 54, 0.12);
  }

  .overview-grid,
  .metric-strip,
  .template-form-grid,
  .account-form-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 760px) {
  .app-shell {
    display: block;
  }

  .sidebar,
  .workspace {
    height: auto;
    position: static;
  }

  .sidebar {
    min-height: 420px;
  }

  .workspace {
    padding: 16px;
  }

  .workspace-header,
  .section-heading {
    display: block;
  }

  .header-actions,
  .section-heading .el-button {
    margin-top: 12px;
  }

  .overview-grid,
  .metric-strip,
  .template-form-grid,
  .account-form-grid {
    grid-template-columns: 1fr;
  }

  .composer {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .composer .el-button--primary {
    grid-column: 1 / -1;
  }

  .favorability-row {
    grid-template-columns: 1fr;
  }
}
</style>
