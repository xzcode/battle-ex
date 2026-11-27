<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref} from 'vue'
import {request,type ModerationReport,type Suspension,type WarningRecord,type Page} from './api'
import type {Language} from './i18n'
const props=defineProps<{origin:string;token:string;language:Language;configured:boolean}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const tab=ref<'mine'|'warnings'|'review'|'suspensions'>('mine'),key=ref(''),unlocked=ref(false),error=ref(''),notice=ref(''),busy=ref(false)
const reports=ref<Page<ModerationReport>|null>(null),suspensions=ref<Page<Suspension>|null>(null),warnings=ref<WarningRecord[]>([]),offset=ref(0),limit=30
const selected=ref<ModerationReport|null>(null),decision=ref('DISMISS'),resolution=ref(''),until=ref(''),permanent=ref(false)
const revokeId=ref(''),revokeReason=ref('')
let generation=0,disposed=false
const total=computed(()=>tab.value==='suspensions'?suspensions.value?.total??0:reports.value?.total??0)
const time=(v:number|null)=>v==null?'—':new Date(v*1000).toLocaleString(props.language==='zh'?'zh-CN':'en-GB')
const state=(s:string)=>({OPEN:l('Pending review','待审核'),DISMISSED:l('Dismissed','驳回'),WARNED:l('Warning issued','已警告'),SUSPENDED:l('Suspended','已封禁')} as Record<string,string>)[s]??s
const reasonName=(s:string)=>({CHEATING:l('Suspected cheating','疑似作弊'),ABUSIVE_BEHAVIOR:l('Abusive behavior','不当行为'),DISCONNECTION_ABUSE:l('Disconnection abuse','恶意断线'),OTHER:l('Other','其他')} as Record<string,string>)[s]??s
const api=<T,>(path:string,method='GET',body?:unknown,admin=false)=>request<T>(props.origin,path,props.token,method,body,admin?key.value:undefined)
async function refresh(){
 const g=++generation;error.value=''
 try{
  const query='?offset='+offset.value+'&limit='+limit
  if(tab.value==='warnings'){
   const data=await api<WarningRecord[]>('/moderation/warnings');if(!disposed&&g===generation)warnings.value=data
  }else if(tab.value==='mine'||tab.value==='review'){
   if(tab.value==='review'&&!unlocked.value)return
   const admin=tab.value==='review'
   const data=await api<Page<ModerationReport>>(admin?'/moderation/admin/reports'+query:'/moderation/reports/mine'+query,'GET',undefined,admin)
   if(!disposed&&g===generation)reports.value=data
  }else if(unlocked.value){
   const data=await api<Page<Suspension>>('/moderation/admin/suspensions'+query,'GET',undefined,true)
   if(!disposed&&g===generation)suspensions.value=data
  }
 }catch(e){if(!disposed&&g===generation)error.value=String(e)}
}
function navigate(next:typeof tab.value){tab.value=next;offset.value=0;selected.value=null;revokeId.value='';reports.value=null;suspensions.value=null;notice.value='';void refresh()}
function lock(){key.value='';unlocked.value=false;navigate('mine')}
async function unlock(){
 if(busy.value)return;busy.value=true;error.value=''
 try{await api('/moderation/admin/reports?limit=1','GET',undefined,true);unlocked.value=true;navigate('review')}
 catch(e){key.value='';error.value=String(e)}finally{busy.value=false}
}
function choose(r:ModerationReport){selected.value=r;decision.value='DISMISS';resolution.value='';until.value='';permanent.value=false}
async function review(){
 if(!selected.value||busy.value)return
 busy.value=true;error.value=''
 try{
  const expiry=decision.value==='SUSPEND'&&!permanent.value?Math.floor(new Date(until.value).getTime()/1000):null
  if(expiry!==null&&(!Number.isFinite(expiry)||expiry<=Date.now()/1000))throw new Error(l('Choose a future expiry or explicitly select permanent suspension.','请选择未来到期时间或明确勾选永久封禁。'))
  await api('/moderation/admin/reports/'+selected.value.id+'/review','POST',{decision:decision.value,resolution:resolution.value,until:expiry},true)
  selected.value=null;notice.value=l('Review recorded.','审核决定已记录。');await refresh()
 }catch(e){error.value=String(e)}finally{busy.value=false}
}
async function revoke(){
 if(!revokeId.value||busy.value)return
 busy.value=true;error.value=''
 try{await api('/moderation/admin/suspensions/'+revokeId.value+'/revoke','POST',{reason:revokeReason.value},true);revokeId.value='';revokeReason.value='';notice.value=l('Suspension revoked.','封禁已解除。');await refresh()}
 catch(e){error.value=String(e)}finally{busy.value=false}
}
function page(step:number){offset.value=Math.max(0,offset.value+step*limit);selected.value=null;void refresh()}
onMounted(()=>void refresh())
onBeforeUnmount(()=>{disposed=true;++generation;key.value=''})
</script>
<template>
 <section class="competition-page archive-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / FAIR PLAY</div><h1>{{l('Fair play','公平竞技')}}</h1><p class="muted">{{l('Reports, review decisions and account restrictions.','举报、审核决定及账号限制。')}}</p></div><button class="secondary" :disabled="busy" @click="refresh">↻ {{l('Refresh','刷新')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice" role="status">{{notice}}</div>
  <div class="competition-table-panel">
   <div class="competition-tabs"><button :class="{active:tab==='mine'}" @click="navigate('mine')">{{l('My reports','我的举报')}}</button><button :class="{active:tab==='warnings'}" @click="navigate('warnings')">{{l('My warnings','我的警告')}}</button><button v-if="configured" :class="{active:tab==='review'}" @click="navigate('review')">{{l('Review console','审核后台')}}</button><button v-if="unlocked" :class="{active:tab==='suspensions'}" @click="navigate('suspensions')">{{l('Suspensions','封禁管理')}}</button><button v-if="unlocked" @click="lock">{{l('Lock console','锁定后台')}}</button></div>
   <form v-if="tab==='review'&&!unlocked" class="archive-inset" @submit.prevent="unlock"><p class="muted">{{l('An authenticated account plus the separately configured operator credential is required. It is kept only in this view memory.','需要已登录账号及独立配置的运营凭据；凭据仅保存在本界面内存。')}}</p><label>{{l('Operator credential','运营凭据')}}<input v-model="key" type="password" autocomplete="off" required></label><button class="primary" :disabled="busy||!key" type="submit">{{l('Unlock review console','进入审核后台')}}</button></form>
   <template v-else-if="tab==='mine'||tab==='review'">
    <p class="muted archive-inset">{{tab==='mine'?l('Report a participant from the match archive. A report does not prove cheating.','可在比赛档案中举报对手。举报不等同于作弊证据。'):l('Review evidence manually; decisions are audited.','人工审核证据；所有决定记录审计信息。')}}</p>
    <table><thead><tr><th>{{l('Time','时间')}}</th><th v-if="tab==='review'">{{l('Reporter','举报者')}}</th><th>{{l('Target','被举报者')}}</th><th>{{l('Reason','原因')}}</th><th>{{l('Status','状态')}}</th><th></th></tr></thead><tbody><tr v-for="r in reports?.entries??[]" :key="r.id"><td>{{time(r.createdAt)}}</td><td v-if="tab==='review'">{{r.reporter.username}}</td><td>{{r.target.username}}</td><td>{{reasonName(r.reason)}}</td><td>{{state(r.status)}}</td><td><button class="secondary small-button" :disabled="busy" @click="choose(r)">{{l('Details','详情')}}</button></td></tr></tbody></table><p v-if="!reports?.total" class="muted competition-empty">{{l('No reports.','暂无举报。')}}</p>
   </template>
   <template v-else-if="tab==='warnings'">
    <div v-for="w in warnings" :key="w.id" class="archive-inset warning-row"><strong>{{time(w.at)}}</strong><p>{{w.message}}</p><small class="muted">{{w.matchId}}</small></div><p v-if="!warnings.length" class="muted competition-empty">{{l('No warnings.','暂无警告。')}}</p>
   </template>
   <template v-else>
    <table><thead><tr><th>{{l('Account','账号')}}</th><th>{{l('Created','封禁时间')}}</th><th>{{l('Expiry','到期')}}</th><th>{{l('Status','状态')}}</th><th>{{l('Reason','原因')}}</th><th></th></tr></thead><tbody><tr v-for="s in suspensions?.entries??[]" :key="s.id"><td>{{s.target.username}}</td><td>{{time(s.createdAt)}}</td><td>{{s.until?time(s.until):l('Permanent','永久')}}</td><td>{{s.revokedAt?l('Revoked','已解除'):s.until&&s.until<=Date.now()/1000?l('Expired','已到期'):l('Active','生效中')}}</td><td>{{s.reason}}</td><td><button v-if="!s.revokedAt" class="secondary small-button" :disabled="busy" @click="revokeId=s.id;revokeReason=''">{{l('Revoke','解除')}}</button></td></tr></tbody></table><p v-if="!suspensions?.total" class="muted competition-empty">{{l('No suspensions.','暂无封禁记录。')}}</p>
    <form v-if="revokeId" class="archive-inset" @submit.prevent="revoke"><label>{{l('Revocation reason','解除原因')}}<textarea v-model="revokeReason" maxlength="4000" required rows="3"/></label><button class="primary" :disabled="busy||!revokeReason.trim()" type="submit">{{l('Confirm revocation','确认解除')}}</button></form>
   </template>
   <div v-if="tab!=='warnings'&&(tab!=='review'||unlocked)" class="competition-pagination"><button class="secondary small-button" :disabled="offset===0||busy" @click="page(-1)">{{l('Previous','上一页')}}</button><span class="muted">{{total}}</span><button class="secondary small-button" :disabled="offset+limit>=total||busy" @click="page(1)">{{l('Next','下一页')}}</button></div>
  </div>
  <div v-if="selected" class="competition-table-panel archive-detail">
   <div class="panel-title">{{l('Report details','举报详情')}} <button class="link" @click="selected=null">{{l('Close','关闭')}}</button></div><div class="archive-inset"><strong>{{selected.reporter.username}} → {{selected.target.username}} · {{reasonName(selected.reason)}}</strong><p class="muted">{{selected.matchId}}</p><p class="archive-description">{{selected.details}}</p><p v-if="selected.replaySha256" class="muted">SHA-256: <code>{{selected.replaySha256}}</code> · {{l('Metadata, not verified game evidence','仅档案指纹，不代表已验证的游戏证据')}}</p><p v-if="selected.resolution">{{state(selected.status)}} · {{selected.resolution}}</p>
    <form v-if="tab==='review'&&unlocked&&selected.status==='OPEN'" @submit.prevent="review"><label>{{l('Decision','审核决定')}}<select v-model="decision" :aria-label="l('Decision','审核决定')"><option value="DISMISS">{{l('Dismiss','驳回')}}</option><option value="WARN">{{l('Warn','警告')}}</option><option value="SUSPEND">{{l('Suspend account','封禁账号')}}</option></select></label><label v-if="decision==='SUSPEND'" class="archive-check"><input v-model="permanent" type="checkbox"> {{l('Permanent suspension','永久封禁')}}</label><label v-if="decision==='SUSPEND'&&!permanent">{{l('Expire at (local time)','到期时间（本地）')}}<input v-model="until" type="datetime-local" required></label><label>{{l('Review reason','审核理由')}}<textarea :aria-label="l('Review reason','审核理由')" v-model="resolution" maxlength="4000" required rows="4"/></label><button class="primary" :disabled="busy||!resolution.trim()" type="submit">{{l('Confirm review','确认审核')}}</button></form>
   </div>
  </div>
 </section>
</template>
