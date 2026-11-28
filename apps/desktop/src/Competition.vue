<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import type {Language} from './i18n'
import {request,type CompetitionView,type MatchMode,type MatchRecord,type Leaderboard,type MatchHistory} from './api'
import catalog from '../game-catalog.json'

const props=defineProps<{origin:string;token:string;language:Language;competition?:CompetitionView;activeRoom:boolean;userId:string;revision:number;refreshLobby:()=>Promise<void>;openRoom:()=>void}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const games=ref('warcraft3'),mode=ref<MatchMode>('CASUAL'),region=ref('global'),tab=ref<'leaderboard'|'history'>('leaderboard')
const board=ref<Leaderboard|null>(null),history=ref<MatchHistory|null>(null)
const offset=ref(0),limit=50,loading=ref(false),working=ref(false),error=ref(''),now=ref(Math.floor(Date.now()/1000))
let disposed=false,gen=0,timer:ReturnType<typeof setInterval>|undefined
const ticket=computed(()=>props.competition?.ticket),match=computed(()=>props.competition?.match),pending=computed(()=>props.competition?.awaitingResult)
const rankedEnabled=computed(()=>props.competition?.rankedGames.includes(games.value)??false)
const count=computed(()=>tab.value==='leaderboard'?board.value?.total??0:history.value?.total??0)
const waited=computed(()=>ticket.value?Math.max(0,now.value-ticket.value.queuedAt):0)
const duration=computed(()=>Math.floor(waited.value/60)+':'+String(waited.value%60).padStart(2,'0'))
const name=(id:string)=>catalog.find(p=>p.id===id)?.[props.language]??id
const modeName=(m:MatchMode)=>m==='RANKED'?l('Ranked','排位'):m==='TOURNAMENT'?l('Tournament','赛事'):l('Casual','休闲')
function state(s:string){return ({WAITING:l('Waiting','等待准备'),RUNNING:l('In game','比赛中'),AWAITING_RESULT:l('Awaiting verified result','等待可信结果'),FINISHED:l('Finished · unrated','已结束 · 不计分'),CANCELLED:l('Cancelled','已取消'),ABANDONED:l('Abandoned · unrated','已中止 · 不计分'),NO_RESULT:l('No trusted result','无可信结果'),RATED:l('Rated','已结算')} as Record<string,string>)[s]??s}
function opponents(m:MatchRecord){return m.players.filter(p=>p.id!==props.userId).map(p=>p.username).join(', ')}
function result(m:MatchRecord){
 if(m.state!=='RATED')return state(m.state)
 return m.outcome==='DRAW'?l('Draw','平局'):m.winnerId===props.userId?l('Win','胜利'):l('Loss','失败')
}
function change(m:MatchRecord){const c=m.ratingChanges.find(c=>c.userId===props.userId);if(!c)return '—';const delta=c.after-c.before;return (delta>0?'+':'')+delta+' ('+c.after+')'}
const date=(seconds:number)=>new Date(seconds*1000).toLocaleString(props.language==='zh'?'zh-CN':'en-GB')
watch(rankedEnabled,v=>{if(!v)mode.value='CASUAL'})
async function refresh(){
 const g=++gen
 loading.value=true;error.value=''
 try{
  const query='?offset='+offset.value+'&limit='+limit
  if(tab.value==='leaderboard'){
   const v=await request<Leaderboard>(props.origin,'/competition/leaderboard/'+encodeURIComponent(games.value)+query,props.token)
   if(!disposed&&g===gen)board.value=v
  }else{
   const v=await request<MatchHistory>(props.origin,'/competition/history'+query,props.token)
   if(!disposed&&g===gen)history.value=v
  }
 }catch(e){if(!disposed&&g===gen)error.value=e instanceof Error?e.message:String(e)}
 finally{if(!disposed&&g===gen)loading.value=false}
}
watch([games,tab],()=>{offset.value=0;board.value=null;history.value=null;void refresh()})
watch(()=>props.competition?.match?.id,(id,old)=>{if(id&&id!==old)props.openRoom();void refresh()})
watch(()=>props.competition?.awaitingResult?.id,()=>{void refresh()})
function page(step:number){offset.value=Math.max(0,offset.value+step*limit);void refresh()}
async function action(cancel=false){
 if(working.value)return
 working.value=true;error.value=''
 try{
  await request(props.origin,'/competition/queue',props.token,cancel?'DELETE':'POST',cancel?undefined:{gameId:games.value,mode:mode.value,region:region.value})
  await props.refreshLobby();await refresh()
 }catch(e){error.value=e instanceof Error?e.message:String(e)}
 finally{working.value=false}
}
onMounted(()=>{void refresh();timer=setInterval(()=>{now.value=Math.floor(Date.now()/1000);if(now.value%8===0&&!loading.value)void refresh()},1000)})
onBeforeUnmount(()=>{disposed=true;++gen;if(timer)clearInterval(timer)})
</script>

