<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'

const router = useRouter()
const employeeNo = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const recovery = ref(false), name = ref(''), received = ref(false)
function switchMode() { recovery.value = !recovery.value; password.value = ''; received.value = false; error.value = '' }
async function submit() {
  loading.value = true
  error.value = ''
  try {
    if (recovery.value) {
      const response = await fetch('/api/auth/password-recovery', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({employeeNo:employeeNo.value.trim(),name:name.value.trim()}) })
      if (!response.ok) throw new Error('提交失败，请稍后重试')
      received.value = true
      return
    }
    const user = await login(employeeNo.value.trim(), password.value)
    router.push(user.mustChangePassword ? '/account' : '/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="entry-page">
    <section class="entry-story" aria-label="培训考试系统">
      <div class="entry-brand"><span class="entry-symbol" aria-hidden="true">学</span>培训考试系统</div>
      <div class="entry-message"><span class="entry-kicker">LEARN. PRACTICE. PROGRESS.</span><h1>每一次学习，<br />向前一步。</h1><p>从日常练习到正式考核，<br />让知识积累与学习进步清晰可见。</p><div class="entry-path"><span>01 学习练习</span><span>02 参加考试</span><span>03 回顾提升</span></div></div>
      <span class="entry-caption">TRAINING & EXAMINATION</span>
    </section>
    <section class="entry-form-area">
      <form class="entry-form" @submit.prevent="submit">
        <span class="entry-mobile-name">培训考试系统</span>
        <span class="eyebrow dark">WELCOME BACK</span><h2>{{ recovery ? '忘记密码' : '欢迎登录' }}</h2><p>{{ recovery ? '填写本人档案中的工号和姓名，由系统管理员审核。' : '使用账号或工号，进入你的工作空间。' }}</p>
        <label for="login-account">账号 / 工号</label><input id="login-account" v-model="employeeNo" required maxlength="32" autocomplete="username" placeholder="请输入账号或工号" />
        <template v-if="recovery"><label for="recovery-name">姓名</label><input id="recovery-name" v-model="name" required maxlength="80" autocomplete="name" /><p v-if="received" role="status">申请已提交，请联系管理员核实身份并领取临时密码。</p></template><template v-else><label for="login-password">密码</label><input id="login-password" v-model="password" required autocomplete="current-password" type="password" placeholder="请输入密码" /></template>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <button class="primary-button" :disabled="loading || (recovery && received)">{{ recovery ? (received ? '已提交' : '提交申请') : loading ? '正在登录…' : '登录系统' }}<span aria-hidden="true">→</span></button>
        <button class="link-button" type="button" :disabled="loading" @click="switchMode">{{ recovery ? '返回登录' : '忘记密码' }}</button><p class="entry-hint">首次登录后，请按提示修改初始密码。</p>
      </form>
      <span class="entry-bottom">培训考试 · 统一入口</span>
    </section>
  </main>
</template>
