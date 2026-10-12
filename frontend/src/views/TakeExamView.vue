<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, currentUser } from '../api'
import QuestionAnswer from '../components/QuestionAnswer.vue'
import { errorText, questionTypeLabels, type Attempt, type Grade } from '../candidates/types'
const route = useRoute()
const router = useRouter()
const attempt = ref<Attempt | null>(null)
const answers = reactive<Record<string, string>>({})
const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const submitted = ref(false)
const confirmSubmit = ref(false)
const error = ref('')
const saveError = ref('')
const lastSaved = ref('')
const focusLossCount = ref(0)
const currentTime = ref(Date.now())
const serverOffset = ref(0)
const dirty = ref(false)
const initialized = ref(false)
const sheetOpen = ref(false)
let away = false
const forced = computed(() => attempt.value?.basicAntiCheat !== false && focusLossCount.value >= 2)
type SaveAck = { status: Attempt['status']; serverTime: string; focusLossCount: number }
let revision = 0
let retryAfter = 0
let timer: number | undefined
let saveTimer: number | undefined
let activeSave: Promise<void> | null = null
const attemptId = String(route.params.attemptId)
const storageKey = `hn-exam-draft-${currentUser()?.userId}-${attemptId}`
const secondsLeft = computed(() => attempt.value ? Math.max(0, Math.ceil((new Date(attempt.value.deadline).getTime() - currentTime.value - serverOffset.value) / 1000)) : 0)
const remainingLabel = computed(() => {
  const value = secondsLeft.value
  return `${String(Math.floor(value / 3600)).padStart(2, '0')}:${String(Math.floor(value % 3600 / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
})
const answeredCount = computed(() => attempt.value?.questions.filter(question => answers[question.id]?.trim()).length || 0)
const missingCount = computed(() => (attempt.value?.questions.length || 0) - answeredCount.value)
const progress = computed(() => attempt.value?.questions.length ? answeredCount.value / attempt.value.questions.length * 100 : 0)
function writeLocal() {
  try { localStorage.setItem(storageKey, JSON.stringify({ answers: { ...answers }, focusLossCount: focusLossCount.value })) }
  catch { /* Server saving remains available when browser storage is unavailable. */ }
}
function clearLocal() {
  try { localStorage.removeItem(storageKey) } catch { /* No persisted copy to remove. */ }
}
watch(answers, () => {
  if (!initialized.value || submitted.value) return
  dirty.value = true; revision++; writeLocal()
}, { deep: true, flush: 'sync' })
async function goToGrade() {
  submitted.value = true; dirty.value = false; clearLocal()
  await router.replace(`/grades/${attemptId}`)
}
async function goToPendingGrade() {
  submitted.value = true; dirty.value = false; clearLocal()
  await router.replace({ path: '/my-grades', query: { submitted: 'pending' } })
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const received = await api<Attempt>(`/api/candidate/attempts/${attemptId}`)
    attempt.value = received
    if (received.status === 'CANCELLED') { submitted.value = true; clearLocal(); await router.replace('/my-exams'); return }
    if (received.status !== 'IN_PROGRESS') { if (received.status === 'SUBMITTED') await goToPendingGrade(); else await goToGrade(); return }
    serverOffset.value = received.serverTime ? new Date(received.serverTime).getTime() - Date.now() : 0
    focusLossCount.value = received.focusLossCount || 0
    for (const question of received.questions) answers[question.id] = question.savedAnswer || ''
    try {
      const pending = JSON.parse(localStorage.getItem(storageKey) || 'null') as { answers?: Record<string, string>; focusLossCount?: number } | null
      if (pending?.answers) {
        for (const question of received.questions) {
          if (typeof pending.answers[question.id] === 'string') answers[question.id] = pending.answers[question.id]
        }
        focusLossCount.value = Math.max(focusLossCount.value, pending.focusLossCount || 0)
        dirty.value = true
      }
    } catch { clearLocal() }
    initialized.value = true
    currentTime.value = Date.now()
    if (!secondsLeft.value || forced.value) void submit(true)
  } catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
async function save() {
  if (!attempt.value || submitted.value || submitting.value || secondsLeft.value <= 0) return
  if (activeSave) return activeSave
  const savedRevision = revision
  saving.value = true
  activeSave = (async () => {
    try {
      const saved = await api<SaveAck>(`/api/candidate/attempts/${attemptId}/answers`, {
        method: 'PUT', body: JSON.stringify({ answers: { ...answers }, focusLossCount: focusLossCount.value })
      })
      focusLossCount.value = Math.max(focusLossCount.value, saved.focusLossCount)
      if (saved.status === 'CANCELLED') { submitted.value = true; clearLocal(); await router.replace('/my-exams'); return }
      if (saved.status !== 'IN_PROGRESS') { if (saved.status === 'SUBMITTED') await goToPendingGrade(); else await goToGrade(); return }
      saveError.value = ''; lastSaved.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      if (revision === savedRevision) { dirty.value = false; clearLocal() }
    } catch (cause) { saveError.value = `答案尚未同步：${errorText(cause)}。请保持页面打开，系统会自动重试。` }
    finally { saving.value = false; activeSave = null }
  })()
  return activeSave
}
async function submit(automatic = false) {
  if (!attempt.value || submitting.value || submitted.value) return
  submitting.value = true; confirmSubmit.value = false; error.value = ''
  try {
    if (activeSave) await activeSave
    if (submitted.value) return
    const result = await api<Grade>(`/api/candidate/attempts/${attemptId}/submit`, {
      method: 'POST', body: JSON.stringify({ answers: { ...answers }, focusLossCount: focusLossCount.value })
    })
    if (result.status === 'CANCELLED') { submitted.value = true; clearLocal(); await router.replace('/my-exams'); return }
    if (result.status === 'SUBMITTED') await goToPendingGrade(); else await goToGrade()
  } catch (cause) {
    error.value = `${automatic ? (forced.value ? '已达到两次切屏，' : '时间已到，') : ''}交卷未完成：${errorText(cause)}。请保持页面打开并重试。`
    retryAfter = Date.now() + 15000
    writeLocal()
  } finally { submitting.value = false }
}
function recordAway() {
  if (away || !initialized.value || submitted.value || submitting.value || attempt.value?.basicAntiCheat === false) return
  away = true; focusLossCount.value++; dirty.value = true; revision++; writeLocal()
  if (forced.value) void submit(true); else void save()
}
function handleVisibility() { if (document.hidden) recordAway(); else { if (document.hasFocus()) away = false; if (forced.value) void submit(true) } }
function handleFocus() { if (!document.hidden) away = false }
function online() { if (forced.value || !secondsLeft.value) void submit(true); else void save() }
function beforeUnload(event: BeforeUnloadEvent) {
  if (initialized.value && !submitted.value) { event.preventDefault(); event.returnValue = ''; if (dirty.value) writeLocal() }
}
function jump(id: number) { sheetOpen.value = false; document.getElementById(`question-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
onBeforeRouteLeave(async () => {
  if (!initialized.value || submitted.value) return true
  if (!window.confirm('离开后考试仍会继续计时。确认离开考试页面？')) return false
  await save()
  return true
})
onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    currentTime.value = Date.now()
    if (initialized.value && !submitted.value && (secondsLeft.value <= 0 || forced.value) && Date.now() > retryAfter) void submit(true)
  }, 1000)
  // Spread the first upload across a full minute, then keep each candidate's phase.
  // Local drafts and immediate focus-loss / reconnect / submit saves are independent.
  saveTimer = window.setTimeout(() => {
    void save()
    saveTimer = window.setInterval(() => { void save() }, 60000)
  }, 1000 + Math.floor(Math.random() * 59000))
  window.addEventListener('blur', recordAway)
  window.addEventListener('focus', handleFocus)
  window.addEventListener('online', online)
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  window.clearInterval(timer); window.clearTimeout(saveTimer); window.clearInterval(saveTimer)
  window.removeEventListener('blur', recordAway)
  window.removeEventListener('focus', handleFocus)
  window.removeEventListener('online', online)
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <article v-if="loading" class="panel"><div class="empty">正在恢复试卷与已保存答案…</div></article>
  <template v-else-if="attempt && !submitted">
    <div class="page-head"><div><span class="eyebrow dark">EXAM IN PROGRESS</span><h1>{{ attempt.examTitle }}</h1><p>答案每分钟自动同步，交卷时立即提交。作答期间请保持页面打开。</p></div><span class="tag">考试中</span></div>
    <div class="exam-sticky-bar"><div><span>剩余时间</span><strong :class="{ urgent: secondsLeft < 300 }">{{ remainingLabel }}</strong></div><div class="save-indicator"><span>{{ saving ? '正在保存…' : dirty ? '有答案待同步' : lastSaved ? `已保存 ${lastSaved}` : '答案已同步' }}</span><small v-if="attempt.basicAntiCheat !== false">切屏 {{ focusLossCount }} / 2 次，满两次自动交卷</small></div><button class="primary-button" :disabled="submitting" @click="secondsLeft && !forced ? confirmSubmit = true : submit(true)">{{ submitting ? '正在交卷…' : secondsLeft && !forced ? '交卷' : '重试交卷' }}</button></div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="saveError" class="notice error" role="alert">{{ saveError }} <button class="link-button" :disabled="saving || submitting" @click="save">立即重试</button></p>
    <p v-if="secondsLeft <= 0" class="notice">考试时间已结束，正在提交试卷，无法继续修改答案。</p>
    <p v-if="forced" class="notice error" role="alert">已累计两次切屏，正在自动交卷，请保持网络连接。</p>
    <button class="secondary-button mobile-sheet-toggle" @click="sheetOpen = !sheetOpen">{{ sheetOpen ? '收起答题卡' : '打开答题卡' }} · {{ answeredCount }}/{{ attempt.questions.length }}</button>
    <div class="exam-workspace">
      <div class="exam-questions"><article v-for="(question, index) in attempt.questions" :key="question.id" :id="`question-${question.id}`" class="panel exam-question"><div class="panel-head"><div><span class="tag muted">第 {{ index + 1 }} 题</span><span class="tag muted">{{ questionTypeLabels[question.type] }}</span></div><span class="date-chip">{{ question.points }} 分</span></div><h2>{{ question.stem }}</h2><QuestionAnswer v-model="answers[question.id]" :type="question.type" :blank-count="question.blankCount" :stem="question.stem" :options-json="question.optionsJson" :name="`exam-${question.id}`" :disabled="submitting || forced || secondsLeft <= 0" /></article></div>
      <aside class="panel answer-sheet" :class="{ expanded: sheetOpen }"><div class="panel-head"><h3>答题卡</h3><span class="date-chip">{{ answeredCount }} / {{ attempt.questions.length }}</span></div><div class="answer-progress"><i :style="{ width: `${progress}%` }"></i></div><div class="answer-numbers"><button v-for="(question, index) in attempt.questions" :key="question.id" :class="{ answered: answers[question.id]?.trim() }" :aria-label="`第 ${index + 1} 题，${answers[question.id]?.trim() ? '已作答' : '未作答'}`" @click="jump(question.id)">{{ index + 1 }}</button></div><p>蓝色为已作答，点击题号定位。</p><div class="notice">未作答 {{ missingCount }} 题。请检查后提交试卷。</div></aside>
    </div>
    <div v-if="confirmSubmit" class="exam-confirm-backdrop" @click.self="confirmSubmit = false"><section class="exam-confirm" role="dialog" aria-modal="true" aria-labelledby="submit-title"><h2 id="submit-title">确认提交试卷？</h2><p>已作答 {{ answeredCount }} / {{ attempt.questions.length }} 题。</p><p v-if="missingCount" class="notice error">还有 {{ missingCount }} 道题未作答，交卷后将按未答计分。</p><p>交卷后不能再修改本次答案。</p><div class="confirm-actions"><button class="secondary-button" @click="confirmSubmit = false">继续检查</button><button class="primary-button" @click="submit(false)">确认交卷</button></div></section></div>
  </template>
  <article v-else class="panel"><div class="empty"><h3>{{ error || '正在打开考试成绩…' }}</h3><button v-if="error" class="secondary-button" @click="load">重新加载</button><RouterLink class="secondary-button" to="/my-exams">返回考试列表</RouterLink></div></article>
