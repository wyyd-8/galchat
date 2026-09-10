<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { BookOpen, History, LoaderCircle, MousePointer2 } from '@lucide/vue'
import { api } from '@/api/client'
import type { Character } from '@/api/types'
import { createParticipantHistory, formatCompanionDate } from './trpgParticipantHistory'
import { toggleParticipantSelection } from './trpgSetupState'

const props = defineProps<{ worldId: number | null; characters: Character[]; busy: boolean }>()
const selectedIds = defineModel<number[]>({ required: true })
const previewId = ref<number | null>(props.characters[0]?.characterId ?? null)
const preview = computed(() => props.characters.find(character => character.characterId === previewId.value) ?? null)
const history = createParticipantHistory(api)
const { state } = history
const summaries = computed(() => new Map(state.summaries.map(item => [item.characterId, item])))
const currentSummary = computed(() => previewId.value == null ? null : summaries.value.get(previewId.value))
const latest = computed(() => currentSummary.value?.latestRun)
const statusLabels = { active: '进行中', completed: '已完成', closed: '已结束' }

function viewCharacter(id: number) {
  if (previewId.value === id) return
  previewId.value = id
  void history.viewCharacter(id)
}
function toggleCharacter(id: number) {
  selectedIds.value = toggleParticipantSelection(selectedIds.value, previewId.value, id).selectedIds
}
function loadWorld() {
  void history.loadWorld(props.worldId)
  previewId.value = props.characters[0]?.characterId ?? null
  void history.viewCharacter(previewId.value)
}
onMounted(loadWorld)
watch(() => props.worldId, loadWorld)
watch(() => props.characters.map(character => character.characterId), ids => {
  if (previewId.value == null || !ids.includes(previewId.value)) {
    previewId.value = ids[0] ?? null
    void history.viewCharacter(previewId.value)
  }
})
onUnmounted(history.dispose)
</script>

