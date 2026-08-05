<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, BookUser, Dices, LoaderCircle, RefreshCw, RotateCcw, Save, Trash2 } from '@lucide/vue'
import { TabsContent, TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { api } from '@/api/client'
import type {
  Character, CharacterCard, CocModule, ContextWindowUsage, Conversation, DiceRollAggregate, DiceRollDetail, DiceRollSummary, TrpgSave,
} from '@/api/types'
import { errorMessage, notify } from '@/composables/useNotice'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ conversation: Conversation; module: CocModule | null; characters: Character[]; latestDiceRoll: DiceRollAggregate | null }>()
const emit = defineEmits<{ restored: [] }>()

const busy = ref(false)
const contextUsage = ref<ContextWindowUsage | null>(null)
const save = ref<TrpgSave | null>(null)
const saveRemark = ref('')
const confirmLoad = ref(false)
const selectedParticipant = ref('')
const card = ref<CharacterCard | null>(null)
const cardText = ref('')
const confirmDelete = ref(false)
const diceId = ref('')
const diceSummary = ref<DiceRollSummary | null>(null)
const diceResults = ref<DiceRollDetail[]>([])

const selectedParticipantId = computed(() => selectedParticipant.value ? Number(selectedParticipant.value) : undefined)
const contextPercent = computed(() => Math.max(0, Math.round((contextUsage.value?.ratio || 0) * 100)))
const contextTone = computed(() => contextPercent.value >= 90 ? 'danger' : contextPercent.value >= 70 ? 'warning' : 'safe')
const selectedActorName = computed(() => selectedParticipantId.value
  ? props.characters.find((item) => item.characterId === selectedParticipantId.value)?.characterName || `角色 #${selectedParticipantId.value}`
  : '玩家调查员')

function time(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '暂无记录' }
function diceStatus(value?: string) {
  return ({ PENDING: '等待投骰', ROLLING: '投骰中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[value || ''] || value || '状态未知'
}
function resultText(item: DiceRollDetail) {
  if (item.resultData?.result !== undefined) return String(item.resultData.result)
  return item.resolvedAt ? '已结算' : '等待玩家投掷'
}
async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await action() }
  catch (error) { notify('跑团工具操作失败', errorMessage(error), 'danger') }
  finally { busy.value = false }
}
async function loadCard() {
  confirmDelete.value = false
  const summaries = await api.investigatorCards(props.conversation.id)
  const summary = summaries.find((item) => selectedParticipantId.value
    ? item.actorType === 'BOT' && item.participantId === selectedParticipantId.value
    : item.actorType === 'PLAYER')
  card.value = summary ? await api.characterCardById(summary.cardId) : null
}
async function refreshOverview() {
  const [usageResult, saveResult] = await Promise.all([
    api.contextWindow(props.conversation.id), api.trpgSave(props.conversation.id),
  ])
  contextUsage.value = usageResult; save.value = saveResult; saveRemark.value = saveResult?.remark || ''
  await loadCard()
}
async function createCard() {
  if (!cardText.value.trim()) throw new Error('请先粘贴人物卡文本')
  card.value = await api.createCharacterCard({ runId: props.conversation.id, participantId: selectedParticipantId.value, characterText: cardText.value.trim() })
  cardText.value = ''; notify('人物卡已导入', card.value.character.name, 'success')
}
async function removeCard() {
  if (!card.value) return
  if (!confirmDelete.value) { confirmDelete.value = true; return }
  await api.deleteCharacterCard(card.value.character.id); card.value = null; confirmDelete.value = false
  notify('人物卡已删除', '', 'success')
}
async function rollLuck() {
  if (!card.value) return
  await api.rollCharacterLuck(card.value.character.id); await loadCard(); notify('幸运值已生成', String(card.value?.character.luckCurrent ?? ''), 'success')
}
async function saveSnapshot() {
  save.value = await api.saveTrpg(props.conversation.id, saveRemark.value); confirmLoad.value = false
  notify('跑团存档已保存', '', 'success')
}
async function loadSnapshot() {
  if (!confirmLoad.value) { confirmLoad.value = true; return }
  await api.loadTrpg(props.conversation.id); confirmLoad.value = false; await refreshOverview(); emit('restored')
  notify('跑团存档已读取', '存档点之后的进度已回滚', 'success')
}
async function loadDice(id = Number(diceId.value)) {
  if (!Number.isFinite(id) || id <= 0) throw new Error('请输入有效的掷骰摘要 ID')
  diceId.value = String(id)
  const [summaryResult, detailResult] = await Promise.all([api.diceSummary(id), api.diceResults(id)])
  diceSummary.value = summaryResult; diceResults.value = detailResult
}
async function rollResult(id: number) {
  const progress = await api.rollDiceResult(id)
  diceSummary.value = progress.summary
  await loadDice(progress.summary.id)
  notify('掷骰已完成', progress.summary.totalResult || String(progress.rolledResult.resultData?.result ?? ''), 'success')
}