</template>

<style scoped>
.exam-sticky-bar{position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:25px;background:#fff;border:1px solid #dbe6ee;box-shadow:0 8px 22px #18365012;border-radius:12px;padding:15px 22px;margin-bottom:20px}.exam-sticky-bar>div:first-child{display:flex;align-items:center;gap:15px}.exam-sticky-bar span{font-size:12px;color:#6e879a}.exam-sticky-bar strong{font:700 27px Consolas,monospace;color:#154e76}.exam-sticky-bar strong.urgent{color:#c74543}.save-indicator{flex:1;display:grid;gap:5px}.save-indicator small{color:#8093a2;font-size:11px}.exam-workspace{display:grid;grid-template-columns:minmax(0,1fr) 235px;gap:20px;align-items:start}.exam-question{scroll-margin-top:105px}.exam-question h2{font-size:16px;font-weight:600;line-height:1.95;white-space:pre-wrap;margin:20px 0}.answer-sheet{position:sticky;top:102px;padding:18px}.answer-sheet .panel-head{align-items:center}.answer-sheet .panel-head h3{font-size:14px}.answer-progress{height:5px;border-radius:5px;background:#e5edf3;margin:18px 0}.answer-progress i{display:block;height:100%;background:#3a84b2;border-radius:5px;transition:width .3s}.answer-numbers{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.answer-numbers button{border:1px solid #d7e3ed;background:#f7fafc;border-radius:6px;min-height:35px;color:#6c8599}.answer-numbers button.answered{background:#0c4f80;border-color:#0c4f80;color:white}.answer-sheet>p{font-size:11px;color:#8193a3;line-height:1.8;margin:16px 0}.answer-sheet .notice{font-size:11px;margin:0}.exam-confirm-backdrop{position:fixed;inset:0;background:#09284099;z-index:100;display:grid;place-items:center;padding:20px}.exam-confirm{background:#fff;border-radius:14px;padding:28px;width:min(480px,100%)}.exam-confirm h2{font-size:22px}.exam-confirm p{font-size:14px;line-height:1.8;color:#637d91}.confirm-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:22px}.confirm-actions .secondary-button{margin:0}@media(max-width:900px){.exam-workspace{display:flex;flex-direction:column-reverse}.answer-sheet{position:static;width:100%}.answer-numbers{grid-template-columns:repeat(auto-fill,minmax(35px,1fr))}.exam-questions{width:100%}}@media(max-width:550px){.exam-sticky-bar{gap:12px;padding:12px;flex-wrap:wrap}.exam-sticky-bar>div:first-child{gap:8px}.exam-sticky-bar strong{font-size:22px}.save-indicator{order:3;flex-basis:100%;display:flex;justify-content:space-between}.exam-sticky-bar>.primary-button{margin-left:auto;padding:9px 13px}}
.mobile-sheet-toggle{display:none}.answer-numbers button{min-height:44px}@media(max-width:900px){.mobile-sheet-toggle{display:block;position:sticky;top:112px;z-index:19;width:100%;background:#fff;margin:0 0 12px}.answer-sheet{display:none}.answer-sheet.expanded{display:block;position:sticky;top:166px;z-index:18;max-height:50vh;overflow:auto}.exam-question{scroll-margin-top:185px}}
</style>
