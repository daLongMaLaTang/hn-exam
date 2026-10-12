<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
const props=defineProps<{ mode:string; userIds:number[]; majors:string[]; readOnly?:boolean }>()
const emit=defineEmits<{ 'update:mode':[string]; 'update:userIds':[number[]]; 'update:majors':[string[]] }>()
type Person={id:number;employeeNo:string;name:string;professionalDirection?:string}
const people=ref<Person[]>([]), query=ref(''), error=ref(''), loading=ref(true)
const directions=computed(()=>[...new Set(people.value.map(p=>p.professionalDirection).filter((x):x is string=>Boolean(x)))].sort())
const matching=computed(()=>people.value.filter(p=>(!props.readOnly||props.userIds.includes(p.id))&&`${p.name} ${p.employeeNo} ${p.professionalDirection||''}`.includes(query.value.trim())))
const count=computed(()=>props.mode==='ALL'?people.value.length:props.mode==='USERS'?props.userIds.length:people.value.filter(p=>props.majors.includes(p.professionalDirection||'')).length)
function person(id:number,on:boolean){emit('update:userIds',on?[...new Set([...props.userIds,id])]:props.userIds.filter(x=>x!==id))}
function major(name:string,on:boolean){emit('update:majors',on?[...new Set([...props.majors,name])]:props.majors.filter(x=>x!==name))}
async function load(){loading.value=true;error.value='';try{people.value=await api('/api/exams/audience-options')}catch(e){error.value=e instanceof Error?e.message:'人员加载失败'}finally{loading.value=false}}
onMounted(load)
</script>
<template>
  <section class="audience-card" aria-label="发布对象"><h3>发布对象</h3>
    <label v-if="!readOnly">参加范围<select :value="mode" @change="emit('update:mode',($event.target as HTMLSelectElement).value)"><option value="ALL">全员考试</option><option value="USERS">指定人员</option><option value="MAJORS">按专业考试</option></select></label>
    <p v-if="loading" role="status">正在加载学员…</p><p v-if="error" class="notice error" role="alert">{{error}} <button type="button" class="link-button" @click="load">重试</button></p>
    <template v-if="mode==='USERS'"><label>搜索学员<input v-model="query" placeholder="姓名、工号或专业方向" /></label><div class="audience-list"><label v-for="p in matching" :key="p.id"><input type="checkbox" :disabled="readOnly" :checked="userIds.includes(p.id)" @change="person(p.id,($event.target as HTMLInputElement).checked)" /><span>{{p.name}} · {{p.employeeNo}}<small>{{p.professionalDirection||'未填专业方向'}}</small></span></label><p v-if="!loading&&!matching.length">没有匹配的学员</p></div></template>
    <div v-if="mode==='MAJORS'" class="audience-list"><label v-for="name in directions" :key="name"><input type="checkbox" :checked="majors.includes(name)" @change="major(name,($event.target as HTMLInputElement).checked)" />{{name}}</label><p v-if="!loading&&!directions.length">暂无专业方向，请先维护或导入学员档案。</p></div>
    <p class="muted-text">当前范围 {{count}} 人。仅包含已启用的学员。{{mode==='MAJORS'?'按档案“专业方向”匹配，发布时固定人员名单。':''}}错题考试仅向其中有符合条件考试错题的学员出卷。</p>
  </section>
</template>
<style scoped>.audience-card{display:grid;gap:12px;border:1px solid #dbe6ed;border-radius:10px;padding:16px}.audience-card h3,.audience-card p{margin:0}.audience-card>label{display:grid;gap:7px;font-size:13px}.audience-card input:not([type=checkbox]),.audience-card select{padding:10px;border:1px solid #d8e2ea;border-radius:7px;max-width:100%;width:100%;box-sizing:border-box;font:inherit}.audience-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,230px),1fr));gap:10px;max-height:260px;overflow:auto}.audience-list label{display:flex;align-items:center;gap:8px;padding:8px;background:#f5f9fb;border-radius:6px;font-size:13px}.audience-list small{display:block;margin-top:4px;color:#637b8e}</style>
