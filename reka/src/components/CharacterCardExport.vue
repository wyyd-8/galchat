<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Download, LoaderCircle } from '@lucide/vue'
import { PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui'
import type { CharacterCard } from '@/api/types'
import { notify } from '@/composables/useNotice'
import { cardPdfFilename, createCharacterCardExporter, prepareCardPortrait, suggestedCardBackground } from './characterCardExport'
import type { CardBackground, CardFont } from './characterCardExport'

const props = defineProps<{ card: CharacterCard; active: boolean }>()
const exporter = createCharacterCardExporter(import.meta.env.VITE_CHARACTER_CARD_PDF_URL || '')
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
  <PopoverRoot v-if="available" v-model:open="expanded">
    <PopoverTrigger as-child>
      <button class="button ghost card-export-trigger" type="button">
        <LoaderCircle v-if="exporting" class="spin" :size="14" /><Download v-else :size="14" />
        {{ exporting ? '正在生成…' : '导出 PDF' }}
      </button>
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent class="card-export-popover" side="bottom" align="end" :side-offset="8" :collision-padding="16" aria-label="导出人物卡 PDF">
        <form @submit.prevent="download">
          <header><strong>导出人物卡</strong><p>{{ card.character.name }} · 双页 PDF</p></header>
          <label>卡面模板
            <select v-model="background" :disabled="exporting" required>
              <option disabled value="">请选择角色卡时代</option>
              <option value="1920s">1920 年代</option>
              <option value="modern">现代</option>
            </select>
          </label>
          <label>字体
            <select v-model="fontIndex" :disabled="exporting">
              <option :value="0">小薇 · 清晰易读</option>
              <option :value="1">志莽行 · 随性手写</option>
              <option :value="2">马善政 · 毛笔风格</option>
            </select>
          </label>
          <p class="card-export-hint">导出当前查看的人物卡，长文本可能省略。</p>
          <p v-if="error" class="card-export-error" role="alert">{{ error }}</p>
          <footer>
            <button class="button ghost" type="button" @click="cancelDownload">取消</button>
            <button class="button primary" type="submit" :disabled="!background || exporting">
              <LoaderCircle v-if="exporting" class="spin" :size="14" /><Download v-else :size="14" />
              {{ exporting ? '正在生成…' : '下载 PDF' }}
            </button>
          </footer>
        </form>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<style>
.card-export-trigger { min-height: 30px; padding: 5px 9px; font-size: 12px; }
.card-export-popover { z-index: 150; width: min(300px, calc(100vw - 32px)); max-height: var(--reka-popover-content-available-height); overflow-y: auto; padding: 18px; border: 1px solid var(--line); border-radius: 12px; color: var(--ink); background: #fffef9; box-shadow: 0 14px 38px rgba(47,59,52,.18); }
.card-export-popover form { display: grid; gap: 14px; }
.card-export-popover header strong { font-size: 15px; }
.card-export-popover header p { margin: 5px 0 0; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.card-export-popover label { display: grid; gap: 6px; font-size: 12px; font-weight: 600; }
.card-export-popover select { width: 100%; min-height: 36px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 7px; color: var(--ink); background: white; font: inherit; }
.card-export-popover .card-export-hint { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
.card-export-popover .card-export-error { margin: 0; color: var(--wine); font-size: 12px; line-height: 1.6; }
.card-export-popover footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
