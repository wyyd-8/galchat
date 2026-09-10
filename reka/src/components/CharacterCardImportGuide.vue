<script setup lang="ts">
import { BookOpenCheck, Download } from '@lucide/vue'
const importTemplateFilename = 'COC七版半自动人物卡v2.0.5(通用).xlsx'
const importTemplateUrl = `${import.meta.env.BASE_URL}templates/${encodeURIComponent(importTemplateFilename)}`
</script>

<template>
  <section id="character-import-template-help" aria-labelledby="character-import-template-title">
    <header class="import-assistant-heading"><BookOpenCheck :size="20" /><span><strong id="character-import-template-title">人物卡模板与导入步骤</strong><p>按照以下步骤准备人物卡，再回到输入页粘贴文本。</p></span></header>
    <div class="import-template-download">
      <small>{{ importTemplateFilename }}</small>
      <a class="button primary" :href="importTemplateUrl" :download="importTemplateFilename"><Download :size="15" />下载人物卡模板</a>
    </div>
    <div class="import-empty-guide">
      <article><i>1</i><span><strong>打开模板</strong><small>下载后，用 Excel 或 WPS 打开人物卡表格。</small></span></article>
      <article><i>2</i><span><strong>逐步完成建卡</strong><small>按照表格中的“建卡”部分，一步一步完成填写。</small></span></article>
      <article><i>3</i><span><strong>选择“txt输出”</strong><small>建卡完成后，选择“txt输出”，复制输出的人物卡文本内容。</small></span></article>
      <article><i>4</i><span><strong>粘贴并导入</strong><small>将内容粘贴到“粘贴人物卡文本”输入框，检查提示后点击“导入并绑定人物卡”。</small></span></article>
    </div>
    <p class="import-template-note">导入的是“txt输出”文本，不能直接上传 XLSX 文件。</p>
  </section>
</template>

<style>
.import-template-note { color: var(--muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 767px) {
  .import-guide-dialog .import-assistant-heading { align-items:flex-start;margin:8px 0 18px; }
  .import-guide-dialog .import-template-download { padding:17px;border:1px solid #dedbd2;border-radius:13px;background:#fbfaf6; }
  .import-guide-dialog .import-assistant-heading strong { font-size: 15px; }
  .import-guide-dialog .import-assistant-heading p, .import-guide-dialog .import-template-note { font-size: 13px; line-height: 1.8; }
  .import-guide-dialog .import-template-download small { overflow-wrap: anywhere; line-height: 1.6; }
  .import-guide-dialog .import-template-download .button { width: 100%; min-height: 48px; margin-top: 12px; }
  .import-guide-dialog .import-empty-guide article { padding:20px 0;gap:14px;border-bottom:1px solid #dedbd2; }
  .import-guide-dialog .import-empty-guide article i { width:32px;height:32px;font:18px Georgia;color:#294f49;background:#e4ece8;border-radius:10px; }
  .import-guide-dialog .import-template-note { padding:14px;border-radius:11px;background:#e4ece8;color:#44614f;margin:16px 0; }
  .import-guide-dialog .import-empty-guide article strong { font-size: 14px; }
  .import-guide-dialog .import-empty-guide article small { font-size: 13px; line-height: 1.85; }
}
</style>
