<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Check, ChevronDown, Dices, LoaderCircle } from '@lucide/vue'
import type { TrpgCompletionReport, TrpgCompletionPerson } from '@/api/types'
import { completionDice, completionOutcomes } from './trpgCompletionState'

const props = defineProps<{ report: TrpgCompletionReport | null; loading: boolean; busy: boolean; error: string }>()
const emit = defineEmits<{ reload: []; archive: []; back: [] }>()
const page = ref(0)
const selectedPerson = ref<number | null>(null)
const container = ref<HTMLElement | null>(null)
const heading = ref<HTMLElement | null>(null)
const content = ref<HTMLElement | null>(null)
const directory = ref<HTMLDetailsElement | null>(null)
const sections = ['跑团概览', '共同旅程', '调查员后传', '掷骰回顾', '战斗回顾']
const dice = computed(() => completionDice(props.report?.rolls || [], selectedPerson.value))
const team = computed(() => completionDice(props.report?.rolls || [], null))
const date = computed(() => props.report?.completedAt?.slice(0, 10).replaceAll('-', '.') || '')
watch(() => props.report?.title, () => { page.value = 0; selectedPerson.value = null })
function state(person: TrpgCompletionPerson) {
  if (person.dead) return '已逝'
  const states = [person.dying && '濒死', person.unconscious && '昏迷', person.majorWound && '重伤', person.temporaryInsanity && '临时疯狂'].filter(Boolean)
  return states.length ? states.join(' · ') : '存活'
}
function name(id: number) { return props.report?.investigators.find((person) => person.characterId === id)?.name || '调查员' }
function outcomeLabel(value: string) { return completionOutcomes.find((item) => item.value === value)?.label || value }
function closeDirectory() {
  if (directory.value) directory.value.open = false
}
function dismissDirectory() {
  closeDirectory()
  directory.value?.querySelector('summary')?.focus()
}
async function goToPage(index: number) {
  page.value = Math.max(0, Math.min(sections.length - 1, index))
  closeDirectory()
  await nextTick()
  container.value?.scrollTo({ top: 0, behavior: 'instant' })
  content.value?.scrollTo({ top: 0, behavior: 'instant' })
  heading.value?.focus({ preventScroll: true })
}
function move(delta: number) { return goToPage(page.value + delta) }
function selectPerson(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  selectedPerson.value = value === 'all' ? null : Number(value)
}
</script>

