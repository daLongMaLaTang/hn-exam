<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, hasRole } from '../api'

type Person = { userId: number; employeeNo: string; name: string; department: string | null; professionalDirection: string | null; active: boolean; state: string; attemptId: number | null; attemptCount: number; score: number | null; passed: boolean | null; startedAt: string | null; submittedAt: string | null; remindedAt: string | null; readAt: string | null; canRemind: boolean }
type Tracking = { examId: number; title: string; examStatus: string; startTime: string | null; endTime: string | null; makeupSourceExamId: number | null; frozen: boolean; generatedAt: string; counts: Record<string, number>; people: Person[] }
const route = useRoute()
const data = ref<Tracking | null>(null), loading = ref(true), error = ref(''), message = ref(''), sending = ref(false)
const search = ref(''), filter = ref(''), selected = ref<number[]>([]), confirmSend = ref(false), page = ref(1)
const labels: Record<string, string> = { NOT_STARTED: '未开始', IN_PROGRESS: '进行中', PROCESSING: '等待自动收卷', SUBMITTED: '待阅卷', GRADED: '已完成', ABSENT: '缺考', UNCONFIRMED: '名单待核对', CANCELLED: '已取消', PASSED: '已通过', FAILED: '未通过' }
const canSend = hasRole('SYSTEM_ADMIN', 'DEPARTMENT_LEADER', 'TRAINING_ADMIN')
const filtered = computed(() => (data.value?.people || []).filter(p => {
  const match = !filter.value || (filter.value === 'PASSED' ? p.state === 'GRADED' && p.passed === true : filter.value === 'FAILED' ? p.state === 'GRADED' && p.passed === false : p.state === filter.value)
  return match && [p.name, p.employeeNo, p.department, p.professionalDirection].some(v => v?.toLowerCase().includes(search.value.trim().toLowerCase()))
}))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 20)))
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20))
const selectable = computed(() => visible.value.filter(p => p.canRemind).map(p => p.userId))
const allSelected = computed(() => selectable.value.length > 0 && selectable.value.every(id => selected.value.includes(id)))
const recipients = computed(() => (data.value?.people || []).filter(p => selected.value.includes(p.userId) && p.canRemind))
const summary = computed(() => ['TOTAL', 'NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'GRADED', data.value?.examStatus === 'CANCELLED' ? 'CANCELLED' : data.value?.frozen ? 'ABSENT' : 'UNCONFIRMED'])
const examStates: Record<string, string> = { PUBLISHED: '已发布', CLOSED: '已关闭', CANCELLED: '已取消' }
const date = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const tone = (p: Person) => p.state === 'GRADED' ? p.passed ? 'success' : 'danger' : p.state === 'ABSENT' ? 'danger' : ['SUBMITTED', 'PROCESSING', 'UNCONFIRMED'].includes(p.state) ? 'warning' : 'info'
let generation = 0
async function load() {
  const request = ++generation
  loading.value = true; error.value = ''; confirmSend.value = false; selected.value = []
  try { const result = await api<Tracking>(`/api/exams/${route.params.id}/tracking`); if (request === generation) data.value = result }
  catch (cause) { if (request === generation) { data.value = null; error.value = cause instanceof Error ? cause.message : '加载失败，请重试' } }
  finally { if (request === generation) loading.value = false }
}
function toggleAll() { selected.value = allSelected.value ? [] : selectable.value }
async function send() {
  if (sending.value || !recipients.value.length) return
  sending.value = true; error.value = ''; message.value = ''
  try {
    const result = await api<{sent: number; skipped: number}>(`/api/exams/${data.value!.examId}/reminders`, { method: 'POST', body: JSON.stringify({ userIds: recipients.value.map(p => p.userId) }) })
    message.value = `已发送 ${result.sent} 条站内提醒${result.skipped ? `，${result.skipped} 人因已作答、近期已提醒或当前不可参加而跳过` : ''}。`
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '发送失败，请重试' }
  finally { sending.value = false }
}
watch([search, filter, page], () => { selected.value = []; confirmSend.value = false })
watch([search, filter], () => { page.value = 1 })
watch(() => route.params.id, () => { data.value = null; page.value = 1; message.value = ''; void load() }, { immediate: true })
</script>

<template>
  <div class="page-head"><div><RouterLink to="/exams" class="tracking-back">← 考试管理</RouterLink><h1>应考追踪</h1><p>查看每位应考人员的进度，提醒尚未开始的学员。</p></div><button class="secondary-button" :disabled="loading || sending" @click="load">{{ loading ? '正在刷新…' : '刷新进度' }}</button></div>
  <p v-if="error" class="notice error" role="alert">{{ error }} <button class="link-button" :disabled="loading || sending" @click="load">重新加载</button></p>
  <p v-if="message" class="notice" role="status">{{ message }}</p>
  <section v-if="loading" class="panel"><div class="empty" role="status">正在核对名单与作答进度…</div></section>
  <template v-else-if="data">
    <section class="panel tracking-heading"><div><span class="status-pill info">{{ examStates[data.examStatus] }}</span><h2>{{ data.title }}</h2><p>开放：{{ data.startTime ? date(data.startTime) : '不限开始时间' }}　截止：{{ data.endTime ? date(data.endTime) : '未设置' }}</p><RouterLink v-if="data.makeupSourceExamId" :to="`/exams/${data.makeupSourceExamId}/tracking`" class="link-button">查看原考试追踪</RouterLink></div><RouterLink :to="`/results?examId=${data.examId}`" class="secondary-button">查看成绩与答卷</RouterLink></section>
    <p v-if="!data.frozen" class="notice">这场历史考试未保存发布时的完整名单，当前按现有范围与作答记录展示。截止后没有答卷的人员标为“名单待核对”，不直接计为缺考。</p>
    <div class="tracking-summary" aria-label="应考概览"><button v-for="key in summary" :key="key" :aria-pressed="filter === (key === 'TOTAL' ? '' : key)" @click="filter = key === 'TOTAL' ? '' : key"><span>{{ key === 'TOTAL' ? (data.frozen ? '应考人数' : '当前范围人数') : labels[key] }}</span><strong>{{ data.counts[key] }}</strong></button></div>
    <section class="panel tracking-list">
      <div class="tracking-filters"><label>查找人员<input v-model="search" type="search" placeholder="姓名、工号、部门或专业" /></label><label>作答状态<select v-model="filter"><option value="">全部状态</option><option v-for="(label, value) in labels" :key="value" :value="value">{{ label }}（{{ data.counts[value] }}）</option></select></label><span class="tracking-count">{{ filtered.length }} 人 · {{ data.counts.PASSED }} 人通过</span></div>
      <div v-if="canSend" class="tracking-actions"><label class="tracking-check"><input type="checkbox" :checked="allSelected" :disabled="!selectable.length || sending" @change="toggleAll" />选择本页可提醒人员</label><button class="primary-button" :disabled="!recipients.length || sending" @click="confirmSend = true">提醒已选 {{ recipients.length }} 人</button></div>
      <div v-if="confirmSend" class="tracking-confirm" role="region" aria-label="确认站内提醒"><strong>给 {{ recipients.length }} 位学员发送站内提醒</strong><p>{{ recipients.map(p => p.name).join('、') }}</p><p>提醒将出现在学员的站内通知中。同一考试、同一学员每24小时最多提醒一次。</p><div><button class="primary-button" :disabled="sending || !recipients.length" @click="send">{{ sending ? '正在发送…' : '确认发送' }}</button><button class="secondary-button" :disabled="sending" @click="confirmSend = false">暂不发送</button></div></div>
      <div v-if="!filtered.length" class="empty"><h3>{{ data.people.length ? '没有符合条件的人员' : '暂无应考人员' }}</h3><p>{{ data.people.length ? '调整状态或关键词后再试。' : '这场考试的发布范围内没有可展示的学员。' }}</p><button v-if="data.people.length" class="secondary-button" @click="search = ''; filter = ''">清除筛选</button></div>
      <div v-else class="tracking-table"><table><caption class="tracking-caption">人员进度，以最近一次作答为准；补考单独统计。</caption><thead><tr><th v-if="canSend">选择</th><th>人员</th><th>部门 / 专业</th><th>作答进度</th><th>最近提醒</th><th>答卷</th></tr></thead><tbody><tr v-for="person in visible" :key="person.userId"><td v-if="canSend" class="tracking-select"><label class="person-selection"><input v-model="selected" type="checkbox" :value="person.userId" :disabled="!person.canRemind || sending" :aria-label="`提醒${person.name}（${person.employeeNo}）`" /><span>选择提醒</span></label></td><td data-label="人员"><strong>{{ person.name }}</strong><small>{{ person.employeeNo }}{{ !person.active ? ' · 当前不可参加' : '' }}</small></td><td data-label="部门 / 专业">{{ person.department || '未设置部门' }}<small>{{ person.professionalDirection || '未设置专业' }}</small></td><td data-label="作答进度"><span class="status-pill" :class="tone(person)">{{ labels[person.state] }}{{ person.state === 'GRADED' ? (person.passed ? ' · 通过' : ' · 未通过') : '' }}</span><small v-if="person.state === 'GRADED'">{{ person.score }} 分</small><small v-if="person.attemptCount > 1">历史作答 {{ person.attemptCount }} 次</small><small v-if="person.startedAt">开始于 {{ date(person.startedAt) }}</small></td><td data-label="最近提醒">{{ person.remindedAt ? date(person.remindedAt) : '尚未提醒' }}<small v-if="person.remindedAt">{{ person.readAt ? '已读 · ' + date(person.readAt) : '未读' }}</small></td><td data-label="答卷"><RouterLink v-if="person.attemptId && ['SUBMITTED', 'GRADED'].includes(person.state)" class="link-button" :to="`/results?examId=${data.examId}&attemptId=${person.attemptId}`">查看答卷</RouterLink><span v-else>—</span></td></tr></tbody></table></div>
      <div v-if="filtered.length" class="tracking-pagination"><button class="secondary-button" :disabled="page <= 1 || sending" @click="page--">上一页</button><span>{{ page }} / {{ pages }}</span><button class="secondary-button" :disabled="page >= pages || sending" @click="page++">下一页</button></div>
      <p class="tracking-footnote">更新于 {{ date(data.generatedAt) }} · 已开始、已截止或24小时内提醒过的人员不可再次提醒。</p>
    </section>
  </template>
</template>

<style scoped>
.tracking-back{color:var(--tracking-accent,#167d8d);font-size:13px;text-decoration:none}.tracking-heading{display:flex;justify-content:space-between;gap:24px;align-items:center}.tracking-heading>div{min-width:0}.tracking-heading h2{font-size:23px;line-height:1.6;overflow-wrap:anywhere;margin:12px 0 6px}.tracking-heading p,.tracking-footnote{font-size:12px;line-height:1.8;color:#667f92}.tracking-heading>a{flex-shrink:0}.tracking-summary{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:1px;background:#dbe5ec;border:1px solid #dbe5ec;border-radius:10px;overflow:hidden;margin-bottom:20px}.tracking-summary button{border:0;border-bottom:3px solid transparent;background:#fff;padding:18px 12px;display:grid;gap:7px;text-align:left;color:#667f92;cursor:pointer;font:inherit}.tracking-summary button[aria-pressed=true]{border-bottom-color:#167d8d;background:#f1f8f9;color:#125967}.tracking-summary span{font-size:12px}.tracking-summary strong{font-size:27px;color:#173550;font-variant-numeric:tabular-nums}.tracking-list{padding:22px}.tracking-filters{display:flex;align-items:end;gap:14px;flex-wrap:wrap}.tracking-filters label{display:grid;gap:7px;color:#496074;font-size:12px}.tracking-filters label:first-child{flex:1;min-width:210px}.tracking-filters input,.tracking-filters select{font:inherit;border:1px solid #ccdbe5;background:#fff;border-radius:6px;padding:11px;width:100%;min-width:0}.tracking-count{font-size:12px;color:#667f92;padding:12px 0}.tracking-actions{display:flex;gap:14px;align-items:center;justify-content:space-between;padding:18px 0}.tracking-check{display:flex;gap:8px;align-items:center;font-size:13px}.tracking-table table{width:100%;border-collapse:collapse}.tracking-table th{font-size:12px;text-align:left;color:#667f92;background:#f4f7f9}.tracking-table td,.tracking-table th{padding:15px 12px;border-bottom:1px solid #e4ebf0}.tracking-table td{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.tracking-table small{display:block;color:#667f92;font-size:11px;margin-top:4px}.tracking-table input,.tracking-check input{width:18px;height:18px;accent-color:#167d8d}.tracking-caption{text-align:left;font-size:12px;color:#667f92;padding:10px 0}.tracking-pagination{display:flex;justify-content:center;align-items:center;gap:16px;padding-top:20px;font-size:13px}.tracking-list .secondary-button,.tracking-heading .secondary-button{margin:0}.tracking-footnote{margin:16px 0 0}.tracking-confirm{background:#f1f8f9;border:1px solid #b7d8dd;border-radius:8px;padding:18px;margin-bottom:12px;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.tracking-confirm p{margin:7px 0;color:#496074}.tracking-confirm>div{display:flex;gap:10px;margin-top:12px}button:disabled{opacity:.5;cursor:not-allowed}
@media(max-width:900px){.tracking-summary{grid-template-columns:repeat(3,minmax(0,1fr))}.tracking-heading{align-items:flex-start;flex-direction:column}.tracking-table thead{display:none}.tracking-table table,.tracking-table tbody,.tracking-table tr,.tracking-table td{display:block}.tracking-table tr{padding:12px 0;border-bottom:1px solid #dbe5ec;position:relative}.tracking-table td{display:grid;grid-template-columns:90px minmax(0,1fr);gap:4px 12px;border:0;padding:7px 0}.tracking-table td:before{content:attr(data-label);grid-column:1;grid-row:1 / span 5;color:#667f92;font-size:12px}.tracking-table td>*{grid-column:2;justify-self:start}.tracking-table td.tracking-select{display:flex;justify-content:flex-end;padding:0;min-height:24px}.tracking-table td.tracking-select:before{content:none}}
@media(max-width:500px){.tracking-list{padding:16px}.tracking-actions{align-items:stretch;flex-direction:column}.tracking-filters{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.tracking-filters label:first-child{min-width:0}.tracking-count{padding:0}.tracking-summary button{padding:14px 10px}.tracking-summary strong{font-size:24px}.tracking-confirm>div{flex-direction:column}.tracking-heading h2{font-size:19px}.tracking-table td{grid-template-columns:76px minmax(0,1fr);gap:4px 8px}}
.person-selection{display:flex;align-items:center;gap:7px;min-height:44px}.person-selection span{display:none}@media(max-width:900px){.tracking-caption{display:block;width:100%;line-height:1.8}.person-selection span{display:inline;font-size:12px;color:#496074}.tracking-table td.tracking-select{min-height:44px}}
</style>
