<script setup lang="ts">
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {request,type Page,type Tournament,type BracketSlot,type OperatorAccess} from './api'
import type {Language} from './i18n'
import catalog from '../game-catalog.json'
const props=defineProps<{origin:string;token:string;userId:string;language:Language;configured:boolean;busyInRoom:boolean}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const rows=ref<Page<Tournament>|null>(null),selected=ref(''),event=ref<Tournament|null>(null),offset=ref(0),busy=ref(false),error=ref(''),notice=ref('')
const key=ref(''),access=ref<OperatorAccess|null>(null),operator=ref(false)
const createOpen=ref(false),eventName=ref(''),game=ref('warcraft3'),desc=ref(''),capacity=ref(8)
const ruling=ref<BracketSlot|null>(null),winner=ref(''),source=ref<'PLAYED'|'FORFEIT'>('PLAYED'),reason=ref(''),cancelOpen=ref(false),cancelReason=ref('')
let gen=0,disposed=false,timer:ReturnType<typeof setInterval>|undefined
const registered=computed(()=>event.value?.entrants.some(e=>e.user.id===props.userId)??false)
const rounds=computed(()=>[...new Set(event.value?.slots.map(s=>s.round)??[])].sort((a,b)=>a-b))
const name=(id:string)=>catalog.find(g=>g.id===id)?.[props.language]??id
const entrant=(id:string|null)=>id?event.value?.entrants.find(e=>e.user.id===id)?.user.username??id:'—'
const state=(s:string)=>({REGISTRATION:l('Registration','报名中'),RUNNING:l('Running','进行中'),FINISHED:l('Finished','已结束'),CANCELLED:l('Cancelled','已取消'),BLOCKED:l('Awaiting previous round','等待上一轮'),READY:l('Check in','待签到'),ASSIGNED:l('Assigned','已分配'),BYE:l('Bye','轮空晋级'),DECIDED:l('Adjudicated','已裁决')} as Record<string,string>)[s]??s
const api=<T,>(path:string,method='GET',body?:unknown,admin=false)=>request<T>(props.origin,path,props.token,method,body,admin?key.value:undefined)
async function refresh(){
 const g=++gen
 try{
  const list=await api<Page<Tournament>>('/community/events?offset='+offset.value+'&limit=20')
  if(disposed||g!==gen)return
  rows.value=list
  if(selected.value){const d=await api<Tournament>('/community/events/'+selected.value);if(!disposed&&g===gen)event.value=d}
 }catch(e){if(!disposed&&g===gen)error.value=String(e)}
}
async function act(fn:()=>Promise<void>){
 if(busy.value)return;busy.value=true;error.value='';notice.value=''
 try{await fn();await refresh()}catch(e){error.value=String(e)}finally{busy.value=false}
}
function select(id:string){if(busy.value)return;selected.value=id;event.value=null;ruling.value=null;cancelOpen.value=false;void refresh()}
const unlock=()=>act(async()=>{
 const a=await api<OperatorAccess>('/operations/access','GET',undefined,true)
 if(!a.root&&!a.roles.includes('EVENT_MANAGER')){key.value='';throw new Error(l('Event manager role required.','需要赛事管理角色。'))}
 access.value=a;operator.value=true
})
function lock(){key.value='';access.value=null;operator.value=false;createOpen.value=false;ruling.value=null;cancelOpen.value=false}
const create=()=>act(async()=>{
 const e=await api<Tournament>('/community/admin/events','POST',{name:eventName.value,gameId:game.value,description:desc.value,capacity:capacity.value},true)
 selected.value=e.id;event.value=e;createOpen.value=false
})
const register=()=>act(async()=>{if(event.value)await api('/community/events/'+event.value.id+'/register',registered.value?'DELETE':'POST',undefined)})
const start=()=>act(async()=>{if(event.value)await api('/community/admin/events/'+event.value.id+'/start','POST',undefined,true)})
const check=(s:BracketSlot)=>act(async()=>{if(event.value)await api('/community/events/'+event.value.id+'/slots/'+s.id+'/check-in','POST',{ready:!s.checkedIn.includes(props.userId)})})
function choose(s:BracketSlot){ruling.value=s;winner.value='';source.value=s.matchId?'PLAYED':'FORFEIT';reason.value=''}
const decide=()=>act(async()=>{if(event.value&&ruling.value){await api('/community/admin/events/'+event.value.id+'/slots/'+ruling.value.id+'/decide','POST',{winner:winner.value,source:source.value,reason:reason.value},true);ruling.value=null}})
const retry=()=>act(async()=>{if(event.value&&ruling.value){await api('/community/admin/events/'+event.value.id+'/slots/'+ruling.value.id+'/retry','POST',{reason:reason.value},true);ruling.value=null}})
const cancel=()=>act(async()=>{if(event.value){await api('/community/admin/events/'+event.value.id+'/cancel','POST',{reason:cancelReason.value},true);cancelOpen.value=false}})
function page(step:number){offset.value=Math.max(0,offset.value+step*20);void refresh()}
onMounted(()=>{void refresh();timer=setInterval(()=>{if(!busy.value&&!ruling.value&&!createOpen.value&&!cancelOpen.value)void refresh()},5000)})
onBeforeUnmount(()=>{disposed=true;++gen;key.value='';if(timer)clearInterval(timer)})
</script>
<template>
 <section class="competition-page archive-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / TOURNAMENTS</div><h1>{{l('Tournaments','锦标赛')}}</h1><p class="muted">{{l('Individual 1v1 single-elimination events with recorded human adjudication.','个人 1v1 单淘汰赛事，人工裁决记录可追溯。')}}</p></div><button v-if="operator" class="primary" @click="createOpen=!createOpen">{{l('Create tournament','创建赛事')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice">{{notice}}</div>
  <form v-if="configured&&!operator" class="competition-table-panel archive-inset" @submit.prevent="unlock"><label>{{l('Event manager credential','赛事运营凭据')}}<input v-model="key" type="password" autocomplete="off" required></label><button class="secondary" :disabled="busy||!key">{{l('Unlock event management','进入赛事管理')}}</button></form>
  <div v-if="operator" class="game-note">{{l('Event manager access active.','赛事管理权限已启用。')}} <button class="link" @click="lock">{{l('Lock','锁定')}}</button></div>
  <form v-if="operator&&createOpen" class="competition-table-panel archive-inset" @submit.prevent="create"><label>{{l('Event name','赛事名称')}}<input v-model="eventName" required maxlength="100"></label><label>{{l('Game','游戏')}}<select v-model="game" :aria-label="l('Game','游戏')"><option v-for="g in catalog" :key="g.id" :value="g.id">{{name(g.id)}}</option></select></label><label>{{l('Entrant capacity','参赛名额')}}<input v-model.number="capacity" type="number" min="2" step="1" required></label><label>{{l('Rules / description','规则与介绍')}}<textarea v-model="desc" maxlength="4000" rows="3"/></label><button class="primary" :disabled="busy">{{l('Publish registration','开放报名')}}</button></form>
  <div class="competition-table-panel"><table><thead><tr><th>{{l('Event','赛事')}}</th><th>{{l('Game','游戏')}}</th><th>{{l('Entrants','参赛人数')}}</th><th>{{l('Status','状态')}}</th><th></th></tr></thead><tbody><tr v-for="e in rows?.entries??[]" :key="e.id"><td><strong>{{e.name}}</strong></td><td>{{name(e.gameId)}}</td><td>{{e.entrants.length}} / {{e.capacity}}</td><td>{{state(e.state)}}</td><td><button class="secondary small-button" @click="select(e.id)">{{l('Event details','赛事详情')}}</button></td></tr></tbody></table><p v-if="!rows?.total" class="muted competition-empty">{{l('No tournaments yet.','暂无赛事。')}}</p><div class="competition-pagination"><span class="muted">{{rows?.total??0}}</span><button class="secondary small-button" :disabled="offset===0" @click="page(-1)">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="offset+20>=(rows?.total??0)" @click="page(1)">{{l('Next','下一页')}}</button></div></div>
  <div v-if="event" class="competition-table-panel"><div class="panel-title">{{event.name}} · {{state(event.state)}} <button class="link" @click="selected='';event=null">{{l('Close','关闭')}}</button></div>
   <div class="archive-inset"><p class="archive-description">{{event.description}}</p><p class="muted">{{l('Joining publishes your username in the entrant list and bracket. Tournament rulings do not affect Elo.','报名将公开用户名及对阵信息；赛事裁决不影响 Elo。')}}</p>
    <div v-if="event.state==='REGISTRATION'" class="archive-actions"><button class="primary" :disabled="busy||(!registered&&event.entrants.length>=event.capacity)" @click="register">{{registered?l('Withdraw registration','取消报名'):l('Register for tournament','报名参赛')}}</button><button v-if="operator" class="secondary" :disabled="busy||event.entrants.length<2" @click="start">{{l('Generate bracket','生成对阵')}}</button></div>
    <p v-if="event.champion" class="accent"><strong>{{l('Champion','冠军')}}: {{entrant(event.champion)}}</strong></p><p v-if="event.cancellationReason" class="muted">{{event.cancellationReason}}</p>
    <h3>{{l('Entrants','参赛名单')}} ({{event.entrants.length}})</h3><div class="entrant-list"><span v-for="e in event.entrants" :key="e.user.id">{{e.user.username}}</span></div>
    <template v-if="event.slots.length"><h3>{{l('Single-elimination bracket','单淘汰对阵')}}</h3><div class="tournament-bracket"><div v-for="round in rounds" :key="round" class="tournament-round"><strong>{{l('Round','轮次')}} {{round}}</strong><div v-for="s in event.slots.filter(s=>s.round===round)" :key="s.id" class="tournament-slot"><div class="tournament-slot-head"><small>#{{s.id}} · {{state(s.state)}}</small></div><div :class="{winner:s.winner===s.first&&!!s.first}">{{entrant(s.first)}} <small v-if="s.first&&s.checkedIn.includes(s.first)">✓</small></div><div :class="{winner:s.winner===s.second&&!!s.second}">{{entrant(s.second)}} <small v-if="s.second&&s.checkedIn.includes(s.second)">✓</small></div><small v-if="s.ruling" class="muted">{{s.ruling.source==='PLAYED'?l('Manual played decision','人工赛果裁决'):l('Forfeit','弃权裁决')}} · {{s.ruling.operator.username}}<br/>{{s.ruling.reason}}</small><small v-if="s.matchId" class="muted">{{l('Assigned match','比赛 ID')}}: {{s.matchId.slice(0,8)}}</small><button v-if="event.state==='RUNNING'&&s.state==='READY'&&(s.first===userId||s.second===userId)" class="primary small-button" :disabled="busy||busyInRoom" @click="check(s)">{{s.checkedIn.includes(userId)?l('Withdraw check-in','取消签到'):l('Check in','签到')}}</button><button v-if="operator&&event.state==='RUNNING'&&['READY','ASSIGNED'].includes(s.state)" class="secondary small-button" @click="choose(s)">{{l('Adjudicate / retry','裁决 / 重赛')}}</button></div></div></div><p class="muted">{{l('Both players must check in while free of rooms and queues. Actual game results need human adjudication; no automatic win inference.','双方须在无房间、无匹配队列时签到；实际比赛结果由人工裁决，不自动推断胜负。')}}</p></template>
    <button v-if="operator&&['REGISTRATION','RUNNING'].includes(event.state)" class="secondary danger" @click="cancelOpen=!cancelOpen">{{l('Cancel tournament','取消赛事')}}</button>
    <form v-if="operator&&cancelOpen" @submit.prevent="cancel"><label>{{l('Cancellation reason','取消理由')}}<textarea v-model="cancelReason" required maxlength="4000" rows="2"/></label><button class="primary" :disabled="busy">{{l('Confirm cancellation','确认取消')}}</button></form>
    <form v-if="operator&&ruling" @submit.prevent="decide"><h3>{{l('Slot ruling','对阵裁决')}} #{{ruling.id}}</h3><label>{{l('Advance winner','晋级选手')}}<select v-model="winner" required :aria-label="l('Advance winner','晋级选手')"><option value="" disabled>{{l('Choose winner','选择胜者')}}</option><option v-if="ruling.first" :value="ruling.first">{{entrant(ruling.first)}}</option><option v-if="ruling.second" :value="ruling.second">{{entrant(ruling.second)}}</option></select></label><label>{{l('Decision source','裁决来源')}}<select v-model="source" :aria-label="l('Decision source','裁决来源')"><option value="PLAYED">{{l('Manually reviewed played match','人工审核已完成比赛')}}</option><option value="FORFEIT">{{l('Explicit forfeit / no-show ruling','明确弃权或缺席裁决')}}</option></select></label><label>{{l('Written reason','裁决理由')}}<textarea v-model="reason" required maxlength="4000" rows="3"/></label><div class="archive-actions"><button class="primary" :disabled="busy||!winner||!reason.trim()">{{l('Confirm advancement','确认晋级')}}</button><button type="button" v-if="ruling.matchId" class="secondary" :disabled="busy||!reason.trim()" @click="retry">{{l('Allow rematch','允许重赛')}}</button><button type="button" class="secondary" @click="ruling=null">{{l('Close','关闭')}}</button></div></form>
   </div>
  </div>
 </section>
</template>
