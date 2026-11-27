<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {ApiError,lobbySocket,request,serverOrigin,type Lobby,type Login,type Room,type Visibility} from './api'
import {messages,type Language,type Message} from './i18n'
import {nativeAvailable,startNetwork,stopNetwork,networkStatus,type NetworkInfo,type NetworkStatus} from './native'

const language=ref<Language>(localStorage.getItem('battle.lang')==='zh'?'zh':'en')
const t=(key:Message)=>messages[language.value][key]
function toggleLanguage(){language.value=language.value==='en'?'zh':'en';localStorage.setItem('battle.lang',language.value)}
const games=[
 {id:'warcraft3',en:'Warcraft III',zh:'魔兽争霸 III',short:'W3',color:'ochre'},
 {id:'redalert2',en:'Red Alert 2',zh:'红色警戒 2',short:'RA',color:'red'},
 {id:'cs16',en:'Counter-Strike 1.6',zh:'反恐精英 1.6',short:'CS',color:'blue'},
 {id:'custom',en:'Custom LAN',zh:'自定义局域网',short:'LAN',color:'teal'}
]
const gameName=(id:string)=>games.find(g=>g.id===id)?.[language.value]||id
const endpoint=ref(localStorage.getItem('battle.server')||'http://127.0.0.1:8080')
const origin=ref(''),username=ref(''),password=ref(''),register=ref(false),token=ref('')
const busy=ref(false),error=ref(''),connected=ref(false),lobby=ref<Lobby|null>(null)
const filter=ref('all'),search=ref(''),modal=ref<'create'|'join'|null>(null)
const roomName=ref(''),gameId=ref('custom'),capacity=ref(8),visibility=ref<Visibility>('PUBLIC')
const joinId=ref(''),joinCode=ref(''),invitation=ref(''),copied=ref(false)
let ws:WebSocket|null=null, timer:ReturnType<typeof setTimeout>|null=null
const room=computed(()=>lobby.value?.activeRoom||null)
const me=computed(()=>lobby.value?.user.id||'')
const host=computed(()=>!!room.value&&room.value.host===me.value)
const netInfo=ref<NetworkInfo|null>(null),netStatus=ref<NetworkStatus>({state:'idle',peers:[]})
const netError=ref(''),netBusy=ref(false),netPaused=ref(false)
const pin=computed(()=>netInfo.value&&origin.value?localStorage.getItem('battle.issuer.'+origin.value)||'':'')
const pinMismatch=computed(()=>!!pin.value&&!!netInfo.value&&pin.value!==netInfo.value.issuer)
const netTrusted=computed(()=>!!netInfo.value?.issuer&&pin.value===netInfo.value.issuer)
let netGen=0,netQueue:Promise<void>=Promise.resolve()
let netPoll:ReturnType<typeof setInterval>|null=null
const netStateLabel=computed(()=>{
 const map:Record<string,string>={idle:language.value==='zh'?'未连接':'Disconnected',starting:language.value==='zh'?'启动中':'Starting',enrolling:language.value==='zh'?'认证设备':'Enrolling',connecting:language.value==='zh'?'连接中':'Connecting',running:language.value==='zh'?'网卡运行中':'Adapter running',waiting:language.value==='zh'?'等待房间':'Waiting',revoked:language.value==='zh'?'权限已撤销':'Revoked',expired:language.value==='zh'?'授权已过期':'Expired',retrying:language.value==='zh'?'重新连接':'Retrying',error:language.value==='zh'?'连接失败':'Error'}
 return map[netStatus.value.state]||netStatus.value.state
})
function networkReconcile(){
 const gen=++netGen,currentToken=token.value,currentRoom=room.value?.id,currentOrigin=origin.value,userId=me.value
 netBusy.value=!!currentRoom&&nativeAvailable
 netError.value='';netInfo.value=null;netStatus.value={state:'idle',peers:[]}
 if(!nativeAvailable){netBusy.value=false;return}
 netQueue=netQueue.catch(()=>{}).then(async()=>{
  try{
   await stopNetwork()
   if(gen!==netGen||!currentToken||!currentRoom)return
   const info=await request<NetworkInfo>(currentOrigin,'/network/info',currentToken)
   if(gen!==netGen)return
   netInfo.value=info
   if(!info.enabled||!netTrusted.value||netPaused.value)return
   await startNetwork(currentOrigin,currentToken,info.issuer,userId)
   if(gen!==netGen){await stopNetwork();return}
   netStatus.value={state:'starting',peers:[]}
  }catch(e){if(gen===netGen)netError.value=e instanceof Error?e.message:String(e)}
  finally{if(gen===netGen)netBusy.value=false}
 })
}
function trustNetwork(){
 if(!netInfo.value?.enabled||!netInfo.value.issuer||!origin.value)return
 localStorage.setItem('battle.issuer.'+origin.value,netInfo.value.issuer)
 netPaused.value=false
 networkReconcile()
}
function toggleNetwork(){
 netPaused.value=!netPaused.value
 networkReconcile()
}
function retryNetwork(){netPaused.value=false;networkReconcile()}
function peerMode(mode:string,relayConnected:boolean){
 if(mode==='ice-selected')return 'P2P'
 if((mode==='relay-configured'||mode==='relay-fallback')&&relayConnected)return language.value==='zh'?'中继':'Relay'
 if(mode==='direct-configured')return language.value==='zh'?'直连':'Direct'
 return language.value==='zh'?'连接中':'Connecting'
}
watch(()=>[token.value,room.value?.id,origin.value],()=>{netPaused.value=false;networkReconcile()})
if(nativeAvailable)netPoll=setInterval(async()=>{
 if(!token.value||!room.value||!netTrusted.value||netPaused.value||netBusy.value)return
 const gen=netGen
 try{
  const status=await networkStatus()
  if(gen===netGen&&(!status.roomId||status.roomId===room.value?.id))netStatus.value=status
 }catch(e){if(gen===netGen)netError.value=e instanceof Error?e.message:String(e)}
},1000)
const myReady=computed(()=>room.value?.members.find(x=>x.userId===me.value)?.ready||false)
const canStart=computed(()=>!!room.value&&room.value.members.length>=2&&room.value.members.every(x=>x.ready))
const filtered=computed(()=> (lobby.value?.rooms||[]).filter(x=>
 (filter.value==='all'||x.gameId===filter.value)&&(!search.value||[x.name,gameName(x.gameId)].some(s=>s.toLowerCase().includes(search.value.toLowerCase())))))
