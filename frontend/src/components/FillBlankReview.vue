<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
type Group = { questionId: number; version: string; slot: number; stem: string; reference: string; submitted: string; count: number; answerIds: number[] }
type History = { id: number; stem: string; slot: number; submitted: string; correct: boolean; count: number; ruleId: number | null; actor: string; at: string; revokedAt: string | null }
type Usage = { attemptId: number; examId: number; examTitle: string; published: boolean }
type Rule = { id: number; stem: string; slot: number; acceptedAnswer: string; active: boolean; actor: string; at: string; usages: Usage[] }
type Workspace = { groups: Group[]; pendingSlots: number; readyCount: number; history: History[]; rules: Rule[] }
const props = defineProps<{ examId: string }>()
const emit = defineEmits<{ changed: [] }>()
const data = ref<Workspace | null>(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
const tab = ref<'pending' | 'history' | 'rules'>('pending'), search = ref(''), page = ref(1)
const confirm = ref<{ text: string; url: string; body: unknown; label: string } | null>(null)
const filtered = computed(() => data.value?.groups.filter(g => `${g.stem} ${g.submitted}`.includes(search.value.trim())) || [])
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 8)))
const visible = computed(() => filtered.value.slice((Math.min(page.value, pages.value) - 1) * 8, Math.min(page.value, pages.value) * 8))
const path = `/api/exams/${props.examId}/blank-review`
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { data.value = await api<Workspace>(path) }
  catch (e) { error.value = e instanceof Error ? e.message : '加载失败，请重试' }
  finally { loading.value = false }
}
function decide(g: Group, correct: boolean, remember: boolean) {
  const label = correct ? (remember ? '判对并加入答案库' : '仅本次判对') : '判错'
  confirm.value = { label, url: `${path}/decisions`, body: { ...g, correct, remember },
    text: `第 ${g.slot} 空「${g.submitted}」将对当前 ${g.count} 人应用“${label}”。${remember ? '以后相同题目版本、相同空位的相同答案自动给分。' : '本次操作不新增长期可接受答案。'}成绩暂不公布。` }
}
function revoke(h: History) {
  confirm.value = { label: '撤销判定', url: `${path}/decisions/${h.id}/revoke`, body: { reopenPublished: true },
    text: `撤销第 ${h.slot} 空「${h.submitted}」的本次判定（原涉及 ${h.count} 人），仍使用此判定的答案将重新待判。涉及的已公布答卷会撤回成绩，保留其他题评分，需重新公布。${h.ruleId ? '同时停用对应可接受答案；其他考试的既有引用可在答案库查看并另行更正。' : ''}` }
}
function disable(r: Rule) {
  confirm.value = { label: '停用答案', url: `/api/exams/blank-rules/${r.id}/disable`, body: {},
    text: `停用第 ${r.slot} 空的「${r.acceptedAnswer}」。以后不再自动匹配；已使用的 ${r.usages.length} 份答卷保持原评分。若要更正旧评分，请使用“撤回引用评分”。` }
}
function withdraw(r: Rule) {
  confirm.value = { label: '撤回引用评分', url: `/api/exams/blank-rules/${r.id}/withdraw-scores`, body: { reopenPublished: true },
    text: `停用「${r.acceptedAnswer}」，并撤回所有仍引用该规则的评分，当前共 ${r.usages.length} 份答卷，可能跨考试。已公布的受影响成绩将撤回，相关空重新待判；其他题评分保留。` }
}
function publish() {
  confirm.value = { label: '公布已完成成绩', url: `${path}/publish`, body: { expectedCount: data.value?.readyCount || 0 },
    text: `将公布 ${data.value?.readyCount || 0} 份所有题目均已评分的答卷。仍有填空或简答待判的答卷不会公布。` }
}
async function execute() {
  if (!confirm.value || busy.value) return
  const action = confirm.value; busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api<{ affected?: number } | undefined>(action.url, { method: 'POST', body: JSON.stringify(action.body) })
    notice.value = `${action.label}已完成${result?.affected != null ? `，实际影响 ${result.affected} 份作答` : ''}。`
    confirm.value = null; await load(); emit('changed')
  } catch (e) {
    const failure = e instanceof Error ? e.message : '操作失败，请刷新后重试'
    confirm.value = null; await load(); error.value = failure
  } finally { busy.value = false }
}
function time(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
onMounted(load)
</script>

<template>
  <section class="panel blank-review" aria-labelledby="blank-title" :aria-busy="busy || loading">
    <header class="blank-head">
      <div><h2 id="blank-title">集中阅卷与成绩公布</h2><p>填空新答案按题目和空位合并；简答在下方逐点复核。全部评分完成后统一公布。</p></div>
      <button class="secondary-button" :disabled="busy || loading" @click="load">刷新答案</button>
    </header>
    <div v-if="notice" class="notice" role="status">{{ notice }}</div>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="loading && !data" class="empty">正在整理填空作答…</div>
    <template v-if="data">
      <div class="blank-summary"><span><strong>{{ data.groups.length }}</strong> 组新答案 · <strong>{{ data.pendingSlots }}</strong> 个待判空</span><div><span>{{ data.readyCount }} 份答卷可公布</span><button class="primary-button" :disabled="busy || loading || !data.readyCount" @click="publish">公布已完成成绩</button></div></div>
      <nav class="blank-tabs" aria-label="填空阅卷内容">
        <button v-for="entry in ([['pending', '待判答案'], ['history', '判定记录'], ['rules', '可接受答案']] as const)" :key="entry[0]" :class="{ selected: tab === entry[0] }" :aria-pressed="tab === entry[0]" :disabled="busy" @click="tab = entry[0]; confirm = null">{{ entry[1] }}</button>
      </nav>
      <section v-if="confirm" class="blank-confirm" role="region" aria-label="确认批量操作">
        <strong>{{ confirm.label }}</strong><p>{{ confirm.text }}</p>
        <div class="blank-actions"><button class="primary-button" :disabled="busy || loading" @click="execute">{{ busy ? '正在保存…' : '确认' + confirm.label }}</button><button class="secondary-button" :disabled="busy" @click="confirm = null">取消</button></div>
      </section>
      <template v-if="tab === 'pending'">
        <label class="blank-search">查找题目或答案<input v-model="search" type="search" placeholder="输入题干或新答案" @input="page = 1" /></label>
        <div v-if="!filtered.length" class="empty"><strong>{{ data.groups.length ? '没有匹配的答案组' : '暂无待判的新答案' }}</strong><p>{{ data.groups.length ? '换个关键词或清空搜索。' : '已匹配答案自动评分；简答题可在下方集中阅卷。' }}</p></div>
        <article v-for="g in visible" :key="`${g.questionId}-${g.version}-${g.slot}-${g.submitted}`" class="blank-group">
          <div class="group-meta"><span>题目 #{{ g.questionId }} · 第 {{ g.slot }} 空</span><strong>{{ g.count }} 人相同答案</strong></div>
          <h3>{{ g.stem }}</h3>
          <div class="blank-compare"><div><span>本空标准答案</span><p>{{ g.reference.split('~').join(' / ') }}</p></div><div class="submitted"><span>待判新答案</span><p>{{ g.submitted }}</p></div></div>
          <div class="blank-actions"><button class="primary-button" :disabled="busy || loading || !!confirm" @click="decide(g, true, true)">判对并加入答案库</button><button class="secondary-button" :disabled="busy || loading || !!confirm" @click="decide(g, true, false)">仅本次判对</button><button class="secondary-button reject" :disabled="busy || loading || !!confirm" @click="decide(g, false, false)">判错</button></div>
        </article>
        <div v-if="filtered.length" class="blank-pagination"><button class="secondary-button" :disabled="page <= 1 || busy" @click="page--">上一页</button><span>{{ Math.min(page, pages) }} / {{ pages }}</span><button class="secondary-button" :disabled="page >= pages || busy" @click="page++">下一页</button></div>
      </template>
      <template v-else-if="tab === 'history'">
        <div v-if="!data.history.length" class="empty">本场还没有批量判定记录。</div>
        <article v-for="h in data.history" :key="h.id" class="blank-group">
          <div class="group-meta"><span>第 {{ h.slot }} 空 · {{ h.count }} 人</span><strong>{{ h.revokedAt ? '已撤销' : h.correct ? '判对' : '判错' }}{{ h.ruleId ? ' · 已收录表达' : '' }}</strong></div>
          <h3>{{ h.stem }}</h3><p class="answer-text">{{ h.submitted }}</p><p class="blank-muted">{{ time(h.at) }} · {{ h.actor }}</p>
          <button v-if="!h.revokedAt" class="secondary-button" :disabled="busy || loading || !!confirm" @click="revoke(h)">撤销本次判定</button>
        </article>
      </template>
      <template v-else>
        <p class="blank-muted">显示本场涉及题目的历史收录表达。仅相同题目版本与空位可自动匹配；停用不会自动修改旧成绩。</p>
        <div v-if="!data.rules.length" class="empty">暂无人工收录答案。判对时选择“加入答案库”即可积累。</div>
        <article v-for="r in data.rules" :key="r.id" class="blank-group">
          <div class="group-meta"><span>第 {{ r.slot }} 空 · 规则 #{{ r.id }}</span><strong>{{ r.active ? '已启用' : '已停用' }}</strong></div>
          <h3>{{ r.stem }}</h3><p class="answer-text">{{ r.acceptedAnswer }}</p><p class="blank-muted">{{ time(r.at) }} · {{ r.actor }}</p>
          <details><summary>查看受影响答卷（{{ r.usages.length }} 份）</summary><ul class="usage-list"><li v-for="u in r.usages" :key="u.attemptId">{{ u.examTitle }} · 答卷 #{{ u.attemptId }} · {{ u.published ? '已公布' : '未公布' }}</li></ul></details>
          <div class="blank-actions"><button v-if="r.active" class="secondary-button" :disabled="busy || loading || !!confirm" @click="disable(r)">停用答案</button><button v-if="r.usages.length" class="secondary-button reject" :disabled="busy || loading || !!confirm" @click="withdraw(r)">撤回引用评分</button></div>
        </article>
      </template>
    </template>
  </section>
</template>

<style scoped>
.blank-review{margin:20px 0;padding:24px;min-width:0}.blank-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.blank-head h2{font-size:20px;margin:0}.blank-head p,.blank-muted{font-size:13px;color:#627b8e;line-height:1.7}.blank-summary{display:flex;justify-content:space-between;gap:16px;align-items:center;padding:16px 0;border-bottom:1px solid #dce5ec;font-size:14px}.blank-summary>div{display:flex;gap:14px;align-items:center}.blank-summary strong{color:#155e68;font-size:20px}.blank-tabs{display:flex;gap:4px;border-bottom:1px solid #dce5ec;margin:16px 0}.blank-tabs button{padding:12px 16px;background:none;border:0;border-bottom:3px solid transparent;color:#627b8e;font:inherit;cursor:pointer}.blank-tabs .selected{border-color:#18746e;color:#145b56;font-weight:700}.blank-search{display:grid;gap:8px;max-width:440px;font-size:13px}.blank-search input{padding:10px 12px;border:1px solid #bdcdd8;border-radius:6px;font:inherit;min-width:0}.blank-group{border-bottom:1px solid #dce5ec;padding:22px 0;min-width:0}.group-meta{display:flex;justify-content:space-between;gap:12px;color:#5e788c;font-size:12px}.group-meta strong{color:#7c5a20}.blank-group h3{font-size:15px;font-weight:600;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;margin:10px 0}.blank-compare{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:12px 0 16px}.blank-compare>div{padding:14px;background:#f4f7fa;border-radius:7px;min-width:0}.blank-compare .submitted{background:#fff9ed;border:1px solid #ecdfc4}.blank-compare span{font-size:12px;color:#647e90}.blank-compare p,.answer-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:8px 0 0;font-size:15px}.blank-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:12px}.secondary-button{margin:0}.reject{color:#a03c31;border-color:#ddc5c1}.blank-confirm{position:sticky;top:8px;z-index:2;background:#f0f7f5;border:1px solid #8eafa4;padding:18px;border-radius:8px;box-shadow:0 4px 16px #183d2814}.blank-confirm p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7;font-size:14px;max-height:190px;overflow:auto}.blank-pagination{display:flex;justify-content:center;align-items:center;gap:16px;padding-top:18px;font-size:13px}.usage-list{max-height:240px;overflow:auto;font-size:13px;line-height:1.9;padding-left:22px;overflow-wrap:anywhere}summary{cursor:pointer;font-size:13px;margin:12px 0}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid #40a79f;outline-offset:3px}@media(max-width:720px){.blank-review{padding:16px}.blank-head,.blank-summary{align-items:flex-start;flex-direction:column}.blank-summary>div{width:100%;justify-content:space-between;flex-wrap:wrap}.blank-compare{grid-template-columns:1fr}.blank-actions button{flex:1;min-width:120px}.blank-tabs button{padding:12px 10px}.group-meta{flex-wrap:wrap}.blank-confirm{position:static}.blank-search{max-width:none}}
</style>
