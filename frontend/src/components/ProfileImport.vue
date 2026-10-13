<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import type { Credential } from '../credentials'
const props=defineProps<{blocked?:boolean}>()
const emit=defineEmits<{ imported: []; credentials:[Credential[],string]; busy:[boolean] }>()
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
  if(busy.value||props.blocked){input.value='';return}
  busy.value=true;emit('busy',true);error.value='';message.value='';failures.value=[]
  try{
    const body=new FormData();body.append('file',file)
    const result=await api<{created:number;updated:number;failed:number;failures:{row:number;reason:string}[];credentials:Credential[];credentialFile:string}>('/api/profiles/import-excel',{method:'POST',body})
    if(result.credentials.length)emit('credentials',result.credentials,result.credentialFile)
    message.value=`导入完成：新增 ${result.created} 人，更新 ${result.updated} 人，失败 ${result.failed} 行。`;failures.value=result.failures;emit('imported')
  }catch(e){error.value=(e instanceof Error?e.message:'导入失败')+'。若连接中断，部分账号可能已保存；请先检查人员清单，未收到密码的账号请联系系统管理员重置。'}finally{busy.value=false;emit('busy',false);input.value=''}
}
</script>
<template>
  <details class="panel profile-import"><summary>Excel 导入学员档案</summary><p class="muted-text">按工号新增或更新学员资料。新账号生成独立初始密码，导入完成后请下载密码清单。已有账号密码与考试记录不变；选填项留空时保留原值。</p>
    <p v-if="blocked" class="muted-text">请先完成当前操作，保存并关闭密码领取区后再导入。</p>
    <div class="actions"><button class="secondary-button" :disabled="busy" @click="template">下载档案模板</button><label>选择 Excel 文件<input type="file" accept=".xlsx" :disabled="busy||blocked" @change="upload" /></label></div>
    <p v-if="busy" role="status">正在导入，请稍候…</p><p v-if="message" class="notice" role="status">{{message}}</p><p v-if="error" class="notice error" role="alert">{{error}}</p>
    <ul v-if="failures.length"><li v-for="f in failures" :key="f.row">第 {{f.row}} 行：{{f.reason}}</li></ul>
  </details>
</template>
<style scoped>.profile-import summary{cursor:pointer;font-weight:700}.profile-import label{display:grid;gap:8px;font-size:13px}.profile-import .actions{flex-wrap:wrap}.profile-import input{max-width:100%}.profile-import ul{max-height:240px;overflow:auto;line-height:1.8}</style>
