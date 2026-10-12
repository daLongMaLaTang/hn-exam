<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
type Person={id:number;employeeNo:string;name:string;practiceSeconds:number;examCount:number;averagePercent:number;passRate:number;entered:number;resolved:number;unresolved:number;resolutionRate:number}
const rows=ref<Person[]>([]),expanded=ref(false),loading=ref(false),error=ref('')
const month=ref(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit'}).format(new Date()))
const visible=computed(()=>expanded.value?rows.value:rows.value.slice(0,3))
let request=0
async function load(){const seq=++request;loading.value=true;error.value='';try{const data=await api<Person[]>('/api/dashboard/monthly-learning?month='+month.value);if(seq===request)rows.value=data}catch(e){if(seq===request)error.value=e instanceof Error?e.message:'分析加载失败'}finally{if(seq===request)loading.value=false}}
watch(month,load);onMounted(load)
</script>
<template>
  <article class="panel monthly-learning">
    <div class="panel-head"><div><h3>月度学习分析</h3><p>按有效学习时长从高到低排列，默认显示前三名。</p></div><label>分析月份 <input v-model="month" type="month" aria-label="分析月份" /></label></div>
    <p v-if="loading" role="status">正在加载月度分析…</p>
    <p v-else-if="error" class="notice error" role="alert">{{error}} <button class="link-button" @click="load">重试</button></p>
    <template v-else>
      <div class="table-wrap"><table><caption>{{month}} 学习分析 · {{expanded?'全部人员':'学习时长前三名'}}</caption><thead><tr><th>排名 / 姓名</th><th>学习时长</th><th>已判考试</th><th>平均分 / 及格率</th><th>考试错题销号率</th><th>入库 / 已销号 / 待销号</th></tr></thead><tbody><tr v-for="(p,i) in visible" :key="p.id"><td>{{i+1}} · <RouterLink :to="'/users/'+p.id+'/profile'">{{p.name}}</RouterLink><small>{{p.employeeNo}}</small></td><td>{{(p.practiceSeconds/60).toFixed(1)}} 分钟</td><td>{{p.examCount}}</td><td>{{p.examCount?p.averagePercent.toFixed(1)+' / '+p.passRate.toFixed(1)+'%':'暂无成绩'}}</td><td>{{p.entered?p.resolutionRate.toFixed(1)+'%':'暂无入库'}}</td><td>{{p.entered}} / {{p.resolved}} / {{p.unresolved}}</td></tr></tbody></table></div>
      <p v-if="!rows.length" class="empty">暂无人员数据。</p>
      <button v-if="rows.length>3" class="secondary-button" :aria-expanded="expanded" @click="expanded=!expanded">{{expanded?'收起，仅看前三名':'查看全部 '+rows.length+' 人的月度学习分析'}}</button>
      <p class="muted-text">学习时长、考试成绩按所选月份统计，平均分为百分制。考试错题销号率为截至当前的累计已销号次数 ÷ 累计入库次数；仅专门的错题考试答对后销号。</p>
    </template>
  </article>
</template>
<style scoped>.monthly-learning{min-width:0}.panel-head{flex-wrap:wrap;gap:12px}input{padding:8px;border:1px solid #cddce6;border-radius:6px}caption{text-align:left;padding:12px 0}small{display:block;color:#728497;margin-top:5px}th{white-space:nowrap}.muted-text{font-size:12px;line-height:1.8}</style>
