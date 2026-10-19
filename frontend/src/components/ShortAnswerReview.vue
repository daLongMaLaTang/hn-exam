<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
type Point={key:string;label:string;source:string;weight:number;kind:string;constraint:string;partialAllowed:boolean;critical:boolean}
type Finding={key:string;verdict:string;evidence:string;reason:string}
type Row={attemptId:number;questionId:number;name:string;stem:string;reference:string;answer:string;maximum:number;state:string;suggested:number|null;awarded:number|null;similarity:number|null;revision:number;rubricId:number|null;rubric:{points:Point[];guidance:string}|null;evaluation:{findings:Finding[]}|null;risks:string[];batchEligible:boolean;sampleMessage:string;published:boolean}
type Workspace={rows:Row[];pending:number;suggested:number;reviewRequired:number;accepted:number}
const props=defineProps<{examId:string}>(),emit=defineEmits<{changed:[]}>()
const data=ref<Workspace|null>(null),busy=ref(false),loading=ref(false),error=ref(''),notice=ref(''),progress=ref(''),stopping=ref(false)
const filter=ref('pending'),question=ref(''),page=ref(1),selected=ref<string[]>([]),expanded=ref(''),manual=ref('')
const manualScore=ref<number|''>(''),similarity=ref<number|''>(''),comment=ref('')
const confirm=ref<{label:string;message:string;path:string;body:unknown;generate?:Row[]}|null>(null)
const confirmationPanel=ref<HTMLElement|null>(null)
watch(confirm,async value=>{if(value){await nextTick();confirmationPanel.value?.scrollIntoView({behavior:'smooth',block:'center'})}})
onBeforeUnmount(()=>{stopping.value=true})
const path=`/api/exams/${props.examId}/short-review`
const labels:Record<string,string>={NOT_STARTED:'待生成',NEEDS_RUBRIC:'先确认评分标准',STALE:'标准已更新',RUNNING:'正在评分',FAILED:'生成失败',SUGGESTED:'建议已生成',REVIEW_REQUIRED:'需要复核',ACCEPTED:'已确认'}
const verdicts:Record<string,string>={MET:'满足',PARTIAL:'部分满足',MISSING:'未答到',CONTRADICTED:'与要求相反',UNCERTAIN:'无法确定'}
const key=(r:Row)=>`${r.attemptId}-${r.questionId}`
const target=(r:Row)=>({attemptId:r.attemptId,questionId:r.questionId,revision:r.revision})
const questions=computed(()=>[...new Map((data.value?.rows||[]).map(r=>[r.questionId,r.stem])).entries()])
const filtered=computed(()=>(data.value?.rows||[]).filter(r=>(!question.value||String(r.questionId)===question.value)&&(filter.value==='all'||filter.value==='accepted'&&r.state==='ACCEPTED'||filter.value==='pending'&&r.state!=='ACCEPTED'||filter.value==='review'&&r.risks.length>0&&r.state!=='ACCEPTED')))
const pages=computed(()=>Math.max(1,Math.ceil(filtered.value.length/6)))
const visible=computed(()=>filtered.value.slice((Math.min(page.value,pages.value)-1)*6,Math.min(page.value,pages.value)*6))
const toGenerate=computed(()=>filtered.value.filter(r=>['NOT_STARTED','STALE','FAILED'].includes(r.state)))
const chosen=computed(()=>(data.value?.rows||[]).filter(r=>selected.value.includes(key(r))&&r.batchEligible))
async function load(){loading.value=true;error.value='';try{data.value=await api<Workspace>(path);selected.value=[]}catch(e){error.value=e instanceof Error?e.message:'加载失败'}finally{loading.value=false}}
function findings(r:Row){return r.rubric?.points.map(p=>({point:p,finding:r.evaluation?.findings.find(f=>f.key===p.key)}))||[]}
function askGenerate(rows:Row[]){confirm.value={label:'生成逐点评分',message:`将处理 ${rows.length} 份作答，只发送题干、参考答案、评分标准和作答文本给已配置的AI服务。结果先保存为建议，不会公布成绩。`,path:'',body:{},generate:rows}}
function accept(r?:Row){const rows=r?[r]:chosen.value;confirm.value={label:r?'确认已逐点核对':'批量确认评分',message:r?`确认已检查本题各评分点、原文依据和疑难提示，采用 ${r.suggested} / ${r.maximum} 分。成绩待统一公布。`:`将采用已选 ${rows.length} 份普通答案的AI建议。需要复核的答案不会加入本次操作，成绩暂不公布。`,path:`${path}/accept`,body:{targets:rows.map(target),individuallyReviewed:!!r}}}
function undo(r:Row){confirm.value={label:'撤销简答确认',message:`撤销答卷 #${r.attemptId} 本题已确认评分。${r.published?'此答卷的已公布成绩将撤回，':''}其他题评分保留，本题重新待判，完成后需统一公布。`,path:`${path}/undo`,body:{target:target(r),reopenPublished:true}}}
function edit(r:Row){manual.value=key(r);expanded.value=key(r);manualScore.value=r.suggested??'';similarity.value=r.similarity??'';comment.value=''}
function saveManual(r:Row){if(manualScore.value===''||similarity.value===''||!comment.value.trim()){error.value='请填写得分、语义相似度和判分说明';return}confirm.value={label:'保存人工评分',message:`将本题保存为 ${manualScore.value} / ${r.maximum} 分，成绩待统一公布。`,path:`${path}/manual`,body:{target:target(r),points:Number(manualScore.value),semanticSimilarity:Number(similarity.value),reason:comment.value}}}
async function execute(){
  if(!confirm.value||busy.value)return;const action=confirm.value;confirm.value=null;busy.value=true;error.value='';notice.value='';stopping.value=false
  try{
    if(action.generate){let done=0,failed=0;for(const r of action.generate){if(stopping.value)break;progress.value=`正在处理 ${done+1} / ${action.generate.length} 份作答`;try{await api(`/api/exams/attempts/${r.attemptId}/short-ai/${r.questionId}`,{method:'POST'})}catch{failed++}done++}notice.value=`已处理 ${done} 份，其中 ${failed} 份未成功；失败项可重试或人工评分。`}
    else{await api(action.path,{method:'POST',body:JSON.stringify(action.body)});notice.value=action.label+'已完成。';manual.value=''}
    await load();emit('changed')
  }catch(e){const message=e instanceof Error?e.message:'操作失败，请刷新后重试';await load();error.value=message}
  finally{busy.value=false;progress.value=''}
}
onMounted(load)
</script>

