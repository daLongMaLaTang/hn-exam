<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, currentUser, hasRole } from '../api'
import MonthlyLearning from '../components/MonthlyLearning.vue'
import ExamTrends from '../components/ExamTrends.vue'
const summary = ref<Record<string,number>>({}), error=ref('')
const admin = computed(() => currentUser()?.roles.some(r => r !== 'CANDIDATE'))
const cards = computed(() => admin.value ? [['系统用户','userCount'],['可用试题','activeQuestionCount'],['待人工判卷','pendingGradingCount'],['已判答卷','gradedCount']] : [['累计练习','practiceCount'],['练习答对','correctCount'],['完成考试','examCount'],['通过考试','passedCount']])
onMounted(async()=>{ try { summary.value=await api('/api/dashboard/summary') } catch(e) { error.value=e instanceof Error?e.message:'加载失败' } })
</script>
<template>
  <div class="page-head"><div><span class="eyebrow dark">{{admin?'OVERVIEW':'LEARNING CENTER'}}</span><h1>{{admin?'管理工作台':'我的学习中心'}}</h1><p>{{currentUser()?.name}}，欢迎回来。</p></div><span class="date-chip">{{new Date().toLocaleDateString('zh-CN')}}</span></div>
  <div v-if="error" class="notice error" role="alert">{{error}}</div>
  <div class="stat-grid"><article v-for="[label,key] in cards" :key="key" class="stat-card"><span>{{label}}</span><strong>{{summary[key] ?? '—'}}</strong></article></div>
  <MonthlyLearning v-if="hasRole('SYSTEM_ADMIN')" />
  <ExamTrends v-if="hasRole('SYSTEM_ADMIN')" />
  <article class="panel"><div class="panel-head"><div><h3>{{admin?'常用管理':'开始学习'}}</h3><p>{{admin?'按题库、组卷、发布、成绩的顺序管理考试':'随时练习，按时参加考试，及时复习错题'}}</p></div></div><div class="quick-grid">
    <RouterLink v-if="hasRole('SYSTEM_ADMIN','TRAINING_ADMIN','SPECIALIST')" to="/questions"><b>维护题库</b><span>录入试题、核对答案、启用题目</span></RouterLink>
    <RouterLink v-if="hasRole('SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER')" to="/exams"><b>考试管理</b><span>编写试卷、安排时间、发布考试</span></RouterLink>
    <RouterLink v-if="hasRole('SYSTEM_ADMIN','TRAINING_ADMIN','DEPARTMENT_LEADER')" to="/results"><b>成绩与判卷</b><span>处理待判答卷，查看分数和及格情况</span></RouterLink>
    <template v-if="hasRole('CANDIDATE')"><RouterLink to="/practice"><b>在线刷题</b><span>选择分类练习，提交后查看解析</span></RouterLink><RouterLink to="/my-exams"><b>参加考试</b><span>查看已发布考试，继续未完成答卷</span></RouterLink><RouterLink to="/wrong-questions"><b>错题复习</b><span>练习和考试错题集中回顾</span></RouterLink></template>
  </div></article>
  <article class="panel"><h3>考试规则</h3><p class="muted-text">默认满分100分，90分钟，90分及格；具体以每场考试说明为准。考试答案会自动保存，时间截止后由系统交卷。每场考试只能参加一次；补考由管理员另开考试。</p></article>
</template>
