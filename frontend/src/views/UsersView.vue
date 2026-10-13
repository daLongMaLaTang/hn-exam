<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, hasRole, currentUser } from '../api'
import ProfileImport from '../components/ProfileImport.vue'
import CredentialsReceipt from '../components/CredentialsReceipt.vue'
import type { Credential } from '../credentials'
const credentials=ref<Credential[]>([]), credentialFile=ref(''), importBusy=ref(false)
async function receiveCredentials(rows:Credential[],file:string){credentials.value=rows;credentialFile.value=file;await nextTick();document.querySelector<HTMLElement>('.credential-receipt')?.focus({preventScroll:true});document.querySelector('.credential-receipt')?.scrollIntoView({block:'start'})}
type User = {id:number; employeeNo:string; name:string; phone:string; department:string; team:string; status:string; roles:string[]}
type Role = {code:string;name:string;permissionGroup:string}
const users=ref<User[]>([]), roles=ref<Role[]>([]), query=ref(''), error=ref(''), success=ref(''), loading=ref(false), busy=ref(false)
const editing=ref<number|null|undefined>(undefined), roleTarget=ref<User|null>(null), selected=ref<string[]>([])
const form=ref({employeeNo:'',name:'',phone:'',department:'',team:'',status:'ACTIVE'})
const roleForm=ref({code:'',name:'',permissionGroup:'CANDIDATE'}), showRole=ref(false), resetTarget=ref<User|null>(null)
const visibleUsers=computed(()=>users.value.filter(u=>`${u.employeeNo} ${u.name} ${u.phone||''} ${u.department||''} ${u.team||''}`.includes(query.value.trim())))
const canEdit=hasRole('SYSTEM_ADMIN','TRAINING_ADMIN'), isSystem=hasRole('SYSTEM_ADMIN')
const canGrant=isSystem && currentUser()?.employeeNo==='admin'
const roleName=(code:string)=>roles.value.find(r=>r.code===code)?.name||code
const statusNames:Record<string,string>={ACTIVE:'正常',LEAVE:'离岗',GRADUATED:'结业',DISABLED:'停用'}
const statusTone=(status:string)=>status==='ACTIVE'?'success':status==='LEAVE'?'warning':status==='DISABLED'?'danger':''
async function load(){ loading.value=true; try { [users.value,roles.value]=await Promise.all([api<User[]>('/api/users'),api<Role[]>('/api/users/roles')]) } catch(e){error.value=e instanceof Error?e.message:'加载失败'} finally{loading.value=false} }
function edit(user?:User){ error.value=''; editing.value=user?.id??null; form.value=user?{employeeNo:user.employeeNo,name:user.name,phone:user.phone||'',department:user.department||'',team:user.team||'',status:user.status}:{employeeNo:'',name:'',phone:'',department:'',team:'',status:'ACTIVE'} }
async function action(fn:()=>Promise<unknown>,message:string){busy.value=true;error.value='';success.value='';try{await fn();success.value=message;await load();return true}catch(e){error.value=e instanceof Error?e.message:'操作失败';return false}finally{busy.value=false}}
async function save(){
  if(credentials.value.length || busy.value || importBusy.value)return
  const creating=editing.value===null
  if(await action(async()=>{
    if(creating){const result=await api<{user:User;credential:Credential;credentialFile:string}>('/api/users',{method:'POST',body:JSON.stringify(form.value)});await receiveCredentials([result.credential],result.credentialFile)}
    else await api(`/api/users/${editing.value}`,{method:'PUT',body:JSON.stringify(form.value)})
  },creating?'账号已创建，请领取初始密码':'人员资料已保存')) editing.value=undefined
}
function editRoles(u:User){roleTarget.value=u;selected.value=[...u.roles];error.value=''}
async function saveRoles(){if(await action(()=>api(`/api/users/${roleTarget.value!.id}/roles`,{method:'PUT',body:JSON.stringify({roles:selected.value})}),'权限已保存，立即生效')) roleTarget.value=null}
async function addRole(){if(await action(()=>api('/api/users/roles',{method:'POST',body:JSON.stringify(roleForm.value)}),'新身份已创建，可为人员分配')){showRole.value=false;roleForm.value={code:'',name:'',permissionGroup:'CANDIDATE'}}}
async function reset(){
  if(credentials.value.length || busy.value || importBusy.value)return
  if(await action(async()=>{const result=await api<{credential:Credential;credentialFile:string}>(`/api/users/${resetTarget.value!.id}/reset-password`,{method:'POST'});await receiveCredentials([result.credential],result.credentialFile)},'密码已重置，请领取新的初始密码'))resetTarget.value=null
}
onMounted(load)
</script>
<template>
  <div class="page-head"><div><span class="eyebrow dark">ACCOUNTS & ROLES</span><h1>人员与权限</h1><p>维护人员信息、工号、账号状态和多身份权限。</p></div><div class="actions"><button v-if="canGrant" class="secondary-button" @click="showRole=!showRole">新增身份</button><button v-if="canEdit" class="primary-button small"  :disabled="busy||importBusy||!!credentials.length" @click="edit()">新增人员</button></div></div>
  <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="success" class="notice success" role="status">{{success}}</p>
  <CredentialsReceipt v-if="credentials.length" :credentials="credentials" :credential-file="credentialFile" @closed="credentials=[];credentialFile=''" /><ProfileImport v-if="canEdit" :blocked="busy||!!credentials.length" @credentials="receiveCredentials" @busy="importBusy=$event" @imported="load" /><form v-if="editing!==undefined" class="panel form-stack" @submit.prevent="save"><h3>{{editing?'编辑人员':'新增考生'}}</h3><div class="form-grid"><label>工号<input v-model="form.employeeNo" required maxlength="32" /></label><label>姓名<input v-model="form.name" required maxlength="80" /></label><label>手机号<input v-model="form.phone" maxlength="32" type="tel" /></label><label>部门<input v-model="form.department" maxlength="100" /></label><label>班组<input v-model="form.team" maxlength="100" /></label><label>状态<select v-model="form.status"><option v-for="(label,value) in statusNames" :key="value" :value="value">{{label}}</option></select></label></div><p v-if="!editing" class="muted-text">新账号为考生；保存后领取独立初始密码，首次登录必须修改。</p><div class="actions"><button class="primary-button" :disabled="busy||importBusy||!!credentials.length">保存资料</button><button type="button" class="secondary-button" @click="editing=undefined">取消</button></div></form>
  <form v-if="roleTarget" class="panel form-stack" @submit.prevent="saveRoles"><h3>为 {{roleTarget.name}} 分配身份</h3><p class="muted-text">可同时拥有多个身份。仅 admin 账号可分配身份；系统管理员负责系统管理；培训管理员管理人员、题库和考试；部门领导查看人员及成绩；专工维护题库；考生练习及考试。</p><div class="checkbox-grid"><label v-for="role in roles" :key="role.code" class="check-label"><input v-model="selected" type="checkbox" :value="role.code" />{{role.name}}</label></div><div class="actions"><button class="primary-button" :disabled="busy||!selected.length">保存权限</button><button type="button" class="secondary-button" @click="roleTarget=null">取消</button></div></form>
  <form v-if="showRole" class="panel form-stack" @submit.prevent="addRole"><h3>新增身份</h3><div class="form-grid"><label>名称<input v-model="roleForm.name" required maxlength="100" placeholder="例如：班组培训负责人" /></label><label>代码<input v-model="roleForm.code" required pattern="[A-Z][A-Z0-9_]{2,63}" placeholder="例如：TEAM_TRAINER" /></label><label>权限模板<select v-model="roleForm.permissionGroup"><option value="CANDIDATE">考生</option><option value="SPECIALIST">专业专工</option><option value="TRAINING_ADMIN">培训管理员</option><option value="DEPARTMENT_LEADER">部门领导</option></select></label></div><div class="actions"><button class="primary-button" :disabled="busy">创建身份</button><button class="secondary-button" type="button" @click="showRole=false">取消</button></div></form>
  <form v-if="resetTarget" class="panel form-stack narrow" @submit.prevent="reset"><h3>重置 {{resetTarget.name}} 的密码</h3><p class="muted-text">系统将生成新的随机初始密码。原密码及已有登录立即失效，请在重置后领取并交给本人，首次登录必须修改。</p><div class="actions"><button class="primary-button" :disabled="busy||importBusy||!!credentials.length">确认重置</button><button class="secondary-button" type="button" @click="resetTarget=null">取消</button></div></form>
  <article class="panel table-panel"><div class="toolbar"><div><strong>人员清单</strong><small>共 {{users.length}} 人 · 匹配 {{visibleUsers.length}} 人</small></div><input v-model="query" class="search" aria-label="搜索人员" placeholder="搜索工号、姓名、部门或手机号" /></div><div class="table-wrap"><table><thead><tr><th>工号 / 姓名</th><th>部门 / 班组</th><th>手机号</th><th>身份</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="6">正在加载…</td></tr><tr v-for="u in visibleUsers" :key="u.id"><td><strong>{{u.name}}</strong><small class="block mono">{{u.employeeNo}}</small></td><td>{{[u.department,u.team].filter(Boolean).join(' / ')||'待补充'}}</td><td>{{u.phone||'待补充'}}</td><td><span v-for="r in u.roles" :key="r" class="tag muted">{{roleName(r)}}</span></td><td><span class="status-pill" :class="statusTone(u.status)">{{statusNames[u.status]||u.status}}</span></td><td><div class="actions"><RouterLink class="link-button" :to="'/users/' + u.id + '/profile'">培训档案</RouterLink><button v-if="canEdit && (u.employeeNo!=='admin' || canGrant)" class="link-button" @click="edit(u)">编辑</button><button v-if="canGrant" class="link-button" @click="editRoles(u)">权限</button><button v-if="isSystem && u.employeeNo!==currentUser()?.employeeNo && (u.employeeNo!=='admin' || canGrant)" class="link-button" :disabled="busy||importBusy||!!credentials.length" @click="resetTarget=u">重置密码</button><span v-if="!canEdit">只读</span></div></td></tr><tr v-if="!loading&&!visibleUsers.length"><td colspan="6">没有符合条件的人员</td></tr></tbody></table></div></article>
</template>


