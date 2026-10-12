<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, currentUser, saveUser, type LoginResult } from '../api'
const router = useRouter()
const oldPassword = ref(''), newPassword = ref(''), repeatPassword = ref(''), error = ref(''), busy = ref(false)
async function submit() {
  error.value = ''
  if (newPassword.value !== repeatPassword.value) { error.value='两次新密码不一致'; return }
  if (!/(?=.*[A-Za-z])(?=.*[0-9])^.{10,64}$/.test(newPassword.value)) { error.value='新密码须为10至64位，包含字母和数字'; return }
  busy.value=true
  try { saveUser(await api<LoginResult>('/api/auth/password',{method:'POST',body:JSON.stringify({oldPassword:oldPassword.value,newPassword:newPassword.value})})); await router.push('/') }
  catch(e) { error.value=e instanceof Error?e.message:'修改失败' } finally { busy.value=false }
}
</script>
<template>
  <div class="page-head"><div><span class="eyebrow dark">ACCOUNT</span><h1>账号设置</h1><p>{{currentUser()?.name}} · {{currentUser()?.employeeNo}}</p></div></div>
  <div v-if="currentUser()?.mustChangePassword" class="notice">首次登录或密码重置后，请先设置自己的密码，再使用系统。</div>
  <form class="panel form-stack narrow" @submit.prevent="submit"><h3>修改密码</h3><label>原密码<input v-model="oldPassword" required type="password" autocomplete="current-password" /></label><label>新密码<input v-model="newPassword" required minlength="10" maxlength="64" type="password" autocomplete="new-password" placeholder="10至64位，包含字母和数字" /></label><label>再次输入新密码<input v-model="repeatPassword" required type="password" autocomplete="new-password" /></label><p v-if="error" role="alert" class="notice error">{{error}}</p><button class="primary-button" :disabled="busy">{{busy?'正在保存…':'保存新密码'}}</button></form>
</template>
