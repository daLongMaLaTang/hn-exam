<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { api, newRequestId } from '../api'
import QuestionAnswer from '../components/QuestionAnswer.vue'
import { errorText, questionTypeLabels, type Question } from '../candidates/types'
const props = withDefaults(defineProps<{ wrong?: boolean; examWrong?: boolean }>(), { wrong: false, examWrong: false })
const questions = ref<Question[]>([])
const loading = ref(true)
const sending = ref(false)
const error = ref('')
const index = ref(0)
const filter = ref('')
const courseFilter = ref('')
const answer = ref('')
const resolvedCurrent=ref(false)
const result = ref<{ correct: boolean | null; correctAnswer: string; explanation?: string } | null>(null)
const completed = ref(0)
const assessed = ref(0)
const wrongSummary = ref<{entered:number;resolved:number;unresolved:number;resolutionRate:number;history:{source:string;sourceId:number;questionId:number;at:string;action:string}[]} | null>(null)
const time = ref({ totalSeconds: 0, todaySeconds: 0, recording: false })
const timeError = ref('')
const session = newRequestId()
let lastActivity = Date.now(), heartbeatTimer: number | undefined, heartbeatBusy = false
function activity(event: Event) { if (event.isTrusted) lastActivity = Date.now() }
function minutes(seconds: number) { return (seconds / 60).toFixed(1) }
async function heartbeat(leaving = false) {
  if (heartbeatBusy) return
  heartbeatBusy = true
  try { time.value = await api('/api/practice/heartbeat', { method: 'POST', keepalive: true, body: JSON.stringify({ session, visible: !leaving && !document.hidden && !!question.value, idleSeconds: Math.min(120, Math.floor((Date.now() - lastActivity) / 1000)) }) }); timeError.value = '' }
  catch { timeError.value = '练习时长暂未同步，恢复连接后继续计时' }
  finally { heartbeatBusy = false }
}
function visibility() { void heartbeat() }

