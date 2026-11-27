<script setup lang="ts">
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {request,type MatchRecord,type MatchHistory,type MatchDetail,type LiveMatch} from './api'
import {nativeAvailable,listReplays,importReplay,verifyReplay,exportReplay,removeReplay,type LocalReplay} from './native'
import type {Language} from './i18n'
import catalog from '../game-catalog.json'
const props=defineProps<{origin:string;token:string;userId:string;language:Language;activeMatch?:MatchRecord|null}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const tab=ref<'history'|'live'|'library'>('history')
const history=ref<MatchHistory|null>(null),live=ref<LiveMatch[]>([]),detail=ref<MatchDetail|null>(null)
const selected=ref(''),local=ref<LocalReplay[]>([]),game=ref('warcraft3'),offset=ref(0)
const busy=ref(false),error=ref(''),notice=ref(''),reportOpen=ref(false),reason=ref('CHEATING'),details=ref(''),evidence=ref('')
const isWindows=nativeAvailable&&navigator.userAgent.toLowerCase().includes('windows')
let disposed=false,generation=0,timer:ReturnType<typeof setInterval>|undefined
const name=(id:string)=>catalog.find(g=>g.id===id)?.[props.language]??id
const supported=(id:string)=>id==='warcraft3'||id==='cs16'
const opponent=computed(()=>detail.value?.match.players.find(p=>p.id!==props.userId))
const ownReplays=computed(()=>detail.value?.replays.filter(r=>r.uploader.id===props.userId)??[])
const localMatches=computed(()=>local.value.filter(r=>r.gameId===detail.value?.match.gameId))
const started=(m:MatchRecord)=>m.startedAt!=null
const ended=(m:MatchRecord)=>m.startedAt!=null&&m.endedAt!=null
const time=(s:number|null)=>s==null?'—':new Date(s*1000).toLocaleString(props.language==='zh'?'zh-CN':'en-GB')
function status(s:string){return ({WAITING:l('Waiting','等待准备'),RUNNING:l('Running','比赛中'),AWAITING_RESULT:l('Awaiting result','等待结果'),FINISHED:l('Finished · unrated','结束 · 不计分'),CANCELLED:l('Cancelled','取消'),ABANDONED:l('Abandoned','中止'),NO_RESULT:l('No trusted result','无可信结果'),RATED:l('Rated','已结算')} as Record<string,string>)[s]??s}
const api=<T,>(path:string,method='GET',body?:unknown)=>request<T>(props.origin,path,props.token,method,body)
async function refresh(){
 const g=++generation;const current=tab.value
 try{
  if(current==='history'){
   const h=await api<MatchHistory>('/competition/history?offset='+offset.value+'&limit=30')
   if(disposed||g!==generation)return
   history.value=h
   if(selected.value){
    const d=await api<MatchDetail>('/competition/matches/'+selected.value)
    if(disposed||g!==generation)return
    detail.value=d
   }
  }else if(current==='live'){
   const v=await api<LiveMatch[]>('/competition/live')
   if(!disposed&&g===generation)live.value=v
  }
  if(nativeAvailable){
   const v=await listReplays(props.origin,props.userId)
   if(!disposed&&g===generation)local.value=v
  }
 }catch(e){if(!disposed&&g===generation)error.value=String(e)}
}
function switchTab(t:typeof tab.value){if(busy.value)return;tab.value=t;selected.value='';detail.value=null;reportOpen.value=false;error.value='';notice.value='';void refresh()}
async function select(id:string){
 if(busy.value)return;selected.value=id;detail.value=null;reportOpen.value=false;details.value='';evidence.value='';error.value='';await refresh()
}
async function action(fn:()=>Promise<void>){
 if(busy.value)return;busy.value=true;error.value='';notice.value=''
 try{await fn();await refresh()}catch(e){error.value=String(e)}finally{busy.value=false}
}
const changeShare=()=>action(async()=>{
 const d=detail.value;if(!d)return
 detail.value=await api<MatchDetail>('/competition/matches/'+d.match.id+'/share-status','POST',{share:!d.sharingStatus})
})
const importLocal=(id:string)=>action(async()=>{
 const r=await importReplay(props.origin,props.userId,id)
 if(r)notice.value=l('Replay copied to your local library.','录像已复制到本地录像库。')
})
const attach=(r:LocalReplay)=>action(async()=>{
 const d=detail.value;if(!d)return
 const verified=await verifyReplay(props.origin,props.userId,r.id)
 await api('/competition/matches/'+d.match.id+'/replays','POST',{filename:verified.filename,sha256:verified.sha256,bytes:verified.bytes})
 notice.value=l('Replay metadata registered; the file remains on this computer.','录像档案已登记，原始文件仍保存在本机。')
})
const detach=(id:string)=>action(async()=>{if(detail.value)await api('/competition/matches/'+detail.value.match.id+'/replays/'+id,'DELETE')})
const localVerify=(r:LocalReplay)=>action(async()=>{await verifyReplay(props.origin,props.userId,r.id);notice.value=l('Archived file SHA-256 verified.','归档文件 SHA-256 校验通过。')})
const localExport=(r:LocalReplay)=>action(async()=>{if(await exportReplay(props.origin,props.userId,r.id))notice.value=l('Replay exported.','录像已导出。')})
const localRemove=(r:LocalReplay)=>action(async()=>{if(await removeReplay(props.origin,props.userId,r.id))notice.value=l('Local archive copy removed.','已移除本地录像副本。')})
const report=()=>action(async()=>{
 if(!detail.value||!opponent.value)return
 await api('/moderation/reports','POST',{matchId:detail.value.match.id,targetId:opponent.value.id,reason:reason.value,details:details.value,replaySha256:evidence.value||null})
 reportOpen.value=false;details.value='';notice.value=l('Report submitted for manual review.','举报已提交人工审核。')
})
function page(step:number){offset.value=Math.max(0,offset.value+step*30);selected.value='';detail.value=null;void refresh()}
onMounted(()=>{if(props.activeMatch)selected.value=props.activeMatch.id;void refresh();timer=setInterval(()=>{if(!busy.value&&!reportOpen.value)void refresh()},6000)})
onBeforeUnmount(()=>{disposed=true;++generation;if(timer)clearInterval(timer)})
</script>
<template>
 <section class="competition-page archive-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / MATCH CENTER</div><h1>{{l('Match center','比赛中心')}}</h1><p class="muted">{{l('Match details, consented live status and local replay archives.','比赛详情、双方同意的实时赛况及本地录像归档。')}}</p></div><button class="secondary" :disabled="busy" @click="refresh">↻ {{l('Refresh','刷新')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice" role="status">{{notice}}</div>
  <div class="competition-table-panel"><div class="competition-tabs"><button :class="{active:tab==='history'}" @click="switchTab('history')">{{l('Match archive','比赛档案')}}</button><button :class="{active:tab==='live'}" @click="switchTab('live')">{{l('Live status','实时赛况')}}</button><button :class="{active:tab==='library'}" @click="switchTab('library')">{{l('Replay library','本地录像库')}}</button></div>
   <template v-if="tab==='history'">
    <table><thead><tr><th>{{l('Game','游戏')}}</th><th>{{l('Players','玩家')}}</th><th>{{l('Started','开始时间')}}</th><th>{{l('Status','状态')}}</th><th></th></tr></thead><tbody><tr v-for="m in history?.entries??[]" :key="m.id"><td>{{name(m.gameId)}}</td><td>{{m.players.map(p=>p.username).join(' vs ')}}</td><td>{{time(m.startedAt)}}</td><td>{{status(m.state)}}</td><td><button class="secondary small-button" :disabled="busy" @click="select(m.id)">{{l('Details','详情')}}</button></td></tr></tbody></table>
    <p v-if="!history?.total" class="muted competition-empty">{{l('No assigned matches yet.','暂无匹配比赛记录。')}}</p>
    <div class="competition-pagination"><span class="muted">{{history?.total??0}} {{l('matches','场比赛')}}</span><button class="secondary small-button" :disabled="offset===0||busy" @click="page(-1)">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="offset+30>=(history?.total??0)||busy" @click="page(1)">{{l('Next','下一页')}}</button></div>
   </template>
   <template v-else-if="tab==='live'">
    <p class="game-note archive-inset">{{l('Only mutually approved running match status is shown. Game video, spectator access, private rooms and network grants are not exposed.','仅显示双方同意公开的进行中比赛赛况，不提供游戏视频、观战网络权限或私人房间访问。')}}</p>
    <table><thead><tr><th>{{l('Game','游戏')}}</th><th>{{l('Players','玩家')}}</th><th>{{l('Mode','模式')}}</th><th>{{l('Started','开始时间')}}</th></tr></thead><tbody><tr v-for="m in live" :key="m.matchId"><td>{{name(m.gameId)}}</td><td>{{m.players.map(p=>p.username).join(' vs ')}}</td><td>{{m.mode}}</td><td>{{time(m.startedAt)}}</td></tr></tbody></table><p v-if="!live.length" class="muted competition-empty">{{l('No public live matches.','暂无公开赛况。')}}</p>
   </template>
   <template v-else>
    <div class="archive-inset"><p class="muted">{{l('Import W3G or DEM files created by your own game. Files remain local; format/playback compatibility is not certified.','导入游戏生成的 W3G 或 DEM 文件。文件保存在本机，暂未认证格式及播放兼容性。')}}</p><div class="archive-tools"><select v-model="game"><option value="warcraft3">{{name('warcraft3')}} · W3G</option><option value="cs16">{{name('cs16')}} · DEM</option></select><button class="primary" :disabled="busy||!isWindows" @click="importLocal(game)">{{l('Import replay','导入录像')}}</button></div></div>
    <table><thead><tr><th>{{l('File','文件')}}</th><th>{{l('Size','大小')}}</th><th>SHA-256</th><th></th></tr></thead><tbody><tr v-for="r in local" :key="r.id"><td>{{r.filename}}</td><td>{{(r.bytes/1048576).toFixed(2)}} MiB</td><td><code :title="r.sha256">{{r.sha256.slice(0,12)}}…</code></td><td class="archive-actions"><button class="secondary small-button" :disabled="busy" @click="localVerify(r)">{{l('Verify','校验')}}</button><button class="secondary small-button" :disabled="busy||!isWindows" @click="localExport(r)">{{l('Export','导出')}}</button><button class="secondary small-button danger" :disabled="busy||!isWindows" @click="localRemove(r)">{{l('Remove','移除')}}</button></td></tr></tbody></table>
    <p v-if="!local.length" class="muted competition-empty">{{isWindows?l('No imported replays.','尚未导入录像。'):l('Windows desktop required for local file management.','需要 Windows 桌面客户端管理本地文件。')}}</p>
   </template>
  </div>
  <div v-if="detail&&tab==='history'" class="competition-table-panel archive-detail">
   <div class="panel-title"><span>{{name(detail.match.gameId)}} · {{status(detail.match.state)}}</span><button class="link" @click="selected='';detail=null">{{l('Close','关闭')}}</button></div>
   <div class="archive-inset"><h2>{{detail.match.players.map(p=>p.username).join(' vs ')}}</h2><p class="muted">{{l('Match ID','比赛 ID')}}: <code>{{detail.match.id}}</code></p><p class="muted">{{l('Created','创建')}}: {{time(detail.match.createdAt)}} · {{l('Started','开始')}}: {{time(detail.match.startedAt)}} · {{l('Ended','结束')}}: {{time(detail.match.endedAt)}}</p>
    <p v-if="detail.match.state==='RATED'">{{l('Verified result','可信结果')}}: {{detail.match.outcome==='DRAW'?l('Draw','平局'):detail.match.players.find(p=>p.id===detail?.match.winnerId)?.username}} <span v-for="c in detail.match.ratingChanges" :key="c.userId" class="archive-delta">{{detail.match.players.find(p=>p.id===c.userId)?.username}} {{c.before}} → {{c.after}}</span></p>
    <div v-if="detail.match.state==='RUNNING'" class="game-note"><p>{{detail.publicStatus?l('Both participants allow public live status.','双方已同意公开赛况。'):l('Live status is private until both participants opt in.','双方同意前赛况保持私密。')}}</p><button class="secondary" :disabled="busy" @click="changeShare">{{detail.sharingStatus?l('Withdraw live-status consent','撤回赛况公开授权'):l('Allow public live status','允许公开实时赛况')}}</button></div>
    <h3>{{l('Replay records','录像档案')}}</h3><p class="muted">{{l('Participant-visible metadata only. Remote files are not transferred or playable here.','仅比赛成员可见的录像元数据，不传输远端文件，不提供在线播放。')}}</p>
    <div v-for="r in detail.replays" :key="r.id" class="archive-record"><div><strong>{{r.filename}}</strong><small>{{r.uploader.username}} · {{r.sha256.slice(0,16)}}… · {{r.bytes}} B</small></div><button v-if="r.uploader.id===userId" class="link" :disabled="busy" @click="detach(r.id)">{{l('Remove record','移除记录')}}</button></div>
    <template v-if="ended(detail.match)&&supported(detail.match.gameId)">
     <button class="secondary" :disabled="busy||!isWindows" @click="importLocal(detail.match.gameId)">{{l('Import local replay','导入本地录像')}}</button>
     <div v-for="r in localMatches" :key="r.id" class="archive-record"><span>{{r.filename}}</span><button class="secondary small-button" :disabled="busy||ownReplays.some(x=>x.sha256===r.sha256)" @click="attach(r)">{{l('Verify and register metadata','校验并登记档案')}}</button></div>
    </template>
    <button v-if="started(detail.match)" class="secondary danger" :disabled="busy" @click="reportOpen=!reportOpen">{{l('Report participant','举报比赛成员')}}</button>
    <form v-if="reportOpen" @submit.prevent="report" class="archive-report"><label>{{l('Participant','被举报成员')}}<input :value="opponent?.username" readonly></label><label>{{l('Reason','举报原因')}}<select v-model="reason"><option value="CHEATING">{{l('Suspected cheating','疑似作弊')}}</option><option value="ABUSIVE_BEHAVIOR">{{l('Abusive behavior','不当行为')}}</option><option value="DISCONNECTION_ABUSE">{{l('Disconnection abuse','恶意断线')}}</option><option value="OTHER">{{l('Other','其他')}}</option></select></label><label>{{l('Description','情况说明')}}<textarea v-model="details" maxlength="4000" required rows="4"/></label><label>{{l('Your registered replay (optional)','本人登记的录像（可选）')}}<select v-model="evidence"><option value="">{{l('None','无')}}</option><option v-for="r in ownReplays" :key="r.id" :value="r.sha256">{{r.filename}}</option></select></label><button class="primary" :disabled="busy||!details.trim()" type="submit">{{l('Submit for review','提交审核')}}</button></form>
   </div>
  </div>
 </section>
</template>
