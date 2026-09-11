<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Download, LoaderCircle } from '@lucide/vue'
import { PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui'
import BaseDialog from '@/components/ui/BaseDialog.vue'
import CharacterCardExportForm from './CharacterCardExportForm.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import type { CharacterCard } from '@/api/types'
import { notify } from '@/composables/useNotice'
import { cardPdfFilename, createCharacterCardExporter, prepareCardPortrait, suggestedCardBackground } from './characterCardExport'
import type { CardBackground, CardFont } from './characterCardExport'

const props = defineProps<{ card: CharacterCard; active: boolean }>()
const exporter = createCharacterCardExporter(import.meta.env.VITE_CHARACTER_CARD_PDF_URL || '')
const { isMobile } = useMobileViewport()
const available = ref(false)
const expanded = ref(false)
const exporting = ref(false)
const error = ref('')
const background = ref<CardBackground | ''>('')
const fontIndex = ref<CardFont>(0)
let downloadController: AbortController | undefined

function cancelDownload() {
  downloadController?.abort()
  downloadController = undefined
  exporting.value = false
  expanded.value = false
}

watch(() => props.active, (active, _, onCleanup) => {
  available.value = false
  if (!active) { cancelDownload(); return }
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  void exporter.available(controller.signal).then((ready) => {
    if (!controller.signal.aborted) available.value = ready
  })
}, { immediate: true })

watch(() => props.card, (card) => {
  cancelDownload()
  background.value = suggestedCardBackground(card.character.era)
  error.value = ''
}, { immediate: true })

watch(expanded, (visible) => { if (!visible) cancelDownload() })
onBeforeUnmount(cancelDownload)

async function download() {
  if (!background.value || exporting.value) return
  const controller = new AbortController()
  downloadController = controller
  exporting.value = true
  error.value = ''
  // Capture the displayed card and selected options before any asynchronous work.
  const card = JSON.parse(JSON.stringify(props.card)) as CharacterCard
  const selectedBackground = background.value
  const selectedFont = fontIndex.value
  try {
    const prepared = await prepareCardPortrait(card, controller.signal)
    const pdf = await exporter.exportPdf(prepared.card, selectedBackground, selectedFont, controller.signal)
    if (controller.signal.aborted) return
    const url = URL.createObjectURL(pdf)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = cardPdfFilename(card.character.name)
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    expanded.value = false
    if (prepared.portraitOmitted) notify('人物卡已导出', '头像无法读取，本次 PDF 未包含头像。')
  } catch (cause) {
    if (!controller.signal.aborted) error.value = cause instanceof Error && cause.name === 'TimeoutError'
      ? '生成超时，请稍后重试'
      : cause instanceof Error && cause.name !== 'TypeError'
        ? cause.message : '无法连接导出服务，请确认服务已启动后重试'
  } finally {
    if (downloadController === controller) {
      exporting.value = false
      downloadController = undefined
    }
  }
}
</script>

<template>
  <template v-if="available && isMobile">
    <button class="button ghost card-export-trigger" type="button" @click="expanded = true">
      <LoaderCircle v-if="exporting" class="spin" :size="14" /><Download v-else :size="14" />
      {{ exporting ? '正在生成…' : '导出 PDF' }}
    </button>
    <BaseDialog v-model="expanded" title="导出人物卡" :description="`${card.character.name} · 双页 PDF`"
      layer="foreground" mobile-presentation="sheet" content-class="card-export-dialog">
      <CharacterCardExportForm v-model:background="background" v-model:font-index="fontIndex"
          :name="card.character.name" :exporting="exporting" :error="error" hide-actions
          @download="download" @cancel="cancelDownload" />
      <template #footer><button class="button secondary" @click="cancelDownload">取消</button><button class="button primary" :disabled="!background || exporting" @click="download"><LoaderCircle v-if="exporting" class="spin" :size="14" />{{ exporting ? '正在生成…' : '生成并下载 PDF' }}</button></template>
    </BaseDialog>
  </template>
  <PopoverRoot v-else-if="available" v-model:open="expanded">
    <PopoverTrigger as-child>
      <button class="button ghost card-export-trigger" type="button">
        <LoaderCircle v-if="exporting" class="spin" :size="14" /><Download v-else :size="14" />
        {{ exporting ? '正在生成…' : '导出 PDF' }}
      </button>
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent class="card-export-popover" side="bottom" align="end" :side-offset="8" :collision-padding="16" aria-label="导出人物卡 PDF">
        <CharacterCardExportForm v-model:background="background" v-model:font-index="fontIndex"
          :name="card.character.name" :exporting="exporting" :error="error"
          @download="download" @cancel="cancelDownload" />
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<style>
.card-export-trigger { min-height: 30px; padding: 5px 9px; font-size: 12px; }
.card-export-popover { z-index: 150; width: min(300px, calc(100vw - 32px)); max-height: var(--reka-popover-content-available-height); overflow-y: auto; padding: 18px; border: 1px solid var(--line); border-radius: 12px; color: var(--ink); background: #fffef9; box-shadow: 0 14px 38px rgba(47,59,52,.18); }
.card-export-form { display: grid; gap: 14px; }
.card-export-form header strong { font-size: 15px; }
.card-export-form header p { margin: 5px 0 0; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.card-export-form label { display: grid; gap: 6px; font-size: 12px; font-weight: 600; }
.card-export-form select { width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 7px; color: var(--ink); background: white; font: inherit; }
.card-export-form .card-export-hint { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.card-export-form .card-export-error { margin: 0; color: var(--wine); font-size: 12px; line-height: 1.6; }
.card-export-form footer { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 767px) {
  .card-export-trigger { min-height: 44px; padding: 9px 12px; font-size: 13px; }
  .card-export-dialog .card-export-form { gap: 17px; }
  .card-export-dialog .card-export-form > header { display: none; }
  .card-export-dialog .card-export-form label { gap: 9px; font-size: 13px; }
  .card-export-dialog .card-export-form select { min-height:48px;padding:12px;border-radius:10px;background:#fffefa;font-size:16px;line-height:1.6; }
  .card-export-dialog .card-export-hint, .card-export-dialog .card-export-error { font-size: 12px; line-height: 1.75; }
  .card-export-dialog .card-export-hint { padding:14px;border-radius:11px;background:#e4ece8;color:#44614f;line-height:1.8; }
  .card-export-dialog .card-export-form footer { flex-wrap: wrap; }
  .card-export-dialog .card-export-form footer .button { min-height: 48px; }
  .card-export-dialog .card-export-form footer .primary { flex: 1; }
}
</style>
