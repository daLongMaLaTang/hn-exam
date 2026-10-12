<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentUser, signOut, hasRole } from './api'
import AppIcon from './components/AppIcon.vue'
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
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark"><span>HN</span></div><div><strong>培训考试系统</strong><small>华能山阴生产准备部</small></div></div>
      <div class="nav-label">{{isAdmin ? '管理中心' : '学习导航'}}</div>
      <nav><RouterLink v-for="item in navigation" :key="item.path" :to="item.path"><span class="nav-icon"><AppIcon :name="item.icon" /></span><span class="nav-text">{{item.label}}</span><i aria-hidden="true"></i></RouterLink></nav>
      <div class="side-foot"><span class="side-status"><i></i>服务正常</span><small>当前身份</small><strong>{{isAdmin?'管理人员':'考生'}}</strong></div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="system-state"><span class="online-dot"></span><span>系统运行正常</span><small>数据实时保存</small></div><div class="profile"><span class="avatar">{{user?.name?.slice(0,1)}}</span><span class="profile-copy"><strong>{{user?.name}}</strong><small>{{user?.employeeNo}}</small></span><button class="link-button logout-button" @click="logout">退出登录</button></div></header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
