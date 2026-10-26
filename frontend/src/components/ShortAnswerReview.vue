<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
type Point = { key: string; label: string; source: string; weight: number; constraint: string }
type Finding = { key: string; verdict: string; evidence: string; reason: string }
type Row = {
  attemptId: number; questionId: number; name: string; stem: string; reference: string; answer: string
  maximum: number; state: string; suggested: number | null; awarded: number | null; revision: number
  rubricId: number | null; currentRubricId: number | null; rubric: { points: Point[] } | null
  evaluation: { findings: Finding[]; strictness?: string; referenceScore?: { points: number; reason: string; evidence: string } | null } | null
  risks: string[]; safeToApply: boolean; published: boolean; automaticZero: boolean; gradingComment: string | null
}
type Workspace = { rows: Row[]; pending: number; suggested: number; reviewRequired: number; accepted: number }
type Draft = { points: number | ''; reason: string; revision: number }
const props = defineProps<{ examId: string }>()
const emit = defineEmits<{ changed: [] }>()
const path = `/api/exams/${props.examId}/short-review`
const data = ref<Workspace | null>(null), busy = ref(false), loading = ref(false)
const error = ref(''), notice = ref('')
const filter = ref('pending'), question = ref(''), page = ref(1), expanded = ref('')
type Job = { id: number; kind: string; state: string; total: number; completed: number; review: number; failed: number; pending: number; errors: string[]; executionEnabled: boolean }
const jobs = ref<Job[]>([])
const activeJob = computed(() => jobs.value.find(j => j.kind === 'GRADE' && j.pending > 0))
const latestJobs = computed(() => ['GRADE', 'PREPARE'].map(kind => jobs.value.find(j => j.kind === kind)).filter((j): j is Job => !!j))
let pollTimer: ReturnType<typeof setTimeout> | undefined
const drafts = ref<Record<string, Draft>>({}), confirmUndo = ref<Row | null>(null)
const listTop = ref<HTMLElement | null>(null), confirmationPanel = ref<HTMLElement | null>(null)
let disposed = false, initialized = false
const labels: Record<string, string> = { NOT_STARTED: '待AI判卷', NEEDS_RUBRIC: '待AI判卷', STALE: '需重新AI判卷', RUNNING: 'AI判卷中', FAILED: 'AI判卷失败', SUGGESTED: '待人工确认', REVIEW_REQUIRED: '需单独复核', ACCEPTED: '已确认' }
const verdicts: Record<string, string> = { FULL: '满足', NONE: '未满足', REVIEW: '无法确定', MET: '满足', PARTIAL: '部分满足', MISSING: '未答到', CONTRADICTED: '与要求相反', UNCERTAIN: '无法确定' }
const key = (r: Row) => `${r.attemptId}-${r.questionId}`
const target = (r: Row) => ({ attemptId: r.attemptId, questionId: r.questionId, revision: r.revision })
const hasSuggestion = (r: Row) => ['SUGGESTED', 'REVIEW_REQUIRED'].includes(r.state) && r.suggested != null
const rows = computed(() => data.value?.rows || [])
const questions = computed(() => [...new Map(rows.value.map(r => [r.questionId, r.stem])).entries()])
const emptyCount = computed(() => rows.value.filter(r => r.automaticZero).length)
const toGenerate = computed(() => rows.value.filter(r => r.answer.trim() && r.awarded == null && !hasSuggestion(r)))
const filtered = computed(() => rows.value.filter(r => (!question.value || String(r.questionId) === question.value) && (
  filter.value === 'all' || filter.value === 'pending' && r.awarded == null || filter.value === 'accepted' && r.awarded != null ||
  filter.value === 'review' && r.awarded == null && r.risks.length > 0 || filter.value === 'failed' && r.state === 'FAILED'
)))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 6)))
const visible = computed(() => filtered.value.slice((page.value - 1) * 6, page.value * 6))
watch(pages, total => { if (page.value > total) void changePage(total) })
watch(confirmUndo, async value => { if (value) { await nextTick(); confirmationPanel.value?.scrollIntoView({ block: 'center', behavior: 'smooth' }) } })
onBeforeUnmount(() => { disposed = true; if (pollTimer) clearTimeout(pollTimer) })
async function changePage(next: number) {
  page.value = Math.max(1, Math.min(next, pages.value)); expanded.value = ''; await nextTick()
  listTop.value?.scrollIntoView({ block: 'start', behavior: 'instant' })
}
function draft(r: Row): Draft {
  const value = drafts.value[key(r)] ??= { points: '', reason: '', revision: r.revision }
  if (value.points === '' && !value.reason.trim()) value.revision = r.revision
  return value
}
function findings(r: Row) { return r.rubric?.points.map(point => ({ point, finding: r.evaluation?.findings.find(f => f.key === point.key) })) || [] }
function summary(r: Row) {
  if (!r.evaluation) return ''
  if (r.evaluation.referenceScore) return r.evaluation.referenceScore.reason
  const deductions = findings(r).filter(f => f.finding && !['MET', 'FULL'].includes(f.finding.verdict))
  return deductions.length ? deductions.slice(0, 2).map(f => `${f.point.label}：${f.finding!.reason}`).join('；') : '各评分点均满足。'
}
async function load(clearError = true): Promise<boolean> {
  loading.value = true; if (clearError) error.value = ''
  try {
    const [next, nextJobs] = await Promise.all([api<Workspace>(path), api<Job[]>(`${path}/jobs`)])
    if (disposed) return false
    data.value = next; jobs.value = nextJobs
    if (!initialized) { question.value = next.rows.length ? String(next.rows[0]!.questionId) : ''; initialized = true }
    return true
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : '加载失败，请刷新重试'; return false }
  finally { if (!disposed) loading.value = false }
}
async function generate() {
  if (busy.value || activeJob.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const job = await api<Job>(`${path}/jobs`, { method: 'POST' })
    notice.value = job.executionEnabled
      ? 'AI建议生成任务已提交。有答案的简答题均须人工确认，关闭页面不影响任务。'
      : '任务已保存，后台自动判卷尚未启用，请联系系统管理员。可继续人工评分。'
    await load(false); emit('changed'); schedulePoll()
  } catch (e) { error.value = e instanceof Error ? e.message : '创建任务失败，请重试' }
  finally { busy.value = false }
}
async function retry(job: Job) {
  await run(`${path}/jobs/${job.id}/retry`, {}, '已重新提交失败项。')
  schedulePoll()
}
function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  if (disposed) return
  pollTimer = setTimeout(async () => {
    if (!busy.value && !loading.value) {
      const before = data.value?.accepted
      await load(false)
      if (before !== data.value?.accepted) emit('changed')
    }
    schedulePoll()
  }, 4000)
}
async function run(url: string, body: unknown, message: string, savedKey?: string) {
  if (busy.value || loading.value) return
  busy.value = true; error.value = ''; notice.value = ''; confirmUndo.value = null
  try {
    await api(url, { method: 'POST', body: JSON.stringify(body) })
    if (disposed) return
    if (savedKey) delete drafts.value[savedKey]
    notice.value = message; await load(false); emit('changed')
  } catch (e) { if (!disposed) { await load(false); error.value = e instanceof Error ? e.message : '保存失败，输入已保留，请刷新后重试' } }
  finally { if (!disposed) busy.value = false }
}
function applySuggestions(list: Row[], ordinaryOnly = false) {
  if (list.length) void run(`${path}/apply-suggestions`, { targets: list.map(target), ordinaryOnly }, `已人工确认 ${list.length} 份AI建议分，待统一公布。`)
}
function saveManual(r: Row) {
  const input = draft(r)
  if (input.points === '' || !Number.isInteger(input.points) || input.points < 0 || input.points > r.maximum) { error.value = `请输入0～${r.maximum}之间的整数分数`; return }
  void run(`${path}/manual`, { target: { ...target(r), revision: input.revision }, points: input.points, reason: input.reason }, `最终分 ${input.points} / ${r.maximum} 分已人工确认，待统一公布。`, key(r))
}
function undo() {
  const r = confirmUndo.value; if (r) void run(`${path}/undo`, { target: target(r), reopenPublished: true }, '已撤销评分，可重新采用建议或输入分数。', key(r))
}
onMounted(async () => { await load(); schedulePoll() })
</script>

