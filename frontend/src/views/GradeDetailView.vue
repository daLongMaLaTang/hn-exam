<script setup lang="ts">
import { displayAnswer } from '../candidates/blankAnswers'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { errorText, formatDate, questionTypeLabels, type Grade, type Question } from '../candidates/types'
const route = useRoute()
type ReviewQuestion = Question & { savedAnswer?: string | null; correctAnswer?: string | null; explanation?: string | null; correct?: boolean; awardedPoints?: number | null; objective?: boolean; gradingMethod?: string | null; gradingComment?: string | null; semanticSimilarity?: number | null }
type Review = { grade: Grade; questions: ReviewQuestion[] }
const review = ref<Review | null>(null)
const loading = ref(true)
const error = ref('')
const wrongOnly = ref(false)
const visible = computed(() => review.value?.questions.filter(question => !wrongOnly.value || !question.correct) || [])
async function load() {
  loading.value = true; error.value = ''
  try { review.value = await api<Review>(`/api/candidate/attempts/${route.params.attemptId}/review`) }
  catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><span class="eyebrow dark">EXAM REVIEW</span><h1>考试结果与解析</h1><p>核对作答，复习知识点，错题会收录到个人错题库。</p></div><RouterLink class="secondary-button" to="/my-grades">返回成绩列表</RouterLink></div>
  <p v-if="error" class="notice error" role="alert">{{ error }} <button class="link-button" @click="load">重新加载</button></p>
  <article v-if="loading" class="panel"><div class="empty">正在加载考试结果…</div></article>
  <template v-else-if="review">
    <article class="panel result-banner" :class="{ passed: review.grade.passed }"><div class="result-score"><strong>{{ review.grade.score ?? '—' }}</strong><span>/ {{ review.grade.totalScore }} 分</span></div><div><span class="tag" :class="{ muted: !review.grade.passed }">{{ review.grade.status !== 'GRADED' ? '待判卷' : review.grade.passed ? '本次考试已通过' : '本次考试未通过' }}</span><h2>{{ review.grade.examTitle }}</h2><p>交卷时间：{{ formatDate(review.grade.submittedAt) }}</p><p>作答编号：#{{ review.grade.attemptId }} · 切屏记录：{{ review.grade.focusLossCount }} 次</p></div><RouterLink v-if="!review.grade.passed" to="/my-exams" class="secondary-button">查看补考安排</RouterLink></article>
    <div class="toolbar"><div><strong>逐题解析</strong><small>共 {{ review.questions.length }} 题</small></div><label class="review-toggle"><input v-model="wrongOnly" type="checkbox"> 只看错题</label></div>
    <article v-for="(question, index) in visible" :key="question.id" class="panel review-question"><div class="panel-head"><div><span class="tag muted">第 {{ question.position || index + 1 }} 题 · {{ questionTypeLabels[question.type] }}</span><span class="tag" :class="{ muted: !question.correct }">{{ question.gradingMethod === 'MANUAL' ? '人工评分' : question.objective ? (question.correct ? '正确' : (question.awardedPoints ?? 0) > 0 ? '部分正确' : '错误 / 未答') : '人工评分' }}</span></div><span class="date-chip">{{ question.awardedPoints ?? 0 }} / {{ question.points }} 分</span></div><h2>{{ question.stem }}</h2><div class="review-answer" :class="{ incorrect: question.objective && !question.correct }"><span>我的答案</span><strong>{{ displayAnswer(question.type, question.savedAnswer) }}</strong></div><div class="review-answer"><span>参考答案</span><strong>{{ question.correctAnswer || '未提供' }}</strong></div><div v-if="question.semanticSimilarity != null" class="notice">语义相似度 {{ question.semanticSimilarity }}%（人工复核），{{ question.semanticSimilarity < 30 ? '低于30%，纳入错题' : '达到30%标准' }}；得分按评分要点单独计算。</div><div v-if="question.gradingComment" class="review-explanation manual-comment"><strong>{{ question.objective && question.gradingMethod !== 'MANUAL' ? '自动判分说明' : '判卷评语' }}</strong><p>{{ question.gradingComment }}</p></div><div class="review-explanation"><strong>答案解析</strong><p>{{ question.explanation || '这道题暂未提供详细解析。' }}</p></div></article>
    <article v-if="!visible.length" class="panel"><div class="empty"><h3>{{ wrongOnly ? '本次没有错题' : '暂无可查看的题目' }}</h3><p>可以继续日常练习，巩固所学知识。</p><RouterLink to="/practice" class="secondary-button">继续刷题</RouterLink></div></article>
  </template>
</template>

<style scoped>
.result-banner{display:flex;align-items:center;gap:28px;border-top:3px solid #d8b16e}.result-banner.passed{border-top-color:#389770}.result-score{min-width:120px;text-align:center}.result-score strong{display:block;font-size:52px;color:#154c73;letter-spacing:-2px}.result-score span{font-size:13px;color:#7a91a3}.result-banner h2{font-size:20px;margin:12px 0}.result-banner p{font-size:12px;color:#7e91a1;line-height:1.7;margin:5px 0}.result-banner>.secondary-button{margin-left:auto}.review-toggle{font-size:13px;color:#50748d;display:flex;gap:8px;align-items:center}.review-toggle input{accent-color:#0c4f80}.review-question h2{font-size:16px;line-height:1.9;white-space:pre-wrap;margin:20px 0}.review-answer{display:grid;grid-template-columns:80px 1fr;gap:16px;font-size:13px;padding:12px 0}.review-answer span{color:#8194a3}.review-answer strong{font-weight:500;color:#286b4e;line-height:1.8;white-space:pre-wrap}.review-answer.incorrect strong{color:#af6840}.review-explanation{background:#f4f8fb;padding:16px;border-radius:8px;margin-top:12px;color:#5a778d;font-size:13px}.review-explanation.manual-comment{background:#fff8e9;border-left:3px solid #d6a755}.review-explanation strong{color:#375f7c}.review-explanation p{margin:8px 0 0;line-height:1.9;white-space:pre-wrap}@media(max-width:650px){.result-banner{flex-wrap:wrap;gap:20px}.result-score{min-width:85px}.result-score strong{font-size:40px}.result-banner>.secondary-button{margin:0}.review-answer{grid-template-columns:65px 1fr;gap:8px}}
</style>
