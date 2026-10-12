<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
type Trend={id:number;title:string;course:string;date:string|null;participants:number;graded:number;averagePercent:number|null;passRate:number|null}
const rows=ref<Trend[]>([]),course=ref(''),metric=ref<'averagePercent'|'passRate'|'participants'>('averagePercent'),loading=ref(true),error=ref('')
const names={averagePercent:'平均分（百分制）',passRate:'及格率（%）',participants:'参考人数'}
const courses=computed(()=>[...new Set(rows.value.map(x=>x.course))].sort())
const selected=ref<number[]>([]),search=ref('')
const options=computed(()=>rows.value.filter(x=>(!course.value||x.course===course.value)&&x.title.toLowerCase().includes(search.value.toLowerCase())))
const visible=computed(()=>rows.value.filter(x=>(!course.value||x.course===course.value)&&selected.value.includes(x.id)).slice().reverse())
const chartWidth=computed(()=>Math.max(960,visible.value.length*150+280))
watch(course,()=>{selected.value=rows.value.filter(x=>!course.value||x.course===course.value).slice(0,12).map(x=>x.id)})
const max=computed(()=>metric.value==='participants'?Math.max(1,...visible.value.map(x=>x.participants)):100)
const ticks=computed(()=>metric.value==='participants'
  ? [...new Set([0,0.25,0.5,0.75,1].map(fraction=>Math.round(fraction*max.value)))]
  : [0,25,50,75,100])
const x=(i:number)=>230+(visible.value.length<=1?(chartWidth.value-300)/2:i*(chartWidth.value-300)/(visible.value.length-1))
const y=(value:number)=>205-value/max.value*160
const segments=computed(()=>{const paths:string[]=[];let current='';visible.value.forEach((row,i)=>{const value=row[metric.value];if(value==null){if(current)paths.push(current);current=''}else current+=`${current?' L':'M'} ${x(i)} ${y(value)}`});if(current)paths.push(current);return paths})
const number=(n:number|null)=>n==null?'待判卷':n.toFixed(1)
async function load(){loading.value=true;error.value='';try{rows.value=await api('/api/dashboard/exam-trends');if(!selected.value.length)selected.value=rows.value.slice(0,12).map(x=>x.id)}catch(e){error.value=e instanceof Error?e.message:'图表加载失败'}finally{loading.value=false}}
onMounted(load)
</script>
<template>
  <article class="panel exam-trends"><div class="panel-head"><div><h3>多场考试对比</h3><p>选择需要对比的考试，横轴显示考试名称。默认选中最近12场。</p></div><button class="link-button" :disabled="loading" @click="load">刷新</button></div>
    <div class="trend-filters"><label>课程<select v-model="course"><option value="">全部课程</option><option v-for="c in courses" :key="c">{{c}}</option></select></label><label>对比指标<select v-model="metric"><option v-for="(name,key) in names" :key="key" :value="key">{{name}}</option></select></label></div>
    <details  class="exam-selection"><summary>选择展示考试（已选 {{visible.length}} 场）</summary><input v-model="search" aria-label="搜索考试名称" placeholder="搜索考试名称，如 test002" /><div class="selection-actions"><button class="link-button" @click="selected=[...new Set([...selected,...options.map(x=>x.id)])]">选中筛选结果</button><button class="link-button" @click="selected=[]">清空选择</button></div><div class="selection-list"><label v-for="row in options" :key="row.id"><input v-model="selected" type="checkbox" :value="row.id" />{{row.title}} <small>{{row.course}}</small></label></div><p v-if="!options.length">没有匹配的考试。</p></details>
    <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-else-if="loading" role="status">正在加载考试数据…</p><p v-else-if="!visible.length" class="empty">暂无可展示考试，请在上方选择；已取消考试不参与对比。</p>
    <template v-else><p class="muted-text">图表和明细可左右滑动，长名称可悬停查看完整内容。</p><div class="trend-chart"><svg :viewBox="`0 0 ${chartWidth} 330`" :style="{minWidth:chartWidth+'px'}" role="img" :aria-label="names[metric]+'变化趋势，详细数值见下表'"><g v-for="tick in ticks" :key="tick"><line x1="55" :x2="chartWidth-65" :y1="y(tick)" :y2="y(tick)" stroke="#dfE8ee"/><text x="45" :y="y(tick)+4" text-anchor="end">{{tick}}</text></g><path v-for="(path,i) in segments" :key="i" :d="path" fill="none" stroke="#147969" stroke-width="3"/><g v-for="(row,i) in visible" :key="row.id"><template v-if="row[metric]!=null"><circle :cx="x(i)" :cy="y(row[metric]!)" r="5" fill="#147969"><title>{{row.title}}：{{row[metric]}}</title></circle><text :x="x(i)" :y="y(row[metric]!)-12" text-anchor="middle">{{metric==='participants'?row.participants:number(row[metric])}}</text></template><text :transform="`translate(${x(i)},228) rotate(-25)`" text-anchor="end"><title>{{row.title}}</title>{{row.title.length>18?row.title.slice(0,18)+'…':row.title}}</text></g></svg></div>
    <div class="table-wrap"><table><caption>各场考试明细</caption><thead><tr><th>序号 / 考试</th><th>平均分（百分制）</th><th>及格率</th><th>参考人数</th><th>已判人数</th></tr></thead><tbody><tr v-for="(row,i) in visible" :key="row.id"><td>{{i+1}} · <RouterLink :to="'/results?examId='+row.id">{{row.title}}</RouterLink><small>{{row.course}}</small></td><td>{{number(row.averagePercent)}}</td><td>{{row.passRate==null?'待判卷':number(row.passRate)+'%'}}</td><td>{{row.participants}}</td><td>{{row.graded}}</td></tr></tbody></table></div>
    <p class="muted-text">平均分换算为百分制；平均分和及格率按每人本场最新已判成绩计算，历史同场多次作答不重复计人。参考人数含正在作答和待判卷人员。取消考试不计入。</p></template>
  </article>
</template>
<style scoped>.exam-trends{min-width:0}.trend-filters{display:flex;flex-wrap:wrap;gap:16px}.trend-filters label{display:grid;gap:6px;font-size:13px}.trend-filters select{padding:8px;border:1px solid #cddce6;border-radius:6px;background:#fff;max-width:100%}.trend-chart{overflow-x:auto;margin-top:18px}.trend-chart svg{display:block;width:100%;min-width:500px}.trend-chart text{fill:#4d6779;font-size:12px}.exam-trends caption{text-align:left;padding:12px 0;font-weight:600}.exam-trends small{display:block;color:#728497;margin-top:4px}.exam-trends .muted-text{font-size:12px;line-height:1.8}.exam-trends th{white-space:nowrap}.exam-selection{margin-top:16px}.exam-selection summary{cursor:pointer;padding:10px 0}.exam-selection>input{padding:8px;max-width:100%;border:1px solid #cddce6;border-radius:6px}.selection-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;max-height:260px;overflow:auto;padding:12px 0}.selection-list label{display:flex;align-items:center;gap:8px;font-size:13px}.selection-actions{display:flex;gap:16px}.panel-head>button{flex-shrink:0;white-space:nowrap}</style>
