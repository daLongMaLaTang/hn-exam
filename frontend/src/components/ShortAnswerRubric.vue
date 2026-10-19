<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api } from '../api'
type Point = { key:string; label:string; source:string; weight:number; kind:string; constraint:string; partialAllowed:boolean; critical:boolean }
type Rubric = { points:Point[]; guidance:string }
type View = { questionId:number; stem:string; reference:string; version:string; id:number|null; status:string; rubric:Rubric; confirmedBy:string|null }
const props=defineProps<{questionId:number}>(), emit=defineEmits<{close:[]}>()
const data=ref<View|null>(null), form=ref<Rubric>({points:[],guidance:''}), busy=ref(false), error=ref(''), notice=ref(''), saved=ref(''), intent=ref(''), root=ref<HTMLElement|null>(null)
const dirty=computed(()=>JSON.stringify(form.value)!==saved.value), total=computed(()=>form.value.points.reduce((n,p)=>n+Number(p.weight||0),0))
const kinds:Record<string,string>={CONCEPT:'内容要点',NUMBER:'数值 / 单位',ORDER:'先后顺序',CONDITION:'适用条件'}
function assign(v:View){data.value=v;form.value=structuredClone(v.rubric);saved.value=JSON.stringify(form.value)}
async function load(){busy.value=true;error.value='';try{assign(await api<View>(`/api/questions/${props.questionId}/rubric`))}catch(e){error.value=e instanceof Error?e.message:'加载失败'}finally{busy.value=false}}
function add(){form.value.points.push({key:'p'+Date.now()+'_'+form.value.points.length,label:'',source:'',weight:1,kind:'CONCEPT',constraint:'',partialAllowed:false,critical:false})}
async function act(action:string){
  if(!data.value)return;busy.value=true;error.value='';notice.value='';intent.value=''
  try{
    const endpoint=`/api/questions/${props.questionId}/rubric`+(action==='save'?'':`/${action}`)
    assign(await api<View>(endpoint,{method:action==='save'?'PUT':'POST',body:JSON.stringify({version:data.value.version,expectedId:data.value.id,...(action==='save'?{rubric:form.value}:{})})}))
    notice.value=action==='confirm'?'评分标准已确认，可用于相同版本题目的AI逐点评分。':action==='generate'?'AI草稿已保存，请核对每个要点和原文依据，再确认使用。':'草稿已保存，确认后才能用于新评分。'
  }catch(e){error.value=e instanceof Error?e.message:'操作失败，请重试'}finally{busy.value=false}
}
function close(){if(dirty.value)intent.value='close';else emit('close')}
onMounted(async()=>{await load();await nextTick();root.value?.scrollIntoView({behavior:'smooth',block:'start'})})
</script>