<template>
  <main ref="container" class="completion-stage">
    <header class="completion-top">
      <button class="text-button" @click="emit('back')"><ArrowLeft :size="14" />返回当前跑团</button>
      <nav v-if="!loading && report?.status === 'ready'" class="completion-progress" aria-label="完成记录进度"><span v-for="(label, index) in sections" :key="label" :class="{ current: index === page, done: index < page }" :aria-current="index === page ? 'step' : undefined"><i><Check v-if="index < page" :size="12" /><template v-else>{{ index === 0 ? '概' : `0${index}` }}</template></i>{{ label }}</span></nav>
      <details v-if="!loading && report?.status === 'ready'" ref="directory" class="completion-directory" @keydown.esc.prevent.stop="dismissDirectory">
        <summary><span>{{ sections[page] }}</span><small>{{ page + 1 }} / {{ sections.length }}</small><ChevronDown :size="16" /></summary>
        <nav aria-label="完成记录章节目录">
          <button v-for="(label, index) in sections" :key="label" :aria-current="index === page ? 'page' : undefined" @click="goToPage(index)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ label }}<Check v-if="index === page" :size="16" /></button>
        </nav>
      </details>
    </header>
    <div ref="content" class="completion-content">
    <div v-if="loading || report?.status !== 'ready'" class="completion-wait" aria-live="polite">
      <LoaderCircle v-if="loading" :size="34" class="spin" />
      <h1>{{ loading ? '正在读取完成记录' : '暂时无法读取完成记录' }}</h1>
      <p v-if="error" role="alert">{{ error }}</p>
      <button v-if="!loading" class="button ghost" @click="emit('reload')">重新读取</button>
    </div>
    <template v-else-if="report">
      <div class="completion-book">
        <div class="completion-mobile-progress" aria-hidden="true"><i v-for="(_, index) in sections" :key="index" :class="{ active: index <= page }" /></div>
        <h1 ref="heading" class="sr-only" tabindex="-1">{{ sections[page] }}</h1>
        <section v-show="page === 0" class="completion-opening">
          <div class="completion-cover" :class="{ illustrated: report.coverUrl }">
            <img v-if="report.coverUrl" :src="report.coverUrl" alt="" />
            <div class="completion-cover-copy"><small>CALL OF CTHULHU · 跑团记录</small><span class="completion-sigil">✦</span><h2>{{ report.title }}</h2><span>{{ date }}</span><strong>已完成</strong></div>
          </div>
          <div class="completion-opening-copy"><small class="completion-kicker">本次跑团已完成</small><h2>你与同伴的跑团总结</h2><p class="completion-prose">{{ report.ending }}</p><div class="completion-team"><span v-for="person in report.investigators" :key="person.characterId">{{ person.name }}<small v-if="person.dead"> · 已逝</small></span></div><div class="completion-totals"><div><strong>{{ report.investigators.length }}</strong><span>位参与调查员</span></div><div><strong>{{ report.turnCount }}</strong><span>个行动轮</span></div><div><strong>{{ team.total }}</strong><span>次公开检定</span></div></div><p class="completion-footnote">{{ date }} · {{ report.archivedAt ? '已归档，可随时查看' : '完成记录已保存' }}</p></div>
        </section>
        <section v-show="page === 1">
          <div class="completion-section-heading"><small class="completion-kicker">01 / 共同旅程</small><h2>主要经历回顾</h2><p>按时间顺序回顾你们的主要经历。以下内容节选自主场景摘要，可展开查看对应的完整摘要。</p></div>
          <div class="completion-journey"><article v-for="(chapter, index) in report.journey" :key="index"><span class="completion-chapter-number">{{ String(index + 1).padStart(2, '0') }}</span><div><h3>{{ chapter.title }}</h3><p class="completion-prose">{{ chapter.excerpt }}</p><details><summary>查看完整场景摘要</summary><p class="completion-full-text">{{ chapter.summary }}</p></details></div></article></div>
          <p v-if="!report.journey.length" class="completion-empty">本次跑团没有可展示的场景摘要。可继续查看调查员后传与掷骰统计。</p>
        </section>
        <section v-show="page === 2">
          <div class="completion-section-heading"><small class="completion-kicker">02 / 调查员后传</small><h2>调查员状态与后传</h2><p>查看每位调查员结束跑团时的状态、生命与理智数值，以及人物后传。数值间的箭头表示从初始记录到结束时的变化。</p></div>
          <div class="completion-people"><article v-for="person in report.investigators" :key="person.characterId" :class="{ dead: person.dead }"><header><div class="completion-avatar"><img v-if="person.image" :src="person.image" alt="" /><span v-else>{{ person.name.slice(0, 1) }}</span></div><div><h3>{{ person.name }}<small v-if="person.player">你的调查员</small></h3><p>{{ person.occupation || '调查员' }}</p></div><span class="completion-person-state">{{ state(person) }}</span></header><div class="completion-resources"><div><small>生命 HP</small><p><template v-if="person.initialHp != null"><del>{{ person.initialHp }}</del><span>→</span></template><strong>{{ person.hp ?? '—' }}</strong></p></div><div><small>理智 SAN</small><p><template v-if="person.initialSan != null"><del>{{ person.initialSan }}</del><span>→</span></template><strong>{{ person.san ?? '—' }}</strong></p></div></div><small class="completion-kicker">后传概要</small><p class="completion-prose">{{ person.lead }}</p><details><summary>展开人物后传</summary><p class="completion-full-text">{{ person.epilogue }}</p></details></article></div>
        </section>
        <section v-show="page === 3">
          <div class="completion-section-heading"><small class="completion-kicker">03 / 掷骰回顾</small><h2>检定结果统计</h2><p>查看团队的公开检定次数与结果分布，也可选择一位调查员查看个人统计。</p></div>
          <div class="completion-dice"><label class="completion-person-select" for="completion-person-select">统计对象<select id="completion-person-select" :value="selectedPerson ?? 'all'" @change="selectPerson"><option value="all">全体调查员</option><option v-for="person in report.investigators" :key="person.characterId" :value="person.characterId">{{ person.name }}</option></select></label><div class="completion-filters" role="group" aria-label="检定统计对象"><button :aria-pressed="selectedPerson == null" @click="selectedPerson = null">全体调查员</button><button v-for="person in report.investigators" :key="person.characterId" :aria-pressed="selectedPerson === person.characterId" @click="selectedPerson = person.characterId">{{ person.name }}</button></div><div class="completion-dice-title"><Dices :size="23" /><strong>{{ dice.total }}</strong><span>次公开检定</span></div><div class="completion-bar" role="img" :aria-label="dice.counts.map((item) => `${item.label}${item.count}次`).join('，')"><span v-for="item in dice.counts" :key="item.value" :style="{ width: `${dice.total ? item.count / dice.total * 100 : 0}%`, background: item.color }" /></div><div class="completion-counts"><div v-for="item in dice.counts" :key="item.value"><span><i :style="{ background: item.color }" />{{ item.label }}</span><strong>{{ item.count }}</strong></div></div><p class="completion-footnote">仅统计已公开且完成结算的调查员检定，按大成功、成功、失败、大失败分别计数。下方最多列出 6 条大成功或大失败记录。</p>
          <div class="completion-highlights"><h3>大成功与大失败记录</h3><p v-if="!dice.total" class="completion-empty">当前选择范围内没有可统计的公开检定。</p><p v-else-if="!dice.highlights.length" class="completion-empty">当前选择范围内没有大成功或大失败记录。</p><article v-for="(roll, index) in dice.highlights" :key="index"><span class="completion-roll-value">{{ roll.value == null ? '—' : String(roll.value).padStart(2, '0') }}</span><div><strong>{{ name(roll.characterId) }} · {{ roll.checkName || '检定' }}</strong><small>{{ roll.turnNo > 0 ? `第 ${roll.turnNo} 轮 · ` : '' }}{{ roll.target != null ? `目标值 ${roll.target}` : '公开记录' }}</small></div><span :class="{ fumble: roll.outcome === 'FUMBLE' }">{{ outcomeLabel(roll.outcome) }}</span></article></div></div>
        </section>
        <section v-show="page === 4">
          <div class="completion-section-heading"><small class="completion-kicker">04 / 战斗回顾</small><h2>战斗场景与结果</h2><p>按发生顺序查看本次跑团中已完成的战斗。每场战斗的结果默认折叠，展开后可阅读原有的战斗结算记录。</p></div>
          <p v-if="report.combats == null" class="completion-empty">这份完成记录尚未收录战斗回顾，可在对话中查看原有战斗记录。</p>
          <p v-else-if="!report.combats.length" class="completion-empty">本次跑团没有已完成的战斗记录。</p>
          <div v-else class="completion-journey"><article v-for="(combat, index) in report.combats" :key="combat.combatId"><span class="completion-chapter-number">{{ String(index + 1).padStart(2, '0') }}</span><div><h3>第 {{ index + 1 }} 场战斗 · {{ combat.sceneName || '场景名称未记录' }}</h3><details><summary>展开战斗结果</summary><p class="completion-full-text">{{ combat.summary || '这场战斗未保存结果文字。' }}</p></details></div></article></div>
        </section>
      </div>
      <p v-if="error" role="alert" class="completion-error">{{ error }}</p>
    </template>
    </div>
      <footer v-if="!loading && report?.status === 'ready'" class="completion-navigation"><button class="button ghost" :disabled="page === 0 || busy" @click="move(-1)"><ArrowLeft :size="16" />上一页</button><span>{{ page + 1 }} / {{ sections.length }}</span><button v-if="page < sections.length - 1" class="button primary" @click="move(1)">继续<ArrowRight :size="16" /></button><button v-else class="button primary" :disabled="busy" @click="emit('archive')"><LoaderCircle v-if="busy" class="spin" :size="16" />返回档案</button></footer>
  </main>