watch(()=>[room.value?.id,room.value?.host,room.value?.membershipRevision],()=>{invitation.value='';copied.value=false})
function reset(message=''){
 token.value='';lobby.value=null;connected.value=false;modal.value=null;invitation.value='';joinCode.value='';password.value='';register.value=false
 if(timer){clearTimeout(timer);timer=null}
 const old=ws;ws=null;old?.close();error.value=message
}
function showError(e:unknown){error.value=e instanceof Error?e.message:String(e)}
async function api<T>(path:string,method='GET',body?:unknown):Promise<T>{
 const current=token.value
 try{return await request<T>(origin.value,path,current,method,body)}
 catch(e){if(e instanceof ApiError&&e.status===401&&token.value===current)reset(t('noLogin'));throw e}
}
function applyLobby(next:Lobby){
 if(!lobby.value||next.revision>=lobby.value.revision)lobby.value=next
}
async function refresh(){
 if(!token.value)return
 const current=token.value
 const next=await api<Lobby>('/lobby')
 if(token.value===current)applyLobby(next)
}
function connect(){
 const current=token.value;if(!current)return
 ws=lobbySocket(origin.value,current,next=>{
  if(token.value===current)applyLobby(next)
 },live=>{
  if(token.value!==current)return
  connected.value=live
  if(!live){
   if(timer)clearTimeout(timer)
   timer=setTimeout(async()=>{timer=null;if(token.value!==current)return;try{await refresh()}catch(e){if(token.value)showError(e)}if(token.value===current)connect()},2000)
  }
 })
}
async function perform(action:()=>Promise<void>){
 if(busy.value)return
 busy.value=true;error.value=''
 try{await action()}catch(e){showError(e)}finally{busy.value=false}
}
async function authenticate(){
 await perform(async()=>{
  const url=serverOrigin(endpoint.value)
  const login=await request<Login>(url,register.value?'/register':'/login',null,'POST',{username:username.value,password:password.value})
  origin.value=url;localStorage.setItem('battle.server',url);token.value=login.token;password.value=''
  try{await refresh();connect()}catch(e){reset();throw e}
 })
}
async function signOut(){await perform(async()=>{try{await api('/logout','POST')}finally{reset()}})}
function openCreate(){roomName.value='';gameId.value=filter.value==='all'?'custom':filter.value;capacity.value=8;visibility.value='PUBLIC';modal.value='create'}
function openJoin(){joinId.value='';joinCode.value='';modal.value='join'}
async function createRoom(){await perform(async()=>{await api<Room>('/rooms','POST',{name:roomName.value,gameId:gameId.value,capacity:capacity.value,visibility:visibility.value});modal.value=null;await refresh()})}
async function joinRoom(id:string,invite?:string){await perform(async()=>{await api('/rooms/'+encodeURIComponent(id)+'/join','POST',{invite});modal.value=null;joinCode.value='';await refresh()})}
async function action(name:string,body?:unknown){if(!room.value)return;const id=room.value.id;await perform(async()=>{await api('/rooms/'+id+'/'+name,'POST',body);await refresh()})}
async function reveal(rotate=false){if(!room.value)return;await perform(async()=>{const v=await api<{invite:string}>('/rooms/'+room.value!.id+'/invitation',rotate?'POST':'GET');invitation.value=v.invite;copied.value=false})}
async function copyInvite(){if(!room.value||!invitation.value)return;try{await navigator.clipboard.writeText(room.value.id+'\n'+invitation.value);copied.value=true}catch(e){showError(e)}}
onBeforeUnmount(()=>{if(netPoll)clearInterval(netPoll);reset();networkReconcile()})
</script>