<template>
  <div class="companion-picker">
    <div class="companion-columns">
      <section class="companion-roster" aria-label="可选 AI 同伴">
        <header class="companion-roster-heading"><h3>选择 AI 同伴</h3><span aria-live="polite">已选 {{ selectedIds.length }} 位</span></header>
        <p class="companion-help">可多选，也可以独自开始。</p>
        <div v-if="characters.length" class="companion-choices">
          <div v-for="character in characters" :key="character.characterId" class="companion-choice" :class="{ viewed: previewId === character.characterId, selected: selectedIds.includes(character.characterId) }">
            <button type="button" class="companion-inspect" :aria-pressed="previewId === character.characterId" :aria-label="`查看${character.characterName}的同行档案`" @click="viewCharacter(character.characterId)">
              <span class="companion-avatar" :style="character.characterImage ? { backgroundImage: `url(${character.characterImage})` } : {}">{{ character.characterImage ? '' : character.characterName.slice(0, 1) }}</span>
              <span class="companion-copy"><strong>{{ character.characterName }}</strong>
                <small v-if="state.summaryLoading">加载同行记录…</small>
                <small v-else-if="state.summaryError">同行记录暂不可用</small>
                <template v-else>
                  <small class="companion-meta"><span>{{ summaries.get(character.characterId)?.completedRunCount ? `完成 ${summaries.get(character.characterId)?.completedRunCount} 次` : summaries.get(character.characterId)?.latestRun ? '尚未完成' : '尚未一起跑团' }}</span><span v-if="summaries.get(character.characterId)?.latestRun">最近 {{ formatCompanionDate(summaries.get(character.characterId)?.latestRun?.lastPlayedAt ?? null) }}</span></small>
                </template>
              </span>
            </button>
            <label class="companion-select"><input type="checkbox" :checked="selectedIds.includes(character.characterId)" :disabled="busy" :aria-label="`选择${character.characterName}加入本次跑团`" @change="toggleCharacter(character.characterId)" /></label>
          </div>
        </div>
        <p v-else class="companion-empty">当前世界还没有可选角色。</p>
        <p v-if="characters.length" class="companion-hint"><MousePointer2 :size="13" />点击查看档案，勾选加入队伍</p>
        <div v-if="state.summaryError" class="companion-error" role="alert"><p>同行记录加载失败：{{ state.summaryError }}</p><button type="button" class="button ghost" @click="history.reloadSummaries">重试</button></div>
      </section>
      <section class="companion-detail" aria-label="同行档案">
        <template v-if="preview">
          <header class="companion-identity">
            <span class="companion-avatar large" :style="preview.characterImage ? { backgroundImage: `url(${preview.characterImage})` } : {}">{{ preview.characterImage ? '' : preview.characterName.slice(0, 1) }}</span>
            <div><small>同行档案</small><h3>{{ preview.characterName }}</h3><p v-if="!state.summaryLoading && !state.summaryError">{{ currentSummary?.completedRunCount ? `与你共同完成 ${currentSummary.completedRunCount} 次跑团` : currentSummary?.latestRun ? '已经同行，故事仍在继续' : '期待第一次同行' }}</p></div>
          </header>
          <section class="companion-recent" :aria-busy="state.summaryLoading">
            <h4><History :size="15" />最近一次同行</h4>
            <p v-if="state.summaryLoading" class="companion-loading"><LoaderCircle :size="15" class="spin" />正在加载同行记录…</p>
            <p v-else-if="state.summaryError" class="companion-help">记录暂不可用，你仍可以选择同伴。</p>
            <template v-else-if="latest">
              <div class="companion-recent-title"><strong>{{ latest.title }}</strong><span class="companion-status">{{ statusLabels[latest.status] }}</span></div>
              <p class="companion-help"><time :datetime="latest.lastPlayedAt ?? undefined">{{ formatCompanionDate(latest.lastPlayedAt) }}</time><span> · {{ latest.moduleName ? `模组《${latest.moduleName}》` : '模组已不可用' }}</span></p>
            </template>
            <template v-else><strong class="companion-first">你们的故事，从这里开始</strong><p class="companion-help">还没有共同跑团记录。</p></template>
          </section>
          <section class="companion-history" :aria-busy="state.runsLoading">
            <header><h4>共同完成的跑团</h4><small>{{ !state.summaryLoading && !state.summaryError ? `${currentSummary?.completedRunCount ?? 0} 次 · ` : '' }}当前世界</small></header>
            <div v-for="run in state.runs" :key="run.conversationId" class="companion-run">
              <BookOpen :size="18" aria-hidden="true" />
              <div><strong>{{ run.title }}</strong><small>{{ run.moduleName ? `模组《${run.moduleName}》` : '模组已不可用' }} · 已完成</small></div>
              <time :datetime="run.completedAt ?? undefined">{{ formatCompanionDate(run.completedAt) }}</time>
            </div>
            <p v-if="state.runsLoading" class="companion-loading"><LoaderCircle :size="15" class="spin" />正在加载跑团记录…</p>
            <div v-else-if="state.runsError" class="companion-error" role="alert"><p>跑团记录加载失败：{{ state.runsError }}</p><button type="button" class="button ghost" @click="history.retryRuns">重试</button></div>
            <p v-else-if="!state.runs.length" class="companion-empty">与你一起完成的跑团，会记录在这里。</p>
            <button v-if="state.nextCursor && !state.runsError" type="button" class="button ghost companion-more" :disabled="state.runsLoading" @click="history.loadMore">查看更多记录</button>
          </section>
          <details :key="preview.characterId" class="companion-profile"><summary>当前世界中的角色资料</summary><p>{{ preview.userInfoPrompt || '暂未记录希望角色记住的事。' }}</p></details>
        </template>
        <div v-else class="companion-empty"><h3>单人团</h3><p>将由你独自进入本次跑团。</p></div>
      </section>
    </div>

  </div>
</template>