<template>
 <section class="competition-page">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / COMPETITION</div><h1>{{l('Matchmaking','自动匹配')}}</h1><p class="muted">{{l('Find a 1v1 opponent and compare verified competitive ratings.','寻找 1v1 对手，查看可信结果结算的竞技积分。')}}</p></div><button class="secondary" :disabled="loading" @click="refresh">↻ {{l('Refresh','刷新')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div>
  <div class="competition-queue">
   <div class="competition-queue-main">
    <div class="eyebrow">{{l('MATCH QUEUE','匹配队列')}}</div>
    <h2 v-if="ticket">{{l('Searching for an opponent','正在寻找对手')}}</h2><h2 v-else-if="match">{{l('Match assigned','已分配比赛')}}</h2><h2 v-else>{{l('Find your next match','寻找下一场比赛')}}</h2>
    <template v-if="ticket"><p class="muted">{{name(ticket.gameId)}} · {{modeName(ticket.mode)}} · {{ticket.region||'global'}} · 1v1</p><div class="competition-clock">{{duration}}</div><button class="secondary" :disabled="working" @click="action(true)">{{l('Cancel matchmaking','取消匹配')}}</button></template>
    <template v-else-if="match"><p class="muted">{{name(match.gameId)}} · {{modeName(match.mode)}} · {{opponents(match)}}</p><button class="primary" @click="openRoom">{{l('Open match room','打开比赛房间')}}</button></template>
    <template v-else>
     <div class="competition-controls"><label>{{l('Game','游戏')}}<select v-model="games"><option v-for="g in catalog" :key="g.id" :value="g.id">{{language==='zh'?g.zh:g.en}}</option></select></label><label>{{l('Mode','模式')}}<select v-model="mode"><option value="CASUAL">{{l('Casual 1v1','休闲 1v1')}}</option><option value="RANKED" :disabled="!rankedEnabled||!!pending">{{l('Ranked 1v1','排位 1v1')}}</option></select></label><label>{{l('Region','地区')}}<select v-model="region" :aria-label="l('Region','地区')"><option value="global">{{l('Global','全球')}}</option><option value="asia">{{l('Asia','亚洲')}}</option><option value="europe">{{l('Europe','欧洲')}}</option><option value="americas">{{l('Americas','美洲')}}</option><option value="oceania">{{l('Oceania','大洋洲')}}</option></select></label></div>
     <p v-if="activeRoom" class="muted">{{l('Leave your current room before joining the queue.','请先离开当前房间，再进入匹配队列。')}}</p>
     <button class="primary" :disabled="activeRoom||working||!competition||(mode==='RANKED'&&(!rankedEnabled||!!pending))" @click="action()">{{l('Find match','开始匹配')}}</button>
    </template>
   </div>
   <div class="competition-queue-info"><div class="eyebrow">{{l('RULES','匹配规则')}}</div><strong>{{l('1v1 · Game-specific queues','1v1 · 按游戏匹配')}}</strong><p class="muted">{{l('Casual matches do not affect rating. Ranked mode is available only when the server has an authorized game-result issuer.','休闲比赛不影响积分。排位模式仅在服务端配置可信游戏结果来源时开放。')}}</p><p class="muted">{{l('Ranked rating starts at 1500; the allowable rating gap expands as both players wait.','排位初始积分 1500，双方等待时间增加时逐渐扩大匹配积分范围。')}}</p><p v-if="!rankedEnabled" class="muted">{{l('Ranked mode is not configured for this game.','当前游戏尚未开放可信排位结算。')}}</p><p v-if="pending" class="competition-pending">{{l('A previous ranked match is awaiting its signed result.','上场排位比赛正在等待可信签名结果。')}}</p></div>
  </div>
  <div class="competition-table-panel">
   <div class="competition-tabs"><button :class="{active:tab==='leaderboard'}" @click="tab='leaderboard'">{{l('Leaderboard','排行榜')}}</button><button :class="{active:tab==='history'}" @click="tab='history'">{{l('My match history','我的比赛记录')}}</button></div>
   <div v-if="tab==='leaderboard'" class="competition-table-tools"><label>{{l('Ranking game','排行游戏')}}<select v-model="games"><option v-for="g in catalog" :key="g.id" :value="g.id">{{language==='zh'?g.zh:g.en}}</option></select></label><span class="muted">{{l('Only verified ranked results','仅可信排位结果')}}</span></div>
   <div class="competition-scroll">
    <table v-if="tab==='leaderboard'"><thead><tr><th>{{l('Rank','名次')}}</th><th>{{l('Player','玩家')}}</th><th>{{l('Rating','积分')}}</th><th>{{l('Matches','场次')}}</th><th>{{l('W / D / L','胜 / 平 / 负')}}</th></tr></thead><tbody><tr v-for="row in board?.entries??[]" :key="row.rating.user.id" :class="{me:row.rating.user.id===userId}"><td>{{row.rank}}</td><td>{{row.rating.user.username}}</td><td><strong>{{row.rating.points}}</strong></td><td>{{row.rating.played}}</td><td>{{row.rating.wins}} / {{row.rating.draws}} / {{row.rating.losses}}</td></tr></tbody></table>
    <table v-else><thead><tr><th>{{l('Time','时间')}}</th><th>{{l('Game / mode','游戏 / 模式')}}</th><th>{{l('Opponent','对手')}}</th><th>{{l('Result','结果')}}</th><th>{{l('Rating change','积分变化')}}</th></tr></thead><tbody><tr v-for="m in history?.entries??[]" :key="m.id"><td>{{date(m.createdAt)}}</td><td>{{name(m.gameId)}} · {{modeName(m.mode)}}</td><td>{{opponents(m)}}</td><td>{{result(m)}}</td><td>{{change(m)}}</td></tr></tbody></table>
    <p v-if="!count&&!loading" class="muted competition-empty">{{tab==='leaderboard'?l('No verified ranked results yet.','暂无可信排位结果。'):l('No assigned matches yet.','暂无比赛记录。')}}</p>
   </div>
   <div class="competition-pagination"><span class="muted">{{count?offset+1:0}}–{{Math.min(offset+limit,count)}} / {{count}}</span><button class="secondary small-button" :disabled="loading||offset===0" @click="page(-1)">{{l('Previous','上一页')}}</button><button class="secondary small-button" :disabled="loading||offset+limit>=count" @click="page(1)">{{l('Next','下一页')}}</button></div>
  </div>
 </section>
</template>
