<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser, signOut, hasRole } from './api'
import AppIcon from './components/AppIcon.vue'
import NotificationLink from './components/NotificationLink.vue'
const route = useRoute(), router = useRouter(), user = computed(() => { void route.path; return currentUser() })
const isLogin = computed(() => route.path === '/login')
const isAdmin = computed(() => user.value?.roles.some(r => r !== 'CANDIDATE'))
const adminNav = [{path:'/',label:'工作台',icon:'dashboard'},{path:'/users',label:'人员与权限',icon:'users'},{path:'/questions',label:'题库管理',icon:'questions'},{path:'/exams',label:'考试管理',icon:'exams'},{path:'/results',label:'成绩与判卷',icon:'results'}]
const candidateNav = [{path:'/',label:'学习中心',icon:'dashboard'},{path:'/practice',label:'在线刷题',icon:'practice'},{path:'/my-exams',label:'参加考试',icon:'take-exam'},{path:'/my-grades',label:'我的成绩',icon:'grades'},{path:'/wrong-questions',label:'日常错题',icon:'wrong'},{path:'/exam-wrong-questions',label:'考试错题',icon:'wrong'}]
const navigation = computed(() => {
  void route.path
  const nav = isAdmin.value ? adminNav.filter(i => i.path === '/' || (i.path === '/questions' ? hasRole('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST') : i.path === '/users' ? hasRole('SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER') : hasRole('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN','SPECIALIST'))) : []
  if (hasRole('CANDIDATE')) nav.push(...candidateNav.filter(i => !nav.some(n => n.path === i.path)))
  if (hasRole('SYSTEM_ADMIN')) nav.push({path:'/audit',label:'操作记录',icon:'audit'})
  return [...nav, {path:'/account',label:'账号设置',icon:'settings'}]
})
function logout(){ signOut(); router.push('/login') }
</script>
<template>
  <router-view v-if="isLogin" />
  <div v-else class="workspace-shell">
    <a class="skip-link" href="#workspace-main">跳到主要内容</a>
    <header class="workspace-header">
      <RouterLink to="/" class="workspace-brand" aria-label="培训考试系统首页"><span class="workspace-mark" aria-hidden="true"><AppIcon name="questions" /></span><span><strong>培训考试系统</strong><small>TRAINING & EXAMINATION</small></span></RouterLink>
      <div class="workspace-profile"><NotificationLink v-if="user?.roles.includes('CANDIDATE')" /><span class="avatar" aria-hidden="true">{{user?.name?.slice(0,1)}}</span><div><strong>{{user?.name}}</strong><small>{{isAdmin?'管理工作空间':'学习工作空间'}}</small></div><button class="link-button" @click="logout">退出登录</button></div>
    </header>
    <div class="workspace-nav-band"><nav class="workspace-nav" aria-label="主导航"><RouterLink v-for="item in navigation" :key="item.path" :to="item.path"><AppIcon :name="item.icon" /><span>{{item.label}}</span></RouterLink></nav></div>
    <main id="workspace-main" class="workspace-main" tabindex="-1"><router-view /></main>
    <footer class="workspace-footer"><span>培训考试系统</span><span>学习有记录 · 考核有依据</span></footer>
  </div>
</template>
