<script setup lang="ts">
import {ref,computed,onMounted,onBeforeUnmount} from 'vue'
import {request,type OperatorAccess,type OperatorRole,type RoleGrant,type OperationEvent,type OperationOverview,type Page,type OperatorSession,type OperatorRoom} from './api'
import type {Language} from './i18n'
const props=defineProps<{origin:string;token:string;language:Language;configured:boolean}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const key=ref(''),access=ref<OperatorAccess|null>(null),overview=ref<OperationOverview|null>(null),grants=ref<RoleGrant[]>([]),audit=ref<Page<OperationEvent>|null>(null)
const name=ref(''),role=ref<OperatorRole>('MODERATOR'),reason=ref(''),revokeTarget=ref<RoleGrant|null>(null),revokeReason=ref(''),offset=ref(0),busy=ref(false),error=ref(''),notice=ref('')
const sessions=ref<Page<OperatorSession>|null>(null),rooms=ref<Page<OperatorRoom>|null>(null),sessionOffset=ref(0),roomOffset=ref(0)
const enforcement=ref<{kind:'session'|'room';id:string;label:string}|null>(null),enforcementReason=ref('')
let disposed=false,timer:ReturnType<typeof setInterval>|undefined,generation=0
const roles:OperatorRole[]=['MODERATOR','EVENT_MANAGER','ANALYST']
const roleName=(r:OperatorRole)=>({MODERATOR:l('Moderator','审核员'),EVENT_MANAGER:l('Event manager','赛事管理员'),ANALYST:l('Analyst','运营观察员')})[r]
const tiles=computed(()=>overview.value?[
 [l('Signed-in accounts','在线账号'),overview.value.signedIn],[l('Rooms','房间'),overview.value.rooms],[l('Running matches','进行中比赛'),overview.value.runningMatches],[l('Queued players','匹配中玩家'),overview.value.queued],
 [l('Teams','战队'),overview.value.teams],[l('Tournaments','赛事'),overview.value.tournaments],[l('Pending reports','待审核举报'),overview.value.pendingReports],[l('Active suspensions','生效封禁'),overview.value.activeSuspensions]
]:[])
const api=<T,>(path:string,method='GET',body?:unknown)=>request<T>(props.origin,'/operations'+path,props.token,method,body,key.value)
function lock(){++generation;key.value='';access.value=null;overview.value=null;grants.value=[];audit.value=null;revokeTarget.value=null;reason.value='';revokeReason.value='';sessions.value=null;rooms.value=null;enforcement.value=null;enforcementReason.value=''}
async function refresh(){
 if(!access.value||disposed)return
 const g=++generation
 try{
  const a=await api<OperatorAccess>('/access')
  if(disposed||g!==generation)return
  if(!a.root&&!a.roles.length){lock();error.value=l('Operator access was revoked.','运营权限已撤销。');return}
  access.value=a
  const v=await api<OperationOverview>('/overview')
  if(disposed||g!==generation)return
  overview.value=v
  if(a.root){
   const [r,e,s,rm]=await Promise.all([api<RoleGrant[]>('/roles'),api<Page<OperationEvent>>('/audit?offset='+offset.value+'&limit=30'),api<Page<OperatorSession>>('/sessions?offset='+sessionOffset.value+'&limit=30'),api<Page<OperatorRoom>>('/rooms?offset='+roomOffset.value+'&limit=30')])
   if(!disposed&&g===generation){grants.value=r;audit.value=e;sessions.value=s;rooms.value=rm}
  }else{grants.value=[];audit.value=null;sessions.value=null;rooms.value=null;enforcement.value=null}
 }catch(e){if(!disposed&&g===generation){lock();error.value=String(e)}}
}
async function act(fn:()=>Promise<void>){
 if(busy.value)return;busy.value=true;error.value='';notice.value=''
 try{await fn();await refresh()}catch(e){error.value=String(e)}finally{busy.value=false}
}
const unlock=()=>act(async()=>{
 const a=await api<OperatorAccess>('/access')
 if(!a.root&&!a.roles.length){key.value='';throw new Error(l('No operator role assigned to this account.','该账号未分配运营角色。'))}
 access.value=a
})
const grant=()=>act(async()=>{await api('/roles','POST',{username:name.value,role:role.value,reason:reason.value});name.value='';reason.value='';notice.value=l('Role granted.','角色已授予。')})
const revoke=()=>act(async()=>{const g=revokeTarget.value;if(g){await api('/roles/'+g.user.id+'/revoke','POST',{role:g.role,reason:revokeReason.value});revokeTarget.value=null;revokeReason.value='';notice.value=l('Role revoked.','角色已撤销。')}})
function selectEnforcement(kind:'session'|'room',id:string,label:string){enforcement.value={kind,id,label};enforcementReason.value=''}
const enforce=()=>act(async()=>{const e=enforcement.value;if(e){await api(e.kind==='session'?'/sessions/'+e.id+'/disconnect':'/rooms/'+e.id+'/close','POST',{reason:enforcementReason.value});enforcement.value=null;enforcementReason.value='';notice.value=l('Action completed and audited.','操作已执行并记录审计。')}})
const time=(v:number)=>new Date(v*1000).toLocaleString(props.language==='zh'?'zh-CN':'en-GB')
onMounted(()=>{timer=setInterval(()=>{if(!busy.value)void refresh()},7000)})
onBeforeUnmount(()=>{disposed=true;lock();if(timer)clearInterval(timer)})
</script>
<template>
 <section class="competition-page archive-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / OPERATIONS</div><h1>{{l('Operations','运营管理')}}</h1><p class="muted">{{l('Service overview, delegated account roles and operator audit.','运营概览、账号角色授权及操作审计。')}}</p></div><button v-if="access" class="secondary" @click="lock">{{l('Lock console','锁定后台')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice" role="status">{{notice}}</div>
  <p v-if="!configured" class="game-note">{{l('Configure root operator UUIDs and an independent operator credential on the server to enable this console.','在服务端配置根运营账号 UUID 和独立运营凭据后可启用后台。')}}</p>
  <form v-else-if="!access" class="competition-table-panel archive-inset" @submit.prevent="unlock"><label>{{l('Operator credential','运营凭据')}}<input v-model="key" type="password" required autocomplete="off"></label><button class="primary" :disabled="busy||!key">{{l('Unlock operations','进入运营管理')}}</button></form>
  <template v-else>
   <div class="game-note">{{access.root?l('Root operator','根运营管理员'):access.roles.map(roleName).join(' · ')}} · {{overview?.persistent?l('Shared MongoDB authority','共享 MongoDB 状态'):l('Memory authority','内存状态')}}</div>
   <div class="operations-grid"><div v-for="tile in tiles" :key="String(tile[0])"><span class="muted">{{tile[0]}}</span><strong>{{tile[1]}}</strong></div></div>
   <template v-if="access.root"><div class="competition-table-panel"><div class="panel-title">{{l('Delegated operator roles','运营角色授权')}}</div><form class="archive-inset" @submit.prevent="grant"><label>{{l('Account username','账号用户名')}}<input v-model="name" required autocomplete="off"></label><label>{{l('Operator role','运营角色')}}<select v-model="role" :aria-label="l('Operator role','运营角色')"><option v-for="r in roles" :key="r" :value="r">{{roleName(r)}}</option></select></label><label>{{l('Grant reason','授权原因')}}<textarea v-model="reason" required maxlength="4000" rows="2"/></label><button class="primary" :disabled="busy||!name.trim()||!reason.trim()">{{l('Grant role','授予角色')}}</button></form>
    <table><thead><tr><th>{{l('Account','账号')}}</th><th>{{l('Role','角色')}}</th><th>{{l('Granted by','授权人')}}</th><th>{{l('Time','时间')}}</th><th></th></tr></thead><tbody><tr v-for="g in grants" :key="g.user.id+g.role"><td>{{g.user.username}}</td><td>{{roleName(g.role)}}</td><td>{{g.grantedBy.username}}</td><td>{{time(g.at)}}</td><td><button class="secondary danger small-button" :disabled="busy" @click="revokeTarget=g;revokeReason=''">{{l('Revoke role','撤销角色')}}</button></td></tr></tbody></table><p v-if="!grants.length" class="muted competition-empty">{{l('No delegated roles.','暂无委派角色。')}}</p>
    <form v-if="revokeTarget" class="archive-inset" @submit.prevent="revoke"><strong>{{revokeTarget.user.username}} · {{roleName(revokeTarget.role)}}</strong><label>{{l('Revocation reason','撤销原因')}}<textarea v-model="revokeReason" required rows="2" maxlength="4000"/></label><button class="primary" :disabled="busy||!revokeReason.trim()">{{l('Confirm role revocation','确认撤销角色')}}</button></form>
   </div>
   <div class="competition-table-panel"><div class="panel-title">{{l('Active sessions','在线会话')}}</div><table><thead><tr><th>{{l('Account','账号')}}</th><th>{{l('Session expiry','会话到期')}}</th><th>{{l('Room / queue','房间 / 队列')}}</th><th></th></tr></thead><tbody><tr v-for="s in sessions?.entries??[]" :key="s.user.id"><td>{{s.user.username}}</td><td>{{time(s.expiresAt)}}</td><td>{{s.roomId?s.roomId.slice(0,8):s.queued?l('Queued','排队中'):'—'}}</td><td><button class="secondary danger small-button" :disabled="busy" @click="selectEnforcement('session',s.user.id,s.user.username)">{{l('Disconnect','断开会话')}}</button></td></tr></tbody></table><div class="competition-pagination"><span class="muted">{{sessions?.total??0}}</span><button class="secondary small-button" :disabled="sessionOffset===0" @click="sessionOffset=Math.max(0,sessionOffset-30);refresh()">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="sessionOffset+30>=(sessions?.total??0)" @click="sessionOffset+=30;refresh()">{{l('Next','下一页')}}</button></div></div>
   <div class="competition-table-panel"><div class="panel-title">{{l('Active rooms','房间管理')}}</div><table><thead><tr><th>{{l('Room','房间')}}</th><th>{{l('Game','游戏')}}</th><th>{{l('State','状态')}}</th><th>{{l('Players','人数')}}</th><th></th></tr></thead><tbody><tr v-for="r in rooms?.entries??[]" :key="r.id"><td>{{r.name}}</td><td>{{r.gameId}}</td><td>{{r.visibility}} · {{r.state}}</td><td>{{r.members}} / {{r.capacity}}</td><td><button class="secondary danger small-button" :disabled="busy" @click="selectEnforcement('room',r.id,r.name)">{{l('Close room','关闭房间')}}</button></td></tr></tbody></table><div class="competition-pagination"><span class="muted">{{rooms?.total??0}}</span><button class="secondary small-button" :disabled="roomOffset===0" @click="roomOffset=Math.max(0,roomOffset-30);refresh()">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="roomOffset+30>=(rooms?.total??0)" @click="roomOffset+=30;refresh()">{{l('Next','下一页')}}</button></div></div>
   <form v-if="enforcement" class="competition-table-panel archive-inset" @submit.prevent="enforce"><strong>{{enforcement.kind==='session'?l('Disconnect session','断开会话'):l('Close room','关闭房间')}}: {{enforcement.label}}</strong><p class="muted">{{l('This revokes current room/session authorization, not a ban or a match result.','此操作撤销当前房间或会话授权，不属于封禁或比赛判负。')}}</p><label>{{l('Operator reason','操作理由')}}<textarea v-model="enforcementReason" required maxlength="4000" rows="2"/></label><div class="archive-actions"><button class="primary" :disabled="busy||!enforcementReason.trim()">{{l('Confirm action','确认操作')}}</button><button type="button" class="secondary" @click="enforcement=null">{{l('Cancel','取消')}}</button></div></form>
   <div class="competition-table-panel"><div class="panel-title">{{l('Audit log','审计日志')}}</div><table><thead><tr><th>{{l('Time','时间')}}</th><th>{{l('Operator','操作人')}}</th><th>{{l('Action','操作')}}</th><th>{{l('Subject','对象')}}</th><th>{{l('Reason','原因')}}</th></tr></thead><tbody><tr v-for="e in audit?.entries??[]" :key="e.id"><td>{{time(e.at)}}</td><td>{{e.actor.username}}</td><td>{{e.action}}</td><td><code>{{e.subject}}</code></td><td>{{e.reason||'—'}}</td></tr></tbody></table><p v-if="!audit?.total" class="muted competition-empty">{{l('No operator actions recorded.','暂无操作记录。')}}</p><div class="competition-pagination"><span class="muted">{{audit?.total??0}}</span><button class="secondary small-button" :disabled="offset===0" @click="offset=Math.max(0,offset-30);refresh()">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="offset+30>=(audit?.total??0)" @click="offset+=30;refresh()">{{l('Next','下一页')}}</button></div></div></template>
  </template>
 </section>
</template>
