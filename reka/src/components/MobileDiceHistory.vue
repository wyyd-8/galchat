<script setup lang="ts">
import { computed } from 'vue'
import { Search, LoaderCircle } from '@lucide/vue'
import type { DiceRollAggregate } from '@/api/types'
import { DICE_HISTORY_CATEGORY_OPTIONS, formatDiceHistoryTime } from '@/dice/domain/dicePlayback'
import type { DiceHistoryCategory, DiceHistoryEntry, DiceHistoryResultKind } from '@/dice/domain/dicePlayback'
import { mobileHistoryDay, mobileHistoryValues, mobileHistoryTitle, mobileHistoryReasons } from '@/components/mobileHistoryPresentation'
const query = defineModel<string>('query', { required: true })
const category = defineModel<DiceHistoryCategory | ''>('category', { required: true })
const result = defineModel<DiceHistoryResultKind | ''>('result', { required: true })
const props = defineProps<{ entries: DiceHistoryEntry[]; allCount: number; matchCopy: string; filtersActive: boolean; loadingOlder: boolean; hasOlder: boolean; loadLabel: string; loadHint: string }>()
const emit = defineEmits<{ clear: []; replay: [aggregate: DiceRollAggregate]; locate: [messageId: number]; earlier: [] }>()
const groups = computed(() => {
  const grouped: { day: string; entries: DiceHistoryEntry[] }[] = []
  for (const entry of props.entries) {
    const day = mobileHistoryDay(entry.occurredAt)
    const last = grouped.at(-1)
    if (last?.day === day) last.entries.push(entry)
    else grouped.push({ day, entries: [entry] })
  }
  return grouped
})
</script>
<template>
  <section class="mobile-history">
    <label class="history-search"><Search :size="20" /><input v-model="query" type="search" aria-label="按角色或检定名称筛选" placeholder="按角色或检定名称筛选" /></label>
    <label class="field"><span>检定类型</span><select v-model="category" aria-label="按掷骰类别筛选"><option value="">全部类别</option><option v-for="item in DICE_HISTORY_CATEGORY_OPTIONS" :key="item" :value="item">{{ item }}</option></select></label>
    <label class="field"><span>检定结果</span><select v-model="result" aria-label="按检定结果筛选"><option value="">全部结果</option><option value="numeric">数值</option><option value="success">成功</option><option value="failure">失败</option><option value="other">其他</option></select></label>
    <div v-if="filtersActive" class="history-filter-summary"><span>{{ matchCopy }}</span><button type="button" @click="emit('clear')">清除筛选</button></div>
    <section v-for="(group, index) in groups" :key="`${group.day}-${index}`" class="history-day">
      <h3>{{ group.day }}</h3>
      <article v-for="entry in group.entries" :key="`${entry.messageId}:${entry.aggregate.results[0]?.roundNo || 1}`" class="history-card">
        <header><strong>{{ mobileHistoryTitle(entry) }}</strong><span class="history-tag" :class="{ danger: entry.resultKind === 'failure' }">{{ entry.statusLabel }}</span></header>
        <div v-for="value in mobileHistoryValues(entry)" :key="value.key" class="history-value">
          <span v-if="mobileHistoryValues(entry).length > 1" class="history-value-name">{{ value.name || '掷骰结果' }}</span>
          <div class="history-value-result"><strong>{{ value.value ?? '—' }} <small v-if="value.target != null">/ {{ value.target }}</small></strong><span v-if="mobileHistoryValues(entry).length > 1 && value.outcome" class="history-tag" :class="{ danger: value.outcome.failure }">{{ value.outcome.label }}</span></div>
        </div>
        <p v-if="!entry.aggregate.results.length" class="history-no-value">尚无可展示的骰点</p>
        <p class="history-meta">{{ entry.category }}<template v-if="formatDiceHistoryTime(entry.occurredAt)"> · {{ formatDiceHistoryTime(entry.occurredAt) }}</template><template v-if="(entry.aggregate.results[0]?.roundNo || 1) > 1"> · 第 {{ entry.aggregate.results[0]?.roundNo }} 次掷骰</template></p>
        <details v-if="mobileHistoryReasons(entry).length" class="history-reason"><summary>查看行动说明</summary><p v-for="reason in mobileHistoryReasons(entry)" :key="reason">{{ reason }}</p></details>
        <div class="history-actions"><button class="button secondary" type="button" @click="emit('replay', entry.aggregate)">查看 / 回放</button><button class="button secondary" type="button" @click="emit('locate', entry.messageId)">定位消息</button></div>
      </article>
    </section>
    <div v-if="!entries.length" class="history-empty"><h3>{{ allCount ? '没有符合条件的掷骰记录' : '当前聊天还没有掷骰记录' }}</h3><p>{{ allCount ? '可以调整筛选，或继续查找更早的掷骰记录。' : '跑团中产生的掷骰会自动出现在这里。' }}</p></div>
    <footer class="history-load"><button class="button secondary" type="button" :disabled="loadingOlder || !hasOlder" @click="emit('earlier')"><LoaderCircle v-if="loadingOlder" class="spin" :size="15" />{{ loadLabel }}</button><p v-if="loadHint">{{ loadHint }}</p></footer>
  </section>
