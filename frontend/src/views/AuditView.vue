<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {api} from '../api'
const items=ref<{id:number;occurredAt:string;actor:string;method:string;path:string;responseStatus:number}[]>([]),error=ref('')
const action=(path:string)=>path.includes('reset-password')?'重置密码':path.includes('/roles')?'维护身份权限':path.includes('/users')?'维护人员':path.includes('/questions')?'维护题库':path.includes('/publish')?'发布考试':path.includes('/close')?'关闭考试':path.includes('/exams')?'维护试卷':path.includes('/password')?'修改密码':'管理操作'
onMounted(async()=>{try{items.value=await api('/api/audit')}catch(e){error.value=e instanceof Error?e.message:'加载失败'}})
</script>
<template><div class="page-head"><div><span class="eyebrow dark">AUDIT</span><h1>操作记录</h1><p>最近200条管理操作，记录操作人、时间和结果。</p></div></div><p v-if="error" class="notice error">{{error}}</p><article class="panel table-panel"><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>记录对象</th><th>结果</th></tr></thead><tbody><tr v-for="r in items" :key="r.id"><td>{{new Date(r.occurredAt).toLocaleString('zh-CN')}}</td><td>{{r.actor}}</td><td>{{action(r.path)}}</td><td>{{r.path}}</td><td>{{r.responseStatus<400?'成功':'未完成'}}</td></tr><tr v-if="!items.length"><td colspan="5">暂无操作记录</td></tr></tbody></table></div></article></template>
