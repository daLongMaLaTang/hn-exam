<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, hasRole } from '../api'
import { useRoute } from 'vue-router'
import ShortAnswerRubric from '../components/ShortAnswerRubric.vue'
const rubricId=ref<number|null>(null), route=useRoute()
type Question = { id: number; type: string; stem: string; answer: string; optionsJson: string | null; explanation: string; status: string; courseCode: string; chapter: string; source: string; difficulty: number }
type Input = Omit<Question, 'id'>
type ExcelImportResult = { total: number; success: number; failed: number; failures: { row: number; reason: string }[] }
const names: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题', FILL_BLANK: '填空题', SHORT_ANSWER: '简答题', CALCULATION: '计算题', IMAGE_CASE: '案例题', ESSAY: '大题' }
const statuses: Record<string, string> = { PENDING_REVIEW: '待审核', ACTIVE: '已启用', REJECTED: '审核未通过', DISABLED: '已停用' }
const questions = ref<Question[]>([]), error = ref(''), message = ref(''), loading = ref(false), saving = ref(false)
const query = ref(''), typeFilter = ref(''), statusFilter = ref(''), courseFilter = ref(''), chapterFilter = ref(''), page = ref(1)
const editorOpen = ref(false), editingId = ref<number | null>(null), optionsText = ref(''), importing = ref(false), importProgress = ref(''), importFailures = ref<string[]>([]), checked = ref<number[]>([]), batchBusy = ref(false)
const batchChapter = ref('')
const deletingCourse=ref(''),deleteConfirmation=ref(''),deleting=ref(false)
async function deleteCourse(){if(deleteConfirmation.value!==deletingCourse.value)return;deleting.value=true;error.value='';try{const result=await api<{deleted:number}>('/api/questions/course',{method:'DELETE',body:JSON.stringify({courseCode:deletingCourse.value})});message.value='已删除课程题库，共 '+result.deleted+' 道题，历史考试记录保留。';deletingCourse.value='';courseFilter.value='';checked.value=[];editorOpen.value=false;await load()}catch(e){error.value=reason(e)}finally{deleting.value=false}}
const blank = (): Input => ({ type: 'SINGLE_CHOICE', stem: '', answer: '', optionsJson: '[]', explanation: '', status: 'PENDING_REVIEW', courseCode: '安全规程', chapter: '未分章', source: '', difficulty: 2 })
const form = reactive<Input>(blank())
const courses = computed(() => [...new Set(questions.value.map(q => q.courseCode))].sort())
const chapters = computed(() => [...new Set(questions.value.filter(q => !courseFilter.value || q.courseCode === courseFilter.value).map(q => q.chapter || '未分章'))].sort())
const courseStats = computed(() => courses.value.map(course => ({ course, count: questions.value.filter(q => q.courseCode === course).length, active: questions.value.filter(q => q.courseCode === course && q.status === 'ACTIVE').length })))
const filtered = computed(() => questions.value.filter(q => (!typeFilter.value || q.type === typeFilter.value) && (!statusFilter.value || q.status === statusFilter.value) && (!courseFilter.value || q.courseCode === courseFilter.value) && (!chapterFilter.value || (q.chapter || '未分章') === chapterFilter.value) && `${q.stem} ${q.source || ''}`.includes(query.value.trim())))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 20)))
const visible = computed(() => filtered.value.slice((Math.min(page.value, totalPages.value) - 1) * 20, Math.min(page.value, totalPages.value) * 20))
const isChoice = computed(() => ['SINGLE_CHOICE', 'MULTIPLE_CHOICE'].includes(form.type))
function statusTone(status: string) { return status === 'ACTIVE' ? 'success' : status === 'PENDING_REVIEW' ? 'warning' : status === 'REJECTED' ? 'danger' : '' }
function reason(e: unknown) { return e instanceof Error ? e.message : '操作失败，请重试' }
async function load() { loading.value = true; try { questions.value = await api<Question[]>('/api/questions') } catch (e) { error.value = reason(e) } finally { loading.value = false } }
function parseOptions(value: string | null) { try { const p = JSON.parse(value || '[]'); return Array.isArray(p) ? p.map(String) : [] } catch { return [] } }
function edit(q?: Question) { editingId.value = q?.id ?? null; Object.assign(form, q ? { ...q } : blank()); optionsText.value = q ? parseOptions(q.optionsJson).join('\n') : ''; editorOpen.value = true; error.value = ''; message.value = '' }
function validate(q: Input) {
  if (!names[q.type]) throw new Error('题型不正确')
  if (!q.stem?.trim() || !q.answer?.trim() || !q.courseCode?.trim()) throw new Error('请填写题干、参考答案和课程分类')
  if (!Number.isInteger(Number(q.difficulty)) || q.difficulty < 1 || q.difficulty > 5) throw new Error('难度须为 1～5 的整数')
  if (!statuses[q.status]) throw new Error('题目状态不正确')
  if (['SINGLE_CHOICE', 'MULTIPLE_CHOICE'].includes(q.type)) {
    const choices = parseOptions(q.optionsJson)
    if (choices.length < 2 || choices.length > 10 || new Set(choices).size !== choices.length || choices.some(x => !x.trim())) throw new Error('选择题需要 2～10 个不重复的选项')
    if (choices.some(x => /[,，;；\n]/.test(x))) throw new Error('选项不能含逗号、分号或换行，请改用顿号')
    const answers = q.type === 'MULTIPLE_CHOICE' ? q.answer.split(/[,，;；\n]/).map(x => x.trim()).filter(Boolean) : [q.answer.trim()]
    if (answers.some(x => !choices.includes(x)) || new Set(answers).size !== answers.length || (q.type === 'MULTIPLE_CHOICE' && answers.length < 2)) throw new Error('答案须填写完整选项文本；多选至少两项，用逗号分隔')
  }
  if (q.type === 'TRUE_FALSE' && !['正确', '错误'].includes(q.answer)) throw new Error('判断题答案请选择“正确”或“错误”')
}
async function save() {
  saving.value = true; error.value = ''; message.value = ''
  try { const q = { ...form, optionsJson: isChoice.value ? JSON.stringify(optionsText.value.split('\n').map(x => x.trim()).filter(Boolean)) : null }; validate(q)
    await api(`/api/questions${editingId.value ? '/' + editingId.value : ''}`, { method: editingId.value ? 'PUT' : 'POST', body: JSON.stringify(q) }); editorOpen.value = false; message.value = '试题已保存'; await load()
  } catch (e) { error.value = reason(e) } finally { saving.value = false }
}
async function setStatus(q: Question) { error.value = ''; const status = q.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; try { await api(`/api/questions/${q.id}/status?status=${status}`, { method: 'PATCH' }); q.status = status; message.value = status === 'ACTIVE' ? '题目已启用' : '题目已停用' } catch (e) { error.value = reason(e) } }
function checkPage(enabled: boolean) { const ids = visible.value.map(q => q.id); checked.value = enabled ? [...new Set([...checked.value, ...ids])] : checked.value.filter(id => !ids.includes(id)) }
async function batchStatus(status: string) {
  batchBusy.value = true; error.value = ''; let success = 0; const failures: string[] = []
  for (const id of [...checked.value]) { try { await api(`/api/questions/${id}/status?status=${status}`, { method: 'PATCH' }); success++; checked.value = checked.value.filter(x => x !== id) } catch (e) { failures.push(`#${id}：${reason(e)}`) } }
  message.value = `批量操作完成：成功 ${success} 道，失败 ${failures.length} 道`; if (failures.length) error.value = failures.join('；'); await load(); batchBusy.value = false
}
async function assignChapter() {
  if (!checked.value.length || !batchChapter.value.trim()) { error.value = '请先选择试题并填写章节名称'; return }
  batchBusy.value = true; error.value = ''; message.value = ''
  try {
    await api('/api/questions/batch/chapter', { method: 'PATCH', body: JSON.stringify({ questionIds: checked.value, chapter: batchChapter.value.trim() }) })
    message.value = `已将 ${checked.value.length} 道题归入“${batchChapter.value.trim()}”`; checked.value = []; batchChapter.value = ''; await load()
  } catch (e) { error.value = reason(e) } finally { batchBusy.value = false }
}
function download(name: string, content: string | Blob, type: string) { const link = document.createElement('a'); const blob = content instanceof Blob ? content : new Blob(['\uFEFF', content], { type }); link.href = URL.createObjectURL(blob); link.download = name; link.click(); URL.revokeObjectURL(link.href) }
const example = { type: 'SINGLE_CHOICE', stem: '【示例，请替换】作业前应先做什么？', options: ['确认安全措施', '立即开始作业'], answer: '确认安全措施', explanation: '作业前应完成安全确认。', courseCode: '安全规程', chapter: '第一章 通用规定', difficulty: 2, status: 'PENDING_REVIEW', source: '培训教材' }
function template(kind: 'json' | 'csv') {
  if (kind === 'json') download('题库导入模板.json', JSON.stringify([example], null, 2), 'application/json;charset=utf-8')
  else download('题库导入模板.csv', 'type,stem,options,answer,explanation,courseCode,chapter,difficulty,status,source\r\n' + Object.values({ ...example, options: example.options.join('|') }).map(x => '"' + String(x).replace(/"/g, '""') + '"').join(','), 'text/csv;charset=utf-8')
}
function parseCsv(text: string) {
  const rows: string[][] = []; let row: string[] = [], cell = '', quoted = false
  for (let i = 0; i < text.length; i++) { const c = text[i]; if (c === '"') { if (quoted && text[i + 1] === '"') { cell += '"'; i++ } else quoted = !quoted } else if (c === ',' && !quoted) { row.push(cell); cell = '' } else if ((c === '\n' || c === '\r') && !quoted) { if (c === '\r' && text[i + 1] === '\n') i++; row.push(cell); if (row.some(x => x.trim())) rows.push(row); row = []; cell = '' } else cell += c }
  if (quoted) throw new Error('CSV 中存在未闭合的引号'); row.push(cell); if (row.some(x => x.trim())) rows.push(row)
  const headers = rows.shift()?.map(x => x.trim()) || []; if (!headers.includes('type') || !headers.includes('stem') || !headers.includes('answer')) throw new Error('CSV 缺少 type、stem 或 answer 列，请使用模板')
  return rows.map(r => Object.fromEntries(headers.map((h, i) => [h, r[i] ?? ''])))
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  importing.value = true; error.value = ''; importFailures.value = []; importProgress.value = ''
  try { if (file.size > 5 * 1024 * 1024) throw new Error('文件不能超过 5 MB'); const text = (await file.text()).replace(/^\uFEFF/, '')
    const records: Record<string, unknown>[] = file.name.toLowerCase().endsWith('.json') ? JSON.parse(text) : parseCsv(text)
    if (!Array.isArray(records) || !records.length || records.length > 2000) throw new Error('文件须包含 1～2000 道题目')
    let success = 0
    for (let i = 0; i < records.length; i++) { try { const r = records[i], options = Array.isArray(r.options) ? r.options : String(r.options || '').split('|').filter(Boolean)
      const q: Input = { type: String(r.type || ''), stem: String(r.stem || ''), answer: String(r.answer || ''), optionsJson: r.optionsJson ? String(r.optionsJson) : JSON.stringify(options), explanation: String(r.explanation || ''), courseCode: String(r.courseCode || '安全规程'), chapter: String(r.chapter || '未分章'), source: String(r.source || file.name), difficulty: Number(r.difficulty || 2), status: String(r.status || 'PENDING_REVIEW') }
      validate(q); await api('/api/questions', { method: 'POST', body: JSON.stringify(q) }); success++
    } catch (e) { importFailures.value.push(`第 ${i + 1} 道题：${reason(e)}`) }; importProgress.value = `已处理 ${i + 1}/${records.length} 道，成功 ${success} 道，失败 ${importFailures.value.length} 道` }
    await load(); message.value = '导入完成。成功题目已保存；请修正失败题目后单独重试。'
  } catch (e) { error.value = reason(e) } finally { importing.value = false; input.value = '' }
}
async function downloadExcelTemplate() {
  error.value = ''
  try {
    const token = localStorage.getItem('hn_exam_token')
    const response = await fetch('/api/questions/excel-template', { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!response.ok) throw new Error((await response.json().catch(() => ({})) as { message?: string }).message || '模板下载失败')
    download('题库导入模板.xlsx', await response.blob(), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  } catch (e) { error.value = reason(e) }
}
async function importExcel(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  importing.value = true; error.value = ''; message.value = ''; importFailures.value = []; importProgress.value = '正在读取并校验 Excel…'
  try {
    const body = new FormData(); body.append('file', file)
    const result = await api<ExcelImportResult>('/api/questions/import-excel', { method: 'POST', body })
    importProgress.value = `已处理 ${result.total} 道，成功 ${result.success} 道，失败 ${result.failed} 道`
    importFailures.value = result.failures.map(item => `第 ${item.row} 行：${item.reason}`)
    message.value = result.failed ? 'Excel 导入完成。正确题目已保存，请根据失败原因修改后重试。' : 'Excel 题库已全部导入，并按课程和章节归入对应题库。'
    await load()
  } catch (e) { error.value = reason(e); importProgress.value = '' }
  finally { importing.value = false; input.value = '' }
}
async function downloadWordTemplate() {
  error.value=''
  try {
    const response=await fetch('/api/questions/word-template',{headers:{Authorization:`Bearer ${localStorage.getItem('hn_exam_token')||''}`}})
    if(!response.ok)throw new Error('Word 模板下载失败，请重试')
    download('题库导入模板.docx',await response.blob(),'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
  }catch(e){error.value=reason(e)}
}
async function importWord(event:Event) {
  const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return
  importing.value=true;error.value='';message.value='';importFailures.value=[];importProgress.value='正在读取并校验 Word…'
  try {
    const body=new FormData();body.append('file',file)
    const result=await api<ExcelImportResult>('/api/questions/import-word',{method:'POST',body})
    importProgress.value=`Word 导入完成：成功 ${result.success} 道，失败 ${result.failed} 道`
    importFailures.value=result.failures.map(f=>`第 ${f.row} 题：${f.reason}`)
    message.value='成功题目已保存为待审核，请核对题干和答案后启用。';await load()
  }catch(e){error.value=reason(e);importProgress.value=''}finally{importing.value=false;input.value=''}
}
onMounted(async()=>{await load();const id=Number(route.query.rubric);if(hasRole('SYSTEM_ADMIN')&&questions.value.some(q=>q.id===id&&q.type==='SHORT_ANSWER'))rubricId.value=id})
</script>
<template>
  <div class="page-head"><div><span class="eyebrow dark">QUESTION BANK</span><h1>题库管理</h1><p>录入、审核和维护各类试题；客观题自动判分，简答题可建立评分标准并逐点辅助阅卷。</p></div><button class="primary-button small" @click="edit()">＋ 添加试题</button></div>
  <div v-if="error" class="notice error" role="alert">{{ error }}</div><div v-if="message" class="notice" role="status">{{ message }}</div>
  <section v-if="courseStats.length" class="course-strip" aria-label="课程题库概览"><button :class="{ active: !courseFilter }" @click="courseFilter = ''; page = 1"><strong>全部课程</strong><span>{{ questions.length }} 道</span></button><button v-for="item in courseStats" :key="item.course" :class="{ active: courseFilter === item.course }" @click="courseFilter = item.course; page = 1"><strong>{{ item.course }}</strong><span>{{ item.count }} 道 · 已启用 {{ item.active }}</span></button></section>
  <div v-if="courseFilter && hasRole('SYSTEM_ADMIN','TRAINING_ADMIN')" class="notice">当前课程：{{courseFilter}} <button class="link-button" @click="deletingCourse=courseFilter;deleteConfirmation=''">删除整门课程题库</button></div>
  <article v-if="deletingCourse" class="panel" aria-label="确认删除课程题库"><h3>删除“{{deletingCourse}}”题库</h3><p>该课程所有试题将从题库和刷题列表移除，不能用于新考试。历史试卷、答卷及成绩保留。未发布草稿需重新选题。</p><label>输入课程名称确认 <input v-model="deleteConfirmation" :disabled="deleting" aria-label="输入课程名称确认" /></label><div class="actions"><button class="secondary-button" :disabled="deleting" @click="deletingCourse=''">保留题库</button><button class="primary-button" :disabled="deleting || deleteConfirmation!==deletingCourse" @click="deleteCourse">{{deleting?'删除中…':'确认删除题库'}}</button></div></article>
  <article v-if="editorOpen" class="panel"><div class="panel-head"><h3>{{ editingId ? '编辑 / 转换题型 #' + editingId : '添加试题' }}</h3><button class="link-button" @click="editorOpen = false">收起</button></div><form class="editor" @submit.prevent="save">
    <div class="form-grid"><label>题型（可转换）<select v-model="form.type" @change="form.answer = ''"><option v-for="(label, value) in names" :key="value" :value="value">{{ label }}</option></select></label><label>课程分类<input v-model="form.courseCode" required maxlength="100" /></label><label>章节<input v-model="form.chapter" required maxlength="100" placeholder="例如：第一章 通用规定" /></label><label>难度<select v-model.number="form.difficulty"><option v-for="n in 5" :key="n" :value="n">{{ n }} / 5</option></select></label><label>状态<select v-model="form.status"><option v-for="(label, value) in statuses" :key="value" :value="value">{{ label }}</option></select></label></div>
    <p v-if="editingId" class="type-convert-note">需要修改题型时，直接选择“单选题 / 多选题 / 填空题”，再按当前题型重新确认答案后保存。</p><label>题干<textarea v-model="form.stem" required rows="3" maxlength="20000" /></label><label v-if="isChoice">备选项（每行一项，不加 A/B 编号；选项中的逗号请改为顿号）<textarea v-model="optionsText" required rows="4" placeholder="第一项内容&#10;第二项内容" /></label>
    <label>{{ ['SHORT_ANSWER','CALCULATION','IMAGE_CASE','ESSAY'].includes(form.type) ? '参考答案 / 评分要点' : '参考答案' }}<select v-if="form.type === 'TRUE_FALSE'" v-model="form.answer" required><option value="" disabled>请选择</option><option>正确</option><option>错误</option></select><textarea v-else v-model="form.answer" required :rows="['SHORT_ANSWER','CALCULATION','IMAGE_CASE','ESSAY'].includes(form.type) ? 5 : 2" :placeholder="form.type === 'FILL_BLANK' ? '按顺序填写每空参考答案，用分号分隔，例如：人身；电网；设备' : form.type === 'MULTIPLE_CHOICE' ? '填写多个完整选项文本，用逗号分隔' : ['SHORT_ANSWER','CALCULATION','IMAGE_CASE','ESSAY'].includes(form.type) ? '填写判卷时使用的参考答案、关键步骤或评分要点' : '选择题请填写完整选项文本'" /></label>
    <p v-if="form.type === 'FILL_BLANK'" class="hint">考试填空先匹配可接受答案，未匹配的非空作答进入集中阅卷；判对后可收录供下次使用。每空可填写多个可接受答案，以 ~ 分隔，例如：断开~切断；接地。不填写评分说明；按题目顺序用分号分隔。也支持每行一个答案或用 | 分隔，此时一个空中的逗号会保留。每空均分，按答对比例计分，整题得分四舍五入取整数。</p><label>答案解析<textarea v-model="form.explanation" rows="2" /></label><label>来源<input v-model="form.source" maxlength="255" /></label><p v-if="!['SINGLE_CHOICE','MULTIPLE_CHOICE','TRUE_FALSE','FILL_BLANK'].includes(form.type)" class="hint">此题为主观题。考生交卷后进入待判卷，由超级管理员逐题给分；参考答案和评分要点仅在判卷时显示。</p><div class="actions"><button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存试题' }}</button><button type="button" class="secondary-button" @click="editorOpen = false">取消</button></div>
  </form></article>
  <ShortAnswerRubric v-if="rubricId" :key="rubricId" :question-id="rubricId" @close="rubricId=null" />
  <details class="panel disclosure-panel"><summary><span><strong>批量导入题库</strong><small>推荐使用 Excel 模板；每行可指定安规、流体力学、泵与风机等课程</small></span></summary><div class="disclosure-content"><div class="import-feature"><div><span class="status-pill info">推荐</span><h3>Excel 自动导入</h3><p class="hint">支持选项 A～F 和答案字母。系统逐行校验，正确行直接保存，错误行单独提示。</p></div><div class="actions"><button class="secondary-button" :disabled="importing" @click="downloadExcelTemplate">下载 Excel 模板</button><label class="primary-button file-button">{{ importing ? '正在导入…' : '选择 Excel 导入' }}<input type="file" accept=".xlsx" :disabled="importing" @change="importExcel" /></label></div></div><div class="import-feature"><div><h3>Word 题库导入</h3><p class="hint">使用 .docx 模板，每题一个表格。支持纯文字题目；图片、扫描件和任意排版暂不支持。导入后待审核。</p></div><div class="actions"><button class="secondary-button" :disabled="importing" @click="downloadWordTemplate">下载 Word 模板</button><label class="primary-button file-button">选择 Word 导入<input type="file" accept=".docx" :disabled="importing" @change="importWord" /></label></div></div><details class="legacy-import"><summary>使用 CSV / JSON 导入</summary><p class="hint">CSV 的 options 列用 | 分隔选项；默认导入为待审核。</p><div class="actions"><button class="secondary-button" @click="template('csv')">下载 CSV 模板</button><button class="secondary-button" @click="template('json')">下载 JSON 模板</button><label class="secondary-button file-button">{{ importing ? '导入中…' : '选择 CSV / JSON' }}<input type="file" accept=".csv,.json" :disabled="importing" @change="importFile" /></label></div></details><p v-if="importProgress" class="import-result" role="status">{{ importProgress }}</p><details v-if="importFailures.length" class="failure-list"><summary>查看 {{ importFailures.length }} 条失败原因</summary><ul><li v-for="(item, i) in importFailures" :key="i">{{ item }}</li></ul></details></div></details>
  <article class="panel table-panel"><div class="toolbar"><div><strong>试题列表</strong><small>筛选后 {{ filtered.length }} 道 / 全部 {{ questions.length }} 道</small></div><div class="filter-row"><select v-model="typeFilter" aria-label="按题型筛选" @change="page = 1"><option value="">全部题型</option><option v-for="(label, value) in names" :key="value" :value="value">{{ label }}</option></select><select v-model="statusFilter" aria-label="按状态筛选" @change="page = 1"><option value="">全部状态</option><option v-for="(label, value) in statuses" :key="value" :value="value">{{ label }}</option></select><select v-model="courseFilter" aria-label="按课程筛选" @change="page = 1"><option value="">全部课程</option><option v-for="c in courses" :key="c">{{ c }}</option></select><input v-model="query" class="search" placeholder="搜索题干、来源" aria-label="搜索试题" @input="page = 1" /></div></div>
    <div class="chapter-filter"><strong>章节</strong><select v-model="chapterFilter" aria-label="按章节筛选" @change="page = 1"><option value="">全部章节</option><option v-for="chapter in chapters" :key="chapter" :value="chapter">{{ chapter }}</option></select></div>
    <div v-if="questions.length" class="batch-toolbar"><div><strong>批量操作</strong><span>已选 {{ checked.length }} 道</span></div><div class="chapter-assign"><input v-model="batchChapter" maxlength="100" placeholder="输入章节，如：第一章 通用规定" aria-label="批量设置章节" /><button class="secondary-button" :disabled="batchBusy || !checked.length || !batchChapter.trim()" @click="assignChapter">归入章节</button></div><div class="actions"><button class="secondary-button" :disabled="batchBusy || !checked.length" @click="batchStatus('ACTIVE')">{{ batchBusy ? '处理中…' : '批量审核启用' }}</button><button class="secondary-button" :disabled="batchBusy || !checked.length" @click="batchStatus('DISABLED')">批量停用</button></div></div>
    <div v-if="loading" class="empty">正在加载题库…</div><div v-else-if="!filtered.length" class="empty"><h3>{{ questions.length ? '没有符合条件的试题' : '还没有试题' }}</h3><p>可以添加试题或使用上方模板批量导入。</p></div><div v-else class="table-wrap"><table><thead><tr><th><input type="checkbox" aria-label="选择本页题目" :checked="visible.length > 0 && visible.every(q => checked.includes(q.id))" @change="checkPage(($event.target as HTMLInputElement).checked)" /></th><th>编号 / 题型</th><th>题干</th><th>课程 / 章节</th><th>难度</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="q in visible" :key="q.id"><td><input v-model="checked" type="checkbox" :value="q.id" :aria-label="'选择题目 ' + q.id" /></td><td>#{{ q.id }} <span class="tag muted">{{ names[q.type] }}</span></td><td class="question-stem">{{ q.stem }}</td><td><strong>{{ q.courseCode }}</strong><div class="chapter-name">{{ q.chapter || '未分章' }}</div></td><td>{{ q.difficulty }}</td><td><span class="status-pill" :class="statusTone(q.status)">{{ statuses[q.status] || q.status }}</span></td><td class="nowrap"><button v-if="q.type==='SHORT_ANSWER' && hasRole('SYSTEM_ADMIN')" class="link-button" @click="rubricId=q.id;editorOpen=false">评分标准</button><button class="link-button" @click="edit(q)">编辑</button><button class="link-button" :disabled="batchBusy" @click="setStatus(q)">{{ q.status === 'ACTIVE' ? '停用' : '启用' }}</button></td></tr></tbody></table></div>
    <div v-if="filtered.length" class="pagination"><button class="secondary-button" :disabled="page <= 1" @click="page--">上一页</button><span>第 {{ Math.min(page, totalPages) }} / {{ totalPages }} 页</span><button class="secondary-button" :disabled="page >= totalPages" @click="page++">下一页</button></div>
  </article>
</template>
<style scoped>
.editor{display:grid;gap:15px;padding-top:18px}.editor label{display:grid;gap:7px;color:#496074;font-size:13px}.editor input,.editor textarea,.editor select{width:100%;font:inherit;padding:10px;border:1px solid #d8e2ea;border-radius:7px;background:#f9fbfc}.form-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.actions .secondary-button,.pagination .secondary-button{margin-top:0}.hint{font-size:12px;color:#6c7e8f;line-height:1.8}.course-strip{display:flex;gap:9px;overflow-x:auto;margin:0 0 18px;padding:2px 1px 7px;scrollbar-width:thin}.course-strip button{min-width:145px;padding:12px 14px;text-align:left;border:1px solid #dce4ea;border-radius:10px;background:#fff;color:#284b67}.course-strip button.active{border-color:#138875;box-shadow:inset 0 -3px 0 #138875}.course-strip strong,.course-strip span{display:block}.course-strip strong{font-size:12px}.course-strip span{margin-top:5px;color:#8da0ae;font-size:10px}.import-feature{display:flex;justify-content:space-between;align-items:center;gap:22px;padding:18px 0 6px}.import-feature h3{margin:9px 0 0;font-size:15px}.legacy-import{margin-top:14px;padding-top:14px;border-top:1px solid #e9eef2}.legacy-import>summary,.failure-list>summary{cursor:pointer;color:#46637a;font-weight:700}.import-result{padding:11px 13px;border-radius:8px;background:#edf7fa;color:#3d687b;font-size:12px}.failure-list{max-height:250px;overflow:auto}.file-button{position:relative;display:inline-flex;align-items:center;justify-content:center;overflow:hidden;font-size:13px;cursor:pointer}.file-button input{position:absolute;inset:0;opacity:0;cursor:pointer;width:100%}.question-stem{min-width:260px;max-width:550px;white-space:pre-wrap;overflow-wrap:anywhere}.nowrap{white-space:nowrap}.filter-row{flex-wrap:wrap}.pagination{display:flex;gap:15px;justify-content:center;align-items:center;padding:18px;font-size:13px}button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:900px){.form-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:600px){.import-feature{display:grid}.import-feature .actions>*{flex:1}.form-grid{grid-template-columns:1fr}}
.type-convert-note{margin:0;padding:10px 12px;border-left:3px solid #138875;border-radius:4px;background:#f0f8f6;color:#46637a;font-size:12px;line-height:1.7}.chapter-filter{display:flex;align-items:center;gap:10px;padding:10px 18px;border-top:1px solid #edf1f4;color:#587084;font-size:12px}.chapter-filter select{min-width:220px}.chapter-name{margin-top:4px;color:#748b9b;font-size:11px}.batch-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px 18px;border-top:1px solid #edf1f4;background:#f9fbfc}.batch-toolbar>div:first-child{display:grid;gap:2px;margin-right:auto}.batch-toolbar>div:first-child strong{font-size:12px}.batch-toolbar>div:first-child span{color:#7c8f9d;font-size:10px}.chapter-assign{display:flex;gap:7px;min-width:320px}.chapter-assign input{min-width:220px;flex:1;border:1px solid #d6e1e8;border-radius:7px;padding:9px;font:inherit}@media(max-width:700px){.batch-toolbar{align-items:stretch}.chapter-assign{min-width:100%;flex-wrap:wrap}.chapter-assign input{min-width:0}.batch-toolbar .actions{width:100%}.batch-toolbar .actions button{flex:1}}</style>

