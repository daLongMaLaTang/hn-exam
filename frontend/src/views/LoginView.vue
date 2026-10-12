<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'

const router = useRouter()
const employeeNo = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
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
  <div class="login-page">
    <div class="login-visual">
      <div class="visual-grid"></div>
      <div class="visual-content">
        <span class="eyebrow">安全培训 · 规范考试 · 数据留痕</span>
        <h1>让每一次培训<br />都有结果可查</h1>
        <p>覆盖题库、组卷、考试、判卷、成绩、补考和错题复习的统一平台。</p>
        <div class="metrics"><span><b>90</b>分钟</span><span><b>100</b>总分</span><span><b>90</b>及格分</span></div>
      </div>
    </div>
    <div class="login-panel">
      <form class="login-card" @submit.prevent="submit">
        <div class="mobile-brand">华能山阴培训考试</div>
        <span class="eyebrow dark">TRAINING & EXAMINATION</span>
        <h2>欢迎登录</h2>
        <p>请使用管理员账号或临时工号进入系统</p>
        <label>账号 / 工号<input v-model="employeeNo" autocomplete="username" placeholder="请输入账号或工号" /></label>
        <label>密码<input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码" /></label>
        <div v-if="error" class="error">{{ error }}</div>
        <button class="primary-button" :disabled="loading">{{ loading ? '正在登录…' : '登录系统' }}</button>
        <div class="login-note">首次登录后需修改初始密码</div>
      </form>
    </div>
  </div>
</template>
