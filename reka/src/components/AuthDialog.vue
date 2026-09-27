<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import BaseDialog from './ui/BaseDialog.vue'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { api } from '@/api/client'
import { errorMessage, notify } from '@/composables/useNotice'

const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }] }>()
const { isMobile } = useMobileViewport()
const mode = ref<'login' | 'register' | 'reset'>('login')
const busy = ref(false)
const codeBusy = ref(false)
const form = reactive({ email: '', password: '', confirmPassword: '', code: '' })
const title = computed(() => mode.value === 'reset' ? '找回密码' : isMobile.value ? (mode.value === 'login' ? 'GalChat' : '创建账号') : (mode.value === 'login' ? '回到你的世界' : '建立旅人档案'))
const description = computed(() => mode.value === 'reset' ? '输入账户邮箱，通过邮箱验证码设置新密码。验证码 5 分钟内有效。' : isMobile.value ? undefined : '世界、角色、单聊与群聊记录会保存在当前账号下。')

function switchMode(next: 'login' | 'register' | 'reset') {
  mode.value = next
  Object.assign(form, { password: '', confirmPassword: '', code: '' })
}

async function submit() {
  if (busy.value) return
  if (!form.email || !form.password) return notify('请填写邮箱和密码', '', 'danger')
  if (mode.value !== 'login' && form.password !== form.confirmPassword) return notify('两次密码不一致', '', 'danger')
  if (mode.value === 'reset' && !/^\d{6}$/.test(form.code)) return notify('请填写 6 位邮箱验证码', '', 'danger')
  busy.value = true
  if (mode.value === 'reset') {
    const { email, password, code } = form
    try {
      await api.resetPassword({ email, newPassword: password, verificationCode: code })
      switchMode('login')
      form.email = email
      emit('submit', { mode: 'login', email, password })
    } catch (error) { notify('重置失败', errorMessage(error), 'danger') }
    finally { busy.value = false }
    return
  }
  try { emit('submit', { mode: mode.value, email: form.email, password: form.password, code: form.code }) }
  finally { window.setTimeout(() => { busy.value = false }, 500) }
}
async function sendCode() {
  if (codeBusy.value || busy.value) return
  if (!form.email) return notify('请先填写邮箱', '', 'danger')
  codeBusy.value = true
  try {
    if (mode.value === 'reset') await api.sendPasswordResetCode(form.email)
    else await api.sendRegisterCode(form.email)
    notify('验证码已发送', '请检查邮箱', 'success')
  }
  catch (error) { notify('发送失败', errorMessage(error), 'danger') }
  finally { codeBusy.value = false }
}
</script>

<template>
  <BaseDialog v-model="open" :title="title" :description="description" mobile-presentation="page" :content-class="mode === 'login' ? 'auth-dialog auth-login-dialog' : 'auth-dialog'" :mobile-back="() => { if (!busy && !codeBusy) { if (mode !== 'login') switchMode('login'); else open = false } }" size="sm">
    <div v-if="isMobile && mode === 'login'" class="auth-hero"><span class="auth-brand">✦</span><span class="eyebrow">GALCHAT</span><h1>回到你的世界。</h1><p>角色、故事和每一次相遇，<br />都保存在你的账号里。</p></div>
    <form id="auth-form" class="form-stack auth-form" @submit.prevent="submit">
      <label class="field"><span>邮箱</span><input v-model.trim="form.email" type="email" autocomplete="email" :placeholder="mode === 'register' ? '8 位学号@bjtu.edu.cn' : '请输入账号邮箱'" /></label>
      <div class="field">
        <label for="auth-password">{{ mode === 'reset' ? '新密码' : '密码' }}</label>
        <div class="auth-password-row">
          <input id="auth-password" v-model="form.password" type="password" :autocomplete="mode !== 'login' ? 'new-password' : 'current-password'" :placeholder="mode === 'reset' ? '请输入新密码' : '请输入密码'" />
          <button v-if="mode === 'login'" class="button secondary" type="button" :disabled="busy" @click="switchMode('reset')">找回密码</button>
        </div>
      </div>
      <label v-if="mode !== 'login'" class="field"><span>{{ mode === 'reset' ? '确认新密码' : '确认密码' }}</span><input v-model="form.confirmPassword" type="password" autocomplete="new-password" /></label>
      <label v-if="mode !== 'login'" class="field"><span>6 位邮箱验证码</span><div class="field-inline"><input v-model.trim="form.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="验证码 5 分钟内有效" /><button class="button secondary" type="button" :disabled="codeBusy || busy" @click="sendCode">{{ codeBusy ? '发送中' : '获取验证码' }}</button></div></label>
      <template v-if="isMobile && mode === 'login'"><button class="button primary auth-wide" type="submit" :disabled="busy">{{ busy ? '请稍候…' : '进入 GalChat' }}</button><button class="button secondary auth-wide" type="button" :disabled="busy" @click="switchMode('register')">创建账号</button></template>
    </form>
    <template v-if="!isMobile || mode !== 'login'" #footer>
      <button class="button ghost" type="button" :disabled="busy || codeBusy" @click="switchMode(mode === 'login' ? 'register' : 'login')">{{ mode === 'login' ? '创建账号' : mode === 'reset' ? '返回登录' : '已有账号' }}</button>
      <button class="button primary" type="submit" form="auth-form" :disabled="busy || codeBusy">{{ busy ? '请稍候…' : mode === 'login' ? '进入 GalChat' : mode === 'reset' ? '重置密码' : '完成注册' }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.auth-password-row { display: grid; grid-auto-flow: column; grid-template-columns: minmax(0, 1fr); gap: 7px; }
@media (max-width: 767px) {
  :global(.dialog-content.auth-login-dialog[data-mobile-presentation] > .dialog-header) { position: absolute; width: 1px; height: 1px; min-height: 0; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
  :global(.auth-login-dialog .mobile-dialog-back) { display: none; }

  .auth-hero { padding: 32px 0 22px; }
  .auth-brand { display: grid; place-items: center; width: 52px; height: 52px; border-radius: 11px; background: var(--pine); color: #fff; font-size: 28px; margin-bottom: 20px; }
  .auth-hero .eyebrow { font-family: ui-monospace, monospace; font-size: 10px; letter-spacing: 1.5px; }
  .auth-hero h1 { font-size: 29px; font-weight: 600; letter-spacing: -.6px; line-height: 1.35; margin: 18px 0; }
  .auth-hero p { color: var(--muted); font-size: 14px; line-height: 1.8; }
  .auth-wide { width: 100%; margin: 7px 0; }
  .auth-form .field-inline { flex-wrap: wrap; }
  .auth-form .field-inline input, .auth-form .field-inline button { width: 100%; flex: auto; }
}
</style>