<template>
  <section v-if="loading||error||data?.rows.length" class="panel short-review" aria-label="简答逐点阅卷" :aria-busy="busy||loading">
    <header><div><h2>简答逐点阅卷</h2><p>按评分点核对依据，先抽查普通答案，再批量确认；疑难答案单独处理。</p></div><button class="secondary-button" :disabled="busy||loading" @click="load">刷新简答</button></header>
    <div v-if="error" class="notice error" role="alert">{{error}}</div><div v-if="notice" class="notice" role="status">{{notice}}</div>
    <div v-if="progress" class="notice" role="status">{{progress}} <button class="link-button" :disabled="stopping" @click="stopping=true">{{stopping?'本条完成后停止':'停止后续生成'}}</button></div>
    <p v-if="loading&&!data">正在加载简答作答…</p>
    <template v-if="data">
      <div class="summary"><span><strong>{{data.pending}}</strong> 份待确认 · <strong>{{data.reviewRequired}}</strong> 份有疑难提示 · {{data.accepted}} 份已确认</span><p>所有题目评分完成后，在上方点击“公布已完成成绩”。</p></div>
      <div class="filters"><label>按题目查看<select v-model="question" @change="page=1"><option value="">全部简答题</option><option v-for="[id,stem] in questions" :key="id" :value="String(id)">#{{id}} {{stem.slice(0,45)}}</option></select></label><label>评分状态<select v-model="filter" @change="page=1"><option value="pending">待确认</option><option value="review">需要复核</option><option value="accepted">已确认</option><option value="all">全部作答</option></select></label></div>
      <div class="actions"><button class="primary-button" :disabled="busy||loading||!toGenerate.length" @click="askGenerate(toGenerate)">生成当前筛选的未评分建议（{{toGenerate.length}}）</button><button class="secondary-button" :disabled="busy||loading||!chosen.length" @click="accept()">确认已选 {{chosen.length}} 份</button><button class="link-button" :disabled="busy" @click="selected=filtered.filter(r=>r.batchEligible).map(key)">选中可批量确认答案</button></div>
      <div v-if="confirm" ref="confirmationPanel" class="confirm" role="region" aria-label="确认简答操作"><strong>{{confirm.label}}</strong><p>{{confirm.message}}</p><button class="primary-button" :disabled="busy" @click="execute">确认执行</button><button class="secondary-button" :disabled="busy" @click="confirm=null">取消</button></div>
      <div v-if="!filtered.length" class="empty">当前筛选没有作答。可切换状态查看已确认记录。</div>
      <article v-for="r in visible" :key="key(r)" class="answer-row" :aria-label="'简答答卷 '+r.attemptId+' 题目 '+r.questionId">
        <div class="row-head"><label class="select-answer"><input v-model="selected" type="checkbox" :value="key(r)" :disabled="busy||!r.batchEligible" :aria-label="'批量选择答卷 '+r.attemptId" />{{r.name}} · 答卷 #{{r.attemptId}}</label><span class="status-pill" :class="r.state==='ACCEPTED'?'success':r.risks.length?'warning':''">{{r.published?'已公布':labels[r.state]||'待处理'}}</span></div>
        <h3>{{r.stem}}</h3>
        <div class="answer-text"><small>考生作答</small><p>{{r.answer||'未作答'}}</p></div>
        <div class="score-line"><strong>{{r.awarded!=null?'已确认 '+r.awarded:r.suggested!=null?'建议 '+r.suggested:'尚无建议'}}<span v-if="r.awarded!=null||r.suggested!=null"> / {{r.maximum}} 分</span></strong><span v-if="r.state!=='ACCEPTED'" class="sample-note">{{r.sampleMessage}}</span></div>
        <ul v-if="r.risks.length" class="risk-list"><li v-for="risk in r.risks" :key="risk">{{risk}}</li></ul>
        <p v-if="r.state==='NEEDS_RUBRIC'" class="help">本试卷题目版本尚无已确认评分标准。<RouterLink :to="'/questions?rubric='+r.questionId">前往题库整理评分标准</RouterLink>；若题库答案已变更，历史试卷请按下方原参考答案人工评分。</p>
        <div class="actions row-actions"><button class="secondary-button" :disabled="busy" @click="expanded=expanded===key(r)?'':key(r)">{{expanded===key(r)?'收起依据':'查看评分依据'}}</button><template v-if="r.state!=='ACCEPTED'"><button v-if="['SUGGESTED','REVIEW_REQUIRED'].includes(r.state)" class="primary-button" :disabled="busy||!!confirm" @click="expanded=key(r);accept(r)">逐点核对后确认</button><button v-if="['NOT_STARTED','STALE','FAILED'].includes(r.state)" class="secondary-button" :disabled="busy||!!confirm" @click="askGenerate([r])">生成本题建议</button><button class="link-button" :disabled="busy||!!confirm" @click="edit(r)">人工给分</button></template><button v-else class="link-button" :disabled="busy||!!confirm" @click="undo(r)">撤销确认</button></div>
        <div v-if="expanded===key(r)" class="evidence">
          <details><summary>本场试卷参考答案</summary><p class="reference-text">{{r.reference}}</p></details>
          <p v-if="!r.evaluation" class="help">还没有逐点判断依据，可以生成建议或人工评分。</p>
          <div v-for="{point,finding} in findings(r)" :key="point.key" class="finding"><div><strong>{{point.label}}</strong><span>{{finding?verdicts[finding.verdict]:'未评分'}} · 权重 {{point.weight}}</span></div><p v-if="finding?.evidence" class="quote">“{{finding.evidence}}”</p><p v-if="finding">{{finding.reason}}</p><p v-if="point.constraint" class="help">判分要求：{{point.constraint}}</p><details><summary>查看标准原文依据</summary><p class="reference-text">{{point.source}}</p></details></div>
          <p v-if="r.similarity!=null" class="help">独立语义相似度 {{r.similarity}}%，用于现有错题管理口径，不用于换算得分。</p>
        </div>
        <form v-if="manual===key(r)" class="manual" @submit.prevent="saveManual(r)"><div><label>人工得分<input v-model.number="manualScore" type="number" min="0" :max="r.maximum" step="1" required :disabled="busy" /></label><label>独立语义相似度（%）<input v-model.number="similarity" type="number" min="0" max="100" step="0.1" required :disabled="busy" /></label></div><label>判分说明<textarea v-model="comment" rows="2" maxlength="2000" required :disabled="busy" /></label><button class="primary-button" :disabled="busy||!!confirm">保存人工评分</button><button type="button" class="secondary-button" :disabled="busy" @click="manual=''">取消人工评分</button></form>
      </article>
      <div v-if="filtered.length" class="pagination"><button class="secondary-button" :disabled="page<=1||busy" @click="page--">上一页</button><span>{{Math.min(page,pages)}} / {{pages}}</span><button class="secondary-button" :disabled="page>=pages||busy" @click="page++">下一页</button></div>
    </template>
  </section>
