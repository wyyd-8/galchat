<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import BaseDialog from './ui/BaseDialog.vue'
import { api } from '@/api/client'
import { errorMessage, notify } from '@/composables/useNotice'

const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ submit: [payload: { mode: 'login' | 'register'; email: string; password: string; code?: string }] }>()
const mode = ref<'login' | 'register'>('login')
const busy = ref(false)
const codeBusy = ref(false)
const form = reactive({ email: '', password: '', confirmPassword: '', code: '' })
const title = computed(() => mode.value === 'login' ? '回到你的世界' : '建立旅人档案')

async function submit() {
  if (!form.email || !form.password) return notify('请填写邮箱和密码', '', 'danger')
  if (mode.value === 'register' && form.password !== form.confirmPassword) return notify('两次密码不一致', '', 'danger')
  busy.value = true
  try { emit('submit', { mode: mode.value, email: form.email, password: form.password, code: form.code }) }
  finally { window.setTimeout(() => { busy.value = false }, 500) }
}
async function sendCode() {
  if (!form.email) return notify('请先填写邮箱', '', 'danger')
  codeBusy.value = true
  try { await api.sendRegisterCode(form.email); notify('验证码已发送', '请检查邮箱', 'success') }
  catch (error) { notify('发送失败', errorMessage(error), 'danger') }
  finally { codeBusy.value = false }
}
</script>

<template>
  <BaseDialog v-model="open" :title="title" description="世界、角色、单聊与群聊记录会保存在当前账号下。" size="sm">
    <form class="form-stack" @submit.prevent="submit">
      <label class="field"><span>邮箱</span><input v-model.trim="form.email" type="email" autocomplete="email" :placeholder="mode === 'register' ? '8 位学号@bjtu.edu.cn' : '请输入账号邮箱'" /></label>
      <label class="field"><span>密码</span><input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码" /></label>
      <label v-if="mode === 'register'" class="field"><span>确认密码</span><input v-model="form.confirmPassword" type="password" autocomplete="new-password" /></label>
      <label v-if="mode === 'register'" class="field"><span>6 位邮箱验证码</span><div class="field-inline"><input v-model.trim="form.code" inputmode="numeric" maxlength="6" placeholder="验证码 5 分钟内有效" /><button class="button secondary" type="button" :disabled="codeBusy" @click="sendCode">{{ codeBusy ? '发送中' : '获取验证码' }}</button></div></label>
    </form>
    <template #footer>
      <button class="button ghost" @click="mode = mode === 'login' ? 'register' : 'login'">{{ mode === 'login' ? '创建账号' : '已有账号' }}</button>
      <button class="button primary" :disabled="busy" @click="submit">{{ busy ? '请稍候…' : mode === 'login' ? '进入 GalChat' : '完成注册' }}</button>
    </template>
  </BaseDialog>
</template>