<template>
  <section ref="root" class="panel rubric-editor" aria-label="简答评分标准" :aria-busy="busy">
    <header><div><h2>简答评分标准</h2><p>先确认原文和评分点，同一版本的题目可以重复使用。</p></div><button class="secondary-button" :disabled="busy" @click="close">收起评分标准</button></header>
    <div v-if="error" class="notice error" role="alert">{{error}} <button :disabled="busy||dirty" class="link-button" @click="load">重新加载</button></div>
    <div v-if="notice" class="notice" role="status">{{notice}}</div>
    <p v-if="busy" role="status">正在处理，请稍候…</p>
    <template v-if="data">
      <div class="rubric-title"><strong>{{data.stem}}</strong><span class="status-pill" :class="data.status==='CONFIRMED'&&!dirty?'success':'warning'">{{dirty?'有未保存修改':data.status==='CONFIRMED'?'已确认':data.status==='EMPTY'?'尚未建立':'草稿待确认'}}</span></div>
      <details open class="reference"><summary>参考答案原文</summary><p>{{data.reference}}</p></details>
      <div class="actions"><button class="secondary-button" :disabled="busy" @click="intent='generate'">用 AI 整理评分点</button><button class="secondary-button" :disabled="busy||form.points.length>=40" @click="add">手工添加评分点</button><span>共 {{form.points.length}} 个评分点 · 总权重 {{total}}</span></div>
      <div v-if="intent" class="confirm" role="region" aria-label="确认评分标准操作">
        <p v-if="intent==='generate'">将题干和参考答案发送给已配置的 AI 服务生成新草稿，{{dirty?'未保存的编辑会被替换，':''}}生成结果仍需你核对后确认。</p>
        <p v-else-if="intent==='confirm'">确认评分点完整、原文依据准确，并已核对数值、条件、顺序及部分分规则。确认后可用于判卷；后续修改会保存为新版本。</p>
        <p v-else>尚有未保存修改，收起后这些修改不会保留。</p>
        <button class="primary-button" :disabled="busy" @click="intent==='close'?emit('close'):act(intent)">{{intent==='generate'?'确认生成草稿':intent==='confirm'?'确认评分标准':'放弃修改并收起'}}</button><button class="secondary-button" :disabled="busy" @click="intent=''">取消</button>
      </div>
      <div v-if="!form.points.length" class="empty">还没有评分点。可以让 AI 生成草稿，也可以手工添加。</div>
      <fieldset v-for="(p,index) in form.points" :key="p.key" :disabled="busy" class="point-editor">
        <legend>评分点 {{index+1}}</legend>
        <div class="point-top"><label>评分点内容<textarea v-model="p.label" rows="2" maxlength="500" /></label><label>权重<input v-model.number="p.weight" type="number" min="1" max="100" step="1" /></label><label>判断类型<select v-model="p.kind"><option v-for="(name,key) in kinds" :key="key" :value="key">{{name}}</option></select></label></div>
        <label>原文依据<textarea v-model="p.source" rows="2" placeholder="复制参考答案中的连续原文，不能改写" /></label>
        <label>具体判分要求<textarea v-model="p.constraint" rows="2" maxlength="1000" placeholder="说明必须包含的条件、数字、顺序，或可接受的等价表达" /></label>
        <div class="point-options"><label><input v-model="p.partialAllowed" type="checkbox" />允许部分满足时给本点一半分</label><label><input v-model="p.critical" type="checkbox" />关键要求，未完全满足时必须复核</label><button type="button" class="link-button remove" @click="form.points.splice(index,1)">移除此点</button></div>
      </fieldset>
      <label class="guidance">整体评分说明<textarea v-model="form.guidance" :disabled="busy" rows="3" maxlength="3000" placeholder="说明必答范围、择一关系、重复内容及需要重点核对的事项" /></label>
      <p class="help">权重按试卷本题满分等比例换算，总分四舍五入。数值、顺序、条件类答案默认进入单独复核。新建草稿后，先确认标准才能生成新评分；已经确认的作答保留原评分记录。</p>
      <footer><button class="secondary-button" :disabled="busy||!form.points.length||!dirty" @click="act('save')">保存草稿</button><button class="primary-button" :disabled="busy||dirty||data.status!=='DRAFT'" @click="intent='confirm'">核对完成，确认使用</button></footer>
    </template>
  </section>
</template>

<style scoped>
.rubric-editor{padding:24px;margin:20px 0;scroll-margin-top:80px;min-width:0}.rubric-editor header,.rubric-title,.actions,footer{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}.rubric-editor h2{font-size:20px;margin:0}.rubric-editor header p,.help,.actions>span{font-size:13px;color:#61798c;line-height:1.8}.rubric-title{padding:18px 0;line-height:1.8}.rubric-title strong{flex:1;min-width:200px;overflow-wrap:anywhere}.reference{padding:16px;background:#f3f7fa;border-radius:8px}.reference p{white-space:pre-wrap;line-height:1.8;max-height:330px;overflow:auto;overflow-wrap:anywhere}.reference summary{cursor:pointer;font-size:14px}.actions{justify-content:flex-start;margin:16px 0}.point-editor{border:1px solid #d7e2e8;border-radius:8px;padding:18px;margin:20px 0;min-width:0}.point-editor legend{padding:0 8px;color:#176a65;font-weight:700}.point-editor label,.guidance{display:grid;gap:7px;font-size:13px;color:#456078;margin-bottom:12px}.rubric-editor textarea,.rubric-editor input[type=number],.rubric-editor select{box-sizing:border-box;width:100%;min-width:0;border:1px solid #c6d5df;border-radius:6px;padding:10px;font:inherit;background:#fff}.point-top{display:grid;grid-template-columns:minmax(0,1fr) 90px 160px;gap:14px}.point-options{display:flex;gap:18px;flex-wrap:wrap}.point-options label{display:flex;align-items:center;gap:6px;margin:0}.remove{margin-left:auto;color:#a23c34}.confirm{padding:18px;background:#f0f7f5;border:1px solid #a0bdb4;border-radius:8px;margin:16px 0;line-height:1.8}.confirm .secondary-button{margin-left:10px}.secondary-button{margin-top:0}footer{justify-content:flex-end;padding-top:12px;border-top:1px solid #d7e2e8}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,textarea:focus-visible,select:focus-visible{outline:3px solid #40a79f;outline-offset:2px}@media(max-width:650px){.rubric-editor{padding:16px}.point-top{grid-template-columns:1fr 1fr}.point-top>label:first-child{grid-column:1/-1}.point-options{display:grid;gap:12px}footer button{flex:1}.remove{margin-left:0}}
</style>
