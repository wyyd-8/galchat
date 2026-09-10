<script setup lang="ts">
import { computed } from 'vue'
import MobileRestoreMessage from './MobileRestoreMessage.vue'
import { LoaderCircle } from '@lucide/vue'
import type { TrpgSaveInvestigator } from '@/api/types'
import type { ToolRollbackMessagePreview } from '@/components/trpgToolsState'
import { restoreInvestigatorCondition } from '@/components/trpgToolsState'
const props = defineProps<{ targetTime: string; preview: ToolRollbackMessagePreview | null; loading: boolean; failed: boolean; investigators: TrpgSaveInvestigator[]; deletesManualSave: boolean }>()
const boundary = computed(() => props.preview?.retained.at(-1))
const shown = (value?: number) => value ?? '—'
</script>
<template>
  <section class="mobile-restore">
    <div class="restore-danger"><strong>将回到{{ targetTime }}</strong><p>此时间点之后的本次跑团进度会被删除。</p><p v-if="deletesManualSave">此恢复点早于当前手动存档，继续恢复还将删除该手动存档。</p></div>
    <h3>恢复到这里</h3>
    <p v-if="loading" class="restore-state" role="status"><LoaderCircle class="spin" :size="16" />正在读取恢复位置…</p>
    <template v-else>
      <MobileRestoreMessage v-for="message in preview?.retained || []" :key="message.id" :message="message" :boundary="message === boundary" />
      <p v-if="!boundary" class="restore-muted">{{ preview ? '此前没有聊天消息，将恢复到本次跑团开始前。' : '当前恢复点没有可用的聊天边界。' }}</p>
      <p v-if="failed" class="restore-warning">未能读取边界附近的消息，仅显示恢复范围。</p>
    </template>
    <div class="restore-cutline">以下进度将被删除</div>
    <p class="restore-muted">恢复点之后的行动轮、消息、掷骰和相关人物状态将回滚。</p>
    <template v-if="!loading && preview && (preview.deleted.length || preview.deletedMessagesOmitted)">
      <MobileRestoreMessage v-for="message in preview.deleted" :key="message.id" :message="message" />
      <p v-if="preview.deletedMessagesOmitted" class="restore-muted">其余后续消息已省略，也会一并删除。</p>
    </template>
    <p v-else-if="!loading && preview" class="restore-muted">当前没有聊天消息会被删除。</p>
    <h3>调查员快照</h3>
    <div v-for="item in investigators" :key="item.characterId" class="restore-person"><span class="restore-avatar">{{ item.name.slice(0, 1) }}</span><div><strong>{{ item.name }}</strong><small>HP {{ shown(item.hpCurrent) }} / {{ shown(item.hpMax) }} · SAN {{ shown(item.sanCurrent) }} / {{ shown(item.sanMax) }} · MP {{ shown(item.mpCurrent) }} / {{ shown(item.mpMax) }}</small><small v-if="restoreInvestigatorCondition(item)" class="restore-warning">{{ restoreInvestigatorCondition(item) }}</small></div></div>
    <p v-if="!investigators.length" class="restore-muted">该恢复点没有调查员状态记录。</p>
  </section>
</template>
<style scoped>
.mobile-restore { min-width:0;color:#252724;font-size:14px; }
.restore-danger { font-size:13px;line-height:1.8;padding:14px;border-radius:11px;background:#f0e1e2;color:#843d47;margin:0 0 16px;overflow-wrap:anywhere; }.restore-danger>strong { font-size:13px; }.restore-danger p { margin:0;line-height:1.8; }
.mobile-restore>h3 { display:flex;align-items:center;min-height:30px;margin:23px 0 12px;font-size:16px;font-weight:600;line-height:1.5; }
.restore-avatar { width:46px;height:46px;border-radius:14px;background:#e4ece8;color:#294f49;display:inline-grid;place-items:center;font:19px Georgia,'Songti SC',serif;flex-shrink:0; }.restore-avatar.small { width:32px;height:32px;border-radius:10px;font-size:15px; }
.restore-cutline { text-align:center;font-size:12px;border-top:1px dashed #bd9699;padding-top:13px;color:#8a3f48;margin:25px 0 10px; }.restore-muted { font-size:14px;line-height:1.8;color:#777970;margin:8px 0 12px; }.restore-warning { color:#843d47!important;font-size:13px;line-height:1.8; }
.restore-person { display:flex;align-items:center;gap:12px;min-height:76px;padding:15px 0;border-bottom:1px solid #dedbd2; }.restore-person>div { flex:1;min-width:0; }.restore-person strong { display:block;font-size:15px;line-height:1.5;font-weight:550; }.restore-person small { display:block;font-size:12px;line-height:1.6;margin-top:4px;color:#777970;overflow-wrap:anywhere; }
.restore-state { display:flex;align-items:center;gap:8px;font-size:13px;color:#777970;min-height:44px; }
</style>
