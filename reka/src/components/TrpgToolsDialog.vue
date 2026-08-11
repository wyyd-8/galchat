<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, BookUser, Check, Dices, FlaskConical, LoaderCircle, LocateFixed, RefreshCw, RotateCcw, Save, UserRound } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import DiceDebugPanel from '@/dice/components/DiceDebugPanel.vue'
import DiceRollMessage from '@/dice/components/DiceRollMessage.vue'
import { api } from '@/api/client'
import type {
  Character, CharacterCard, CocModule, ContextWindowUsage, Conversation, DiceRollAggregate, GroupMessage,
  InvestigatorCardSummary, TrpgSave,
} from '@/api/types'
import { errorMessage, notify } from '@/composables/useNotice'
import { listDiceMessagesNewestFirst } from '@/dice/domain/dicePlayback'
import { buildToolCharacterTargets, toolDialogContentClass, useToolConfirmations } from '@/components/trpgToolsState'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  conversation: Conversation
  module: CocModule | null
  characters: Character[]
  participantIds: number[]
  messages: GroupMessage[]
}>()
const emit = defineEmits<{
  restored: []
  openDice: [aggregate: DiceRollAggregate]
  debugDice: [aggregate: DiceRollAggregate]
  locateDice: [messageId: number]
}>()

const busy = ref(false)
const contextUsage = ref<ContextWindowUsage | null>(null)
const save = ref<TrpgSave | null>(null)
const saveRemark = ref('')
const cards = ref<InvestigatorCardSummary[]>([])
const selectedKey = ref('player')
const selectedToolTab = ref('status')
const { confirmLoad, confirmRollback } = useToolConfirmations(open, selectedToolTab)
const card = ref<CharacterCard | null>(null)
const cardText = ref('')

const characterTargets = computed(() => buildToolCharacterTargets(props.characters, cards.value, props.participantIds))
const selectedTarget = computed(() => characterTargets.value.find((target) => target.key === selectedKey.value)
  || characterTargets.value[0])
const selectedParticipantId = computed(() => selectedTarget.value?.participantId)
const contextPercent = computed(() => Math.max(0, Math.round((contextUsage.value?.ratio || 0) * 100)))
const contextTone = computed(() => contextPercent.value >= 90 ? 'danger' : contextPercent.value >= 70 ? 'warning' : 'safe')
const selectedActorName = computed(() => selectedTarget.value?.name || '玩家调查员')
const completedCardCount = computed(() => characterTargets.value.filter((target) => target.cardId !== undefined).length)
const dialogContentClass = computed(() => toolDialogContentClass(selectedToolTab.value))
const diceMessages = computed(() => listDiceMessagesNewestFirst(props.messages))

function time(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '暂无记录' }
async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await action() }
  catch (error) { notify('跑团工具操作失败', errorMessage(error), 'danger') }
  finally { busy.value = false }
}
async function loadCard() {
  card.value = null
  card.value = selectedTarget.value?.cardId
    ? await api.characterCardById(selectedTarget.value.cardId)
    : null
}
async function refreshCards() {
  cards.value = await api.investigatorCards(props.conversation.id)
  await loadCard()
}
async function refreshOverview() {
  const [usageResult, saveResult] = await Promise.all([
    api.contextWindow(props.conversation.id), api.trpgSave(props.conversation.id),
  ])
  contextUsage.value = usageResult; save.value = saveResult; saveRemark.value = saveResult?.remark || ''
  await refreshCards()
}
async function createCard() {
  if (!cardText.value.trim()) throw new Error('请先粘贴人物卡文本')
  const createdCard = await api.createCharacterCard({ runId: props.conversation.id, participantId: selectedParticipantId.value, characterText: cardText.value.trim() })
  cardText.value = ''; await refreshCards(); notify('人物卡已导入', createdCard.character.name, 'success')
}
async function saveSnapshot() {
  save.value = await api.saveTrpg(props.conversation.id, saveRemark.value)
  confirmLoad.value = false; confirmRollback.value = false
  notify('跑团存档已保存', '', 'success')
}
async function loadSnapshot() {
  if (!confirmLoad.value) { confirmLoad.value = true; confirmRollback.value = false; return }
  await api.loadTrpg(props.conversation.id); confirmLoad.value = false; await refreshOverview(); emit('restored')
  notify('跑团存档已读取', '存档点之后的进度已回滚', 'success')
}
async function rollbackTurn() {
  if (!confirmRollback.value) { confirmRollback.value = true; confirmLoad.value = false; return }
  await api.rollbackTrpgTurn(props.conversation.id)
  confirmRollback.value = false
  await refreshOverview()
  emit('restored')
  notify('最近一轮已回滚', '可以从该行动轮开始前重新继续跑团', 'success')
}
async function selectTarget(key: string) {
  if (busy.value || selectedKey.value === key) return
  selectedKey.value = key
  cardText.value = ''
  await execute(loadCard)
}
watch(open, (visible) => { if (visible) void execute(refreshOverview) })
watch(() => props.conversation.id, () => {
  selectedKey.value = 'player'
  cards.value = []
  card.value = null
  confirmLoad.value = false
  confirmRollback.value = false
})
</script>