</template>
<style scoped>
.mobile-history { min-width:0;color:#252724; }
.history-search { display:flex;align-items:center;background:#fffefa;border:1px solid #dedbd2;border-radius:10px;margin:0 0 15px;padding:0 10px;gap:7px;color:#7f887b; }.history-search input { width:100%;min-width:0;border:0;background:transparent;padding:12px 0;min-height:48px;font-size:16px;line-height:1.6; }
.history-day>h3 { display:flex;align-items:center;min-height:30px;margin:23px 0 12px;font-size:16px;font-weight:600;line-height:1.5; }
.history-card { background:#fbfaf6;border:1px solid #dedbd2;border-radius:13px;padding:17px;margin:12px 0; }.history-card>header { display:flex;justify-content:space-between;align-items:center;gap:12px; }.history-card>header>strong { font-size:14px;line-height:1.6;font-weight:550;overflow-wrap:anywhere; }
.history-tag { display:inline-block;flex:none;max-width:50%;font-size:11px;color:#294f49;background:#e4ece8;padding:4px 8px;border-radius:6px;line-height:1.6;overflow-wrap:anywhere; }.history-tag.danger { color:#8a3f48;background:#f0e0e2; }
.history-value { margin:15px 0; }.history-value-result>strong { display:block;font:36px Georgia;color:#294f49;overflow-wrap:anywhere; }.history-value small { font-size:18px;color:#777970; }.history-value-name { display:block;font-size:12px;color:#777970;line-height:1.6;margin-bottom:5px; }
.history-value-result { display:flex;align-items:center;justify-content:space-between;gap:12px; }
.history-reason { color:#777970;font-size:12px;line-height:1.8;margin:8px 0; }.history-reason summary { cursor:pointer;min-height:24px; }.history-reason p { margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere; }
.history-meta,.history-no-value { font-size:13px;line-height:1.8;color:#777970;margin:8px 0 12px; }
.history-actions { display:flex;gap:10px;margin-top:16px; }.history-actions>.button { flex:1;min-width:0;min-height:46px;border-radius:11px;font-size:14px;padding:10px 8px; }
.history-filter-summary { display:flex;align-items:center;gap:8px;font-size:12px;line-height:1.6;color:#777970; }.history-filter-summary>span { flex:1; }.history-filter-summary>button { border:0;background:transparent;min-height:44px;color:#294f49;font-size:12px; }
.history-empty { margin:23px 0;padding:17px;border:1px solid #dedbd2;border-radius:13px;background:#fbfaf6; }.history-empty h3 { font-size:16px;margin:0; }.history-empty p,.history-load p { font-size:13px;line-height:1.8;color:#777970; }.history-load { margin-top:23px; }.history-load>.button { width:100%; }
</style>
