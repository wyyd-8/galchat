<script setup lang="ts">
import { ChevronRight, Cpu, KeyRound, UserRound } from '@lucide/vue'
defineProps<{ username: string }>()
const emit = defineEmits<{ account: []; models: []; password: []; logout: [] }>()
</script>

<template>
  <main class="mobile-profile-page">
    <header class="profile-top"><span class="profile-brand">✦</span><strong>我的</strong></header>
    <div class="profile-content">
      <section class="profile-hero"><span class="profile-avatar">{{ username.slice(0, 1) || '旅' }}</span><h1>{{ username }}</h1><p>你的世界，随时继续。</p></section>
      <nav class="profile-rows" aria-label="账号与偏好">
        <button @click="emit('account')"><span class="profile-row-icon"><UserRound :size="21" /></span><span><strong>账号资料</strong><small>用户名、生日与骰子皮肤</small></span><ChevronRight :size="16" /></button>
        <button @click="emit('models')"><span class="profile-row-icon"><Cpu :size="21" /></span><span><strong>模型管理</strong><small>管理角色使用的回复模型</small></span><ChevronRight :size="16" /></button>
        <button @click="emit('password')"><span class="profile-row-icon"><KeyRound :size="21" /></span><span><strong>修改密码</strong><small>通过账户邮箱验证码修改</small></span><ChevronRight :size="16" /></button>
      </nav>
      <h3 class="profile-section-title">当前账号</h3>
      <button class="button secondary profile-logout" @click="emit('logout')">退出登录</button>
    </div>
  </main>
</template>

<style scoped>
.mobile-profile-page { width: 100%; max-width: none; margin: 0; padding: 0; min-height: 0; display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.profile-top { min-height: 64px; padding: 6px 12px 10px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); flex-shrink: 0; }
.profile-top strong { font-size: 17px; font-weight: 600; }
.profile-brand { width: 38px; height: 38px; margin: 0 9px; display: grid; place-items: center; border-radius: 11px; color: #fff; background: var(--pine); font-size: 22px; }
.profile-content { padding: 18px 18px 24px; flex: 1; min-height: 0; overflow-y: auto; }
.profile-hero { padding: 16px 0 22px; }
.profile-avatar { display: grid; place-items: center; width: 76px; height: 84px; border-radius: 18px; font: 35px Georgia, 'Songti SC', serif; background: var(--pine-soft); color: var(--pine); }
.profile-hero h1 { font-size: 28px; font-weight: 600; line-height: 1.35; letter-spacing: -.6px; margin: 12px 0 2px; }
.profile-hero p { margin: 4px 0; font-size: 13px; line-height: 1.8; color: var(--muted); }
.profile-rows button { width: 100%; min-height: 76px; padding: 15px 0; display: flex; align-items: center; gap: 12px; border: 0; border-bottom: 1px solid var(--line); background: transparent; text-align: left; color: var(--ink); }
.profile-row-icon { width: 46px; height: 46px; border-radius: 14px; background: var(--pine-soft); display: grid; place-items: center; color: var(--pine); flex-shrink: 0; }
.profile-rows button > span:nth-child(2) { flex: 1; min-width: 0; }
.profile-rows strong { display: block; font-size: 15px; line-height: 1.5; font-weight: 550; }
.profile-rows small { display: block; margin-top: 4px; font-size: 12px; line-height: 1.6; color: var(--muted); }
.profile-rows button > svg { color: var(--muted); flex-shrink: 0; }
.profile-section-title { margin: 23px 0 12px; min-height: 30px; font-size: 16px; font-weight: 600; }
.profile-logout { width: 100%; min-height: 46px; }
</style>
