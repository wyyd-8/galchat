<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BookUser, Check, LoaderCircle, RefreshCw, Sparkles, Trash2, UserRound } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { api } from '@/api/client'
import type { Character, CharacterCard, CharacterCardCreationDraft, Conversation, InvestigatorCardSummary } from '@/api/types'
import { buildBindingTargets, canAutoGenerateCard, loadBindingTargetContent } from '@/components/trpgSetupState'
import { errorMessage, notify } from '@/composables/useNotice'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{
  conversation: Conversation | null
  characters: Character[]
  participantIds: number[]
}>()
const emit = defineEmits<{ complete: [] }>()

const busy = ref(false)
const cards = ref<InvestigatorCardSummary[]>([])
const selectedKey = ref('player')
const card = ref<CharacterCard | null>(null)
const draft = ref<CharacterCardCreationDraft | null>(null)
const cardText = ref('')
const confirmDelete = ref(false)

const targets = computed(() => buildBindingTargets(props.participantIds, cards.value))
const selectedTarget = computed(() => targets.value.find((target) => target.key === selectedKey.value) || targets.value[0])
const selectedCharacter = computed(() => props.characters.find((item) => item.characterId === selectedTarget.value?.participantId))
const selectedName = computed(() => selectedTarget.value?.actorType === 'PLAYER'
  ? '玩家调查员'
  : selectedCharacter.value?.characterName || `角色 #${selectedTarget.value?.participantId}`)
const completedCount = computed(() => targets.value.filter((target) => target.boundCardId !== undefined).length)
const complete = computed(() => targets.value.length > 0 && completedCount.value === targets.value.length)
const displayCard = computed(() => card.value || draft.value?.state.preview || null)
const autoGenerationAvailable = computed(() => canAutoGenerateCard(selectedTarget.value))

function characterName(participantId?: number) {
  return props.characters.find((item) => item.characterId === participantId)?.characterName || `角色 #${participantId}`
}

async function loadSelectedCard() {
  confirmDelete.value = false
  cardText.value = ''
  const content = await loadBindingTargetContent(
    selectedTarget.value,
    (cardId) => api.characterCardById(cardId),
    (participantId) => props.conversation
      ? api.activeCharacterCardDraft(props.conversation.id, participantId)
      : Promise.resolve(null),
  )
  card.value = content.card
  draft.value = content.draft
}

