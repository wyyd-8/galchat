<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BookUser, Check, LoaderCircle, Trash2, UserRound } from '@lucide/vue'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import { api } from '@/api/client'
import type { Character, CharacterCard, Conversation, InvestigatorCardSummary } from '@/api/types'
import { buildBindingTargets } from '@/components/trpgSetupState'
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

function characterName(participantId?: number) {
  return props.characters.find((item) => item.characterId === participantId)?.characterName || `角色 #${participantId}`
}

async function loadSelectedCard() {
  confirmDelete.value = false
  cardText.value = ''
  const cardId = selectedTarget.value?.boundCardId
  card.value = cardId ? await api.characterCardById(cardId) : null
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
        <div v-if="busy && !card" class="binding-empty"><LoaderCircle class="spin" :size="24" /><strong>正在读取人物卡…</strong></div>
        <section v-else-if="card" class="character-sheet binding-sheet">
          <div class="sheet-heading">
            <span><small>{{ card.character.actorType === 'PLAYER' ? '玩家调查员' : 'AI 调查员' }} · {{ card.character.occupation || '未填写职业' }}</small><h3>{{ card.character.name }}</h3><p>{{ card.character.sex || '—' }} · {{ card.character.age || '—' }} 岁 · {{ card.character.era || '时代未填' }}</p></span>
            <div class="vitals"><b>HP {{ card.character.hpCurrent }}/{{ card.character.hpMax }}</b><b>SAN {{ card.character.sanCurrent }}/{{ card.character.sanMax }}</b><b>MP {{ card.character.mpCurrent }}/{{ card.character.mpMax }}</b></div>
          </div>
          <div class="attribute-grid"><span v-for="[name, value] in Object.entries({ STR: card.character.str, CON: card.character.con, SIZ: card.character.siz, DEX: card.character.dex, APP: card.character.app, INT: card.character.intValue, POW: card.character.pow, EDU: card.character.edu })" :key="name"><small>{{ name }}</small><strong>{{ value }}</strong></span></div>
          <div class="sheet-columns"><div><strong>技能</strong><p>{{ card.skills.map((item) => `${item.displayName} ${item.value}%`).join(' · ') || '暂无技能' }}</p></div><div><strong>武器</strong><p>{{ card.weapons.map((item) => `${item.name}${item.damage ? ` ${item.damage}` : ''}`).join(' · ') || '暂无武器' }}</p></div></div>
          <div class="tool-actions"><button class="button" :class="confirmDelete ? 'danger' : 'ghost'" :disabled="busy" @click="execute(removeCard)"><Trash2 :size="15" />{{ confirmDelete ? '确认解除绑定' : '解除并重新绑定' }}</button></div>
        </section>
        <section v-else class="tool-card import-card binding-import-card">
          <BookUser :size="25" />
          <strong>{{ selectedName }}尚未绑定人物卡</strong>
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
