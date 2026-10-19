<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '../api'
import { errorText, formatDate, type Attempt, type Exam, type Grade } from '../candidates/types'
const router = useRouter()
const route = useRoute()
const exams = ref<Exam[]>([])
const focusedExamId = computed(() => Number(route.query.examId) || null)
const shownExams = computed(() => focusedExamId.value ? exams.value.filter(e => e.id === focusedExamId.value) : exams.value)
const grades = ref<Grade[]>([])
const loading = ref(true)
const starting = ref<number | null>(null)
const error = ref('')
const selected = ref<Exam | null>(null)
const now = ref(Date.now())
let offset = 0
let clock: number | undefined
let refresh: number | undefined
const available = computed(() => exams.value.filter(item => item.status !== 'CANCELLED' && (!item.endTime || new Date(item.endTime).getTime() > now.value)).length)
function attemptsFor(examId: number) { return grades.value.filter(grade => grade.examId === examId) }
function activeFor(examId: number) { return attemptsFor(examId).find(grade => grade.status === 'IN_PROGRESS') }
function examState(exam: Exam) {
  if (exam.status === 'CANCELLED') return '已取消'
  if (activeFor(exam.id)) return '继续考试'
  if (attemptsFor(exam.id).some(grade => grade.status === 'SUBMITTED')) return '等待判卷'
  if (attemptsFor(exam.id).some(grade => grade.passed)) return '已通过'
  if (exam.endTime && new Date(exam.endTime).getTime() <= now.value) return '已截止'
  if (exam.startTime && new Date(exam.startTime).getTime() > now.value) return '未开始'
  const count = attemptsFor(exam.id).length
  if (count >= 1) return '已完成'
  return '开始考试'
}
function canStart(exam: Exam) { return ['开始考试', '继续考试'].includes(examState(exam)) }
function stateTone(exam: Exam) { const state = examState(exam); return state === '已通过' ? 'success' : state === '已截止' ? 'danger' : state === '等待判卷' || state === '未开始' || state === '已完成' ? 'warning' : state === '继续考试' ? 'warning' : 'info' }
async function load() {
  loading.value = true; error.value = ''
  try { const time = await api<{serverTime:string}>('/api/candidate/time'); offset = new Date(time.serverTime).getTime() - Date.now(); now.value = Date.now() + offset; [exams.value, grades.value] = await Promise.all([api<Exam[]>('/api/candidate/exams'), api<Grade[]>('/api/candidate/grades')]) }
  catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
async function start() {
  if (!selected.value || starting.value) return
  starting.value = selected.value.id; error.value = ''
  try {
    const attempt = await api<Attempt>(`/api/candidate/exams/${selected.value.id}/start`, { method: 'POST' })
    await router.push(`/take/${attempt.attemptId}`)
  } catch (cause) { error.value = errorText(cause); selected.value = null; await load() }
  finally { starting.value = null }
}
onMounted(() => { void load(); clock = window.setInterval(() => { now.value = Date.now() + offset }, 1000); refresh = window.setInterval(async () => { try { exams.value = await api<Exam[]>('/api/candidate/exams') } catch { /* Existing schedule remains visible. */ } }, 30000) })
onBeforeUnmount(() => { window.clearInterval(clock); window.clearInterval(refresh) })
</script>

<template>
  <div class="page-head"><div><span class="eyebrow dark">MY EXAMINATIONS</span><h1>我的考试</h1><p>查看考试通知、参加考试和补考，作答期间会自动保存答案。</p></div><RouterLink to="/my-grades" class="secondary-button">我的成绩</RouterLink></div>
  <div class="notice">已发布 {{ exams.length }} 场考试，其中 {{ available }} 场尚未截止。进入考试前请确认网络连接正常。</div>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="focusedExamId" class="notice">正在查看通知对应的考试。<RouterLink to="/my-exams">查看全部考试</RouterLink><span v-if="!loading && !shownExams.length"> 该考试已关闭或不在当前可参加范围内。</span></p>
  <article v-if="loading" class="panel"><div class="empty">正在加载考试安排…</div></article>
  <article v-else-if="!exams.length" class="panel"><div class="empty"><span>考</span><h3>暂无已发布考试</h3><p>管理员发布考试后，考试通知将显示在这里。</p><button class="secondary-button" @click="load">刷新列表</button></div></article>
  <div v-else class="candidate-exam-grid">
    <article v-for="exam in shownExams" :key="exam.id" class="panel candidate-exam-card">
      <div class="panel-head"><span class="status-pill" :class="stateTone(exam)">{{ examState(exam) }}</span><span class="date-chip">{{ exam.wrongQuestionPaper ? '个人专属试卷' : exam.questionCount + ' 道题' }}</span></div>
      <p v-if="exam.cancellationReason" class="notice error">取消原因：{{ exam.cancellationReason }}</p><div class="exam-course-label">{{ exam.courseCode || '综合课程' }}</div><h2>{{ exam.title }}</h2><p class="exam-description">{{ exam.description || '请按考试要求独立完成答题；含主观题的试卷将在人工判卷后公布成绩。' }}</p>
      <div class="exam-metrics"><div><strong>{{ exam.durationMinutes }}<small>分钟</small></strong><span>考试时长</span></div><div><strong>{{ exam.totalScore }}<small>分</small></strong><span>试卷总分</span></div><div><strong>{{ exam.passScore }}<small>分</small></strong><span>及格分数</span></div></div>
      <dl class="exam-schedule"><div><dt>开始时间</dt><dd>{{ formatDate(exam.startTime) }}</dd></div><div><dt>截止时间</dt><dd>{{ formatDate(exam.endTime) }}</dd></div><div><dt>作答次数</dt><dd>{{ attemptsFor(exam.id).length }} / 1 次（补考另行安排）</dd></div></dl>
      <button class="primary-button" :disabled="!canStart(exam) || starting !== null" @click="selected = exam">{{ examState(exam) }}</button>
    </article>
  </div>
  <div v-if="selected" class="exam-dialog-backdrop" @click.self="!starting && (selected = null)"><section class="exam-dialog" role="dialog" aria-modal="true" aria-labelledby="exam-confirm-title"><span class="eyebrow dark">EXAM INSTRUCTIONS</span><h2 id="exam-confirm-title">{{ selected.title }}</h2><p>确认进入后开始计时，刷新或离开页面不会暂停计时。</p><ul><li>考试时长 {{ selected.durationMinutes }} 分钟，总分 {{ selected.totalScore }} 分，{{ selected.passScore }} 分及格。</li><li>答案会定期保存，请留意页面中的保存提示。</li><li v-if="selected.basicAntiCheat !== false">离开当前考试页面或切换应用累计 2 次自动交卷，电脑和手机均适用。</li><li>考试时间结束后自动交卷；普通浏览器只能记录页面可见性和焦点变化。</li><li>考试期间禁止复制、剪切、粘贴及拖入答案，请独立作答。</li><li>提前交卷后不能修改答案。</li></ul><div class="dialog-actions"><button class="secondary-button" :disabled="starting !== null" @click="selected = null">暂不进入</button><button class="primary-button" :disabled="starting !== null" @click="start">{{ starting ? '正在进入…' : activeFor(selected.id) ? '继续考试' : '确认开始' }}</button></div></section></div>
</template>

<style scoped>
.candidate-exam-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(310px,1fr));gap:18px}.candidate-exam-card{margin:0;display:flex;flex-direction:column;border-top:3px solid #d9e3e9}.candidate-exam-card:has(.status-pill.info),.candidate-exam-card:has(.status-pill.warning){border-top-color:#f4b740}.candidate-exam-card:has(.status-pill.success){border-top-color:#19a58e}.exam-course-label{align-self:flex-start;margin-top:16px;padding:4px 9px;border-radius:99px;background:#edf6fb;color:#175f88;font-size:10px;font-weight:750}.candidate-exam-card h2{font-size:19px;line-height:1.7;margin:10px 0 8px}.exam-description{font-size:13px;line-height:1.9;color:#708496;white-space:pre-wrap;min-height:48px}.exam-metrics{display:grid;grid-template-columns:repeat(3,1fr);background:#f4f8fb;border-radius:10px;padding:16px 8px;margin:18px 0;text-align:center}.exam-metrics>div+div{border-left:1px solid #dce6ec}.exam-metrics strong{display:block;font-size:23px;color:#164e74}.exam-metrics small{font-size:11px;font-weight:400;margin-left:5px}.exam-metrics span{display:block;color:#7d92a2;font-size:11px;margin-top:6px}.exam-schedule{display:grid;gap:12px;font-size:12px;margin:22px 0}.exam-schedule div{display:flex;justify-content:space-between;gap:10px}.exam-schedule dt{color:#8695a2}.exam-schedule dd{margin:0;text-align:right}.candidate-exam-card>.primary-button{width:100%;margin-top:auto}.exam-dialog-backdrop{position:fixed;inset:0;background:#072039aa;backdrop-filter:blur(4px);z-index:100;display:grid;place-items:center;padding:20px}.exam-dialog{width:min(560px,100%);background:white;border-radius:14px;padding:30px;box-shadow:0 20px 80px #071e3844}.exam-dialog h2{font-size:21px;line-height:1.6}.exam-dialog p,.exam-dialog li{font-size:14px;color:#617b8f;line-height:1.9}.exam-dialog ul{padding-left:22px}.dialog-actions{display:flex;justify-content:flex-end;gap:12px;margin-top:24px}.dialog-actions .secondary-button{margin:0}@media(max-width:520px){.candidate-exam-grid{grid-template-columns:1fr}.exam-dialog{padding:23px}.exam-metrics{padding-inline:4px}.exam-schedule div{display:grid;gap:4px}.exam-schedule dd{text-align:left;color:#284b67}}
</style>
