<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
const emit=defineEmits<{ imported: [] }>()
const busy=ref(false), message=ref(''), error=ref(''), failures=ref<{row:number;reason:string}[]>([])
async function template(){
  error.value=''
  try{
    const response=await fetch('/api/profiles/excel-template',{headers:{Authorization:`Bearer ${localStorage.getItem('hn_exam_token')||''}`}})
    if(!response.ok)throw new Error('模板下载失败，请重试')
    const url=URL.createObjectURL(await response.blob()), link=document.createElement('a');link.href=url;link.download='学员档案导入模板.xlsx';link.click();URL.revokeObjectURL(url)
  }catch(e){error.value=e instanceof Error?e.message:'下载失败'}
}
async function upload(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return
  busy.value=true;error.value='';message.value='';failures.value=[]
  try{
    const body=new FormData();body.append('file',file)
    const result=await api<{created:number;updated:number;failed:number;failures:{row:number;reason:string}[]}>('/api/profiles/import-excel',{method:'POST',body})
    message.value=`导入完成：新增 ${result.created} 人，更新 ${result.updated} 人，失败 ${result.failed} 行。`;failures.value=result.failures;emit('imported')
  }catch(e){error.value=e instanceof Error?e.message:'导入失败'}finally{busy.value=false;input.value=''}
}
</script>
<template>
  <details class="panel profile-import"><summary>Excel 导入学员档案</summary><p class="muted-text">按工号新增或更新学员资料。选填项留空时保留原值；考试记录不受影响。请先下载模板。</p>
    <div class="actions"><button class="secondary-button" :disabled="busy" @click="template">下载档案模板</button><label>选择 Excel 文件<input type="file" accept=".xlsx" :disabled="busy" @change="upload" /></label></div>
    <p v-if="busy" role="status">正在导入，请稍候…</p><p v-if="message" class="notice" role="status">{{message}}</p><p v-if="error" class="notice error" role="alert">{{error}}</p>
    <ul v-if="failures.length"><li v-for="f in failures" :key="f.row">第 {{f.row}} 行：{{f.reason}}</li></ul>
  </details>
</template>
<style scoped>.profile-import summary{cursor:pointer;font-weight:700}.profile-import label{display:grid;gap:8px;font-size:13px}.profile-import .actions{flex-wrap:wrap}.profile-import input{max-width:100%}.profile-import ul{max-height:240px;overflow:auto;line-height:1.8}</style>
