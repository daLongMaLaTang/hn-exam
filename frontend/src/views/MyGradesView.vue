<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { errorText, formatDate, type Grade } from '../candidates/types'
const grades = ref<Grade[]>([])
const route = useRoute()
const loading = ref(true)
const error = ref('')
const search = ref('')
const filter = ref('')
const graded = computed(() => grades.value.filter(grade => grade.status === 'GRADED'))
const passed = computed(() => graded.value.filter(grade => grade.passed).length)
const average = computed(() => graded.value.length ? (graded.value.reduce((sum, grade) => sum + (grade.score || 0), 0) / graded.value.length).toFixed(1) : '—')
const filtered = computed(() => grades.value.filter(grade => grade.examTitle.includes(search.value.trim()) && (!filter.value || (filter.value === 'passed' ? grade.status === 'GRADED' && grade.passed : filter.value === 'failed' ? grade.status === 'GRADED' && !grade.passed : grade.status === filter.value))))
function resultTone(grade: Grade) { if (grade.status === 'CANCELLED') return 'warning'; return grade.status === 'IN_PROGRESS' ? 'info' : grade.status !== 'GRADED' ? 'warning' : grade.passed ? 'success' : 'danger' }
function resultLabel(grade: Grade) { if (grade.status === 'CANCELLED') return '已取消'; return grade.status === 'IN_PROGRESS' ? '考试中' : grade.status !== 'GRADED' ? '待判卷' : grade.passed ? '已通过' : '未通过' }
async function load() {
  loading.value = true; error.value = ''
  try { grades.value = await api<Grade[]>('/api/candidate/grades') }
  catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><span class="eyebrow dark">MY LEARNING RECORD</span><h1>我的考试成绩</h1><p>查看每次考试结果和逐题解析，未通过的考试可按规则参加补考。</p></div><RouterLink class="secondary-button" to="/my-exams">查看考试安排</RouterLink></div>
  <p v-if="route.query.submitted === 'pending'" class="notice" role="status">试卷已成功提交。主观题正在等待超级管理员判卷，完成后这里会显示最终成绩。</p>
  <div class="stat-grid"><article class="stat-card"><span>已判卷次数</span><strong>{{ graded.length }}</strong><small>包含补考记录</small></article><article class="stat-card accent"><span>通过次数</span><strong>{{ passed }}</strong><small>达到试卷及格分数</small></article><article class="stat-card"><span>平均得分</span><strong>{{ average }}</strong><small>已判卷成绩平均值</small></article><article class="stat-card"><span>通过率</span><strong>{{ graded.length ? Math.round(passed / graded.length * 100) : 0 }}%</strong><small>按考试作答次数统计</small></article></div>
  <p v-if="error" class="notice error" role="alert">{{ error }} <button class="link-button" @click="load">重试</button></p>
  <article class="panel table-panel"><div class="toolbar"><div><strong>考试记录</strong><small>仅显示你本人的考试信息</small></div><div class="filter-row"><input v-model="search" class="search" placeholder="搜索考试名称" aria-label="搜索考试名称"><select v-model="filter" aria-label="筛选考试结果"><option value="">全部结果</option><option value="SUBMITTED">待判卷</option><option value="passed">已通过</option><option value="failed">未通过</option></select></div></div><div v-if="loading" class="empty">正在加载成绩…</div><div v-else-if="!filtered.length" class="empty"><span>绩</span><h3>{{ grades.length ? '没有匹配的考试记录' : '还没有考试成绩' }}</h3><p>完成考试并交卷后，成绩会保存在这里。</p></div><div v-else class="table-wrap mobile-cards"><table><thead><tr><th>考试名称</th><th>交卷时间</th><th>得分 / 总分</th><th>结果</th><th>操作</th></tr></thead><tbody><tr v-for="grade in filtered" :key="grade.attemptId"><td><strong>{{ grade.examTitle }}</strong><div class="grade-sub">作答编号 #{{ grade.attemptId }}</div></td><td data-label="交卷时间">{{ formatDate(grade.submittedAt) }}</td><td data-label="得分 / 总分"><strong>{{ grade.score ?? '待判卷' }}</strong><template v-if="grade.score != null"> / {{ grade.totalScore }}</template></td><td data-label="考试结果"><span class="status-pill" :class="resultTone(grade)">{{ resultLabel(grade) }}</span></td><td data-label="操作"><RouterLink v-if="grade.status === 'IN_PROGRESS'" class="grade-link" :to="`/take/${grade.attemptId}`">继续考试</RouterLink><span v-else-if="grade.status === 'SUBMITTED'" class="grade-wait">已交卷，等待管理员完成判卷</span><span v-else-if="grade.status === 'CANCELLED'">考试已取消，记录保留</span><RouterLink v-else class="grade-link" :to="`/grades/${grade.attemptId}`">查看详情 →</RouterLink></td></tr></tbody></table></div></article>
</template>

<style scoped>
.grade-sub{font-size:11px;color:#8b9ba8;margin-top:5px}.grade-link{color:#1d6898;font-weight:600;white-space:nowrap}.grade-link:hover{text-decoration:underline}.grade-wait{font-size:12px;color:#9a6a20}
</style>