<template>
  <section v-if="loading || error || rows.length || jobs.length" class="panel short-review" aria-label="简答题集中判卷" :aria-busy="busy || loading">
    <header><div><h2>简答题集中判卷</h2><p>空答自动0分；有答案的由AI建议分 → 人工确认或改分 → 统一公布。</p></div><button class="secondary-button" :disabled="busy || loading" @click="load()">刷新简答</button></header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div><div v-if="notice" class="notice" role="status">{{ notice }}</div>
    <p v-if="loading && !data">正在加载简答作答…</p>
    <template v-if="data">
      <div class="summary"><strong>{{ data.pending }} 份待人工确认</strong><span>{{ emptyCount }} 份空答自动0分</span><span>{{ data.accepted - emptyCount }} 份已人工确认</span><span>{{ data.reviewRequired }} 份需重点复核</span></div>
      <div ref="listTop" class="filters"><label>按题目集中查看<select v-model="question" @change="page = 1"><option value="">全部简答题</option><option v-for="[id, stem] in questions" :key="id" :value="String(id)">#{{ id }} {{ stem.slice(0, 45) }}</option></select></label><label>评分状态<select v-model="filter" @change="page = 1"><option value="pending">待人工确认</option><option value="review">需重点复核</option><option value="failed">AI评阅失败</option><option value="accepted">已确认 / 空答0分</option><option value="all">全部作答</option></select></label></div>
      <div class="batch-bar">
        <div class="actions batch-actions"><button class="primary-button" :disabled="busy || loading || !!activeJob || !toGenerate.length || !!confirmUndo" @click="generate">{{ activeJob ? (activeJob.executionEnabled ? '后台判卷进行中' : '等待后台启用') : 'AI一键判卷（' + toGenerate.length + '）' }}</button></div>
        <p class="help">按要点宽松给分，同义和概括表达均认可。AI建议仅供核对，每份有答案的作答都须人工确认；已有建议和人工最终分会保留。</p>
        <div v-for="job in latestJobs" :key="job.id" class="notice" role="status">
          <strong>{{ job.kind === 'PREPARE' ? '准备评分标准' : 'AI建议生成' }}</strong> · 已完成 {{ job.completed }} / {{ job.total }}
          <span v-if="job.pending"> · 待处理 {{ job.pending }}</span><span v-if="job.review"> · 转人工 {{ job.review }}</span><span v-if="job.failed"> · 失败 {{ job.failed }}</span>
          <p v-if="!job.executionEnabled && job.pending">后台自动判卷尚未启用，请联系系统管理员。可继续人工评分。</p>
          <template v-if="job.kind === 'PREPARE' || toGenerate.length"><p v-for="message in job.errors" :key="message">{{ message }}</p></template>
          <button v-if="job.failed" class="secondary-button" :disabled="busy || loading || !job.executionEnabled" @click="retry(job)">重试失败项</button>
        </div>
      </div>
      <div v-if="confirmUndo" ref="confirmationPanel" class="confirm" role="region" aria-label="撤销评分确认"><strong>撤销评分</strong><p>撤销答卷 #{{ confirmUndo.attemptId }} 本题的评分。{{ confirmUndo.published ? '此答卷已公布的成绩将撤回，完成修改后需重新公布。' : '其他题评分保留。' }}</p><div class="actions"><button class="primary-button" :disabled="busy || loading" @click="undo">确认撤销评分</button><button class="secondary-button" :disabled="busy" @click="confirmUndo = null">取消</button></div></div>
      <div v-if="!filtered.length" class="empty">当前筛选没有作答。可切换“全部作答”或“已确认 / 空答0分”查看记录。</div>
      <article v-for="r in visible" :key="key(r)" class="answer-row" :aria-label="'简答答卷 ' + r.attemptId + ' 题目 ' + r.questionId">
        <div class="row-head"><span>{{ r.name }} · 答卷 #{{ r.attemptId }}</span><span class="status-pill" :class="r.awarded != null ? 'success' : r.risks.length ? 'warning' : ''">{{ r.published ? '已公布' : r.automaticZero ? '未作答 · 自动0分' : labels[r.state] || '待处理' }}</span></div>
        <div class="question-heading"><h3>{{ r.stem }}</h3><strong class="maximum">本题满分 {{ r.maximum }} 分</strong></div>
        <div class="answer-compare"><div><small>参考答案</small><p>{{ r.reference }}</p></div><div class="candidate-answer"><small>考生作答</small><p>{{ r.answer.trim() || '未作答' }}</p></div></div>
        <div v-if="r.automaticZero" class="score-line"><strong>得分 0 / {{ r.maximum }} 分</strong><span class="help">未作答，系统自动记0分，无需AI或人工确认。</span></div>
        <template v-else>
          <div class="score-line"><span v-if="!hasSuggestion(r) && r.awarded == null" class="help">{{ r.state === 'STALE' ? '评分标准已更新，请重新AI判卷。' : r.state === 'FAILED' ? 'AI判卷失败，未计分；可重试或人工评分。' : '尚无AI建议，可点击“AI一键判卷”或直接人工评分。' }}</span><button v-if="hasSuggestion(r) && r.safeToApply" class="secondary-button" :disabled="busy || loading || !!confirmUndo" @click="applySuggestions([r])">确认此分：{{ r.suggested }}分</button></div>
          <p v-if="r.evaluation && r.state !== 'STALE'" class="ai-summary">{{ summary(r) }}</p>
          <ul v-if="r.risks.length && r.awarded == null" class="risk-list"><li v-for="risk in r.risks" :key="risk">{{ risk }}</li></ul>
          <form v-if="r.awarded == null" class="manual" @submit.prevent="saveManual(r)"><div class="manual-line"><div class="ai-score" aria-label="AI建议分"><span>AI建议分</span><strong>{{ r.state === 'STALE' ? '已过期' : r.suggested ?? '—' }}</strong><span>/ {{ r.maximum }} 分</span><small v-if="r.suggested != null">{{ r.evaluation?.referenceScore ? '历史参考答案建议' : '逐点评分' }}</small></div><div class="manual-score"><label :for="'score-' + key(r)">人工最终分</label><div class="score-input"><input :id="'score-' + key(r)" v-model.number="draft(r).points" type="number" min="0" :max="r.maximum" step="1" required :placeholder="'0～' + r.maximum" :disabled="busy || loading" /><span>/ {{ r.maximum }} 分</span></div></div><button class="secondary-button" :disabled="busy || loading || !!confirmUndo">{{ hasSuggestion(r) ? '修改并确认' : '确认人工评分' }}</button></div><details><summary>添加改分说明（可选）</summary><label :for="'reason-' + key(r)" class="sr-only">改分说明</label><textarea :id="'reason-' + key(r)" v-model="draft(r).reason" rows="2" maxlength="2000" placeholder="可说明得分或扣分依据" :disabled="busy || loading" /></details><p v-if="draft(r).revision !== r.revision" class="rubric-note">此作答已更新，输入暂未保存。<button type="button" class="link-button" @click="delete drafts[key(r)]">清空输入并使用最新记录</button></p></form>
          <div v-else class="confirmed-scores"><div class="ai-score"><span>AI建议分</span><strong>{{ r.suggested ?? '—' }}</strong><span>/ {{ r.maximum }} 分</span><small v-if="r.suggested != null">原建议保留</small></div><strong>已确认最终分 {{ r.awarded }} / {{ r.maximum }} 分</strong></div>
          <p v-if="r.awarded != null && r.gradingComment" class="help">{{ r.gradingComment }}</p>
          <div class="actions row-actions"><button v-if="r.evaluation" class="link-button" :disabled="busy" @click="expanded = expanded === key(r) ? '' : key(r)">{{ expanded === key(r) ? '收起详细依据' : '查看详细依据' }}</button><button v-if="r.state === 'FAILED'" class="secondary-button" :disabled="busy || loading || !!confirmUndo" @click="generate">重新启动待评分任务</button><button v-if="r.awarded != null" class="link-button" :disabled="busy || loading || !!confirmUndo" @click="confirmUndo = r">撤销评分</button></div>
          <div v-if="expanded === key(r)" class="evidence"><div v-if="r.evaluation?.referenceScore" class="finding"><strong>历史参考答案对照</strong><p>{{ r.evaluation.referenceScore.reason }}</p><p v-if="r.evaluation.referenceScore.evidence" class="quote">考生原文：“{{ r.evaluation.referenceScore.evidence }}”</p></div><div v-for="{ point, finding } in findings(r)" :key="point.key" class="finding"><div><strong>{{ point.label }}</strong><span>{{ finding ? verdicts[finding.verdict] : '未评分' }} · 权重 {{ point.weight }}</span></div><p v-if="finding?.evidence" class="quote">“{{ finding.evidence }}”</p><p v-if="finding">{{ finding.reason }}</p><p v-if="point.constraint" class="help">判分要求：{{ point.constraint }}</p><details><summary>参考答案原文依据</summary><p>{{ point.source }}</p></details></div></div>
        </template>
      </article>
      <div v-if="filtered.length" class="pagination"><button class="secondary-button" :disabled="page <= 1 || busy || loading" @click="changePage(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span><button class="secondary-button" :disabled="page >= pages || busy || loading" @click="changePage(page + 1)">下一页</button></div>
      <p class="publication-note">有答案的简答题均须人工确认（含AI建议0分或满分），确认后仍可撤销修改；全部题目就绪后，在上方“公布已完成成绩”。</p>
    </template>
  </section>
