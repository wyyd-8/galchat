// Local visual fixture: no authentication, profile writes, or game requests.
import { createApp, h, ref } from 'vue'
import AccountDiceSettings from '../../src/components/AccountDiceSettings.vue'
import BaseDialog from '../../src/components/ui/BaseDialog.vue'
import type { DiceSkin } from '../../src/dice/domain/dicePlayback'
import '../../src/styles/index.css'
import '../../src/styles/mobile.css'

createApp({
  setup() {
    const skin = ref<DiceSkin>('galaxy')
    const open = ref(true)
    return () => h('main', { style: { padding: '24px' } }, [
      h('button', { class: 'button', onClick: () => { open.value = true } }, '打开账户预览'),
      h(BaseDialog, {
        modelValue: open.value,
        'onUpdate:modelValue': (value: boolean) => { open.value = value },
        title: '账号资料', description: '个人信息与掷骰偏好', size: 'lg', mobilePresentation: 'page', contentClass: 'account-settings-dialog',
      }, {
        default: () => open.value ? h(AccountDiceSettings, {
          modelValue: skin.value,
          'onUpdate:modelValue': (value: DiceSkin) => { skin.value = value },
        }, {
          default: () => h('div', { class: 'form-stack' }, [
            h('label', { class: 'field' }, [h('span', '用户名'), h('input', { value: '旅人' })]),
            h('label', { class: 'field' }, [h('span', '邮箱（不可在此修改）'), h('input', { value: 'traveler@example.com', disabled: true })]),
            h('label', { class: 'field' }, [h('span', '生日'), h('input', { type: 'date', value: '2000-01-01' })]),
          ]),
        }) : null,
        footer: () => h('button', { class: 'button primary', onClick: () => { open.value = false } }, '保存'),
      }),
    ])
  },
}).mount('#app')
