<script setup lang="ts">
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {request,type Team,type TeamDetail,type Page,type TeamRole} from './api'
import type {Language} from './i18n'
const props=defineProps<{origin:string;token:string;userId:string;language:Language}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const rows=ref<Page<Team>|null>(null),mine=ref<Team|null>(null),detail=ref<TeamDetail|null>(null)
const search=ref(''),offset=ref(0),selected=ref(''),busy=ref(false),error=ref(''),notice=ref('')
const form=ref<'create'|'edit'|null>(null),teamName=ref(''),tag=ref(''),desc=ref(''),message=ref('')
const confirmation=ref<{label:string;action:()=>Promise<void>}|null>(null)
let generation=0,disposed=false,timer:ReturnType<typeof setInterval>|undefined
const role=computed(()=>detail.value?.team.members.find(m=>m.user.id===props.userId)?.role)
const manager=computed(()=>role.value==='OWNER'||role.value==='OFFICER')
const title=(v:TeamRole)=>({OWNER:l('Captain','队长'),OFFICER:l('Officer','官员'),MEMBER:l('Member','队员')})[v]
const api=<T,>(path:string,method='GET',body?:unknown)=>request<T>(props.origin,'/community'+path,props.token,method,body)
async function refresh(){
 const g=++generation
 try{
  const [list,my]=await Promise.all([api<Page<Team>>('/teams?offset='+offset.value+'&limit=30&search='+encodeURIComponent(search.value)),api<{team:Team|null}>('/teams/mine')])
  if(disposed||g!==generation)return
  rows.value=list;mine.value=my.team
  if(selected.value){
   const d=await api<TeamDetail>('/teams/'+selected.value)
   if(!disposed&&g===generation)detail.value=d
  }
 }catch(e){if(!disposed&&g===generation)error.value=String(e)}
}
async function act(fn:()=>Promise<void>){
 if(busy.value)return
 busy.value=true;error.value='';notice.value=''
 try{await fn();await refresh()}catch(e){error.value=String(e)}finally{busy.value=false}
}
function select(id:string){if(busy.value)return;selected.value=id;detail.value=null;form.value=null;message.value='';confirmation.value=null;void refresh()}
function openForm(v:'create'|'edit'){
 form.value=v
 teamName.value=v==='edit'?detail.value?.team.name??'':''
 tag.value=v==='edit'?detail.value?.team.tag??'':''
 desc.value=v==='edit'?detail.value?.team.description??'':''
}
function save(){return act(async()=>{
 const edit=form.value==='edit'
 const t=await api<Team>(edit?'/teams/'+selected.value+'/edit':'/teams','POST',{name:teamName.value,tag:tag.value,description:desc.value})
 selected.value=t.id;form.value=null;notice.value=l('Team saved.','战队已保存。')
})}
function apply(){return act(async()=>{await api('/teams/'+selected.value+'/applications','POST',{message:message.value});message.value='';notice.value=l('Application submitted.','入队申请已提交。')})}
function review(user:string,accept:boolean){return act(async()=>{await api('/teams/'+selected.value+'/applications/'+user+'/review','POST',{accept})})}
function setRole(user:string,value:TeamRole){return act(async()=>{await api('/teams/'+selected.value+'/members/'+user+'/role','POST',{role:value})})}
function confirm(label:string,action:()=>Promise<void>){confirmation.value={label,action}}
function confirmAction(){const c=confirmation.value;confirmation.value=null;if(c)void act(c.action)}
function remove(user:string){const id=selected.value;confirm(user===props.userId?l('Leave this team?','确认退出战队？'):l('Remove this member?','确认移除队员？'),async()=>{await api('/teams/'+id+'/members/'+user,'DELETE')})}
function transfer(user:string){const id=selected.value;confirm(l('Transfer captain ownership to this member?','确认将队长转让给该成员？'),async()=>{await api('/teams/'+id+'/transfer','POST',{targetId:user})})}
function disband(){const id=selected.value;confirm(l('Disband this team and release all members?','确认解散战队并解除所有成员关系？'),async()=>{await api('/teams/'+id,'DELETE');selected.value='';detail.value=null})}
function page(step:number){offset.value=Math.max(0,offset.value+step*30);void refresh()}
onMounted(()=>{void refresh();timer=setInterval(()=>{if(!busy.value&&!form.value&&!confirmation.value)void refresh()},7000)})
onBeforeUnmount(()=>{disposed=true;++generation;if(timer)clearInterval(timer)})
</script>
<template>
 <section class="competition-page archive-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / TEAMS</div><h1>{{l('Teams','战队')}}</h1><p class="muted">{{l('Create a team, recruit players and manage its roster.','创建战队、招募成员并管理队伍。')}}</p></div><button class="primary" :disabled="!!mine||busy" @click="openForm('create')">{{l('Create team','创建战队')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice" role="status">{{notice}}</div>
  <div class="game-note" v-if="mine"><strong>[{{mine.tag}}] {{mine.name}}</strong> · {{mine.members.length}} {{l('members','位成员')}} <button class="link" @click="select(mine.id)">{{l('My team','我的战队')}}</button></div>
  <form v-if="form" class="competition-table-panel archive-inset" @submit.prevent="save">
   <h2>{{form==='create'?l('Create team','创建战队'):l('Edit team','编辑战队')}}</h2>
   <label>{{l('Team name','战队名称')}}<input v-model="teamName" required maxlength="48"></label><label>{{l('Team tag','战队标签')}}<input v-model="tag" required pattern="[A-Za-z0-9]{2,8}" maxlength="8"></label>
   <label>{{l('Description','介绍')}}<textarea v-model="desc" maxlength="2000" rows="3"/></label><div class="archive-actions"><button class="primary" :disabled="busy" type="submit">{{l('Save team','保存战队')}}</button><button type="button" class="secondary" @click="form=null">{{l('Cancel','取消')}}</button></div>
   <p class="muted">{{l('Team names, tags and member usernames are visible to signed-in users.','战队名称、标签及成员用户名对已登录用户可见。')}}</p>
  </form>
  <div class="competition-table-panel"><div class="archive-inset archive-tools"><input v-model="search" :placeholder="l('Search team name or tag','搜索战队名称或标签')" @keyup.enter="offset=0;refresh()"><button class="secondary" @click="offset=0;refresh()">{{l('Search','搜索')}}</button></div>
   <table><thead><tr><th>{{l('Tag','标签')}}</th><th>{{l('Team','战队')}}</th><th>{{l('Captain','队长')}}</th><th>{{l('Members','成员')}}</th><th></th></tr></thead><tbody><tr v-for="t in rows?.entries??[]" :key="t.id"><td><strong>{{t.tag}}</strong></td><td>{{t.name}}</td><td>{{t.members.find(m=>m.role==='OWNER')?.user.username}}</td><td>{{t.members.length}}</td><td><button class="secondary small-button" @click="select(t.id)">{{l('Team details','战队详情')}}</button></td></tr></tbody></table>
   <p v-if="!rows?.total" class="muted competition-empty">{{l('No teams found.','暂无战队。')}}</p><div class="competition-pagination"><span class="muted">{{rows?.total??0}} {{l('teams','个战队')}}</span><button class="secondary small-button" :disabled="offset===0" @click="page(-1)">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="offset+30>=(rows?.total??0)" @click="page(1)">{{l('Next','下一页')}}</button></div>
  </div>
  <div v-if="detail" class="competition-table-panel"><div class="panel-title">[{{detail.team.tag}}] {{detail.team.name}} <button class="link" @click="selected='';detail=null">{{l('Close','关闭')}}</button></div>
   <div class="archive-inset"><p class="archive-description">{{detail.team.description||l('No description','暂无介绍')}}</p>
    <template v-if="!mine"><form v-if="!detail.myApplication" @submit.prevent="apply"><label>{{l('Application message (optional)','申请留言（可选）')}}<textarea v-model="message" maxlength="1000" rows="2"/></label><button class="primary" :disabled="busy" type="submit">{{l('Apply to join','申请加入')}}</button></form><button v-else class="secondary" :disabled="busy" @click="act(async()=>{await api('/teams/'+selected+'/applications/mine','DELETE')})">{{l('Withdraw application','撤回申请')}}</button></template>
    <div v-if="role" class="archive-actions"><button v-if="role==='OWNER'" class="secondary" @click="openForm('edit')">{{l('Edit team','编辑战队')}}</button><button v-if="role==='OWNER'" class="secondary danger" @click="disband">{{l('Disband team','解散战队')}}</button><button v-else class="secondary danger" @click="remove(userId)">{{l('Leave team','退出战队')}}</button></div>
   </div>
   <table><thead><tr><th>{{l('Player','玩家')}}</th><th>{{l('Role','身份')}}</th><th>{{l('Actions','操作')}}</th></tr></thead><tbody><tr v-for="m in detail.team.members" :key="m.user.id"><td>{{m.user.username}}</td><td>{{title(m.role)}}</td><td><div v-if="m.user.id!==userId" class="archive-actions"><button v-if="role==='OWNER'&&m.role!=='OWNER'" class="secondary small-button" :disabled="busy" @click="setRole(m.user.id,m.role==='MEMBER'?'OFFICER':'MEMBER')">{{m.role==='MEMBER'?l('Promote','晋升'):l('Demote','降级')}}</button><button v-if="role==='OWNER'&&m.role!=='OWNER'" class="secondary small-button" :disabled="busy" @click="transfer(m.user.id)">{{l('Transfer captain','转让队长')}}</button><button v-if="(role==='OWNER'&&m.role!=='OWNER')||(role==='OFFICER'&&m.role==='MEMBER')" class="secondary danger small-button" :disabled="busy" @click="remove(m.user.id)">{{l('Remove','移除')}}</button></div></td></tr></tbody></table>
   <div v-if="manager" class="archive-inset"><h3>{{l('Join applications','入队申请')}}</h3><div v-for="a in detail.applications" :key="a.user.id" class="archive-record"><div><strong>{{a.user.username}}</strong><small>{{a.message}}</small></div><div class="archive-actions"><button class="primary small-button" :disabled="busy" @click="review(a.user.id,true)">{{l('Accept','接受')}}</button><button class="secondary small-button" :disabled="busy" @click="review(a.user.id,false)">{{l('Reject','拒绝')}}</button></div></div><p v-if="!detail.applications.length" class="muted">{{l('No pending applications.','暂无待审核申请。')}}</p></div>
  </div>
  <div v-if="confirmation" class="competition-table-panel archive-inset"><strong>{{confirmation.label}}</strong><div class="archive-actions"><button class="primary" :disabled="busy" @click="confirmAction">{{l('Confirm','确认')}}</button><button class="secondary" @click="confirmation=null">{{l('Cancel','取消')}}</button></div></div>
 </section>
</template>