<template>
 <div v-if="!token || !lobby" class="auth-page">
  <header class="auth-header"><div class="brand"><div class="brand-mark">B<span>×</span></div><span>Battle EX</span></div><button class="quiet" @click="toggleLanguage">{{t('language')}}</button></header>
  <main class="auth-main">
   <div class="auth-intro"><div class="eyebrow">{{t('devBuild')}}</div><h1>Battle<br>EX<span class="accent">.</span></h1><p>{{t('tagline')}}</p>
    <div class="network-art"><span class="orbit orbit-one"></span><span class="orbit orbit-two"></span><span class="network-node node-one">A</span><span class="network-node node-two">B</span><span class="network-node node-three">C</span><span class="network-center">EX</span></div>
   </div>
   <form class="auth-card" @submit.prevent="authenticate">
    <h2>{{register?t('register'):t('signIn')}}</h2><p class="muted">{{t('authHint')}}</p>
    <label>{{t('server')}}<input v-model="endpoint" required type="url" placeholder="http://127.0.0.1:8080"></label>
    <label>{{t('username')}}<input v-model="username" required autocomplete="username" spellcheck="false"></label>
    <label>{{t('password')}}<input v-model="password" required type="password" :autocomplete="register?'new-password':'current-password'"></label>
    <p v-if="error" class="error" role="alert">{{error}}</p>
    <button class="primary full" :disabled="busy" type="submit">{{busy?t('pending'):(register?t('register'):t('signIn'))}}</button>
    <div class="switch-auth">{{register?t('haveAccount'):t('needAccount')}} <button type="button" class="link" @click="register=!register;error=''">{{register?t('signIn'):t('register')}}</button></div>
    <p class="small muted">{{t('noPassword')}}</p>
   </form>
  </main>
 </div>
 <div v-else class="shell">
  <aside class="sidebar">
   <div class="brand"><div class="brand-mark">B<span>×</span></div><span>Battle EX</span></div>
   <div class="side-caption">{{t('lobby')}}</div>
   <button class="side-item" :class="{selected:filter==='all'}" @click="filter='all'"><span class="side-glyph">▦</span>{{t('all')}}</button>
   <div class="side-caption">{{t('gameCatalog')}}</div>
   <button v-for="g in games" :key="g.id" class="side-item" :class="{selected:filter===g.id}" @click="filter=g.id"><span class="game-icon" :class="g.color">{{g.short}}</span><span>{{gameName(g.id)}}</span></button>
   <div class="side-bottom"><div class="side-note">{{t('adapterNote')}}</div><div class="version">v0.1 · {{t('devBuild')}}</div></div>
  </aside>
  <main class="workspace">
   <header class="topbar"><div class="crumb">{{t('lobby')}} <span> / </span> {{filter==='all'?t('all'):gameName(filter)}}</div>
    <div class="top-actions"><span class="connection"><i :class="{online:connected}"></i>{{connected?t('live'):t('reconnecting')}}</span><button class="quiet" @click="toggleLanguage">{{t('language')}}</button><div class="user-avatar">{{lobby.user.username.slice(0,1).toUpperCase()}}</div><span class="username">{{lobby.user.username}}</span><button class="quiet" :disabled="busy" @click="signOut">{{t('signOut')}}</button></div>
   </header>
   <div class="workspace-body">
    <div class="main-panel">
     <div class="headline"><div><div class="eyebrow">BATTLE EX / LOBBY</div><h1>{{t('rooms')}}</h1><p class="muted">{{lobby.rooms.length}} {{t('roomCount')}} · {{lobby.signedInUsers}} {{t('signedIn')}}</p></div><button class="primary" :disabled="!!room||busy" @click="openCreate">＋ {{t('create')}}</button></div>
     <div class="toolbar"><input v-model="search" class="search" type="search" :placeholder="t('search')"><button class="secondary" :disabled="busy" @click="perform(refresh)">↻ {{t('refresh')}}</button><button class="secondary" :disabled="!!room||busy" @click="openJoin">{{t('privateJoin')}}</button></div>
     <div v-if="error" class="error banner" role="alert">{{error}}</div>
     <div class="room-list">
      <table><thead><tr><th>{{t('room')}}</th><th>{{t('game')}}</th><th>{{t('players')}}</th><th>{{t('status')}}</th><th></th></tr></thead><tbody>
       <tr v-for="r in filtered" :key="r.id"><td><strong>{{r.name}}</strong></td><td>{{gameName(r.gameId)}}</td><td class="numbers">{{r.occupancy}} / {{r.capacity}}</td><td><span class="state" :class="r.state==='IN_GAME'?'playing':'waiting'">{{r.state==='IN_GAME'?t('playing'):t('waiting')}}</span></td><td><button class="secondary small-button" :disabled="busy||!!room||r.state==='IN_GAME'||r.occupancy>=r.capacity" @click="joinRoom(r.id)">{{t('join')}}</button></td></tr>
      </tbody></table>
      <div v-if="!filtered.length" class="empty"><div class="empty-icon">▦</div><h3>{{t('noRooms')}}</h3><p>{{t('noRoomsBody')}}</p></div>
     </div>
    </div>
    <aside class="room-panel">
     <div class="panel-title"><span>{{t('activeRoom')}}</span><span class="tiny-label" v-if="room">{{room.visibility==='PRIVATE'?t('privateLabel'):t('publicLabel')}}</span></div>
     <div v-if="!room" class="empty room-empty"><div class="empty-icon">◈</div><h3>{{t('noActive')}}</h3><p>{{t('noActiveBody')}}</p></div>
     <template v-else>
      <div class="room-heading"><div class="eyebrow">{{gameName(room.gameId)}}</div><h2>{{room.name}}</h2><span class="state" :class="room.state==='IN_GAME'?'playing':'waiting'">{{room.state==='IN_GAME'?t('playing'):t('waiting')}}</span></div>
      <div class="member-header">{{t('players')}} <span>{{room.members.length}} / {{room.capacity}}</span></div>
      <div class="members"><div v-for="m in room.members" :key="m.userId" class="member"><span class="user-avatar">{{(lobby.memberNames[m.userId]||'?')[0].toUpperCase()}}</span><div class="member-name"><strong>{{lobby.memberNames[m.userId]||m.userId}}</strong><small v-if="m.userId===room.host">{{t('host')}}</small></div><span class="state" :class="m.ready?'waiting':'idle'">{{m.ready?t('ready'):t('notReady')}}</span></div></div>
      <div class="room-actions"><button v-if="room.state==='WAITING'" class="primary full" :disabled="busy" @click="action('ready',{ready:!myReady})">{{myReady?t('notReady'):t('ready')}}</button>
      <button v-if="host&&room.state==='WAITING'" class="secondary full" :disabled="busy||!canStart" @click="action('start')">{{t('start')}}</button><button v-if="host&&room.state==='IN_GAME'" class="secondary full" :disabled="busy" @click="action('finish')">{{t('finish')}}</button>
      <div v-if="host&&room.visibility==='PRIVATE'" class="invitation"><div class="label-row">{{t('invite')}}<button v-if="!invitation" class="link" @click="reveal()">{{t('reveal')}}</button><button v-else class="link" @click="reveal(true)">{{t('rotate')}}</button></div><template v-if="invitation"><label>{{t('roomId')}}<input :value="room.id" readonly></label><label>{{t('inviteCode')}}<input :value="invitation" readonly></label><button class="secondary full" @click="copyInvite">{{copied?t('copied'):t('copy')}}</button></template></div>
      <button class="secondary full danger" :disabled="busy" @click="action('leave')">{{t('leave')}}</button></div>
     </template>
     <section v-if="room" class="network-section">
      <div class="network-header"><strong>{{language==='zh'?'房间网络':'Room network'}}</strong><span class="state" :class="netStatus.state==='running'?'waiting':'idle'">{{netStateLabel}}</span></div>
      <div v-if="!nativeAvailable" class="network-help">{{language==='zh'?'需要桌面客户端才能建立虚拟网络。':'The desktop application is required for virtual networking.'}}</div>
      <template v-else>
       <p v-if="netBusy" class="network-help">{{language==='zh'?'正在配置网络…':'Configuring network…'}}</p>
       <p v-else-if="netInfo&&!netInfo.enabled" class="network-help">{{language==='zh'?'服务器尚未启用房间网络。':'Room networking is not configured on this server.'}}</p>
       <div v-if="netInfo?.enabled&&!netTrusted" class="trust-box">
        <p>{{pinMismatch?(language==='zh'?'服务器签名公钥已改变，请向管理员核实后再替换。':'The issuer has changed. Verify the new key with your administrator before replacing it.'):(language==='zh'?'首次连接，请核实并信任服务器签名公钥。':'Verify and trust the server signing key on first use.')}}</p>
        <code>{{netInfo.issuer}}</code><button class="secondary full" :disabled="netBusy" @click="trustNetwork">{{pinMismatch?(language==='zh'?'确认替换信任':'Replace trusted key'):(language==='zh'?'信任并连接':'Trust and connect')}}</button>
       </div>
       <template v-if="netInfo?.enabled&&netTrusted">
        <div v-if="netStatus.ip" class="network-ip"><span>{{language==='zh'?'本机虚拟 IP':'Local virtual IP'}}</span><code>{{netStatus.ip}}</code></div>
        <div v-for="peer in netStatus.peers||[]" :key="peer.userId" class="network-peer">
         <div><strong>{{lobby.memberNames[peer.userId]||peer.ip}}</strong><small>{{peer.ip}}</small></div>
         <div class="network-metrics"><span>{{peerMode(peer.mode,peer.relayConnected)}}</span><strong>{{peer.rttMillis===null?'—':peer.rttMillis.toFixed(1)+' ms'}}</strong></div>
        </div>
        <p v-if="netStatus.state==='running'&&!(netStatus.peers||[]).length" class="network-help">{{language==='zh'?'等待其他玩家连接网络。':'Waiting for other players to connect.'}}</p>
        <p v-if="netStatus.error" class="network-error">{{netStatus.error}}</p>
        <div class="network-controls"><button class="secondary" :disabled="netBusy" @click="toggleNetwork">{{netPaused?(language==='zh'?'连接网络':'Connect network'):(language==='zh'?'断开网络':'Disconnect network')}}</button><button v-if="['error','revoked','expired'].includes(netStatus.state)" class="secondary" :disabled="netBusy" @click="retryNetwork">{{language==='zh'?'重试':'Retry'}}</button></div>
       </template>
       <p v-if="netError" class="network-error">{{netError}}</p>
       <button v-if="netError&&!netInfo" class="secondary full" :disabled="netBusy" @click="retryNetwork">{{language==='zh'?'重试':'Retry'}}</button>
      </template>
      <p class="network-help">{{language==='zh'?'延迟来自实际 UDP 回显；不代表游戏兼容性。':'Latency is measured using UDP echo; game compatibility is not implied.'}}</p>
     </section>
    </aside>
   </div>
  </main>
  <div v-if="modal" class="modal-backdrop" @click.self="modal=null">
   <form class="modal" @submit.prevent="modal==='create'?createRoom():joinRoom(joinId,joinCode)">
    <h2>{{modal==='create'?t('create'):t('privateJoin')}}</h2>
    <template v-if="modal==='create'"><label>{{t('roomName')}}<input v-model="roomName" required maxlength="128"></label><label>{{t('game')}}<select v-model="gameId"><option v-for="g in games" :key="g.id" :value="g.id">{{gameName(g.id)}}</option></select></label><label>{{t('capacity')}}<input v-model.number="capacity" type="number" min="2" step="1" required></label><label>{{t('visibility')}}<select v-model="visibility"><option value="PUBLIC">{{t('publicRoom')}}</option><option value="PRIVATE">{{t('privateRoom')}}</option></select></label></template>
    <template v-else><p class="muted">{{t('joinHint')}}</p><label>{{t('roomId')}}<input v-model.trim="joinId" required autocomplete="off"></label><label>{{t('inviteCode')}}<input v-model.trim="joinCode" required autocomplete="off"></label></template>
    <p v-if="error" class="error" role="alert">{{error}}</p><div class="modal-actions"><button type="button" class="secondary" @click="modal=null;error=''">{{t('cancel')}}</button><button class="primary" type="submit" :disabled="busy">{{busy?t('pending'):modal==='create'?t('create'):t('join')}}</button></div>
   </form>
  </div>
 </div>
</template>
