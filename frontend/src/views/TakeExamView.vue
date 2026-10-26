<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, ApiError, currentUser, newRequestId } from '../api'
import { isClipboardShortcut, isClipboardInput } from '../candidates/examClipboard'
import { sameAnswerSnapshot, draftNeedsReview, type PendingAnswerWrite } from '../candidates/examSync'
import QuestionAnswer from '../components/QuestionAnswer.vue'
import { errorText, questionTypeLabels, type Attempt } from '../candidates/types'
const route = useRoute()
const router = useRouter()
const attempt = ref<Attempt | null>(null)
const answers = reactive<Record<string, string>>({})
const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const finalizing = ref(false)
const submitted = ref(false)
const ended = ref(false)
const conflict = ref(false)
const serverCopy = ref<Attempt | null>(null)
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
const storageWarning = ref('')
let away = false
let destroyed = false
const forced = computed(() => attempt.value?.basicAntiCheat !== false && focusLossCount.value >= 2)
type WriteAck = { status: string; serverTime: string; focusLossCount: number; answerRevision: number; answersAccepted: boolean }
type Draft = { answers: Record<string, string>; focusLossCount: number; baseRevision?: number; pending?: PendingAnswerWrite | null; savedAt?: number }
let recoveredBackup = false
let serverRevision = 0
let pending: PendingAnswerWrite | null = null
let retryAfter = 0
let timer: number | undefined
let saveTimer: number | undefined
let debounceTimer: number | undefined
let maxWaitTimer: number | undefined
let activeSave: Promise<boolean> | null = null
const attemptId = String(route.params.attemptId)
const storageKey = `hn-exam-draft-${currentUser()?.userId}-${attemptId}`
// sessionStorage keeps simultaneous tabs' drafts separate. The backup survives closing a tab.
let tabId = newRequestId()
try { tabId = sessionStorage.getItem(`${storageKey}-tab`) || tabId; sessionStorage.setItem(`${storageKey}-tab`, tabId) } catch { /* Saving still works without browser storage. */ }
const backupKey = `${storageKey}-${tabId}`
const secondsLeft = computed(() => attempt.value ? Math.max(0, Math.ceil((new Date(attempt.value.deadline).getTime() - currentTime.value - serverOffset.value) / 1000)) : 0)
const remainingLabel = computed(() => {
  const value = secondsLeft.value
  return `${String(Math.floor(value / 3600)).padStart(2, '0')}:${String(Math.floor(value % 3600 / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
})
const answeredCount = computed(() => attempt.value?.questions.filter(question => answers[question.id]?.trim()).length || 0)
const missingCount = computed(() => (attempt.value?.questions.length || 0) - answeredCount.value)
const progress = computed(() => attempt.value?.questions.length ? answeredCount.value / attempt.value.questions.length * 100 : 0)
const differences = computed(() => serverCopy.value?.questions.filter(q => (answers[q.id] || '') !== (q.savedAnswer || '')) || [])
const inputLocked = computed(() => finalizing.value || submitting.value || forced.value || secondsLeft.value <= 0 || conflict.value || ended.value)
function writeLocal() {
  const value = JSON.stringify({ answers: { ...answers }, focusLossCount: focusLossCount.value, baseRevision: serverRevision, pending, savedAt: Date.now() })
  let persisted = false
  try { sessionStorage.setItem(storageKey, value); persisted = true } catch { /* Try the persistent backup. */ }
  try { localStorage.setItem(backupKey, value); persisted = true } catch { /* Session copy may still be available. */ }
  storageWarning.value = persisted ? '' : '浏览器无法保存本地草稿，请保持页面打开并确认答案已同步。'
}
function readLocal(): Draft | null {
  try {
    let raw = sessionStorage.getItem(storageKey) || localStorage.getItem(backupKey) || localStorage.getItem(storageKey)
    recoveredBackup = false
    if (!raw) {
      const backups: Draft[] = []
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (!key?.startsWith(`${storageKey}-`)) continue
        try {
          const draft = JSON.parse(localStorage.getItem(key) || 'null')
          if (draft?.answers && typeof draft.answers === 'object') backups.push(draft)
        } catch { /* One damaged backup must not hide other drafts. */ }
      }
      backups.sort((a, b) => (b.savedAt || 0) - (a.savedAt || 0))
      if (backups[0]) { raw = JSON.stringify(backups[0]); recoveredBackup = true }
    }
    const value = raw ? JSON.parse(raw) : null
    return value?.answers && typeof value.answers === 'object' ? value as Draft : null
  } catch { return null }
}
function clearLocal() {
  try { sessionStorage.removeItem(storageKey); localStorage.removeItem(backupKey) } catch { /* No accessible copy to remove. */ }
}
function serverAnswers(value: Attempt) { return Object.fromEntries(value.questions.map(q => [String(q.id), q.savedAnswer || ''])) }
function queueSave() {
  if (inputLocked.value || destroyed) return
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => { void save() }, secondsLeft.value <= 10 ? 100 : 700)
  if (!maxWaitTimer) maxWaitTimer = window.setTimeout(() => { maxWaitTimer = undefined; void save() }, 2500)
}
watch(answers, () => {
  if (!initialized.value || submitted.value) return
  dirty.value = true; writeLocal(); queueSave()
}, { deep: true, flush: 'sync' })
async function leaveForResult(status: string, clear = true) {
  submitted.value = true
  if (clear) { dirty.value = false; clearLocal() }
  if (status === 'CANCELLED') await router.replace('/my-exams')
  else if (status === 'SUBMITTED') await router.replace({ path: '/my-grades', query: { submitted: 'pending' } })
  else await router.replace(`/grades/${attemptId}`)
}
function preserveEnded(status: string) {
  ended.value = true
  if (attempt.value) attempt.value.status = status
  saveError.value = ''
  error.value = '考试已收卷，本页有未计入的内容。草稿已保留，请下载后联系管理员核对。'
  writeLocal()
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const received = await api<Attempt>(`/api/candidate/attempts/${attemptId}`, { timeoutMs: 5000 })
    attempt.value = received
    serverRevision = received.answerRevision
    serverOffset.value = new Date(received.serverTime || '').getTime() - Date.now() || 0
    focusLossCount.value = received.focusLossCount || 0
    initialized.value = false
    Object.assign(answers, serverAnswers(received))
    const draft = readLocal()
    if (draft) {
      for (const q of received.questions) if (typeof draft.answers[q.id] === 'string') answers[q.id] = draft.answers[q.id]
      focusLossCount.value = Math.max(focusLossCount.value, draft.focusLossCount || 0)
      dirty.value = !sameAnswerSnapshot(answers, serverAnswers(received)) || focusLossCount.value > (received.focusLossCount || 0)
      // A backup from another tab is shown for review before it can write anything.
      pending = recoveredBackup ? null : draft.pending || null
      if (pending) serverRevision = pending.body.expectedRevision
      else if ((recoveredBackup && dirty.value) || draftNeedsReview(answers, draft.baseRevision, serverAnswers(received), received.answerRevision)) {
        conflict.value = true; serverCopy.value = received
        saveError.value = '本地草稿与服务器答案不同，请核对下面的差异再继续。'
      }
    }
    initialized.value = true; currentTime.value = Date.now()
    if (received.status !== 'IN_PROGRESS') {
      if (dirty.value) preserveEnded(received.status)
      else await leaveForResult(received.status)
      return
    }
    if (pending?.kind === 'submit' || !secondsLeft.value || forced.value) void submit(true)
    else if (pending || dirty.value) void save()
  } catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false }
}
async function refreshConflict() {
  conflict.value = true
  pending = null
  writeLocal()
  saveError.value = '另一页面已更新答案，本页内容已保留。请核对差异后选择如何继续。'
  try {
    serverCopy.value = await api<Attempt>(`/api/candidate/attempts/${attemptId}`, { timeoutMs: 5000 })
    if (serverCopy.value.status !== 'IN_PROGRESS') preserveEnded(serverCopy.value.status)
  } catch (cause) { saveError.value = `无法取得最新答案：${errorText(cause)}。本页草稿已保留，请重试核对。` }
}
async function resolveConflict(keepLocal: boolean) {
  if (!serverCopy.value || ended.value) return
  initialized.value = false
  if (!keepLocal) { downloadDraft(); Object.assign(answers, serverAnswers(serverCopy.value)) }
  serverRevision = serverCopy.value.answerRevision
  focusLossCount.value = Math.max(focusLossCount.value, serverCopy.value.focusLossCount || 0)
  conflict.value = false; serverCopy.value = null; pending = null
  dirty.value = true; saveError.value = ''; initialized.value = true; writeLocal()
  if (!secondsLeft.value || forced.value || finalizing.value) await submit(true); else await save()
}
function newWrite(kind: PendingAnswerWrite['kind']): PendingAnswerWrite {
  return { kind, body: { answers: { ...answers }, focusLossCount: focusLossCount.value, expectedRevision: serverRevision, requestId: newRequestId() } }
}
async function sendPending(): Promise<boolean> {
  if (!pending) return true
  const sent = pending
  try {
    const result = await api<WriteAck>(`/api/candidate/attempts/${attemptId}/${sent.kind === 'save' ? 'answers' : 'submit'}`, {
      method: sent.kind === 'save' ? 'PUT' : 'POST', body: JSON.stringify(sent.body), timeoutMs: sent.kind === 'save' ? 4000 : 5000
    })
    if (destroyed) return false
    serverRevision = result.answerRevision
    focusLossCount.value = Math.max(focusLossCount.value, result.focusLossCount)
    pending = null
    const currentAccepted = result.answersAccepted && sameAnswerSnapshot(answers, sent.body.answers)
      && focusLossCount.value <= result.focusLossCount
    if (result.status !== 'IN_PROGRESS') {
      if (currentAccepted) await leaveForResult(result.status)
      else preserveEnded(result.status)
      return currentAccepted
    }
    if (!result.answersAccepted) { await refreshConflict(); return false }
    dirty.value = !currentAccepted
    saveError.value = ''; lastSaved.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    if (dirty.value) writeLocal(); else clearLocal()
    return true
  } catch (cause) {
    if (destroyed) return false
    if (cause instanceof ApiError && cause.code === 'ANSWER_CONFLICT') await refreshConflict()
    else {
      saveError.value = `尚未确认保存结果：${errorText(cause)}。草稿已保留，可立即重试。`
      writeLocal()
    }
    return false
  }
}
async function save(): Promise<boolean> {
  if (activeSave) return activeSave
  if (!attempt.value || submitted.value || ended.value || conflict.value || submitting.value) return false
  if (!secondsLeft.value || pending?.kind === 'submit') { void submit(true); return false }
  if (!dirty.value && !pending) return true
  window.clearTimeout(debounceTimer); window.clearTimeout(maxWaitTimer); maxWaitTimer = undefined
  saving.value = true
  pending ||= newWrite('save'); writeLocal()
  activeSave = sendPending()
  try { return await activeSave }
  finally {
    activeSave = null; saving.value = false
    if (dirty.value && !pending && !conflict.value && !ended.value && !submitted.value) queueSave()
  }
}
async function submit(automatic = false) {
  if (!attempt.value || submitting.value || submitted.value || ended.value || conflict.value) return
  finalizing.value = true; submitting.value = true; confirmSubmit.value = false; error.value = ''
  try {
    // Each wait is bounded. An uncertain save retries its original request ID before a new revision is sent.
    if (activeSave) await activeSave
    if (submitted.value || ended.value || conflict.value) return
    if (pending && !(await sendPending())) return
    if (submitted.value || ended.value || conflict.value) return
    pending = newWrite('submit'); writeLocal()
    if (!(await sendPending()) && !conflict.value && !ended.value)
      error.value = `${automatic ? '正在自动交卷。' : ''}交卷结果尚待确认，请保持页面打开，系统会重试。`
  } finally { submitting.value = false; retryAfter = Date.now() + 1000 }
}
function recordAway() {
  if (away || !initialized.value || submitted.value || submitting.value || ended.value || conflict.value || attempt.value?.basicAntiCheat === false) return
  away = true; focusLossCount.value++; dirty.value = true; writeLocal()
  if (forced.value) void submit(true); else void save()
}
function handleVisibility() { if (document.hidden) recordAway(); else { if (document.hasFocus()) away = false; if (forced.value) void submit(true) } }
function handleFocus() { if (!document.hidden) away = false }
function online() { if (finalizing.value || forced.value || !secondsLeft.value || pending?.kind === 'submit') void submit(true); else void save() }
function beforeUnload(event: BeforeUnloadEvent) {
  if (initialized.value && !submitted.value) { event.preventDefault(); event.returnValue = ''; if (dirty.value || pending) writeLocal() }
}
function downloadDraft() {
  const text = `${attempt.value?.examTitle || '考试'}\n本地草稿，不代表已计入成绩。\n\n` + (attempt.value?.questions || []).map((q, i) => `第${i + 1}题 ${q.stem}\n${answers[q.id] || '（未作答）'}`).join('\n\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = `考试草稿-${attemptId}.txt`; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function jump(id: number) { sheetOpen.value = false; document.getElementById(`question-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
onBeforeRouteLeave(async () => {
  if (!initialized.value || submitted.value) return true
  if (!window.confirm('离开后考试仍会继续计时。未同步的草稿会保留，确认离开？')) return false
  await save()
  return true
})
onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    currentTime.value = Date.now()
    if (initialized.value && !submitted.value && !ended.value && Date.now() > retryAfter) {
      if (finalizing.value || secondsLeft.value <= 0 || forced.value || pending?.kind === 'submit') void submit(true)
      else if (secondsLeft.value <= 10 && dirty.value) void save()
    }
  }, 200)
  saveTimer = window.setInterval(() => { void save() }, 5000)
  window.addEventListener('blur', recordAway); window.addEventListener('focus', handleFocus)
  window.addEventListener('online', online); document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  destroyed = true
  window.clearInterval(timer); window.clearInterval(saveTimer); window.clearTimeout(debounceTimer); window.clearTimeout(maxWaitTimer)
  window.removeEventListener('blur', recordAway); window.removeEventListener('focus', handleFocus)
  window.removeEventListener('online', online); document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener('beforeunload', beforeUnload)
})
const clipboardNotice = ref('')
function blockClipboard(event: Event) {
  if (!initialized.value || submitted.value) return
  event.preventDefault(); event.stopPropagation()
  clipboardNotice.value = '已阻止剪贴板操作。考试期间请自行输入答案，可正常选中和修改已输入的文字。'
}
function examKeydown(event: KeyboardEvent) { if (isClipboardShortcut(event)) blockClipboard(event) }
function examBeforeInput(event: Event) { if (isClipboardInput((event as InputEvent).inputType)) blockClipboard(event) }
</script>