const correct = ref(0)
const filtered = computed(() => questions.value.filter(question => (!filter.value || question.type === filter.value) && (!courseFilter.value || question.courseCode === courseFilter.value)))
const question = computed(() => filtered.value[index.value])
const types = computed(() => Array.from(new Set(questions.value.map(item => item.type))))
const courses = computed(() => Array.from(new Set(questions.value.map(item => item.courseCode).filter(Boolean))).sort())
async function load() {
  loading.value = true; error.value = ''; index.value = 0; answer.value = ''; result.value = null; resolvedCurrent.value=false
  try { questions.value = await api<Question[]>(props.wrong ? (props.examWrong ? '/api/practice/wrong/exam' : '/api/practice/wrong/daily') : '/api/practice/questions'); wrongSummary.value = await api('/api/practice/wrong/summary?source=' + (props.examWrong ? 'EXAM' : 'DAILY')) }
  catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
function move(offset: number) {
  if (sending.value || !filtered.value.length) return
  if(resolvedCurrent.value && question.value){const id=question.value.id;questions.value=questions.value.filter(q=>q.id!==id);index.value=Math.min(index.value,Math.max(0,filtered.value.length-1));resolvedCurrent.value=false}
  else index.value = (index.value + offset + filtered.value.length) % filtered.value.length
  answer.value = ''; result.value = null; error.value = ''
}
async function check() {
  if (!question.value || sending.value || !answer.value.trim()) return
  sending.value = true; error.value = ''
  try {
    result.value = await api(`/api/practice/questions/${question.value.id}/submit`, { method: 'POST', body: JSON.stringify({ answer: answer.value }) })
    completed.value++
    if (result.value?.correct != null) assessed.value++
    wrongSummary.value = await api('/api/practice/wrong/summary?source=' + (props.examWrong ? 'EXAM' : 'DAILY'))
    if (result.value?.correct) correct.value++
    if(props.wrong && !props.examWrong && result.value?.correct){const remaining=await api<Question[]>('/api/practice/wrong/daily');resolvedCurrent.value=!remaining.some(q=>q.id===question.value?.id)}
  } catch (cause) { error.value = errorText(cause) }
  finally { sending.value = false }
}
watch(() => [props.wrong, props.examWrong], () => { filter.value = ''; courseFilter.value = ''; completed.value = 0; assessed.value = 0; correct.value = 0; load() })
watch([filter, courseFilter], () => { index.value = 0; answer.value = ''; result.value = null })
onMounted(() => { void load().then(() => heartbeat()); for (const event of ['pointerdown','keydown','scroll','touchstart']) window.addEventListener(event, activity, { passive: true }); document.addEventListener('visibilitychange', visibility); heartbeatTimer = window.setInterval(() => { void heartbeat() }, 10000) })
onBeforeUnmount(() => { window.clearInterval(heartbeatTimer); for (const event of ['pointerdown','keydown','scroll','touchstart']) window.removeEventListener(event, activity); document.removeEventListener('visibilitychange', visibility); void heartbeat(true) })
</script>

<template>
  <div class="page-head"><div><span class="eyebrow dark">{{ wrong ? 'REVIEW & IMPROVE' : 'DAILY PRACTICE' }}</span><h1>{{ examWrong ? '考试错题库' : wrong ? '日常错题库' : '日常刷题' }}</h1><p>{{ examWrong ? '保留考试未掌握题目，简答题按语义相似度低于30%入库。仅错题考试达标后销号。' : wrong ? '连续两次答对同一题后自动消除，中间答错重新计数。跳题不计作答。' : '按题型和章节练习，提交答案后查看解析，练习记录自动保存。' }}</p></div><RouterLink class="secondary-button" :to="wrong ? '/practice' : '/wrong-questions'">{{ wrong ? '全部题库' : '去复习错题' }}</RouterLink></div>
  <div class="notice">累计有效练习 {{ minutes(time.totalSeconds) }} 分钟 · 今日 {{ minutes(time.todaySeconds) }} 分钟 · {{ time.recording ? '正在计时' : '计时暂停' }}<br>两分钟无操作、页面不可见或另一标签正在计时，均暂停累计。{{ timeError }}</div>
  <div v-if="wrongSummary" class="notice">错题累计入库 {{ wrongSummary.entered }} 次 · 已销号 {{ wrongSummary.resolved }} 次 · 待销号 {{ wrongSummary.unresolved }} 题 · 销号率 {{ wrongSummary.resolutionRate.toFixed(1) }}%（已销号次数 / 入库次数）</div>
  <details v-if="wrong && wrongSummary?.history.length" class="panel"><summary>入库与销号记录</summary><p v-for="(event,i) in wrongSummary.history.slice(0,50)" :key="i">{{ new Date(event.at).toLocaleString('zh-CN') }} · 题目 #{{ event.questionId }} · {{ event.action === 'ENTER' ? '入库' : event.source === 'DAILY' ? '连续两次答对销号' : '错题考试销号' }} · {{ event.source === 'DAILY' ? '练习' : '答卷' }} #{{ event.sourceId }}</p></details>
  <div class="practice-summary"><div><span>本次已练 <strong>{{ completed }}</strong> 题</span><span>答对 <strong>{{ correct }}</strong> 题</span><span>客观题正确率 <strong>{{ assessed ? Math.round(correct / assessed * 100) : 0 }}%</strong></span></div><div class="practice-progress" aria-hidden="true"><i :style="{ width: `${completed ? correct / completed * 100 : 0}%` }"></i></div></div>
  <p v-if="error" role="alert" class="notice error">{{ error }} <button v-if="!questions.length" class="link-button" @click="load">重新加载</button></p>
  <article class="panel" v-if="loading"><div class="empty">正在加载题目…</div></article>
  <template v-else>
    <div class="toolbar"><div><strong>可练习 {{ filtered.length }} 题</strong><small>{{ courseFilter || '全部课程' }} · 完成答题后再查看答案</small></div><div class="filter-row"><select v-model="courseFilter" aria-label="筛选课程"><option value="">全部课程</option><option v-for="course in courses" :key="course" :value="course">{{ course }}</option></select><select v-model="filter" aria-label="筛选题型"><option value="">全部题型</option><option v-for="type in types" :key="type" :value="type">{{ questionTypeLabels[type] || type }}</option></select></div></div>
    <article class="panel" v-if="question">
      <div class="panel-head"><div><span class="tag muted">{{ questionTypeLabels[question.type] }}</span><span class="tag muted" v-if="question.courseCode">{{ question.courseCode }}</span><span class="tag muted" v-if="question.chapter">{{ question.chapter }}</span></div><span class="date-chip">{{ index + 1 }} / {{ filtered.length }}</span></div>
      <h2 class="practice-stem">{{ question.stem }}</h2>
      <QuestionAnswer v-model="answer" practice :type="question.type" :blank-count="question.blankCount" :stem="question.stem" :options-json="question.optionsJson" :disabled="Boolean(result) || sending" :name="`practice-${question.id}`" />
      <div v-if="result" class="practice-feedback" :class="{ incorrect: !result.correct }" role="status"><strong>{{ result.correct == null ? (question.type === 'FILL_BLANK' ? 'AI暂未完成判定，请对照参考答案复习，本次不计对错' : '请对照参考答案复习（主观题练习未能自动判定）') : result.correct ? '回答正确' : '这道题还需要巩固' }}</strong><p v-if="resolvedCurrent">已连续两次答对，此题已从日常错题库消除。</p><p>参考答案：{{ result.correctAnswer }}</p><p v-if="result.explanation">解析：{{ result.explanation }}</p><p v-else>这道题暂未提供详细解析。</p></div>
      <div class="practice-actions"><button class="secondary-button" :disabled="sending || index === 0" @click="move(-1)">上一题</button><button v-if="!result" class="secondary-button" :disabled="sending || filtered.length < 2" @click="move(1)">跳过此题</button><button v-if="!result" class="primary-button" :disabled="sending || !answer.trim()" @click="check">{{ sending ? '正在核对…' : '提交答案' }}</button><button v-else class="primary-button" @click="move(1)">{{ index === filtered.length - 1 ? '再练一轮' : '下一题' }}</button></div>
    </article>
    <article class="panel" v-else><div class="empty"><span>练</span><h3>{{ wrong ? '目前没有待复习错题' : '暂无可练习题目' }}</h3><p>{{ examWrong ? '考试中答错的题目会收录在这里，后续可参加管理员发布的错题复训考试。' : wrong ? '日常练习答错后会收录，连续两次答对后自动消除。' : '管理员启用题目后，就可以在这里开始练习。' }}</p><RouterLink v-if="wrong" class="secondary-button" to="/practice">开始练习</RouterLink></div></article>
  </template>
</template>

<style scoped>
.practice-summary{display:grid;gap:13px;padding:16px 20px;border:1px solid #d9e4eb;border-radius:10px;background:#f7fafb;color:#567087;margin-bottom:20px;font-size:13px}.practice-summary>div:first-child{display:flex;flex-wrap:wrap;gap:28px}.practice-summary strong{color:#154b70;margin:0 5px}.practice-progress{height:5px;border-radius:99px;background:#e1e9ee;overflow:hidden}.practice-progress i{display:block;height:100%;border-radius:inherit;background:#19a58e;transition:width .25s ease}.practice-stem{font-size:18px;line-height:1.9;white-space:pre-wrap;font-weight:600;margin:22px 0}.practice-actions{display:flex;justify-content:space-between;gap:12px;margin-top:20px}.practice-actions .secondary-button{margin-top:0}.practice-feedback{margin-top:20px;padding:17px;border-radius:10px;background:#edf8f1;color:#256543;border:1px solid #cce6d6}.practice-feedback.incorrect{background:#fff6ed;color:#8d541f;border-color:#f2d8bc}.practice-feedback p{font-size:13px;line-height:1.9;white-space:pre-wrap;margin:8px 0 0}@media(max-width:520px){.practice-summary>div:first-child{justify-content:space-between;gap:10px}.practice-summary span{display:flex;align-items:baseline;gap:3px;flex-wrap:wrap}.practice-summary strong{margin:0}.practice-stem{font-size:16px}.practice-actions>*{flex:1}}
</style>