<style scoped>
.companion-picker { min-width: 0; }
.companion-columns { display: grid; grid-template-columns: minmax(285px, .95fr) minmax(0, 1.15fr); margin-inline: -28px; border-top: 1px solid var(--line); }
.companion-roster { padding: 24px 24px 28px 28px; border-right: 1px solid var(--line); background: #f6f5ef; }
.companion-roster-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.companion-roster-heading h3 { margin: 0; font-size: 14px; }
.companion-roster-heading > span { color: var(--pine); font-size: 12px; white-space: nowrap; }
.companion-help { margin: 5px 0 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.companion-choices { display: grid; gap: 10px; margin-top: 18px; }
.companion-choice { display: flex; align-items: center; gap: 2px; padding-right: 4px; border: 1px solid var(--line); border-radius: 7px; background: var(--surface-strong); }
.companion-choice.viewed { border-color: var(--pine); }
.companion-choice.selected { background: var(--pine-soft); }
.companion-inspect { flex: 1; min-width: 0; display: flex; align-items: center; gap: 12px; padding: 14px 0 14px 12px; border: 0; border-radius: 6px; background: transparent; text-align: left; cursor: pointer; }
.companion-inspect:hover { background: rgba(42, 79, 72, .04); }
.companion-avatar { display: grid; place-items: center; flex: 0 0 auto; width: 32px; height: 36px; border-radius: 7px; background: var(--pine); background-size: cover; background-position: center; color: var(--surface-strong); font-size: 17px; }
.companion-avatar.large { width: 48px; height: 54px; border-radius: 11px; font-size: 24px; }
.companion-copy { display: grid; min-width: 0; gap: 5px; overflow-wrap: anywhere; }
.companion-copy strong { font-size: 13px; font-weight: 600; }
.companion-copy small { color: var(--muted); font-size: 11px; line-height: 1.45; }
.companion-meta { display: flex; flex-wrap: wrap; column-gap: 8px; row-gap: 2px; }
.companion-meta > span { white-space: nowrap; }
.companion-select { display: grid; place-items: center; flex: 0 0 auto; width: 32px; min-height: 44px; cursor: pointer; }
.companion-select input { width: 16px; height: 16px; margin: 0; accent-color: var(--pine); cursor: pointer; }
.companion-hint { display: flex; align-items: center; gap: 6px; margin: 18px 0 0; color: var(--muted); font-size: 11px; }
.companion-hint svg { flex-shrink: 0; }
.companion-detail { min-width: 0; padding: 24px 28px 28px; background: var(--surface-strong); }
.companion-identity { display: flex; align-items: center; gap: 12px; overflow-wrap: anywhere; }
.companion-identity small { color: var(--muted); font-size: 11px; letter-spacing: .06em; }
.companion-identity h3 { margin: 3px 0 0; font-family: var(--font-display); font-size: 18px; }
.companion-identity p { margin: 5px 0 0; color: var(--pine); font-size: 12px; }
.companion-recent { margin: 22px 0 24px; padding: 16px 18px; border-radius: 10px; background: var(--pine-soft); }
.companion-recent h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; color: var(--pine); font-size: 12px; font-weight: 500; }
.companion-recent-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.companion-recent-title strong { overflow-wrap: anywhere; font-size: 14px; font-weight: 600; }
.companion-recent > .companion-help { margin: 5px 0 0; font-size: 11px; }
.companion-status { flex-shrink: 0; padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; color: var(--pine); background: var(--surface-strong); font-size: 11px; }
.companion-first { display: block; font-size: 13px; font-weight: 500; }
.companion-history > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.companion-history h4 { margin: 0; font-size: 13px; font-weight: 600; }
.companion-history header small { color: var(--muted); font-size: 11px; }
.companion-run { display: flex; align-items: center; gap: 12px; padding: 15px 0; border-bottom: 1px solid var(--line); }
.companion-run > svg { flex-shrink: 0; color: var(--muted); }
.companion-run > div { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.companion-run strong { display: block; font-size: 13px; font-weight: 500; }
.companion-run small { display: block; margin-top: 3px; color: var(--muted); font-size: 11px; line-height: 1.5; }
.companion-run time { color: var(--muted); font-size: 11px; white-space: nowrap; }
.companion-empty { margin: 14px 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.companion-empty h3 { color: var(--ink); font-size: 14px; }
.companion-loading { display: flex; align-items: center; gap: 8px; margin: 12px 0; color: var(--muted); font-size: 12px; }
.companion-recent .companion-loading { margin: 0; }
.companion-error { margin-top: 12px; color: var(--wine); font-size: 12px; overflow-wrap: anywhere; }
.companion-error p { margin: 0; }
.companion-error .button { padding: 0 6px; }
.companion-more { display: flex; margin: 6px auto 0; font-size: 12px; }
.companion-profile { margin-top: 20px; font-size: 12px; }
.companion-profile summary { padding: 5px 0; cursor: pointer; }
.companion-profile p { color: var(--muted); line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 680px) {
  .companion-columns { grid-template-columns: 1fr; margin-inline: -16px; }
  .companion-roster { padding: 14px 16px; border-right: 0; border-bottom: 1px solid var(--line); }
  .companion-detail { padding: 20px 16px; }
  .companion-inspect { gap: 9px; padding: 12px 0 12px 9px; }
  .companion-recent { margin: 18px 0 20px; padding: 14px; }
}
@media (max-width: 380px) {
  .companion-run { flex-wrap: wrap; }
  .companion-run time { margin-left: 27px; }
}
@media (pointer: coarse) {
  .companion-select { width: 44px; }
  .companion-profile summary { padding: 13px 0; }
}
</style>
