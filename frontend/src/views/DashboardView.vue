<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, currentUser, hasRole } from '../api'
import { formatDate, type Exam, type Grade } from '../candidates/types'
import MonthlyLearning from '../components/MonthlyLearning.vue'
import ExamTrends from '../components/ExamTrends.vue'
import AppIcon from '../components/AppIcon.vue'
const summary = ref<Record<string, number>>({}), exams = ref<Exam[]>([]), grades = ref<Grade[]>([])
const loading = ref(true), error = ref(''), showAnalysis = ref(false), now = ref(Date.now())
const admin = computed(() => currentUser()?.roles.some(r => r !== 'CANDIDATE'))
const listPath = computed(() => admin.value ? '/exams' : '/my-exams')
const cards = computed(() => admin.value ? [['系统用户','userCount'],['可用试题','activeQuestionCount'],['考试安排','examCount'],['已判答卷','gradedCount']] : [['累计练习','practiceCount'],['练习答对','correctCount'],['完成考试','examCount'],['通过考试','passedCount']])
function state(exam: Exam) {
  if (exam.status === 'CANCELLED') return '已取消'
  if (exam.status === 'DRAFT') return '待发布'
  if (!admin.value) {
    const own = grades.value.filter(g => g.examId === exam.id)
    if (own.some(g => g.status === 'IN_PROGRESS')) return '作答中'
    if (own.some(g => g.status === 'SUBMITTED')) return '待判卷'
    if (own.some(g => g.status === 'GRADED')) return '已完成'
  }
  if (exam.status === 'CLOSED' || (exam.endTime && new Date(exam.endTime).getTime() <= now.value)) return '已截止'
  if (exam.startTime && new Date(exam.startTime).getTime() > now.value) return '未开始'
  return '已开放'
}
const priority: Record<string, number> = {作答中:0, 已开放:1, 待发布:2, 未开始:3, 待判卷:4, 已完成:5, 已截止:6, 已取消:7}
const tasks = computed(() => [...exams.value].sort((a,b) => (priority[state(a)] ?? 9) - (priority[state(b)] ?? 9) || b.id-a.id).slice(0,5))
const pending = computed(() => admin.value ? summary.value.pendingGradingCount : exams.value.filter(e => ['已开放','作答中'].includes(state(e))).length)
const quickLinks = computed(() => admin.value ? [
  {to:'/questions', title:'维护题库', subtitle:'试题与参考答案', icon:'questions'},
  {to:'/exams', title:'安排考试', subtitle:'组卷、发布与管理', icon:'exams'},
  {to:'/results', title:'成绩与判卷', subtitle:'核对答案与评分', icon:'results'}
] : [
  {to:'/practice',title:'在线刷题',subtitle:'按课程巩固知识',icon:'practice'},
  {to:'/wrong-questions',title:'错题复习',subtitle:'回顾日常练习错题',icon:'wrong'},
  {to:'/my-grades',title:'查看成绩',subtitle:'了解考试结果',icon:'grades'}
])
async function load() {
  loading.value = true; error.value = ''
  try {
    const [nextSummary, nextExams, nextGrades, time] = await Promise.all([
      api<Record<string,number>>('/api/dashboard/summary'),
      api<Exam[]>(admin.value ? '/api/exams' : '/api/candidate/exams'),
      admin.value ? Promise.resolve([] as Grade[]) : api<Grade[]>('/api/candidate/grades'),
      admin.value ? Promise.resolve({serverTime:new Date().toISOString()}) : api<{serverTime:string}>('/api/candidate/time')
    ])
    summary.value = nextSummary; exams.value = nextExams; grades.value = nextGrades; now.value = new Date(time.serverTime).getTime()
  } catch (e) { error.value = e instanceof Error ? e.message : '加载失败，请重试' }
  finally { loading.value = false }
}
onMounted(load)
</script>
<template>
  <div class="desk-heading"><div><span class="eyebrow dark">{{admin?'WORKSPACE':'LEARNING SPACE'}}</span><h1>{{admin?'管理工作台':'我的学习中心'}}</h1></div><span class="desk-date">{{new Date().toLocaleDateString('zh-CN',{year:'numeric',month:'long',day:'numeric',weekday:'long'})}}</span></div>
  <section class="desk-welcome"><div><span class="desk-welcome-label">{{admin?'今日工作，从这里开始':'保持积累，循序前行'}}</span><h2>{{currentUser()?.name}}，欢迎回来。</h2><p>{{admin?'安排考试、处理答卷，让每一步考核有序进行。':'查看考试安排，完成练习，回顾每一次学习的收获。'}}</p><RouterLink :to="admin?'/results':'/my-exams'" class="desk-hero-link">{{admin?'进入成绩与判卷':'查看我的考试'}}<span aria-hidden="true">↗</span></RouterLink></div><div class="desk-welcome-art" aria-hidden="true"><div></div><div></div><div></div><span>学 · 练 · 考</span></div></section>
  <div v-if="error" class="notice error" role="alert">{{error}} <button class="link-button" :disabled="loading" @click="load">重新加载</button></div>
  <div class="desk-columns" :aria-busy="loading">
    <section class="desk-section desk-tasks"><header class="desk-section-head"><div><span class="section-number">01</span><h2>{{admin?'考试安排':'我的考试安排'}}</h2></div><RouterLink :to="listPath">查看全部 <span aria-hidden="true">→</span></RouterLink></header>
      <div v-if="loading" class="desk-empty" role="status">正在加载考试安排…</div>
      <div v-else-if="error" class="desk-empty">考试安排暂时无法读取，请重新加载。</div>
      <div v-else-if="!tasks.length" class="desk-empty"><AppIcon name="exams" /><h3>{{admin?'还没有考试安排':'暂无分配的考试'}}</h3><p>{{admin?'创建试卷并发布后，考试将显示在这里。':'管理员发布考试后，可以在这里查看安排。'}}</p><RouterLink :to="admin?'/exams':'/practice'" class="secondary-button">{{admin?'去安排考试':'先去练习'}}</RouterLink></div>
      <div v-else class="desk-task-list"><div class="desk-task-labels" aria-hidden="true"><span>考试 / 课程</span><span>状态</span><span>截止时间</span></div><RouterLink v-for="exam in tasks" :key="exam.id" :to="listPath" class="desk-task-row"><div class="desk-task-title"><strong>{{exam.title}}</strong><small>{{exam.courseCode || '综合课程'}} · {{exam.durationMinutes}} 分钟</small></div><span class="desk-state" :class="{'is-open':['已开放','作答中'].includes(state(exam))}">{{state(exam)}}</span><span class="desk-task-date">{{exam.endTime?formatDate(exam.endTime):'未设置截止'}}<span aria-hidden="true">↗</span></span></RouterLink></div>
    </section>
    <aside class="desk-section desk-overview"><header class="desk-section-head"><div><span class="section-number">02</span><h2>{{admin?'待办概览':'学习概览'}}</h2></div></header><div class="desk-pending"><span>{{admin?'待人工判卷':'待参加 / 继续考试'}}</span><strong>{{loading || error?'—':pending ?? '—'}}<small>{{admin?'份答卷':'场考试'}}</small></strong><RouterLink :to="admin?'/results':'/my-exams'">{{admin?'前往处理':'查看安排'}} <span aria-hidden="true">→</span></RouterLink></div><p class="desk-tip">{{admin?'含主观题的答卷需要完成判卷后，才会公布最终成绩。':'正式考试禁止复制粘贴，请独立作答；进入考试后不会暂停计时。'}}</p></aside>
  </div>
  <section class="desk-shortcuts" aria-label="常用功能"><RouterLink v-for="item in quickLinks" :key="item.to" :to="item.to"><AppIcon :name="item.icon" /><div><strong>{{item.title}}</strong><small>{{item.subtitle}}</small></div><span aria-hidden="true">↗</span></RouterLink></section>
  <section class="desk-statistics" aria-label="累计统计"><div class="desk-stat-title"><span class="section-number">03</span><strong>{{admin?'平台统计':'学习记录'}}</strong><small>已保存的数据</small></div><div v-for="[label,key] in cards" :key="key" class="desk-stat"><strong>{{loading || error?'—':summary[key] ?? '—'}}</strong><span>{{label}}</span></div></section>
  <section v-if="hasRole('SYSTEM_ADMIN')" class="desk-analysis"><button class="secondary-button" :aria-expanded="showAnalysis" aria-controls="learning-analysis" @click="showAnalysis=!showAnalysis">{{showAnalysis?'收起':'展开'}}月度学习与考试趋势 <span aria-hidden="true">{{showAnalysis?'−':'+'}}</span></button><div v-if="showAnalysis" id="learning-analysis"><MonthlyLearning /><ExamTrends /></div></section>
</template>