watch(open, (visible) => { if (visible) void execute(refreshOverview) })
watch(selectedParticipant, () => { if (open.value) void execute(loadCard) })
watch(() => props.latestDiceRoll, (aggregate) => {
  if (!aggregate) return
  diceId.value = String(aggregate.summary.id); diceSummary.value = aggregate.summary; diceResults.value = aggregate.results
}, { immediate: true })
</script>

<template>
  <BaseDialog v-model="open" title="跑团工具" :description="`${conversation.title} · ${module?.name || `模组 #${conversation.moduleId || '未记录'}`}`" size="lg">
    <TabsRoot default-value="status" class="tabs trpg-tools">
      <TabsList class="tabs-list">
        <TabsTrigger value="status"><Activity :size="15" />状态与存档</TabsTrigger>
        <TabsTrigger value="card"><BookUser :size="15" />人物卡</TabsTrigger>
        <TabsTrigger value="dice"><Dices :size="15" />骰子</TabsTrigger>
      </TabsList>

      <TabsContent value="status" class="tabs-content tool-section">
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>最近一次 KP 提示词长度</strong><small>{{ contextUsage ? `${contextUsage.characterCount.toLocaleString()} / ${contextUsage.softLimit.toLocaleString()} 字符` : '尚无记录' }}</small></span><button class="icon-button bordered" :disabled="busy" title="刷新" @click="execute(refreshOverview)"><RefreshCw :size="15" /></button></div>
          <div class="context-meter" :class="contextTone"><i :style="{ width: `${Math.min(contextPercent, 100)}%` }" /></div><small>{{ contextPercent }}% · {{ contextUsage ? `更新于 ${time(contextUsage.updatedAt)}` : '模型执行一次跑团行动后显示' }}</small>
        </section>
        <section class="tool-card">
          <div class="tool-card-heading"><span><strong>跑团存档</strong><small>{{ save ? `${time(save.savedAt)} · 格式 v${save.formatVersion || 1}` : '尚未创建存档' }}</small></span><Save :size="18" /></div>
          <label class="field"><span>存档备注</span><textarea v-model.trim="saveRemark" rows="3" maxlength="200" placeholder="记录当前场景、线索或风险…" /></label>
          <div v-if="save?.investigators?.length" class="investigator-grid"><span v-for="item in save.investigators" :key="item.characterId"><strong>{{ item.name }}</strong><small>HP {{ item.hpCurrent }}/{{ item.hpMax }} · SAN {{ item.sanCurrent }}/{{ item.sanMax }} · MP {{ item.mpCurrent }}/{{ item.mpMax }}</small><em v-if="item.dead">死亡</em><em v-else-if="item.dying">濒死</em><em v-else-if="item.unconscious">昏迷</em></span></div>
          <div class="tool-actions"><button class="button secondary" :disabled="busy" @click="execute(saveSnapshot)"><Save :size="16" />{{ save ? '覆盖存档' : '创建存档' }}</button><button class="button" :class="confirmLoad ? 'danger' : 'ghost'" :disabled="!save || busy" @click="execute(loadSnapshot)"><RotateCcw :size="16" />{{ confirmLoad ? '再次点击确认读档' : '读取存档' }}</button></div>
          <p v-if="confirmLoad" class="destructive-note">读档会删除存档点之后的行动轮、消息、骰子和人物状态，此操作不可撤销。</p>
        </section>
      </TabsContent>

      <TabsContent value="card" class="tabs-content tool-section">
        <label class="field"><span>查看对象</span><select v-model="selectedParticipant"><option value="">玩家调查员</option><option v-for="item in characters" :key="item.characterId" :value="String(item.characterId)">{{ item.characterName }}（AI 调查员）</option></select></label>
        <section v-if="card" class="character-sheet">
          <div class="sheet-heading"><span><small>{{ card.character.actorType === 'PLAYER' ? '玩家调查员' : card.character.actorType === 'BOT' ? 'AI 调查员' : '模组角色' }} · {{ card.character.occupation || '未填写职业' }}</small><h3>{{ card.character.name }}</h3><p>{{ card.character.sex || '—' }} · {{ card.character.age || '—' }} 岁 · {{ card.character.era || '时代未填' }}</p></span><div class="vitals"><b>HP {{ card.character.hpCurrent }}/{{ card.character.hpMax }}</b><b>SAN {{ card.character.sanCurrent }}/{{ card.character.sanMax }}</b><b>MP {{ card.character.mpCurrent }}/{{ card.character.mpMax }}</b></div></div>
          <div class="attribute-grid"><span v-for="[name, value] in Object.entries({ STR: card.character.str, CON: card.character.con, SIZ: card.character.siz, DEX: card.character.dex, APP: card.character.app, INT: card.character.intValue, POW: card.character.pow, EDU: card.character.edu })" :key="name"><small>{{ name }}</small><strong>{{ value }}</strong></span></div>
          <div class="sheet-columns"><div><strong>技能</strong><p>{{ card.skills.map((item) => `${item.displayName} ${item.value}%`).join(' · ') || '暂无技能' }}</p></div><div><strong>武器</strong><p>{{ card.weapons.map((item) => `${item.name}${item.damage ? ` ${item.damage}` : ''}`).join(' · ') || '暂无武器' }}</p></div></div>
          <div class="tool-actions"><button class="button secondary" :disabled="card.character.luckCurrent != null || busy" @click="execute(rollLuck)"><Dices :size="16" />{{ card.character.luckCurrent == null ? '投掷幸运' : `幸运 ${card.character.luckCurrent}` }}</button><button class="button" :class="confirmDelete ? 'danger' : 'ghost'" :disabled="busy" @click="execute(removeCard)"><Trash2 :size="16" />{{ confirmDelete ? '确认删除' : '删除人物卡' }}</button></div>
        </section>
        <section v-else class="tool-card import-card"><strong>{{ selectedActorName }}尚未建立人物卡</strong><p>首行必须是“姓名, 职业, 性别, 年龄岁”，并包含 STR、CON、SIZ、DEX、APP、INT、POW、EDU 八项属性；年龄范围为 15–90。</p><label class="field"><span>人物卡文本</span><textarea v-model="cardText" rows="10" placeholder="调查员, 记者, 女, 27岁\n时代: 1920s\nSTR 50 CON 55 SIZ 60 DEX 65 APP 60 INT 70 POW 55 EDU 70\n——技能——\n侦查 60%" /></label><button class="button primary" :disabled="!cardText.trim() || busy" @click="execute(createCard)">导入人物卡</button></section>
      </TabsContent>

      <TabsContent value="dice" class="tabs-content tool-section">
        <div class="field-inline"><label class="field"><span>掷骰摘要 ID</span><input v-model="diceId" inputmode="numeric" placeholder="仅在已知摘要 ID 时手动查询" /></label><button class="button secondary dice-load-button" :disabled="!diceId || busy" @click="execute(() => loadDice())"><RefreshCw :size="16" />查询</button></div>
        <section v-if="diceSummary" class="tool-card dice-summary"><div class="tool-card-heading"><span><strong>{{ diceSummary.reason || `掷骰 #${diceSummary.id}` }}</strong><small>{{ diceStatus(diceSummary.status) }} · {{ diceSummary.roundCount || 1 }} 轮</small></span><b>{{ diceSummary.totalResult || '进行中' }}</b></div></section>
        <div v-if="diceResults.length" class="dice-result-list"><article v-for="item in diceResults" :key="item.id"><span class="dice-result-value">{{ resultText(item) }}</span><span><strong>{{ item.reason || `骰位 #${item.id}` }}</strong><small>{{ item.resultData?.formula || item.displayType || '待投骰' }} · 第 {{ item.roundNo || 1 }} 轮</small></span><button v-if="!item.resolvedAt" class="button secondary" :disabled="busy" @click="execute(() => rollResult(item.id))"><Dices :size="15" />投掷</button></article></div>
        <div v-else class="empty-panel compact"><Dices :size="24" /><p>打开本页面后收到的新掷骰事件会自动显示；历史掷骰仅支持按已知摘要 ID 查询。</p></div>
      </TabsContent>
    </TabsRoot>
    <div v-if="busy" class="dialog-busy"><LoaderCircle class="spin" :size="17" />正在处理…</div>
  </BaseDialog>
</template>
