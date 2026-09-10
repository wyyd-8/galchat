<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, Search } from '@lucide/vue'
import type { DraftCharacterCard } from '@/api/types'
import WeaponRiskNotice from '@/components/WeaponRiskNotice.vue'
import { resolveWeaponCheckValue, shouldShowWeaponRisk } from '@/components/trpgToolsState'
const props = defineProps<{ card: DraftCharacterCard; actorName?: string; canSwitch?: boolean }>()
const emit = defineEmits<{ switch: [] }>()
const tab = ref('skills')
const query = ref('')
const sort = ref('default')
const showSorting = ref(false)
const openProfile = ref('人物背景')
function navigate(section: string, profile?: string) { tab.value = section; showSorting.value = section === 'skills'; if (profile) openProfile.value = profile }
defineExpose({ navigate })
const attributes = computed(() => {
  const c = props.card.character
  return [['力量', c.str], ['体质', c.con], ['体型', c.siz], ['敏捷', c.dex], ['外貌', c.app], ['智力', c.intValue], ['意志', c.pow], ['教育', c.edu]]
})
const resources = computed(() => {
  const c = props.card.character
  return [{ label: '生命 HP', value: c.hpCurrent, max: c.hpMax }, { label: '理智 SAN', value: c.sanCurrent, max: c.sanMax }, { label: '魔法 MP', value: c.mpCurrent, max: c.mpMax }, { label: '幸运', value: c.luckCurrent, max: 100, single: true }]
})
const skills = computed(() => {
  const items = props.card.skills.filter(skill => `${skill.displayName} ${skill.category || ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))
  if (sort.value === 'high') return items.sort((a, b) => b.value - a.value)
  if (sort.value === 'low') return items.sort((a, b) => a.value - b.value)
  if (sort.value === 'category') return items.sort((a, b) => (a.category || '').localeCompare(b.category || '', 'zh-CN'))
  return items
})
const status = computed(() => {
  const c = props.card.character
  if (c.dead) return '死亡'
  return [c.dying && '濒死', c.unconscious && '昏迷', c.majorWound && '重伤', c.temporaryInsanity && '临时性疯狂'].filter(Boolean).join(' · ') || '状态稳定'
})
const profileGroups = computed(() => {
  const p = props.card.profile
  return [
    { title: '人物背景', items: [['形象描述', p?.appearance], ['思想与信念', p?.ideology], ['特质', p?.traits]] },
    { title: '重要联系', items: [['重要之人', p?.significantPeople], ['意义非凡之地', p?.meaningfulLocations], ['宝贵之物', p?.treasuredPossessions], ['关键连接', p?.keyConnectionText]] },
    { title: '创伤记录', items: [['伤口和疤痕', p?.injuriesAndScars], ['恐惧症和狂躁症', p?.phobiasAndManias]] },
    { title: '资产与笔记', items: [['装备和道具', p?.equipmentText], ['消费水平', p?.spendingLevel], ['现金', p?.cash], ['资产', p?.assetsText], ['调查员笔记', p?.notes]] },
  ]
})
const shown = (value: unknown) => value == null || value === '' ? '—' : value
const ratio = (value?: number, max?: number) => `${Math.max(0, Math.min(100, (value || 0) / (max || 1) * 100))}%`
</script>

<template>
  <section class="mobile-investigator-sheet">
    <button v-if="canSwitch" type="button" class="investigator-switch" @click="emit('switch')">
      <span class="investigator-avatar" :style="card.character.image ? { backgroundImage: `url(${card.character.image})` } : {}">{{ card.character.image ? '' : card.character.name.slice(0, 1) }}</span>
      <span><strong>{{ card.character.name }} · {{ actorName || '调查员' }}</strong><small>切换人物卡</small></span><ChevronDown :size="18" />
    </button>
    <header v-show="tab === 'skills'" class="investigator-identity">
      <small>INVESTIGATOR / {{ actorName || card.character.name }}</small><h1>{{ card.character.name }}</h1>
      <p>{{ card.character.occupation || '未填写职业' }} · {{ shown(card.character.age) }} 岁 · {{ card.character.era || '时代未填' }}</p>
      <span class="investigator-status" :class="{ danger: status !== '状态稳定' }">{{ status }}</span>
    </header>
    <div v-show="tab === 'skills'" class="investigator-resources">
      <div v-for="resource in resources" :key="resource.label"><span>{{ resource.label }}</span><strong>{{ shown(resource.value) }}<template v-if="!resource.single"> / {{ shown(resource.max) }}</template></strong><div class="investigator-meter"><i :style="{ width: ratio(resource.value, resource.max) }" /></div></div>
    </div>
    <div v-show="tab === 'skills'" class="investigator-attributes" aria-label="调查员属性"><div v-for="attribute in attributes" :key="String(attribute[0])"><small>{{ attribute[0] }}</small><strong>{{ shown(attribute[1]) }}</strong></div></div>
    <nav class="investigator-tabs" aria-label="人物卡内容"><button v-for="item in [{ id: 'skills', label: '技能' }, { id: 'weapons', label: '武器' }, { id: 'background', label: '背景与资产' }]" :key="item.id" type="button" :aria-pressed="tab === item.id" @click="tab = item.id">{{ item.label }}</button></nav>
    <template v-if="tab === 'skills'">
      <label class="investigator-search"><Search :size="18" /><input v-model="query" type="search" placeholder="检索技能" aria-label="检索技能" /></label>
      <label v-if="showSorting" class="field"><span>技能排序</span><select v-model="sort"><option value="default">默认顺序</option><option value="high">成功率从高到低</option><option value="low">成功率从低到高</option><option value="category">按技能大类</option></select></label>
      <div class="investigator-card investigator-skills"><div v-for="skill in skills" :key="skill.id || skill.displayName"><span>{{ skill.displayName }}</span><strong>{{ skill.value }}</strong><small>困难 {{ Math.floor(skill.value / 2) }} / 极难 {{ Math.floor(skill.value / 5) }}<template v-if="skill.category"> · {{ skill.category }}</template></small></div><p v-if="!skills.length">{{ query ? '没有匹配的技能' : '暂无技能' }}</p></div>
    </template>
    <template v-else-if="tab === 'weapons'">
      <article v-for="weapon in card.weapons" :key="weapon.id" class="investigator-card">
        <h3>{{ weapon.name }} <WeaponRiskNotice v-if="shouldShowWeaponRisk(weapon)" :weapon="weapon" /></h3>
        <dl><div><dt>使用技能 / 成功率</dt><dd>{{ weapon.skillName || '—' }} {{ shown(resolveWeaponCheckValue(weapon, card.skills)) }}%</dd></div><div><dt>伤害</dt><dd>{{ shown(weapon.damage) }}</dd></div><div><dt>射程 / 每轮攻击</dt><dd>{{ shown(weapon.range) }} / {{ shown(weapon.attacksPerRound) }} 次</dd></div><div v-if="weapon.remainingAmmo != null || weapon.ammoCapacity != null"><dt>弹药</dt><dd>{{ shown(weapon.remainingAmmo) }} / {{ shown(weapon.ammoCapacity) }}</dd></div><div v-if="weapon.malfunction != null"><dt>故障值</dt><dd>{{ weapon.malfunction }}</dd></div></dl><p v-if="weapon.notes">{{ weapon.notes }}</p><p v-if="weapon.isBroken" class="danger">已损坏</p>
      </article>
      <p v-if="!card.weapons.length" class="investigator-muted">暂无武器</p>
      <article class="investigator-card"><h3>战斗资料</h3><dl><div><dt>伤害加值 / 体格</dt><dd>{{ shown(card.character.damageBonus) }} / {{ shown(card.character.build) }}</dd></div><div><dt>移动力 / 护甲</dt><dd>{{ shown(card.character.mov) }} / {{ shown(card.character.armor) }}</dd></div><div><dt>闪避</dt><dd>{{ card.skills.find(skill => skill.displayName === '闪避')?.value ?? Math.floor(card.character.dex / 2) }}%</dd></div></dl></article>
    </template>
    <template v-else>
      <details v-for="group in profileGroups" :key="group.title" class="investigator-profile" :open="group.title === openProfile"><summary>{{ group.title }}</summary><div v-for="item in group.items" :key="item[0] || ''"><strong>{{ item[0] }}</strong><p>{{ item[1]?.trim() || '暂未记录' }}</p></div></details>
      <details class="investigator-profile"><summary>基本资料</summary><dl><div><dt>性别</dt><dd>{{ shown(card.character.sex) }}</dd></div><div><dt>出身地</dt><dd>{{ shown(card.character.birthplace) }}</dd></div><div><dt>居住地</dt><dd>{{ shown(card.character.residence) }}</dd></div></dl></details>
    </template>
    <slot />
  </section>
</template>

<style scoped>
.mobile-investigator-sheet { color: #252724; font-size: 14px; }
.investigator-switch { display:flex;align-items:center;gap:12px;width:100%;padding:15px 0;min-height:76px;border:0;border-bottom:1px solid #dedbd2;background:transparent;text-align:left; }
.investigator-switch>span:nth-child(2){flex:1;min-width:0}.investigator-switch strong{display:block;font-size:15px;font-weight:550;line-height:1.5}.investigator-switch small{display:block;font-size:12px;color:#777970;margin-top:4px}
.investigator-avatar{display:grid;place-items:center;width:46px;height:46px;flex:none;border-radius:14px;background:#e4ece8 center/cover;color:#294f49;font:19px Georgia}
.investigator-identity{padding:17px 0 5px}.investigator-identity>small{font:10px/1.7 ui-monospace,monospace;letter-spacing:1.5px;color:#7a8177}.investigator-identity h1{margin:8px 0 5px;font-size:28px;font-weight:600;letter-spacing:-.6px;line-height:1.35}.investigator-identity p{font-size:13px;color:#777970;margin:8px 0 12px;line-height:1.8}.investigator-status{display:inline-block;font-size:11px;color:#294f49;background:#e4ece8;padding:4px 8px;border-radius:6px;line-height:1.6}.danger{color:#8a3f48!important}
.investigator-resources{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:18px 0}.investigator-resources>div{background:#fbfaf6;border:1px solid #dedbd2;border-radius:11px;padding:13px}.investigator-resources span{font-size:11px;color:#777970}.investigator-resources strong{display:block;font-size:21px;font-weight:550;margin-top:7px}.investigator-meter{height:4px;border-radius:5px;overflow:hidden;background:#e0e3d9;margin:12px 0 4px}.investigator-meter i{display:block;height:100%;background:#294f49;border-radius:5px}
.investigator-attributes{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin:14px 0 20px;border:1px solid #dedbd2;border-radius:12px;overflow:hidden}.investigator-attributes>div{text-align:center;padding:10px 3px;border-right:1px solid #dedbd2;border-bottom:1px solid #dedbd2;background:#fbfaf6}.investigator-attributes>div:nth-child(4n){border-right:0}.investigator-attributes>div:nth-last-child(-n+4){border-bottom:0}.investigator-attributes small{display:block;font-size:11px;color:#777970}.investigator-attributes strong{display:block;font-size:21px;font-weight:550;margin-top:4px}
.investigator-tabs{display:flex;background:#e8e7df;border-radius:10px;padding:3px;gap:3px;margin:12px 0 16px}.investigator-tabs button{flex:1;min-width:0;min-height:40px;border:0;border-radius:8px;background:transparent;color:#72786e;font-size:13px;padding:5px}.investigator-tabs button[aria-pressed=true]{background:#fffefa;color:#294f49;box-shadow:0 2px 6px #22382b0a;font-weight:600}
.investigator-search{display:flex;align-items:center;background:#fffefa;border:1px solid #dedbd2;border-radius:10px;margin:15px 0;padding:0 10px;gap:7px}.investigator-search input{min-width:0;width:100%;padding:12px 0;border:0;background:transparent;font-size:16px;line-height:1.6;min-height:48px}
.investigator-card{background:#fbfaf6;border:1px solid #dedbd2;border-radius:13px;padding:17px;margin:12px 0}.investigator-card h3{display:flex;align-items:center;gap:8px;margin:0;font-size:16px;line-height:1.5;font-weight:600}.investigator-card p{font-size:13px;color:#777970;line-height:1.8;white-space:pre-wrap}.investigator-skills>div{display:grid;grid-template-columns:1fr auto;gap:5px;border-bottom:1px solid #dedbd2;padding:10px 0}.investigator-skills>div:last-child{border:0}.investigator-skills small{grid-column:1/-1;font-size:11px;color:#777970}dl{margin:6px 0 0}dl>div{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-bottom:1px solid #dedbd2;font-size:13px;line-height:1.6}dl>div:last-child{border:0}dt{color:#777970;flex-shrink:0}dd{margin:0;text-align:right;overflow-wrap:anywhere}
.investigator-profile{margin:15px 0;padding:12px 14px;border:1px solid #dedbd2;border-radius:10px;font-size:12px;color:#777970}.investigator-profile summary{cursor:pointer;min-height:22px}.investigator-profile>div{margin-top:15px}.investigator-profile strong{font-size:12px;color:#252724}.investigator-profile p{font-size:13px;line-height:1.8;margin:8px 0 12px;white-space:pre-wrap;overflow-wrap:anywhere}.investigator-muted{font-size:13px;color:#777970}
</style>