<template>
  <article v-if="loading" class="panel"><div class="empty">正在恢复试卷与已保存答案…</div></article>
  <section v-else-if="attempt && !submitted" class="exam-session" @copy.capture="blockClipboard" @cut.capture="blockClipboard" @paste.capture="blockClipboard" @drop.capture="blockClipboard" @dragstart.capture="blockClipboard" @keydown.capture="examKeydown" @beforeinput.capture="examBeforeInput" @contextmenu.capture="blockClipboard">
    <div class="page-head"><div><span class="eyebrow dark">EXAM IN PROGRESS</span><h1>{{ attempt.examTitle }}</h1><p>答案编辑后自动同步。到时停止作答，保留10秒上传最终答案，请保持页面打开。</p></div><span class="tag">{{ ended ? '已收卷' : conflict ? '请核对答案' : finalizing ? '正在确认交卷' : '考试中' }}</span></div>
    <div class="exam-sticky-bar"><div><span>剩余时间</span><strong :class="{ urgent: secondsLeft < 300 }">{{ remainingLabel }}</strong></div><div class="save-indicator" role="status"><span>{{ ended ? '存在未计入的本地内容' : conflict ? '答案待核对' : saving ? '正在保存…' : saveError ? '保存结果待确认' : dirty ? '有答案待同步' : lastSaved ? `已保存 ${lastSaved}` : '答案已同步' }}</span><small v-if="attempt.basicAntiCheat !== false">切屏 {{ focusLossCount }} / 2 次，满两次自动交卷</small></div><button v-if="!ended" class="primary-button" :disabled="submitting || conflict" @click="secondsLeft && !forced && !finalizing ? confirmSubmit = true : submit(true)">{{ submitting ? '正在交卷…' : secondsLeft && !forced && !finalizing ? '交卷' : '重试交卷' }}</button></div>
    <p class="exam-integrity-note">独立作答 · 本场考试禁止复制、剪切、粘贴及拖入答案。</p>
    <p class="clipboard-feedback" role="status" aria-live="polite">{{ clipboardNotice }}</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="storageWarning" class="notice error" role="alert">{{ storageWarning }}</p>
    <p v-if="saveError" class="notice error" role="alert">{{ saveError }} <button v-if="!conflict" class="link-button" :disabled="saving || submitting" @click="finalizing || !secondsLeft ? submit(true) : save()">立即重试</button></p>
    <section v-if="conflict && !ended" class="panel answer-conflict" aria-label="核对答案差异">
      <h2>核对本页与服务器答案</h2><p>本地草稿与服务器版本需要核对。确认要保留哪一份，再继续作答。</p>
      <div v-for="q in differences" :key="q.id" class="answer-difference"><h3>{{ q.stem }}</h3><div><strong>本页内容</strong><p>{{ answers[q.id] || '（未作答）' }}</p></div><div><strong>服务器已保存</strong><p>{{ q.savedAnswer || '（未作答）' }}</p></div></div>
      <p v-if="serverCopy && !differences.length">答案内容相同，可以使用服务器保存的版本继续。</p>
      <div class="recovery-actions"><button class="secondary-button" @click="downloadDraft">下载本页草稿</button><button v-if="serverCopy" class="secondary-button" @click="resolveConflict(false)">使用服务器答案</button><button v-if="serverCopy" class="primary-button" @click="resolveConflict(true)">核对后保留本页答案</button><button v-else class="primary-button" @click="refreshConflict">重试核对</button></div>
    </section>
    <div v-if="ended" class="recovery-actions"><button class="secondary-button" @click="downloadDraft">下载未同步草稿</button><button class="primary-button" @click="leaveForResult(attempt.status, false)">查看交卷结果</button></div>
    <p v-if="secondsLeft <= 0 && !ended" class="notice">考试时间已结束，无法继续修改答案。正在确认最终答案和交卷结果。</p>
    <p v-if="forced" class="notice error" role="alert">已累计两次切屏，正在自动交卷，请保持网络连接。</p>
    <button class="secondary-button mobile-sheet-toggle" @click="sheetOpen = !sheetOpen">{{ sheetOpen ? '收起答题卡' : '打开答题卡' }} · {{ answeredCount }}/{{ attempt.questions.length }}</button>
    <div class="exam-workspace">
      <div class="exam-questions"><article v-for="(question, index) in attempt.questions" :key="question.id" :id="`question-${question.id}`" class="panel exam-question"><div class="panel-head"><div><span class="tag muted">第 {{ index + 1 }} 题</span><span class="tag muted">{{ questionTypeLabels[question.type] }}</span></div><span class="date-chip">{{ question.points }} 分</span></div><h2>{{ question.stem }}</h2><QuestionAnswer v-model="answers[question.id]" :type="question.type" :blank-count="question.blankCount" :stem="question.stem" :options-json="question.optionsJson" :name="`exam-${question.id}`" :disabled="inputLocked" /></article></div>
      <aside class="panel answer-sheet" :class="{ expanded: sheetOpen }"><div class="panel-head"><h3>答题卡</h3><span class="date-chip">{{ answeredCount }} / {{ attempt.questions.length }}</span></div><div class="answer-progress"><i :style="{ width: `${progress}%` }"></i></div><div class="answer-numbers"><button v-for="(question, index) in attempt.questions" :key="question.id" :class="{ answered: answers[question.id]?.trim() }" :aria-label="`第 ${index + 1} 题，${answers[question.id]?.trim() ? '已作答' : '未作答'}`" @click="jump(question.id)">{{ index + 1 }}</button></div><p>蓝色为已作答，点击题号定位。</p><div class="notice">未作答 {{ missingCount }} 题。请检查后提交试卷。</div></aside>
    </div>
    <div v-if="confirmSubmit" class="exam-confirm-backdrop" @click.self="confirmSubmit = false"><section class="exam-confirm" role="dialog" aria-modal="true" aria-labelledby="submit-title"><h2 id="submit-title">确认提交试卷？</h2><p>已作答 {{ answeredCount }} / {{ attempt.questions.length }} 题。</p><p v-if="missingCount" class="notice error">还有 {{ missingCount }} 道题未作答，交卷后将按未答计分。</p><p>交卷后不能再修改本次答案。</p><div class="confirm-actions"><button class="secondary-button" @click="confirmSubmit = false">继续检查</button><button class="primary-button" @click="submit(false)">确认交卷</button></div></section></div>
  </section>
  <article v-else class="panel"><div class="empty"><h3>{{ error || '正在打开考试成绩…' }}</h3><button v-if="error" class="secondary-button" @click="load">重新加载</button><RouterLink class="secondary-button" to="/my-exams">返回考试列表</RouterLink></div></article>
