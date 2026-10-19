<script setup lang="ts">
import FillBlankReview from '../components/FillBlankReview.vue'
import ShortAnswerReview from '../components/ShortAnswerReview.vue'
import { displayAnswer, decodeBlankAnswers } from '../candidates/blankAnswers'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, hasRole, currentUser } from '../api'
type Exam = { id: number; title: string; status: string; passScore: number }
type Grade = { attemptId: number; examId: number; examTitle: string; userId: number; employeeNo: string; userName: string; status: string; startedAt: string; submittedAt: string | null; score: number | null; totalScore: number; passed: boolean; focusLossCount: number; subjectiveQuestionCount: number; pendingSubjectiveCount: number; gradedAt?: string | null; gradedByEmployeeNo?: string | null; gradingNote?: string | null }
type ReviewQuestion = { id: number; type: string; stem: string; optionsJson: string; points: number; position: number; savedAnswer: string | null; correctAnswer: string; explanation: string; correct: boolean | null; awardedPoints: number | null; objective: boolean; gradingMethod?: string | null; gradingComment?: string | null; aiSuggestedPoints?: number | null; aiSuggestionReason?: string | null; semanticSimilarity?: number | null; aiSuggestedSimilarity?: number | null; aiModel?: string; aiStatus?: string }
type Review = { grade: Grade; questions: ReviewQuestion[] }
type Statistics = { examId: number; submittedCount: number; participantCount: number; passedCount: number; passRate: number; averageScore: number; highestScore: number; lowestScore: number; scoreBands: { label: string; count: number }[]; highErrorQuestions: { questionId: number; stem: string; type: string; chapter: string; answeredCount: number; wrongCount: number; errorRate: number }[] }
type ScoreInput = { correctBlanks: number | '';  awardedPoints: number | ''; comment: string; semanticSimilarity: number | '' }
const route = useRoute(), exams = ref<Exam[]>([]), examId = ref(''), grades = ref<Grade[]>([]), statistics = ref<Statistics | null>(null), query = ref(''), outcome = ref(''), error = ref(''), message = ref(''), loading = ref(false), reviewLoading = ref(false), grading = ref(false), review = ref<Review | null>(null), page = ref(1)
const blankRefresh = ref(0), shortRefresh = ref(0)
const scores = ref<Record<number, ScoreInput>>({}), overallComment = ref('')
const canGrade = hasRole('SYSTEM_ADMIN')
const canConfigureAi = canGrade && currentUser()?.canManageSystem === true
const names: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', FILL_BLANK: '填空题', SHORT_ANSWER: '简答题', CALCULATION: '计算题', IMAGE_CASE: '案例题', ESSAY: '大题' }
const completed = computed(() => grades.value.filter(g => g.status === 'GRADED'))
const pending = computed(() => grades.value.filter(g => g.status === 'SUBMITTED'))
const average = computed(() => completed.value.length ? (completed.value.reduce((sum, g) => sum + (g.score || 0), 0) / completed.value.length).toFixed(1) : '—')
const passRate = computed(() => completed.value.length ? (completed.value.filter(g => g.passed).length / completed.value.length * 100).toFixed(1) + '%' : '—')
const participantCount = computed(() => new Set(grades.value.map(g => g.userId)).size)
function matchesOutcome(g: Grade) {
  if (!outcome.value) return true
  if (outcome.value === 'progress') return g.status === 'IN_PROGRESS'
  if (outcome.value === 'pending') return g.status === 'SUBMITTED'
  return g.status === 'GRADED' && (outcome.value === 'passed' ? g.passed : !g.passed)
}
const filtered = computed(() => grades.value.filter(g => `${g.employeeNo} ${g.userName}`.includes(query.value.trim()) && matchesOutcome(g)))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 20)))
const visible = computed(() => filtered.value.slice((Math.min(page.value, pages.value) - 1) * 20, Math.min(page.value, pages.value) * 20))
const bestByUser = computed(() => { const best = new Map<number, number>(); completed.value.forEach(g => best.set(g.userId, Math.max(best.get(g.userId) ?? 0, g.score ?? 0))); return best })
const maxBand = computed(() => Math.max(1, ...(statistics.value?.scoreBands.map(b => b.count) || [1])))
function reason(e: unknown) { return e instanceof Error ? e.message : '加载失败，请重试' }
function date(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function options(value: string) { try { const p = JSON.parse(value || '[]'); return Array.isArray(p) ? p.map(String) : [] } catch { return [] } }
function state(g: Grade) { if (g.status === 'CANCELLED') return '已取消'; return g.status === 'GRADED' ? (g.passed ? '及格' : '未及格') : g.status === 'IN_PROGRESS' ? '考试中' : g.pendingSubjectiveCount ? '待判卷' : '待公布' }
async function loadResults() {
  const requested = examId.value; if (!requested) { grades.value = []; statistics.value = null; return }; loading.value = true; error.value = ''; review.value = null; page.value = 1
  try { const [result, analysis] = await Promise.all([api<Grade[]>(`/api/exams/${requested}/results`), api<Statistics>(`/api/exams/${requested}/statistics`)]); if (examId.value === requested) { grades.value = result; statistics.value = analysis } } catch (e) { if (examId.value === requested) { grades.value = []; statistics.value = null; error.value = reason(e) } } finally { if (examId.value === requested) loading.value = false }
}
async function openReview(grade: Grade) {
  reviewLoading.value = true; error.value = ''; message.value = ''
  try {
    review.value = await api<Review>(`/api/exams/attempts/${grade.attemptId}/review`)
    scores.value = Object.fromEntries(review.value.questions.filter(q => !q.objective).map(q => [q.id, { awardedPoints: q.awardedPoints ?? '', comment: q.gradingComment || '', semanticSimilarity: q.semanticSimilarity ?? '', correctBlanks: '' }]))
    overallComment.value = review.value.grade.gradingNote || ''
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) { error.value = reason(e) } finally { reviewLoading.value = false }
}
async function submitGrade() {
  if (!review.value || !canGrade || grading.value) return
  const subjective = review.value.questions.filter(q => !q.objective)
  for (const question of subjective) {
    if (question.type === 'FILL_BLANK') {
      const count=scores.value[question.id].correctBlanks, total=decodeBlankAnswers(question.correctAnswer).length
      if(count==='' || !Number.isInteger(count) || count<0 || count>total){error.value='请逐空复核并填写答对空数';return}
      scores.value[question.id].awardedPoints=Math.round(question.points*count/total)
    }
    const value = scores.value[question.id]?.awardedPoints
    if (question.type === 'SHORT_ANSWER' && (scores.value[question.id].semanticSimilarity === '' || !Number.isFinite(Number(scores.value[question.id].semanticSimilarity)) || Number(scores.value[question.id].semanticSimilarity) < 0 || Number(scores.value[question.id].semanticSimilarity) > 100)) { error.value = '请填写简答题语义相似度（0至100%）'; return }
    if (value === '' || !Number.isInteger(Number(value)) || Number(value) < 0 || Number(value) > question.points) { error.value = `第 ${question.position} 题得分须为 0～${question.points} 的整数`; return }
  }
  if (review.value.grade.status === 'GRADED' && !window.confirm('这份答卷已完成判卷。重新提交会更新考生成绩和通过状态，确认继续？')) return
  grading.value = true; error.value = ''; message.value = ''
  try {
    const body = { scores: Object.fromEntries(subjective.map(q => [q.id, { correctBlanks: scores.value[q.id].correctBlanks === '' ? null : Number(scores.value[q.id].correctBlanks), awardedPoints: Number(scores.value[q.id].awardedPoints), comment: scores.value[q.id].comment, semanticSimilarity: scores.value[q.id].semanticSimilarity === '' ? null : Number(scores.value[q.id].semanticSimilarity) }])), overallComment: overallComment.value }
    const updated = await api<Review>(`/api/exams/attempts/${review.value.grade.attemptId}/grade`, { method: 'POST', body: JSON.stringify(body) })
    review.value = updated
    statistics.value = await api<Statistics>(`/api/exams/${examId.value}/statistics`)
    const index = grades.value.findIndex(g => g.attemptId === updated.grade.attemptId); if (index >= 0) grades.value[index] = updated.grade
    blankRefresh.value++; shortRefresh.value++
    message.value = updated.grade.status === 'SUBMITTED' ? '评分已保存。所有题目完成后，请在集中阅卷区统一公布。' : `判卷已完成：${updated.grade.score} / ${updated.grade.totalScore} 分，${updated.grade.passed ? '已通过' : '未通过'}`
  } catch (e) { error.value = reason(e) } finally { grading.value = false }
}
const ai = ref({ enabled: false, keyConfigured: false, available: false })
const apiKey = ref(''), configError = ref(''), configMessage = ref('')
const aiBusy = ref(false), aiProgress = ref(''), configBusy = ref(false), configLoading = ref(true)
async function loadAi() {
  configLoading.value = true; configError.value = ''
  try { ai.value = { ...ai.value, ...await api<typeof ai.value>(canConfigureAi ? '/api/ai/settings' : '/api/ai/status') } }
  catch (e) { if (canConfigureAi) configError.value = reason(e); else error.value = reason(e) }
  finally { configLoading.value = false }
}
async function saveAi() {
  if (configBusy.value || !apiKey.value.trim()) return
  configBusy.value = true; configError.value = ''; configMessage.value = ''
  try {
    ai.value = await api('/api/ai/settings', { method: 'PUT', body: JSON.stringify({ apiKey: apiKey.value.trim() }) })
    apiKey.value = ''; configMessage.value = 'DeepSeek 已接入并启用，无需重启。'
  } catch (e) { configError.value = reason(e) } finally { configBusy.value = false }
}
async function disableAi() {
  if (configBusy.value || !window.confirm('停用 AI 后，新的语义判断和评分建议将暂停。确认停用？')) return
  configBusy.value = true; configError.value = ''; configMessage.value = ''
  try { ai.value = await api('/api/ai/settings/disable', { method: 'POST' }); apiKey.value = ''; configMessage.value = 'AI 已停用。重新填写 API Key 可再次启用。' }
  catch (e) { configError.value = reason(e) } finally { configBusy.value = false }
}
function adopt(q: ReviewQuestion) { if (q.aiSuggestedPoints != null) scores.value[q.id] = { correctBlanks: '', awardedPoints: q.aiSuggestedPoints, comment: q.aiSuggestionReason || '', semanticSimilarity: q.aiSuggestedSimilarity ?? '' } }
async function suggestAi() {
  if (!review.value || aiBusy.value) return
  aiBusy.value = true; error.value = ''; let failed = 0
  const attemptId = review.value.grade.attemptId
  const pendingQuestions = review.value.questions.filter(q => !q.objective && q.type !== 'FILL_BLANK' && q.aiStatus !== 'READY')
  try {
    for (let i = 0; i < pendingQuestions.length; i++) {
      aiProgress.value = '正在生成建议 ' + (i + 1) + ' / ' + pendingQuestions.length
      try { await api('/api/exams/attempts/' + attemptId + '/ai/' + pendingQuestions[i].id, { method: 'POST' }) } catch { failed++ }
      const next = await api<Review>('/api/exams/attempts/' + attemptId + '/review')
      if (review.value?.grade.attemptId === attemptId) review.value = next
    }
    aiProgress.value = failed ? failed + ' 题未生成，可重试或人工评分' : '建议已生成，请逐题复核后采纳，再公布成绩'
  } catch (e) { error.value = reason(e) } finally { aiBusy.value = false }
}
function exportCsv() {
  const cell = (value: unknown) => { let text = String(value ?? ''); if (/^[\s]*[=+@\-]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"' }
  const rows: unknown[][] = [['考试名称', '工号', '姓名', '答卷编号', '开始时间', '交卷时间', '得分', '总分', '结果', '切屏次数', '个人本场最高分']]
  filtered.value.forEach(g => rows.push([g.examTitle, "'" + g.employeeNo, g.userName, g.attemptId, date(g.startedAt), date(g.submittedAt), g.score ?? '', g.totalScore, state(g), g.focusLossCount, bestByUser.value.get(g.userId) ?? '']))
  const url = URL.createObjectURL(new Blob(['\uFEFF', rows.map(row => row.map(cell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })); const link = document.createElement('a'); link.href = url; link.download = `考试成绩_${examId.value}_${new Date().toISOString().slice(0,10)}.csv`; link.click(); URL.revokeObjectURL(url)
}
function exportAnalysis() {
  if (!statistics.value) return
  const cell = (value: unknown) => { let text = String(value ?? ''); if (/^[\s]*[=+@\-]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text; return '"' + text.replace(/"/g, '""') + '"' }
  const current = exams.value.find(e => String(e.id) === examId.value)
  const s = statistics.value
  const rows: unknown[][] = [
    ['考试分析报表'], ['考试名称', current?.title || ''], ['生成时间', new Date().toLocaleString('zh-CN', { hour12: false })],
    [], ['总体概览'], ['参加人数', s.participantCount], ['有效答卷', s.submittedCount], ['通过答卷数', s.passedCount], ['及格率', s.passRate.toFixed(1) + '%'], ['平均分', s.averageScore.toFixed(1)], ['最高分', s.highestScore], ['最低分', s.lowestScore],
    [], ['成绩分布'], ['百分制分数段', '答卷数'], ...s.scoreBands.map(item => [item.label, item.count]),
    [], ['高错误率题目'], ['题目编号', '章节', '题型', '题干', '作答人次', '错误人次', '错误率'],
    ...s.highErrorQuestions.map(item => [item.questionId, item.chapter || '未分章', names[item.type] || item.type, item.stem, item.answeredCount, item.wrongCount, item.errorRate.toFixed(1) + '%'])
  ]
  const url = URL.createObjectURL(new Blob(['\uFEFF', rows.map(row => row.map(cell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })); const link = document.createElement('a'); link.href = url; link.download = `考试分析_${current?.title || examId.value}_${new Date().toISOString().slice(0,10)}.csv`; link.click(); URL.revokeObjectURL(url)
}
watch(examId, loadResults)
watch([() => route.query.attemptId, grades], () => {
  const target = grades.value.find(g => g.attemptId === Number(route.query.attemptId) && g.examId === Number(route.query.examId))
  if (target && target.status !== 'IN_PROGRESS' && review.value?.grade.attemptId !== target.attemptId) void openReview(target)
})
watch(() => route.query.examId, value => { if (value && exams.value.some(e => String(e.id) === String(value))) examId.value = String(value) })
onMounted(async () => { if (canGrade) void loadAi(); try { exams.value = await api<Exam[]>('/api/exams'); examId.value = exams.value.some(e => String(e.id) === String(route.query.examId)) ? String(route.query.examId) : String(exams.value[0]?.id || '') } catch (e) { error.value = reason(e) } })
</script>
<template>
  <div class="page-head"><div><span class="eyebrow dark">RESULTS & GRADING</span><h1>成绩与人工判卷</h1><p>处理待判主观题，查看考试结果、补考记录并导出最终成绩。</p></div><div class="head-actions"><button class="secondary-button small" :disabled="!statistics || loading" @click="exportAnalysis">导出分析报表</button><button class="primary-button small" :disabled="!filtered.length || loading" @click="exportCsv">导出成绩明细</button></div></div>
  <div v-if="error" class="notice error" role="alert">{{ error }}</div><div v-if="message" class="notice" role="status">{{ message }}</div>
  <details v-if="canConfigureAi" class="panel ai-config">
    <summary>接入 AI · DeepSeek · {{ configLoading ? '正在读取…' : ai.available ? '已启用' : '未启用' }}</summary>
    <p>仅具有系统配置授权的管理员可设置。填写 DeepSeek API Key 即可接入，用于日常练习语义判断和简答辅助判卷。考试填空使用答案库与集中阅卷。</p>
    <p v-if="ai.keyConfigured" class="hint">已保存密钥，不显示原文。填写新密钥可更换；验证失败会保留原配置。</p>
    <div v-if="configError" class="notice error" role="alert">{{ configError }} <button type="button" class="secondary-button small" :disabled="configBusy || configLoading" @click="loadAi">刷新状态</button></div>
    <div v-if="configMessage" class="notice" role="status">{{ configMessage }}</div>
    <form class="config-grid" :aria-busy="configBusy" @submit.prevent="saveAi">
      <label>DeepSeek API Key<input v-model="apiKey" type="password" autocomplete="new-password" spellcheck="false" maxlength="512" required :disabled="configBusy || configLoading" :placeholder="ai.keyConfigured ? '输入新 API Key 以更换或重新启用' : '粘贴 DeepSeek 平台生成的 API Key'" aria-describedby="ai-key-help"></label>
      <div class="config-actions"><button class="primary-button" :disabled="configBusy || configLoading || !apiKey.trim()">{{ configBusy ? '正在处理…' : '验证并接入' }}</button><button v-if="ai.enabled" type="button" class="secondary-button" :disabled="configBusy || configLoading" @click="disableAi">停用 AI</button></div>
    </form>
    <p id="ai-key-help" class="hint">保存后立即生效。调用 AI 会使用 DeepSeek 账户余额；辅助评分建议仍需人工复核。</p>
  </details>
  <article class="panel"><div class="toolbar"><label>选择考试 <select v-model="examId"><option value="" disabled>请选择考试</option><option v-for="exam in exams" :key="exam.id" :value="String(exam.id)">{{ exam.title }}</option></select></label><button class="secondary-button" :disabled="loading || !examId" @click="loadResults(); blankRefresh++; shortRefresh++">刷新成绩</button></div><p v-if="!exams.length" class="hint">还没有考试。发布考试并有考生作答后，这里会显示成绩。</p><p v-else class="hint">分布以百分制折算分组；平均、最高、最低为原始分。统计按全部考试记录计算：平均分、及格率以已判卷的答卷为准，补考单独计次。下方“个人最高分”可用于查看补考后的最终成绩。</p></article>
  <FillBlankReview v-if="canGrade && examId && exams.find(e => String(e.id) === examId)?.status !== 'CANCELLED'" :key="examId + '-' + blankRefresh" :exam-id="examId" @changed="loadResults(); shortRefresh++" />
  <ShortAnswerReview v-if="canGrade && examId && exams.find(e => String(e.id) === examId)?.status !== 'CANCELLED'" :key="examId + '-short-' + shortRefresh" :exam-id="examId" @changed="loadResults(); blankRefresh++" />
  <div class="stat-grid"><article class="stat-card"><span>参加人数</span><strong>{{ participantCount }}</strong><small>同一人员只计一次</small></article><article class="stat-card pending-card"><span>待判卷 / 待公布</span><strong>{{ pending.length }}</strong><small>含填空待判与已评分待公布</small></article><article class="stat-card"><span>已判卷答卷</span><strong>{{ completed.length }}</strong><small>含首次考试及补考</small></article><article class="stat-card"><span>平均分</span><strong>{{ average }}</strong><small>全部已判卷答卷</small></article><article class="stat-card accent"><span>答卷及格率</span><strong>{{ passRate }}</strong><small>全部已判卷答卷</small></article></div>
  <section v-if="statistics" class="analysis-grid">
    <article class="analysis-card distribution"><div class="analysis-head"><div><span>成绩分布</span><h3>各分数段答卷数</h3></div><strong>{{ statistics.submittedCount }}<small>份有效答卷</small></strong></div><div v-if="statistics.scoreBands.some(b => b.count)" class="band-chart"><div v-for="band in statistics.scoreBands" :key="band.label" class="band-column"><span class="band-value">{{ band.count }}</span><div class="band-track"><i :style="{ height: (band.count ? Math.max(5, band.count / maxBand * 100) : 0) + '%' }"></i></div><small>{{ band.label }}</small></div></div><div v-else class="analysis-empty">有已判卷成绩后显示分数分布</div><footer><span>最高 {{ statistics.submittedCount ? statistics.highestScore : '—' }} 分</span><span>最低 {{ statistics.submittedCount ? statistics.lowestScore : '—' }} 分</span><span>平均 {{ statistics.averageScore.toFixed(1) }} 分</span></footer></article>
    <article class="analysis-card errors"><div class="analysis-head"><div><span>试题诊断</span><h3>高错误率题目（前10）</h3></div><strong>{{ statistics.highErrorQuestions.length }}<small>道需重点讲解</small></strong></div><div v-if="statistics.highErrorQuestions.length" class="error-list"><div v-for="(item, index) in statistics.highErrorQuestions" :key="item.questionId" class="error-item"><div class="error-title"><b>{{ String(index + 1).padStart(2, '0') }}</b><span><small>{{ item.chapter || '未分章' }} · {{ names[item.type] || item.type }} · #{{ item.questionId }}</small><strong>{{ item.stem }}</strong></span><em>{{ item.errorRate.toFixed(1) }}%</em></div><div class="error-track"><i :style="{ width: item.errorRate + '%' }"></i></div><small>{{ item.wrongCount }} / {{ item.answeredCount }} 人次答错</small></div></div><div v-else class="analysis-empty">暂无错误作答数据</div></article>
  </section>
  <article v-if="review" class="panel review-panel">
    <div class="grading-head"><div><span class="status-pill" :class="review.grade.status === 'SUBMITTED' ? 'warning' : 'success'">{{ state(review.grade) }}</span><h3>{{ review.grade.userName }} · {{ review.grade.examTitle }}</h3><p>工号 {{ review.grade.employeeNo }} · 答卷 #{{ review.grade.attemptId }} · {{ review.grade.score == null ? '等待最终评分' : review.grade.score + ' / ' + review.grade.totalScore + ' 分' }}</p></div><button class="link-button" @click="review = null">收起答卷</button></div>
    <div v-if="review.grade.status === 'SUBMITTED' && !canGrade" class="notice">这份答卷含主观题，需由超级管理员完成判卷。你可以查看作答，但不能修改分数。</div>
    <section v-for="(question, index) in review.questions" :key="question.id" class="review-question" :class="{ subjective: !question.objective }">
      <div class="review-meta"><span>{{ index + 1 }}. {{ names[question.type] || question.type }} · 满分 {{ question.points }} 分</span><span v-if="question.objective" class="tag" :class="{ muted: !question.correct }">{{ question.type === 'FILL_BLANK' ? '填空评分' : question.gradingMethod === 'MANUAL' ? '人工评分' : '自动判分' }}：{{ question.awardedPoints == null ? '待集中判定' : question.awardedPoints + ' 分' }}</span><span v-else class="status-pill warning">人工评分</span></div>
      <p class="stem-text">{{ question.stem }}</p><ol v-if="options(question.optionsJson).length" type="A"><li v-for="option in options(question.optionsJson)" :key="option">{{ option }}</li></ol>
      <div class="answer-compare"><div><span>考生作答</span><p>{{ displayAnswer(question.type, question.savedAnswer) }}</p></div><div><span>参考答案 / 评分要点</span><p>{{ question.correctAnswer || '未提供' }}</p><small v-if="question.explanation">{{ question.explanation }}</small></div></div>
      <p v-if="question.objective && question.gradingComment" class="notice">{{ question.gradingComment }}</p><div v-if="question.aiSuggestedPoints != null" class="notice"><strong>AI建议 {{ question.aiSuggestedPoints }} / {{ question.points }} 分 · 语义相似度 {{ question.aiSuggestedSimilarity }}%</strong><p>{{ question.aiSuggestionReason }}</p><small>模型：{{ question.aiModel }}</small><button v-if="canGrade" class="secondary-button" @click="adopt(question)">采纳到待复核评分</button></div>
      <div v-if="!question.objective && canGrade" class="score-editor"><label v-if="question.type === 'FILL_BLANK'">答对空数（共 {{decodeBlankAnswers(question.correctAnswer).length}} 空）<input v-model.number="scores[question.id].correctBlanks" type="number" min="0" :max="decodeBlankAnswers(question.correctAnswer).length" step="1" required /><small>按答对比例自动计算得分；全部空答对才可销号。</small></label><label v-else>本题得分<div><input v-model.number="scores[question.id].awardedPoints" type="number" min="0" :max="question.points" step="1" required><span>/ {{ question.points }} 分</span></div></label><label v-if="question.type === 'SHORT_ANSWER'">语义相似度（%）<input v-model.number="scores[question.id].semanticSimilarity" type="number" min="0" max="100" step="0.1" required><small>低于30%入错题库；相似度不等于得分率</small></label><label>判卷评语（可选）<textarea v-model="scores[question.id].comment" rows="2" maxlength="2000" placeholder="说明扣分点，便于考生复盘"></textarea></label></div>
      <div v-else-if="!question.objective" class="manual-result"><strong>{{ question.awardedPoints ?? '尚未评分' }}<template v-if="question.awardedPoints != null"> / {{ question.points }} 分</template></strong><p v-if="question.gradingComment">评语：{{ question.gradingComment }}</p></div>
    </section>
    <div v-if="review.questions.some(q => !q.objective)" class="grading-footer"><div class="ai-reserved"><strong>AI 辅助评分 · {{ ai.available ? '已启用' : '未启用' }}</strong><span>仅发送题目、参考答案、作答和分值；由人工复核后公布。{{ aiProgress }}</span><button v-if="canGrade && review.grade.status === 'SUBMITTED'" class="secondary-button" :disabled="!ai.available || aiBusy" @click="suggestAi">{{ aiBusy ? '正在生成…' : '生成未完成题目建议' }}</button></div><template v-if="canGrade"><label>整卷评语（可选）<textarea v-model="overallComment" rows="3" maxlength="2000" placeholder="填写整体评价或复核说明"></textarea></label><button class="primary-button" :disabled="grading || aiBusy || review.grade.status === 'CANCELLED'" @click="submitGrade">{{ grading ? '正在保存判卷…' : review.questions.some(q => q.aiStatus?.startsWith('FILL_GROUP_') || q.aiStatus?.startsWith('SHORT_')) ? '保存评分，待统一公布' : review.grade.status === 'GRADED' ? '重新提交判卷' : '完成判卷并公布成绩' }}</button></template></div>
  </article>
  <article class="panel table-panel"><div class="toolbar"><div><strong>考试记录</strong><small>{{ filtered.length }} 条</small></div><div class="filter-row"><select v-model="outcome" aria-label="按结果筛选" @change="page = 1"><option value="">全部结果</option><option value="pending">待人工判卷</option><option value="passed">及格</option><option value="failed">未及格</option><option value="progress">考试中</option></select><input v-model="query" class="search" placeholder="搜索工号或姓名" aria-label="搜索人员" @input="page = 1" /></div></div><div v-if="loading" class="empty">正在加载成绩…</div><div v-else-if="!filtered.length" class="empty"><h3>暂无符合条件的考试记录</h3><p>考生开始考试后会自动显示，无需手工导入。</p></div><div v-else class="table-wrap"><table><thead><tr><th>考生</th><th>成绩</th><th>个人最高分</th><th>结果</th><th>交卷时间</th><th>切屏次数</th><th>答卷</th></tr></thead><tbody><tr v-for="g in visible" :key="g.attemptId" :class="{ 'pending-row': g.status === 'SUBMITTED' }"><td><strong>{{ g.userName }}</strong><br /><span class="mono">{{ g.employeeNo }}</span></td><td>{{ g.score ?? '待判卷' }}<template v-if="g.score != null"> / {{ g.totalScore }}</template></td><td>{{ bestByUser.get(g.userId) ?? '—' }}</td><td><span class="status-pill" :class="g.status === 'SUBMITTED' ? 'warning' : g.passed ? 'success' : g.status === 'GRADED' ? 'danger' : 'info'">{{ state(g) }}</span></td><td>{{ date(g.submittedAt) }}</td><td>{{ g.focusLossCount }}</td><td><button class="link-button" :disabled="reviewLoading || g.status === 'IN_PROGRESS'" @click="openReview(g)">{{ g.status === 'SUBMITTED' && canGrade ? '开始判卷' : '查看答卷' }} #{{ g.attemptId }}</button></td></tr></tbody></table></div><div v-if="filtered.length" class="pagination"><button class="secondary-button" :disabled="page <= 1" @click="page--">上一页</button><span>第 {{ Math.min(page, pages) }} / {{ pages }} 页</span><button class="secondary-button" :disabled="page >= pages" @click="page++">下一页</button></div></article>
</template>
<style scoped>
.hint{font-size:12px;color:#748598;line-height:1.8}.toolbar label{font-size:13px}.toolbar label select{min-width:260px;margin-left:10px}.secondary-button{margin:0}.pending-card{border-color:#ead9b6;background:linear-gradient(140deg,#fffaf0,#fff)}.grading-head{display:flex;justify-content:space-between;gap:20px;border-bottom:1px solid #e1eaf0;padding-bottom:20px}.grading-head h3{font-size:20px;margin:10px 0 4px}.grading-head p{margin:0;color:#6f8596;font-size:13px}.review-question{padding:24px 0;border-bottom:1px solid #e5edf2}.review-question.subjective{margin:18px -4px 0;padding:24px;border:1px solid #e2e8ed;border-left:4px solid #d6a755;border-radius:10px;background:#fffdfa}.review-meta{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:13px;color:#54718a}.stem-text,.review-question li{font-size:14px;line-height:1.9;white-space:pre-wrap}.answer-compare{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:16px}.answer-compare>div{background:#f4f8fb;border-radius:8px;padding:14px;min-width:0}.answer-compare span{font-size:11px;font-weight:700;color:#6f8495}.answer-compare p,.answer-compare small{font-size:13px;line-height:1.85;white-space:pre-wrap;overflow-wrap:anywhere}.answer-compare small{display:block;color:#6c8192;border-top:1px solid #dfe8ee;padding-top:10px}.score-editor{display:grid;grid-template-columns:190px 1fr;gap:15px;margin-top:16px;padding:16px;background:#f8f3e9;border-radius:8px}.score-editor label,.grading-footer>label{display:grid;gap:7px;font-size:12px;color:#5b7182}.score-editor label>div{display:flex;align-items:center;gap:8px}.score-editor input{width:80px}.score-editor input,.score-editor textarea,.grading-footer textarea{font:inherit;border:1px solid #d7dfdf;border-radius:7px;padding:9px;background:#fff;resize:vertical}.manual-result{margin-top:14px;padding:12px 14px;background:#f8f3e9;border-radius:8px}.manual-result p{margin:6px 0 0;font-size:13px}.grading-footer{display:grid;gap:16px;padding-top:22px}.grading-footer .primary-button{justify-self:end}.ai-reserved{display:flex;align-items:center;gap:12px;padding:12px 14px;border:1px dashed #b9cad6;border-radius:8px;color:#607b8e}.ai-reserved strong{font-size:12px}.ai-reserved span{font-size:11px}.pending-row{background:#fffdf8}.pagination{display:flex;align-items:center;justify-content:center;gap:15px;padding:18px;font-size:13px}button:disabled{cursor:not-allowed;opacity:.5}@media(max-width:720px){.toolbar label select{min-width:0;width:100%;margin:8px 0 0}.grading-head{align-items:flex-start}.answer-compare,.score-editor{grid-template-columns:1fr}.review-question.subjective{margin-inline:-10px;padding:18px}.ai-reserved{align-items:flex-start;flex-direction:column}.grading-footer .primary-button{width:100%;justify-self:stretch}}
.analysis-grid{display:grid;grid-template-columns:minmax(300px,.85fr) minmax(420px,1.4fr);gap:16px;margin:16px 0}.analysis-card{min-width:0;border-radius:12px;padding:20px;background:#102f43;color:#edf5f8;box-shadow:0 12px 30px rgba(16,47,67,.12)}.analysis-card.errors{background:#fff;color:#263f50;border:1px solid #dde7ed;box-shadow:none}.analysis-head{display:flex;justify-content:space-between;align-items:flex-start;gap:15px}.analysis-head span{font-size:10px;letter-spacing:.13em;text-transform:uppercase;color:#7eb6c8}.errors .analysis-head span{color:#9b6b20}.analysis-head h3{margin:4px 0 0;font-size:18px}.analysis-head>strong{display:flex;align-items:flex-end;gap:7px;font-size:28px}.analysis-head>strong small{max-width:70px;color:#94acb9;font-size:10px;font-weight:400;line-height:1.2}.band-chart{display:flex;height:175px;align-items:flex-end;gap:12px;margin:22px 0 14px;border-bottom:1px solid #486173}.band-column{display:grid;flex:1;grid-template-rows:18px 110px 20px;gap:5px;text-align:center;font-size:10px}.band-track{display:flex;align-items:flex-end;border-radius:4px 4px 0 0;background:repeating-linear-gradient(to top,rgba(255,255,255,.06) 0,rgba(255,255,255,.06) 1px,transparent 1px,transparent 25%)}.band-track i{display:block;width:100%;border-radius:4px 4px 0 0;background:linear-gradient(#43b8a3,#1b8d80)}.band-value{color:#cae0e7}.band-column small{color:#9fb5c0}.analysis-card footer{display:flex;justify-content:space-between;color:#a6bec9;font-size:11px}.error-list{display:grid;gap:15px;margin-top:20px}.error-item{display:grid;gap:6px}.error-title{display:flex;align-items:center;gap:11px}.error-title>b{color:#c69b52;font:700 12px/1 monospace}.error-title>span{display:grid;min-width:0;flex:1}.error-title small{color:#8598a5;font-size:10px}.error-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.error-title em{color:#a95c24;font-size:14px;font-style:normal;font-weight:800}.error-track{height:5px;border-radius:9px;background:#edf1f3;overflow:hidden}.error-track i{display:block;height:100%;border-radius:9px;background:linear-gradient(90deg,#d8a34d,#c86630)}.error-item>small{padding-left:32px;color:#8295a1;font-size:10px}.analysis-empty{display:grid;min-height:175px;place-items:center;color:#8ca0ad;font-size:12px}@media(max-width:950px){.analysis-grid{grid-template-columns:1fr}}@media(max-width:520px){.analysis-card{padding:16px}.band-chart{gap:6px}.band-column{font-size:9px}}
.head-actions{display:flex;gap:9px;flex-wrap:wrap;justify-content:flex-end}.head-actions .secondary-button{background:#fff}@media(max-width:620px){.head-actions{width:100%;justify-content:stretch}.head-actions button{flex:1}}
.ai-config{margin:16px 0}.ai-config summary{cursor:pointer;font-weight:600}.ai-config p{font-size:13px;line-height:1.8}.config-grid{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:end;gap:14px}.config-grid label{display:grid;gap:8px;font-size:13px}.config-grid input{min-width:0;padding:10px;border:1px solid #d7e3eb;border-radius:6px}.config-actions{display:flex;gap:10px;flex-wrap:wrap}.score-editor small{line-height:1.6}@media(max-width:720px){.config-grid{grid-template-columns:1fr}}
.stat-grid{grid-template-columns:repeat(5,minmax(0,1fr))}@media(max-width:1080px){.stat-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:620px){.page-head{flex-wrap:wrap}.page-head>div:first-child{width:100%}.stat-grid>article:last-child{grid-column:1/-1}}
</style>