</template>

<style scoped>
.short-review{padding:24px;margin:20px 0;min-width:0}.short-review header,.row-head,.score-line,.finding>div{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}.short-review h2{font-size:20px;margin:0}.short-review header p,.help,.sample-note{font-size:13px;color:#627b8e;line-height:1.8}.summary{padding:18px 0;border-bottom:1px solid #dce5ec}.summary strong{font-size:20px;color:#176a65}.summary p{font-size:13px;color:#627b8e;margin-bottom:0}.filters{display:flex;gap:16px;flex-wrap:wrap;margin:18px 0}.filters label,.manual label{display:grid;gap:8px;font-size:13px;color:#456078}.filters label:first-child{flex:1;min-width:0}.short-review select,.manual input,.manual textarea{box-sizing:border-box;width:100%;min-width:0;padding:10px;border:1px solid #bdcdd8;border-radius:6px;font:inherit;background:white}.actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:14px 0}.secondary-button{margin-top:0}.answer-row{padding:24px 0;border-bottom:1px solid #dce5ec;min-width:0}.select-answer{display:flex;gap:9px;align-items:center;font-size:13px;color:#587184}.answer-row h3{font-size:15px;line-height:1.8;overflow-wrap:anywhere}.answer-text{padding:14px;background:#f4f7fa;border-radius:7px}.answer-text small{color:#627b8e}.answer-text p,.reference-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:8px 0}.score-line{padding:14px 0 0}.score-line strong{color:#165e65}.score-line strong span{font-size:13px;font-weight:400}.risk-list{font-size:13px;line-height:1.8;padding:12px 12px 12px 30px;background:#fff7e9;color:#815d24;border-radius:6px;overflow-wrap:anywhere}.confirm{position:sticky;top:80px;z-index:2;padding:18px;background:#f0f7f5;border:1px solid #9ab9af;border-radius:8px;line-height:1.8}.confirm .secondary-button,.manual>.secondary-button{margin-left:10px}.evidence{border-left:3px solid #73aaa1;padding:14px 18px;background:#fafcfc}.evidence summary{cursor:pointer;font-size:13px;color:#4e6a7c}.finding{padding:16px 0;border-bottom:1px solid #e1e9ed}.finding>div{align-items:flex-start;font-size:14px}.finding>div span{font-size:12px;color:#587184;flex-shrink:0}.finding p{font-size:13px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.quote{padding:10px;background:#eaf4f1;border-radius:5px}.manual{padding:18px;border:1px solid #bdcdd8;border-radius:8px;margin-top:18px}.manual>div{display:grid;grid-template-columns:1fr 1fr;gap:16px}.manual label{margin-bottom:12px}.pagination{display:flex;justify-content:center;align-items:center;gap:14px;padding-top:20px;font-size:13px}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible{outline:3px solid #40a79f;outline-offset:2px}@media(max-width:650px){.short-review{padding:16px}.filters{display:grid;grid-template-columns:1fr}.manual>div{grid-template-columns:1fr}.confirm{position:static}.row-actions button{flex:1;min-width:120px}.evidence{padding:12px}.finding>div{display:grid}.sample-note{width:100%}.actions>.primary-button{width:100%}}
</style>