</template>

<style scoped>
.completion-directory,.completion-person-select,.completion-mobile-progress{display:none}
.completion-stage{height:100vh;height:100dvh;overflow:auto;color:var(--ink);background:var(--paper);padding:0 36px 32px;font-size:14px;line-height:1.7}.completion-top{position:sticky;top:0;z-index:2;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:24px;font-size:12px;color:var(--muted);background:var(--paper);margin:0 -36px 36px;padding:24px max(36px,calc((100% - 1060px)/2 + 36px))}.completion-top button{display:flex;align-items:center;gap:7px;flex-shrink:0;white-space:nowrap}.completion-progress{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:24px;margin:0 0 0 auto;color:var(--muted);font-size:12px}.completion-progress>span{display:flex;align-items:center;gap:9px;white-space:nowrap}.completion-progress i{font:normal 12px Georgia;display:grid;place-items:center;width:25px;height:25px;border:1px solid var(--line);border-radius:50%}.completion-progress .current{color:var(--pine);font-weight:600}.completion-progress .current i,.completion-progress .done i{color:var(--surface);border-color:var(--pine);background:var(--pine)}.completion-book,.completion-navigation{max-width:960px;margin:auto}.completion-book{min-height:540px}.completion-stage h2,.completion-stage h3,.completion-stage p{margin:0}.completion-stage h2{font:400 30px/1.5 'Songti SC',STSong,serif;letter-spacing:.04em}.completion-kicker{color:var(--pine);font-size:11px;letter-spacing:.08em}.completion-opening{display:grid;grid-template-columns:270px minmax(0,1fr);gap:54px;align-items:center;padding:32px 0 42px}.completion-cover{position:relative;min-height:392px;border-radius:4px 10px 10px 4px;background:var(--pine);color:#f7efdd;box-shadow:7px 10px 25px #213f3522,inset 8px 0 0 #0002;overflow:hidden}.completion-cover>img{position:absolute;width:100%;height:100%;object-fit:cover;opacity:.28}.completion-cover-copy{position:relative;min-height:392px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:26px;gap:22px}.completion-cover small{font-size:9px;letter-spacing:.1em}.completion-cover h2{font-size:30px;overflow-wrap:anywhere}.completion-sigil{font-size:38px;font-weight:200}.completion-cover-copy>span:last-of-type{font:12px Georgia;letter-spacing:.14em}.completion-cover strong{border:1px solid #f7efdd66;padding:2px 18px;font-size:11px;font-weight:400;letter-spacing:.18em}.completion-opening-copy h2{margin:12px 0 22px;font-size:32px}.completion-prose{white-space:pre-wrap;font:15px/2 'Songti SC',STSong,serif;overflow-wrap:anywhere}.completion-team{display:flex;gap:8px 18px;flex-wrap:wrap;margin:23px 0;font-size:12px}.completion-team small{color:var(--muted)}.completion-totals{border-top:1px solid var(--line);padding-top:20px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.completion-totals strong{display:block;font:32px Georgia;color:var(--pine)}.completion-totals span{display:block;color:var(--muted);font-size:11px;margin-top:7px}.completion-footnote{font-size:11px;color:var(--muted);margin-top:20px!important}.completion-section-heading{padding:12px 0 30px}.completion-section-heading h2{margin:7px 0 12px}.completion-section-heading p{color:var(--muted);font-size:13px}.completion-journey article{display:grid;grid-template-columns:42px minmax(0,1fr);gap:20px;padding:24px 0;border-top:1px solid var(--line)}.completion-chapter-number{font:26px Georgia;color:var(--pine);opacity:.65}.completion-stage h3{font-size:16px;font-weight:500}.completion-journey h3{margin-bottom:8px}.completion-stage details{font-size:12px;margin-top:15px}.completion-stage summary{cursor:pointer;color:var(--pine);width:fit-content;padding:4px 0}.completion-full-text{white-space:pre-wrap;overflow-wrap:anywhere;color:var(--muted);font-size:13px;line-height:1.9;margin-top:12px!important;border-top:1px solid var(--line);padding-top:14px}.completion-people{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.completion-people article{padding:23px;border:1px solid var(--line);background:var(--surface);border-radius:8px}.completion-people header{display:grid;grid-template-columns:42px minmax(0,1fr) auto;gap:12px;align-items:center}.completion-avatar{width:42px;height:48px;border:1px solid var(--line);border-radius:4px;display:grid;place-items:center;background:var(--pine-soft);font:23px 'Songti SC',serif;overflow:hidden}.completion-avatar img{width:100%;height:100%;object-fit:cover}.completion-people h3 small{display:inline-block;color:var(--pine);font-size:9px;background:var(--pine-soft);padding:1px 4px;margin-left:7px}.completion-people header p{color:var(--muted);font-size:11px}.completion-person-state{font-size:11px;color:var(--pine);max-width:80px;text-align:right}.dead .completion-person-state{color:var(--wine)}.completion-resources{display:grid;grid-template-columns:1fr 1fr;gap:12px;border-block:1px solid var(--line);padding:13px 0;margin:20px 0}.completion-resources small{color:var(--muted);font-size:11px}.completion-resources p{display:flex;align-items:center;gap:12px;margin-top:4px}.completion-resources del{font:17px Georgia;color:var(--muted);text-decoration:none}.completion-resources p>span{color:var(--muted)}.completion-resources strong{font:23px Georgia}.completion-dice{border:1px solid var(--line);border-radius:8px;background:var(--surface);padding:26px}.completion-filters{display:flex;flex-wrap:wrap;gap:8px}.completion-filters button{padding:5px 12px;border:1px solid var(--line);border-radius:5px;background:transparent;cursor:pointer;font-size:12px}.completion-filters button[aria-pressed=true]{background:var(--pine);color:var(--surface);border-color:var(--pine)}.completion-dice-title{display:flex;align-items:baseline;gap:12px;margin:27px 0 17px;color:var(--pine)}.completion-dice-title strong{font:38px Georgia}.completion-dice-title>span{font-size:12px;color:var(--muted)}.completion-bar{height:15px;display:flex;overflow:hidden;border-radius:4px;background:var(--line)}.completion-counts{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:21px}.completion-counts span{font-size:11px;color:var(--muted);display:flex;gap:6px;align-items:center}.completion-counts i{width:7px;height:7px;border-radius:50%}.completion-counts strong{display:block;font:25px Georgia;margin-top:6px}.completion-highlights{margin-top:30px;padding-top:22px;border-top:1px solid var(--line)}.completion-highlights h3{margin-bottom:8px}.completion-highlights article{display:grid;grid-template-columns:43px minmax(0,1fr) auto;align-items:center;gap:12px;padding:17px 0;border-bottom:1px solid var(--line)}.completion-highlights article:last-child{border:0;padding-bottom:0}.completion-roll-value{font:26px Georgia;color:var(--pine)}.completion-highlights article strong{font-size:13px;font-weight:500}.completion-highlights article small{display:block;font-size:11px;color:var(--muted);margin-top:3px}.completion-highlights article>span:last-child{font-size:11px;color:#947135}.completion-highlights .fumble{color:var(--wine)!important}.completion-navigation{position:sticky;bottom:-32px;z-index:1;background:var(--paper);padding-bottom:18px;display:flex;align-items:center;justify-content:space-between;padding-top:24px;margin-top:28px;border-top:1px solid var(--line)}.completion-navigation>span{font:12px Georgia;color:var(--muted)}.completion-navigation button{min-width:112px}.completion-wait{max-width:540px;margin:12vh auto;text-align:center;display:flex;flex-direction:column;align-items:center;gap:20px}.completion-wait h1{font:26px 'Songti SC',serif}.completion-wait p{color:var(--muted);line-height:1.9}.completion-wait small{font-size:9px;letter-spacing:.15em;color:var(--pine)}.completion-empty{padding:24px 0;color:var(--muted);font-size:13px}.completion-error{max-width:960px;margin:16px auto!important;color:var(--wine);font-size:13px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-width:1100px){.completion-stage{padding:0 24px 20px}.completion-top{margin:0 -24px 36px;padding:20px 24px}.completion-opening{gap:30px;grid-template-columns:220px minmax(0,1fr)}.completion-cover,.completion-cover-copy{min-height:340px}.completion-opening-copy h2{font-size:28px}.completion-progress{gap:20px}.completion-people article{padding:18px}}
@media (max-width: 767px){
  .completion-stage{height:100vh;height:100dvh;min-height:0;padding:0;display:flex;flex-direction:column;overflow:hidden;font-size:14px}
  .completion-top{position:relative;flex:none;display:flex;flex-wrap:nowrap;gap:8px;margin:0;padding:calc(8px + env(safe-area-inset-top,0px)) 20px 8px;border-bottom:1px solid var(--line)}
  .completion-top>.text-button{min-height:44px;font-size:12px}
  .completion-progress{display:none}
  .completion-stage .completion-directory{display:block;position:relative;margin:0 0 0 auto;min-width:0;font-size:12px}
  .completion-directory>summary{display:flex;align-items:center;justify-content:flex-end;gap:6px;min-height:44px;padding:0;list-style:none;width:auto}
  .completion-directory>summary::-webkit-details-marker{display:none}
  .completion-directory>summary>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .completion-directory>summary small{white-space:nowrap;color:var(--muted);font:12px Georgia}
  .completion-directory>summary svg{flex:none}
  .completion-directory nav{position:absolute;right:0;top:calc(100% + 8px);width:min(280px,calc(100vw - 40px));border:1px solid var(--line);border-radius:8px;padding:6px;background:var(--surface);box-shadow:0 12px 28px #213f3522}
  .completion-directory nav button{width:100%;min-height:48px;display:flex;gap:12px;padding:10px 12px;border:0;border-radius:4px;background:transparent;color:var(--ink);font:inherit;text-align:left;cursor:pointer}
  .completion-directory nav button>span{color:var(--muted);font:14px Georgia}
  .completion-directory nav button>svg{margin-left:auto}
  .completion-directory nav button[aria-current=page]{background:var(--pine-soft);color:var(--pine)}
  .completion-content{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;padding:24px 20px;scrollbar-gutter:stable}
  .completion-book{min-height:0;max-width:100%;overflow-wrap:anywhere}
  .completion-opening{grid-template-columns:minmax(0,1fr);padding:0;gap:28px}
  .completion-cover{width:100%;min-height:210px;margin:0;border-radius:4px 10px 10px 4px}
  .completion-cover-copy{min-height:210px;gap:10px;padding:20px 24px}
  .completion-cover h2{font-size:22px;line-height:1.4}
  .completion-sigil{font-size:18px;line-height:1}
  .completion-opening-copy h2{font-size:22px;margin:10px 0 18px}
  .completion-stage h2{font-size:22px}
  .completion-prose,.completion-full-text{font-size:14px;line-height:1.85}
  .completion-section-heading{padding:0 0 24px}
  .completion-section-heading p,.completion-empty{font-size:13px;line-height:1.8}
  .completion-footnote{font-size:12px;line-height:1.8}
  .completion-team{font-size:13px}
  .completion-stage summary{min-height:44px;display:flex;align-items:center;font-size:12px}
  .completion-stage summary::before{content:'▸';margin-right:7px}
  .completion-stage details[open]>summary::before{content:'▾'}
  .completion-stage .completion-directory>summary::before{content:none}
  .completion-people{grid-template-columns:minmax(0,1fr)}
  .completion-people article{padding:18px 16px}
  .completion-people header{grid-template-columns:42px minmax(0,1fr);gap:8px 12px}
  .completion-people h3 small{font-size:11px}
  .completion-people header p{font-size:12px}
  .completion-person-state{grid-column:2;max-width:none;text-align:left;font-size:12px;overflow-wrap:anywhere}
  .completion-journey article{gap:10px;grid-template-columns:26px minmax(0,1fr)}
  .completion-chapter-number{font-size:20px}
  .completion-dice{padding:20px 16px}
  .completion-filters{display:none}
  .completion-person-select{display:flex;flex-direction:column;gap:8px;color:var(--muted);font-size:12px}
  .completion-person-select select{width:100%;min-width:0;min-height:44px;padding:8px 10px;border:1px solid var(--line);border-radius:5px;background:var(--paper);color:var(--ink);font-family:inherit;font-size:16px}
  .completion-counts{grid-template-columns:repeat(2,minmax(0,1fr));gap:20px 12px}
  .completion-counts span{font-size:12px}
  .completion-highlights article{grid-template-columns:48px minmax(0,1fr);gap:6px 12px}
  .completion-highlights article>span:last-child{grid-column:2;font-size:12px}
  .completion-highlights .completion-roll-value{white-space:nowrap;overflow-wrap:normal;font-size:24px;min-width:3ch}
  .completion-highlights article strong{font-size:13px}
  .completion-highlights article small{font-size:12px}
  .completion-navigation{position:static;flex:none;width:100%;max-width:none;margin:0;padding:12px 20px calc(12px + env(safe-area-inset-bottom,0px));gap:10px}
  .completion-navigation button{min-width:96px;min-height:44px;justify-content:center}
  .completion-wait{margin:8vh auto}
  .completion-error{margin-top:24px!important}
}
@media (max-width:767px) {
  .completion-top { min-height:64px;padding:6px 12px 10px;gap:8px;background:#f4f1ea; }
  .completion-top>.text-button { font-size:12px;padding:0 8px; }.completion-top>.text-button svg { width:22px;height:22px; }
  .completion-content { padding:18px 18px 24px;scrollbar-gutter:auto; }
  .completion-mobile-progress { display:flex;gap:6px;margin:0 0 22px; }.completion-mobile-progress i { height:3px;background:#dedbd2;flex:1;border-radius:3px; }.completion-mobile-progress i.active { background:#294f49; }
  .completion-stage .completion-cover,.completion-stage .completion-cover-copy { min-height:211px; }.completion-stage .completion-cover h2 { font-size:29px;line-height:1.45; }.completion-cover-copy { gap:8px; }.completion-cover { box-shadow:inset 7px 0 #183c3544; }
  .completion-stage .completion-opening-copy h2,.completion-stage .completion-section-heading h2 { font-size:27px;line-height:1.45;margin:8px 0 10px; }.completion-stage .completion-section-heading { padding-bottom:18px; }
  .completion-prose { font-size:16px;line-height:1.85; }.completion-stage .completion-full-text { font-size:14px;line-height:1.85; }
  .completion-people article,.completion-dice { padding:17px;border-radius:13px;background:#fbfaf6;border-color:#dedbd2; }
  .completion-stage details:not(.completion-directory) { margin:15px 0;padding:12px 14px;border:1px solid #dedbd2;border-radius:10px;font-size:12px;color:#777970; }.completion-stage details:not(.completion-directory)>summary { min-height:22px;padding:0; }
  .completion-navigation { padding:12px 18px max(12px,env(safe-area-inset-bottom));gap:10px;background:#fbfaf6; }.completion-navigation > .button { flex:1;min-height:46px;border-radius:11px; }.completion-navigation>span { display:none; }
}
@media(max-width:359px){.completion-top{padding-inline:16px}.completion-content{padding-inline:16px}.completion-navigation{padding-inline:16px}.completion-top>.text-button{font-size:11px;gap:4px}.completion-directory>summary{gap:4px}.completion-cover-copy{padding-inline:18px}}
</style>