function requestId() {
  return crypto.randomUUID?.() || `card-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function generateCard() {
  if (!props.conversation || !selectedTarget.value?.participantId) return
  draft.value = await api.createAutoCharacterCardDraft({
    runId: props.conversation.id,
    participantId: selectedTarget.value.participantId,
    requestId: requestId(),
  })
}

async function regenerateCard() {
  if (!draft.value) return
  draft.value = await api.regenerateCharacterCardDraft(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
}

async function rewriteBackground() {
  if (!draft.value) return
  draft.value = await api.rewriteCharacterCardBackground(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
}

async function confirmGeneratedCard() {
  if (!draft.value) return
  await api.completeCharacterCardDraft(draft.value.draftId, {
    requestId: requestId(),
    expectedVersion: draft.value.version,
  })
  draft.value = null
  await refreshCards()
  notify('人物卡已生成并绑定', selectedName.value, 'success')
}

async function refreshCards(selectFirstMissing = false) {
  if (!props.conversation) return
  cards.value = await api.investigatorCards(props.conversation.id)
  if (selectFirstMissing) {
    selectedKey.value = buildBindingTargets(props.participantIds, cards.value)
      .find((target) => target.boundCardId === undefined)?.key || 'player'
  }
  await loadSelectedCard()
}

async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
  } catch (error) {
    notify('人物卡绑定失败', errorMessage(error), 'danger')
  } finally {
    busy.value = false
  }
}

async function selectTarget(key: string) {
  if (busy.value) return
  selectedKey.value = key
  await execute(loadSelectedCard)
}

async function bindCard() {
  if (!props.conversation || !selectedTarget.value || !cardText.value.trim()) return
  await api.createCharacterCard({
    runId: props.conversation.id,
    participantId: selectedTarget.value.participantId,
    characterText: cardText.value.trim(),
  })
  await refreshCards()
  notify('人物卡已绑定', selectedName.value, 'success')
}

async function removeCard() {
  if (!card.value) return
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  await api.deleteCharacterCard(card.value.character.id)
  await refreshCards()
  notify('人物卡已解除绑定', selectedName.value, 'success')
}

function finish() {
  if (!complete.value) return
  open.value = false
  emit('complete')
}

watch(open, (visible) => {
  if (visible) void execute(() => refreshCards(true))
}, { immediate: true })
watch(() => props.conversation?.id, () => {
  selectedKey.value = 'player'
  cards.value = []
  card.value = null
  draft.value = null
})
</script>

<template>
  <BaseDialog
    v-model="open"
    title="绑定调查员人物卡"
    description="第三阶段 · 为玩家和每位 AI 调查员准备本次跑团使用的人物卡。"
    size="lg"
    content-class="trpg-binding-dialog"
  >
    <div class="trpg-binding-layout">
      <section class="trpg-binding-list-pane">
        <header class="settings-section-heading">
          <span><strong>调查员</strong><small>选择左侧对象，在右侧查看或绑定人物卡</small></span>
          <em>{{ completedCount }}/{{ targets.length }} 已绑定</em>
        </header>
        <div class="character-choice-list trpg-binding-targets">
          <button
            type="button"
            class="choice-row"
            :class="{ active: selectedKey === 'player' }"
            @click="selectTarget('player')"
          >
            <span class="character-avatar small player-avatar"><UserRound :size="17" /></span>
            <span><strong>玩家调查员</strong><small>由当前登录用户控制</small></span>
            <span class="binding-state" :class="{ complete: targets[0]?.boundCardId !== undefined }">
              <Check v-if="targets[0]?.boundCardId !== undefined" :size="13" />{{ targets[0]?.boundCardId !== undefined ? '已绑定' : '待绑定' }}
            </span>
          </button>
          <button
            v-for="target in targets.slice(1)"
            :key="target.key"
            type="button"
            class="choice-row"
            :class="{ active: selectedKey === target.key }"
            @click="selectTarget(target.key)"
          >
            <span
              class="character-avatar small"
              :style="characters.find((item) => item.characterId === target.participantId)?.characterImage ? { backgroundImage: `url(${characters.find((item) => item.characterId === target.participantId)?.characterImage})` } : {}"
            >{{ characters.find((item) => item.characterId === target.participantId)?.characterImage ? '' : characterName(target.participantId).slice(0, 1) }}</span>
            <span><strong>{{ characterName(target.participantId) }}</strong><small>AI 调查员</small></span>
            <span class="binding-state" :class="{ complete: target.boundCardId !== undefined }">
              <Check v-if="target.boundCardId !== undefined" :size="13" />{{ target.boundCardId !== undefined ? '已绑定' : '待绑定' }}
            </span>
          </button>
        </div>
      </section>

      <aside class="trpg-binding-card-pane">
        <div v-if="busy && !displayCard" class="binding-empty"><LoaderCircle class="spin" :size="24" /><strong>正在处理人物卡…</strong><p>自动生成时，AI 会确定职业与排序，并依据背景骰补全人物经历。</p></div>
        <section v-else-if="displayCard" class="character-sheet binding-sheet">
          <div class="sheet-heading">
            <span><small>{{ displayCard.character.actorType === 'PLAYER' ? '玩家调查员' : 'AI 调查员' }} · {{ displayCard.character.occupation || '未填写职业' }}</small><h3>{{ displayCard.character.name }}</h3><p>{{ displayCard.character.sex || '—' }} · {{ displayCard.character.age || '—' }} 岁 · {{ displayCard.character.era || '时代未填' }}</p></span>
            <div class="vitals"><b>HP {{ displayCard.character.hpCurrent }}/{{ displayCard.character.hpMax }}</b><b>SAN {{ displayCard.character.sanCurrent }}/{{ displayCard.character.sanMax }}</b><b>MP {{ displayCard.character.mpCurrent }}/{{ displayCard.character.mpMax }}</b></div>
          </div>
          <div class="attribute-grid"><span v-for="[name, value] in Object.entries({ STR: displayCard.character.str, CON: displayCard.character.con, SIZ: displayCard.character.siz, DEX: displayCard.character.dex, APP: displayCard.character.app, INT: displayCard.character.intValue, POW: displayCard.character.pow, EDU: displayCard.character.edu })" :key="name"><small>{{ name }}</small><strong>{{ value }}</strong></span></div>
          <div class="sheet-columns"><div><strong>技能</strong><p>{{ displayCard.skills.map((item) => `${item.displayName} ${item.value}%`).join(' · ') || '暂无技能' }}</p></div><div><strong>武器与装备</strong><p>{{ displayCard.weapons.map((item) => `${item.name}${item.damage ? ` ${item.damage}` : ''}`).join(' · ') || '无武器' }}<br>{{ displayCard.profile?.equipmentText || '无额外装备' }}</p></div></div>
          <div v-if="draft && displayCard.profile" class="sheet-columns">
            <div><strong>形象与信念</strong><p>{{ displayCard.profile.appearance }}<br>{{ displayCard.profile.ideology }}</p></div>
            <div><strong>重要联系</strong><p>{{ displayCard.profile.significantPeople }}<br>{{ displayCard.profile.keyConnectionText }}</p></div>
            <div><strong>地点与珍宝</strong><p>{{ displayCard.profile.meaningfulLocations }}<br>{{ displayCard.profile.treasuredPossessions }}</p></div>
            <div><strong>特质</strong><p>{{ displayCard.profile.traits }}</p></div>
          </div>
          <div v-if="draft" class="tool-actions">
            <button class="button ghost" :disabled="busy" @click="execute(regenerateCard)"><RefreshCw :size="15" />完整重试</button>
            <button class="button ghost" :disabled="busy" @click="execute(rewriteBackground)"><Sparkles :size="15" />重骰并重写背景</button>
            <button class="button primary" :disabled="busy" @click="execute(confirmGeneratedCard)"><Check :size="15" />确认并绑定</button>
          </div>
          <div v-else class="tool-actions"><button class="button" :class="confirmDelete ? 'danger' : 'ghost'" :disabled="busy" @click="execute(removeCard)"><Trash2 :size="15" />{{ confirmDelete ? '确认解除绑定' : '解除并重新绑定' }}</button></div>
        </section>
        <section v-else class="tool-card import-card binding-import-card">
          <BookUser :size="25" />
          <strong>{{ selectedName }}尚未绑定人物卡</strong>
          <template v-if="autoGenerationAvailable">
            <p>可依据该角色的性格、背景、玩法偏好与当前模组，用快速开始规则自动生成。</p>
            <button class="button primary" :disabled="busy" @click="execute(generateCard)"><Sparkles :size="15" />AI 自动生成</button>
            <small>生成后可完整重试，或仅重骰并重写背景；确认前不会绑定。</small>
          </template>
          <p>粘贴人物卡文本；首行填写姓名、职业、性别与年龄，并包含八项基础属性。</p>
          <label class="field"><span>人物卡文本</span><textarea v-model="cardText" rows="12" placeholder="调查员, 记者, 女, 27岁\n时代: 1920s\nSTR 50 CON 55 SIZ 60 DEX 65 APP 60 INT 70 POW 55 EDU 70\n——技能——\n侦查 60%" /></label>
          <button class="button primary" :disabled="!cardText.trim() || busy" @click="execute(bindCard)">绑定人物卡</button>
        </section>
      </aside>
    </div>
    <div v-if="busy" class="dialog-busy"><LoaderCircle class="spin" :size="17" />正在处理…</div>
    <template #footer>
      <span class="binding-footer-status">{{ complete ? '全部人物卡已绑定' : `仍有 ${targets.length - completedCount} 张人物卡待绑定` }}</span>
      <button class="button ghost" :disabled="busy" @click="open = false">稍后处理</button>
      <button class="button primary" :disabled="!complete || busy" @click="finish">完成并进入跑团</button>
    </template>
  </BaseDialog>
</template>