</template>

<style scoped>
.answer-conflict{margin-bottom:20px}.answer-conflict h2{font-size:18px}.answer-difference{border-top:1px solid #dbe6ee;margin-top:16px;padding-top:12px;overflow-wrap:anywhere}.answer-difference h3{font-size:15px}.answer-difference p{white-space:pre-wrap;font-size:14px}.recovery-actions{display:flex;flex-wrap:wrap;gap:10px;margin:16px 0}.recovery-actions button{min-height:44px;margin:0;white-space:normal}.save-indicator span{overflow-wrap:anywhere}
.exam-session{user-select:none;-webkit-user-select:none}.exam-session :deep(input),.exam-session :deep(textarea){user-select:text;-webkit-user-select:text}.exam-integrity-note{font-size:13px;color:var(--ink-600)}.clipboard-feedback:empty{display:none}.clipboard-feedback{padding:12px 16px;background:#edf5f7;border-left:3px solid #138875;font-size:13px;line-height:1.7}

.exam-sticky-bar{position:sticky;top:0;z-index:20;display:flex;align-items:center;gap:25px;background:#fff;border:1px solid #dbe6ee;box-shadow:0 8px 22px #18365012;border-radius:12px;padding:15px 22px;margin-bottom:20px}.exam-sticky-bar>div:first-child{display:flex;align-items:center;gap:15px}.exam-sticky-bar span{font-size:12px;color:#6e879a}.exam-sticky-bar strong{font:700 27px Consolas,monospace;color:#154e76}.exam-sticky-bar strong.urgent{color:#c74543}.save-indicator{flex:1;display:grid;gap:5px}.save-indicator small{color:#8093a2;font-size:11px}.exam-workspace{display:grid;grid-template-columns:minmax(0,1fr) 235px;gap:20px;align-items:start}.exam-question{scroll-margin-top:105px}.exam-question h2{font-size:16px;font-weight:600;line-height:1.95;white-space:pre-wrap;margin:20px 0}.answer-sheet{position:sticky;top:102px;padding:18px}.answer-sheet .panel-head{align-items:center}.answer-sheet .panel-head h3{font-size:14px}.answer-progress{height:5px;border-radius:5px;background:#e5edf3;margin:18px 0}.answer-progress i{display:block;height:100%;background:#3a84b2;border-radius:5px;transition:width .3s}.answer-numbers{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.answer-numbers button{border:1px solid #d7e3ed;background:#f7fafc;border-radius:6px;min-height:35px;color:#6c8599}.answer-numbers button.answered{background:#0c4f80;border-color:#0c4f80;color:white}.answer-sheet>p{font-size:11px;color:#8193a3;line-height:1.8;margin:16px 0}.answer-sheet .notice{font-size:11px;margin:0}.exam-confirm-backdrop{position:fixed;inset:0;background:#09284099;z-index:100;display:grid;place-items:center;padding:20px}.exam-confirm{background:#fff;border-radius:14px;padding:28px;width:min(480px,100%)}.exam-confirm h2{font-size:22px}.exam-confirm p{font-size:14px;line-height:1.8;color:#637d91}.confirm-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:22px}.confirm-actions .secondary-button{margin:0}@media(max-width:900px){.exam-workspace{display:flex;flex-direction:column-reverse}.answer-sheet{position:static;width:100%}.answer-numbers{grid-template-columns:repeat(auto-fill,minmax(35px,1fr))}.exam-questions{width:100%}}@media(max-width:550px){.exam-sticky-bar{gap:12px;padding:12px;flex-wrap:wrap}.exam-sticky-bar>div:first-child{gap:8px}.exam-sticky-bar strong{font-size:22px}.save-indicator{order:3;flex-basis:100%;display:flex;justify-content:space-between}.exam-sticky-bar>.primary-button{margin-left:auto;padding:9px 13px}}
.mobile-sheet-toggle{display:none}.answer-numbers button{min-height:44px}@media(max-width:900px){.mobile-sheet-toggle{display:block;position:sticky;top:112px;z-index:19;width:100%;background:#fff;margin:0 0 12px}.answer-sheet{display:none}.answer-sheet.expanded{display:block;position:sticky;top:166px;z-index:18;max-height:50vh;overflow:auto}.exam-question{scroll-margin-top:185px}}
</style>