<template>
  <BaseDialog v-model="open" title="跑团工具" :description="`${conversation.title} · ${module?.name || `模组 #${conversation.moduleId || '未记录'}`}`" size="lg" :content-class="dialogContentClass">
    <TabsRoot v-model="selectedToolTab" class="tabs trpg-tools">
      <TabsList class="tabs-list">
        <TabsTrigger value="status"><Activity :size="15" />状态</TabsTrigger>
        <TabsTrigger value="save"><Save :size="15" />存档</TabsTrigger>
        <TabsTrigger value="card"><BookUser :size="15" />人物卡</TabsTrigger>
        <TabsTrigger value="dice"><Dices :size="15" />骰子</TabsTrigger>
        <TabsTrigger value="dice-debug"><FlaskConical :size="15" />骰子调试</TabsTrigger>
      </TabsList>

      <TabsContent value="status" class="tabs-content tool-section">
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>最近一次 KP 提示词长度</strong><small>{{ contextUsage ? `${contextUsage.characterCount.toLocaleString()} / ${contextUsage.softLimit.toLocaleString()} 字符` : '尚无记录' }}</small></span><button class="icon-button bordered" :disabled="busy" title="刷新" @click="execute(refreshOverview)"><RefreshCw :size="15" /></button></div>
          <div class="context-meter" :class="contextTone"><i :style="{ width: `${Math.min(contextPercent, 100)}%` }" /></div><small>{{ contextPercent }}% · {{ contextUsage ? `更新于 ${time(contextUsage.updatedAt)}` : '模型执行一次跑团行动后显示' }}</small>
        </section>
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>行动轮自动存档</strong><small>每次开始新行动轮前自动覆盖</small></span><button class="button danger" :disabled="busy" @click="execute(rollbackTurn)"><RotateCcw :size="16" />{{ confirmRollback ? '再次点击确认回滚' : '回滚最近一轮' }}</button></div>
          <p v-if="confirmRollback" class="destructive-note">将删除自动存档点之后的行动轮、消息、骰子和人物状态；如跑团已误结束，也会恢复到结束前状态。</p>
        </section>
      </TabsContent>

      <TabsContent value="save" class="tabs-content tool-section">
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>保存跑团存档</strong><small>记录当前跑团进度</small></span><Save :size="18" /></div>
          <label class="field"><span>存档备注</span><textarea v-model.trim="saveRemark" rows="3" maxlength="200" placeholder="记录当前场景、线索或风险…" /></label>
          <div class="tool-actions"><button class="button secondary" :disabled="busy" @click="execute(saveSnapshot)"><Save :size="16" />{{ save ? '覆盖存档' : '创建存档' }}</button></div>
        </section>
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>读取存档</strong><small>{{ save ? `${time(save.savedAt)} · 格式 v${save.formatVersion || 1}` : '尚未创建存档' }}</small></span><RotateCcw :size="18" /></div>
          <div v-if="save" class="save-remark"><small>存档备注</small><p>{{ save.remark || '无备注' }}</p></div>
          <div v-if="save?.investigators?.length" class="investigator-grid"><span v-for="item in save.investigators" :key="item.characterId"><strong>{{ item.name }}</strong><small>HP {{ item.hpCurrent }}/{{ item.hpMax }} · SAN {{ item.sanCurrent }}/{{ item.sanMax }} · MP {{ item.mpCurrent }}/{{ item.mpMax }}</small><em v-if="item.dead">死亡</em><em v-else-if="item.dying">濒死</em><em v-else-if="item.unconscious">昏迷</em></span></div>
          <div class="tool-actions"><button class="button" :class="confirmLoad ? 'danger' : 'ghost'" :disabled="!save || busy" @click="execute(loadSnapshot)"><RotateCcw :size="16" />{{ confirmLoad ? '再次点击确认读档' : '读取存档' }}</button></div>
          <p v-if="confirmLoad" class="destructive-note">读档会删除存档点之后的行动轮、消息、骰子和人物状态，此操作不可撤销。</p>
        </section>
      </TabsContent>

      <TabsContent value="card" class="tabs-content tool-section">
        <div class="trpg-binding-layout trpg-tools-card-layout">
          <section class="trpg-binding-list-pane">
            <header class="settings-section-heading">
              <span><strong>调查员</strong><small>选择左侧人物，在右侧查看人物卡详情</small></span>
              <em>{{ completedCardCount }}/{{ characterTargets.length }} 已建立</em>
            </header>
            <div class="character-choice-list trpg-binding-targets" role="radiogroup" aria-label="人物卡角色">
              <button
                v-for="target in characterTargets"
                :key="target.key"
                type="button"
                class="choice-row"
                :class="{ active: selectedKey === target.key }"
                role="radio"
                :aria-checked="selectedKey === target.key"
                @click="selectTarget(target.key)"
              >
                <span
                  class="character-avatar small"
                  :class="{ 'player-avatar': target.actorType === 'PLAYER' }"
                  :style="target.image ? { backgroundImage: `url(${target.image})` } : {}"
                >
                  <UserRound v-if="target.actorType === 'PLAYER'" :size="17" />
                  <template v-else>{{ target.image ? '' : target.name.slice(0, 1) }}</template>
                </span>
                <span><strong>{{ target.name }}</strong><small>{{ target.actorType === 'PLAYER' ? '由当前登录用户控制' : 'AI 调查员' }}</small></span>
                <span class="binding-state" :class="{ complete: target.cardId !== undefined }">
                  <Check v-if="target.cardId !== undefined" :size="13" />{{ target.cardId !== undefined ? '已建立' : '待建立' }}
                </span>
              </button>
            </div>
          </section>

          <aside class="trpg-binding-card-pane">
            <div v-if="busy && !card" class="binding-empty"><LoaderCircle class="spin" :size="24" /><strong>正在读取人物卡…</strong></div>
            <section v-else-if="card" class="character-sheet binding-sheet">
              <div class="sheet-heading"><span><small>{{ card.character.actorType === 'PLAYER' ? '玩家调查员' : card.character.actorType === 'BOT' ? 'AI 调查员' : '模组角色' }} · {{ card.character.occupation || '未填写职业' }}</small><h3>{{ card.character.name }}</h3><p>{{ card.character.sex || '—' }} · {{ card.character.age || '—' }} 岁 · {{ card.character.era || '时代未填' }}</p></span><div class="vitals"><b>HP {{ card.character.hpCurrent }}/{{ card.character.hpMax }}</b><b>SAN {{ card.character.sanCurrent }}/{{ card.character.sanMax }}</b><b>MP {{ card.character.mpCurrent }}/{{ card.character.mpMax }}</b></div></div>
              <div class="attribute-grid"><span v-for="[name, value] in Object.entries({ STR: card.character.str, CON: card.character.con, SIZ: card.character.siz, DEX: card.character.dex, APP: card.character.app, INT: card.character.intValue, POW: card.character.pow, EDU: card.character.edu })" :key="name"><small>{{ name }}</small><strong>{{ value }}</strong></span></div>
              <div class="sheet-columns"><div><strong>技能</strong><p>{{ card.skills.map((item) => `${item.displayName} ${item.value}%`).join(' · ') || '暂无技能' }}</p></div><div><strong>武器与装备</strong><p>{{ card.weapons.map((item) => `${item.name}${item.damage ? ` ${item.damage}` : ''}`).join(' · ') || '暂无武器' }}<br />{{ card.profile?.equipmentText || '无额外装备' }}</p></div></div>
            </section>
            <section v-else class="tool-card import-card binding-import-card"><BookUser :size="25" /><strong>{{ selectedActorName }}尚未建立人物卡</strong><p>首行必须是“姓名, 职业, 性别, 年龄岁”，并包含 STR、CON、SIZ、DEX、APP、INT、POW、EDU 八项属性；年龄范围为 15–90。</p><label class="field"><span>人物卡文本</span><textarea v-model="cardText" rows="10" placeholder="调查员, 记者, 女, 27岁\n时代: 1920s\nSTR 50 CON 55 SIZ 60 DEX 65 APP 60 INT 70 POW 55 EDU 70\n——技能——\n侦查 60%" /></label><button class="button primary" :disabled="!cardText.trim() || busy" @click="execute(createCard)">导入人物卡</button></section>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="dice" class="tabs-content tool-section">
        <div v-if="diceMessages.length" class="dice-history-list">
          <div
            v-for="message in diceMessages"
            :key="message.id"
            class="dice-history-item"
          >
            <DiceRollMessage
              :aggregate="message.diceRoll!"
              :show-icon="false"
              @open="emit('openDice', message.diceRoll!)"
            />
            <button
              type="button"
              class="dice-history-locate"
              aria-label="定位到聊天记录"
              title="定位到聊天记录"
              @click="emit('locateDice', message.id)"
            >
              <LocateFixed :size="15" />
            </button>
          </div>
        </div>
        <div v-else class="dice-history-empty">
          <Dices :size="26" />
          <strong>当前聊天还没有掷骰记录</strong>
          <p>跑团中产生的掷骰会自动出现在这里。</p>
        </div>
      </TabsContent>

      <TabsContent value="dice-debug" class="tabs-content tool-section">
        <DiceDebugPanel @play="(aggregate) => emit('debugDice', aggregate)" />
      </TabsContent>

    </TabsRoot>
    <div v-if="busy" class="dialog-busy"><LoaderCircle class="spin" :size="17" />正在处理…</div>
  </BaseDialog>
</template>
