<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import type { Credential } from '../credentials'
const props=defineProps<{credentials:Credential[];credentialFile:string}>()
const emit=defineEmits<{closed:[]}>()
const shown=ref(new Set<number>()), message=ref(''), error=ref('')
function toggle(id:number){const next=new Set(shown.value);next.has(id)?next.delete(id):next.add(id);shown.value=next}
async function copy(row:Credential){
  error.value='';message.value=''
  try {
    if(!navigator.clipboard)throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(`工号：${row.employeeNo}\n姓名：${row.name}\n初始密码：${row.initialPassword}\n首次登录请修改密码。`)
    message.value='已复制该账号的登录信息，请单独发送给对应学员。'
  }catch{error.value='当前浏览器无法复制。请显示密码后手动复制，或下载密码清单。'}
}
function download(){
  error.value=''
  try {
    if(!props.credentialFile)throw new Error('missing workbook')
    const bytes=Uint8Array.from(atob(props.credentialFile),c=>c.charCodeAt(0))
    const url=URL.createObjectURL(new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}))
    const link=document.createElement('a');link.href=url;link.download='新增或重置账号_密码分发清单.xlsx';link.click()
    window.setTimeout(()=>URL.revokeObjectURL(url),1000)
    message.value='已发起下载，请确认文件已保存，再关闭领取区。清单含密码，请妥善保管。'
  }catch{error.value='未能下载，请重试或逐个复制登录信息。'}
}
function leaving(event:BeforeUnloadEvent){if(props.credentials.length){event.preventDefault();event.returnValue=''}}
onMounted(()=>window.addEventListener('beforeunload',leaving))
onUnmounted(()=>window.removeEventListener('beforeunload',leaving))
onBeforeRouteLeave(()=>!props.credentials.length || window.confirm('当前密码清单尚未关闭。离开后无法再次查看，确认已保存并离开？'))
</script>
<template>
  <section class="panel credential-receipt" aria-label="领取初始密码" tabindex="-1">
    <div class="receipt-heading"><div><h3>领取初始密码</h3><p>共 {{credentials.length}} 个账号 · 首次登录必须修改密码</p></div><button type="button" class="primary-button" @click="download">下载密码清单</button></div>
    <p class="notice">密码仅在本次操作后提供。请先复制或下载，再确认关闭；关闭或刷新后无法再次查看。遗失时请联系系统管理员重置。</p>
    <div class="receipt-list">
      <article v-for="row in credentials" :key="row.userId" class="receipt-row">
        <div class="receipt-person"><strong>{{row.name}}</strong><span class="mono">工号 {{row.employeeNo}}</span></div>
        <label class="receipt-password">初始密码<input :value="row.initialPassword" :type="shown.has(row.userId)?'text':'password'" readonly autocomplete="off" :aria-label="`${row.employeeNo} 的初始密码`" /></label>
        <div class="actions"><button type="button" class="secondary-button" :aria-pressed="shown.has(row.userId)" @click="toggle(row.userId)">{{shown.has(row.userId)?'隐藏密码':'显示密码'}}</button><button type="button" class="secondary-button" @click="copy(row)">复制登录信息</button></div>
      </article>
    </div>
    <p v-if="message" class="notice success" role="status">{{message}}</p><p v-if="error" class="notice error" role="alert">{{error}}</p>
    <div class="actions"><button type="button" class="secondary-button" @click="emit('closed')">已保存，关闭领取区</button></div>
  </section>
</template>
<style scoped>
.credential-receipt{scroll-margin-top:24px;border-top:3px solid var(--energy-500);margin-bottom:20px}.receipt-heading{display:flex;justify-content:space-between;gap:16px;align-items:center}.receipt-heading h3{margin:0}.receipt-heading p{margin:6px 0 0;font-size:13px}.receipt-list{max-height:360px;overflow:auto;margin:16px 0}.receipt-row{display:grid;grid-template-columns:minmax(100px,1fr) minmax(180px,1.5fr) auto;align-items:end;gap:16px;padding:14px 0;border-bottom:1px solid var(--line,#dce3e8)}.receipt-person{display:grid;gap:6px;align-self:center;overflow-wrap:anywhere}.receipt-person span{font-size:12px}.receipt-password{display:grid;gap:6px;font-size:12px;min-width:0}.receipt-password input{width:100%;min-width:0;min-height:42px;padding:9px 10px;border:1px solid var(--line);border-radius:8px;background:var(--surface-soft);font-family:monospace;font-size:13px}.receipt-row .actions{flex-wrap:wrap}.credential-receipt .notice{overflow-wrap:anywhere}@media(max-width:900px){.receipt-row{grid-template-columns:1fr 1.5fr}.receipt-row .actions{grid-column:1/-1}}@media(max-width:720px){.credential-receipt{scroll-margin-top:124px}}@media(max-width:540px){.receipt-heading{align-items:stretch;flex-direction:column}.receipt-row{grid-template-columns:1fr}.receipt-row .actions{grid-column:auto}.receipt-list{max-height:440px}}
</style>