</template>

<style scoped>
.short-review{padding:24px;margin:20px 0;min-width:0}.short-review header,.row-head,.score-line,.finding>div{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}.short-review header>div{min-width:0}.short-review h2{font-size:20px;margin:0}.short-review header p,.help,.publication-note{font-size:13px;color:#627b8e;line-height:1.8}.summary{display:flex;gap:12px 24px;flex-wrap:wrap;padding:18px 0;border-bottom:1px solid #dce5ec;font-size:13px;color:#587184}.summary strong{font-size:18px;color:#176a65}.generation-bar{padding:4px 0 14px;border-bottom:1px solid #dce5ec}.filters{display:flex;gap:16px;flex-wrap:wrap;margin:18px 0;scroll-margin-top:24px}.filters label{display:grid;gap:8px;font-size:13px;color:#456078}.filters label:first-child{flex:1;min-width:0}.short-review select,.manual input,.manual textarea{box-sizing:border-box;width:100%;min-width:0;padding:10px;border:1px solid #bdcdd8;border-radius:6px;font:inherit;background:white}.actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:14px 0}.secondary-button{margin-top:0}.batch-bar{padding-bottom:16px;border-bottom:1px solid #dce5ec}.batch-bar p{margin:7px 0 0}.answer-row{padding:24px 0;border-bottom:1px solid #dce5ec;min-width:0}.row-head{font-size:13px;color:#587184}.question-heading{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin:16px 0}.question-heading h3{font-size:15px;line-height:1.8;overflow-wrap:anywhere;white-space:pre-wrap;margin:0}.maximum{font-size:13px;white-space:nowrap;color:#173550}.answer-compare{display:grid;grid-template-columns:1fr 1fr;gap:12px}.answer-compare>div{min-width:0;padding:14px;background:#f4f7fa;border-radius:7px}.answer-compare .candidate-answer{background:#edf6f4}.answer-compare small{color:#627b8e}.answer-compare p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:8px 0 0;font-size:14px}.score-line{padding:18px 0 0}.score-line strong{color:#165e65;font-size:17px}.ai-summary{font-size:13px;line-height:1.8;color:#456078;white-space:pre-wrap;overflow-wrap:anywhere}.risk-list,.rubric-note{font-size:13px;line-height:1.8;background:#fff7e9;color:#815d24;border-radius:6px;overflow-wrap:anywhere}.risk-list{padding:12px 12px 12px 30px}.rubric-note{padding:10px 12px}.manual{padding:16px;border:1px solid #dce5ec;border-radius:8px;margin-top:16px;background:#fafcfc}.manual-line{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.manual-line label{font-size:14px;color:#456078}.score-input{display:flex;align-items:center;gap:8px}.score-input input{width:100px}.score-input span{font-size:13px;color:#587184}.manual details{margin-top:12px}.manual summary,.evidence summary{cursor:pointer;font-size:13px;color:#4e6a7c}.manual textarea{margin-top:10px;font-size:14px}.row-actions{margin-bottom:0}.confirm{position:sticky;top:24px;z-index:2;padding:18px;background:#f0f7f5;border:1px solid #9ab9af;border-radius:8px;line-height:1.8}.evidence{border-left:3px solid #73aaa1;padding:14px 18px;background:#fafcfc;margin-top:14px}.finding{padding:16px 0;border-bottom:1px solid #e1e9ed}.finding>div{align-items:flex-start;font-size:14px}.finding>div span{font-size:12px;color:#587184}.finding p{font-size:13px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.quote{padding:10px;background:#eaf4f1;border-radius:5px}.pagination{display:flex;justify-content:center;align-items:center;gap:14px;padding-top:20px;font-size:13px}.publication-note{margin:20px 0 0}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:3px solid #40a79f;outline-offset:2px}@media(max-width:650px){.short-review{padding:16px}.filters{display:grid;grid-template-columns:1fr}.question-heading{display:block}.maximum{display:block;margin-top:8px}.answer-compare{grid-template-columns:1fr}.confirm{position:static}.score-line{align-items:flex-start}.score-line>.primary-button{width:100%}.manual-line{align-items:flex-start;flex-direction:column}.manual-line>.secondary-button{width:100%}.score-input{width:100%}.score-input input{flex:1}.evidence{padding:12px}.finding>div{display:grid}.generation-bar .actions>button{width:100%}.batch-bar>button{width:100%;white-space:normal;line-height:1.6}button,input,select{min-height:44px}}
.grading-settings{display:flex;align-items:center;gap:18px;flex-wrap:wrap}.grading-settings label{display:flex;align-items:center;gap:10px;font-size:14px;color:#456078;white-space:nowrap}.grading-settings select{width:110px}.grading-settings .help{flex:1;min-width:220px;margin:0}.batch-actions{margin:12px 0}.ai-score{display:flex;align-items:baseline;gap:7px;flex-wrap:wrap;padding-right:18px;border-right:1px solid #dce5ec;color:#456078;font-size:14px}.ai-score strong{font-size:23px;color:#176a65;font-variant-numeric:tabular-nums}.ai-score small{flex-basis:100%;font-size:12px;color:#627b8e}.manual-score{display:flex;align-items:center;gap:12px}.confirmed-scores{display:flex;align-items:center;gap:22px;flex-wrap:wrap;padding:16px;margin-top:16px;background:#fafcfc;border:1px solid #dce5ec;border-radius:8px}.confirmed-scores>strong{font-size:16px;color:#173550}
@media(max-width:650px){.grading-settings{gap:8px}.grading-settings label,.grading-settings .help{width:100%;min-width:0}.grading-settings select{flex:1}.batch-actions>button{width:100%;white-space:normal;line-height:1.6}.ai-score{width:100%;box-sizing:border-box;border-right:0;border-bottom:1px solid #dce5ec;padding:0 0 12px}.manual-score{width:100%}.manual-score label{white-space:nowrap}.manual-score .score-input{flex:1;min-width:0}.manual-score input{min-width:60px}.confirmed-scores{gap:12px}}
</style>
