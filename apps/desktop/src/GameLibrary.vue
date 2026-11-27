<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import {nativeAvailable,listGames,detectGame,chooseGame,adoptGame,verifyGame,removeGame,launchGame,joinCsPeer,type GameEntry,type PeerNetworkStatus} from './native'
import type {Language} from './i18n'
import type {GameBuild} from './api'
import catalog from '../game-catalog.json'

const props=defineProps<{language:Language;initialGame:string;roomGame?:string;roomId?:string;roomState?:string;reportedBuild?:GameBuild|null;networkReady:boolean;networkPeers:PeerNetworkStatus[];memberNames:Record<string,string>;publishBuild:(roomId:string,build:GameBuild|null)=>Promise<void>}>()
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const selected=ref(catalog.some(p=>p.id===props.initialGame)?props.initialGame:'warcraft3')
const profile=computed(()=>catalog.find(p=>p.id===selected.value)!)
const entries=ref<GameEntry[]>([])
const entry=computed(()=>entries.value.find(e=>e.id===selected.value))
const installation=computed(()=>entry.value?.install)
const busy=ref(false),error=ref(''),notice=ref(''),candidates=ref<string[]>([])
let disposed=false
let timer:ReturnType<typeof setInterval>|undefined
const windowsDesktop=nativeAvailable&&navigator.userAgent.toLowerCase().includes('windows')
const discovery=computed(()=>{
 switch(profile.value.discovery){
  case 'broadcast-pending':return l('Experimental directed UDP discovery can be enabled by the server; Windows real-game testing is pending.','服务端可启用实验性定向 UDP 发现；Windows 真机游戏尚未验收。')
  case 'edition-pending':return l('Multiplayer discovery depends on the exact edition and installed LAN components.','联机发现取决于具体版本及已安装的局域网组件。')
  default:return l('Manual IPv4 connection: use the room peer address in the game, if supported.','手动 IPv4 连接：若游戏支持直接 IP，请使用房间玩家虚拟地址。')
 }
})
const edition=computed(()=>{
 const values:Record<string,[string,string]>={
  warcraft3:['Classic / The Frozen Throne candidates','经典版 / 冰封王座候选版本'],
  redalert2:['Base game and Yuri’s Revenge are distinct targets','原版和尤里的复仇属于不同适配目标'],
  cs16:['GoldSrc / Counter-Strike 1.6','GoldSrc / 反恐精英 1.6'],
  custom:['User-selected Windows executable','用户指定的 Windows 本地程序']
 }
 const v=values[profile.value.id];return v?l(v[0],v[1]):profile.value.edition
})
function state(e:GameEntry|undefined){
 if(e?.pid)return l('Launched process running','已启动进程运行中')
 switch(e?.state){
  case 'configured':return l('Configured','已配置')
  case 'missing':return l('File missing','文件缺失')
  case 'changed':return l('File changed','文件发生变化')
  default:return l('Not configured','未配置')
 }
}
async function refresh(){
 if(!nativeAvailable||disposed)return
 try{const v=await listGames();if(!disposed)entries.value=v}
 catch(e){if(!disposed)error.value=String(e)}
}
async function perform(action:()=>Promise<void>){
 if(busy.value)return
 busy.value=true;error.value='';notice.value=''
 try{await action();await refresh()}catch(e){if(!disposed)error.value=String(e)}
 finally{busy.value=false}
}
function select(id:string){if(busy.value)return;selected.value=id;candidates.value=[];error.value='';notice.value=''}
watch(()=>props.initialGame,id=>{if(catalog.some(p=>p.id===id))select(id)})
const detect=()=>perform(async()=>{
 candidates.value=await detectGame(selected.value)
 notice.value=candidates.value.length?l('Choose an installation below or browse to its file.','找到候选安装，可在下方查看路径，或直接浏览文件。'):l('No candidate in common folders. You can choose a file manually.','常用目录未找到候选安装，请手动选择程序。')
})
const choose=()=>perform(async()=>{if(await chooseGame(selected.value))notice.value=l('Local executable saved.','已保存本机游戏程序。')})
const adopt=(path:string)=>perform(async()=>{if(await adoptGame(selected.value,path)){notice.value=l('Detected installation saved.','已保存检测到的安装。');candidates.value=[]}})
const verify=()=>perform(async()=>{notice.value=(await verifyGame(selected.value))?l('Executable SHA-256 matches your saved file.','可执行文件 SHA-256 与已保存记录一致。'):l('Executable differs. Choose the updated file again before launch.','文件内容不同；启动前请重新选择并确认更新后的文件。')})
const remove=()=>perform(async()=>{await removeGame(selected.value);notice.value=l('Configuration removed; game files were not deleted.','配置已移除，未删除任何游戏文件。')})
const publish=()=>perform(async()=>{
 const roomId=props.roomId,id=selected.value
 if(!roomId||props.roomGame!==id||props.roomState!=='WAITING')throw new Error(l('Join a waiting room for this game first.','请先加入该游戏的等待中房间。'))
 if(!await verifyGame(id))throw new Error(l('Executable changed. Select it again before sharing.','程序已改变，请重新选择后再上报。'))
 const current=(await listGames()).find(e=>e.id===id)?.install
 if(!current)throw new Error(l('No configured executable.','尚未配置程序。'))
 // Only the explicitly chosen metadata is sent. No paths, file bytes or private keys.
 await props.publishBuild(roomId,{gameId:id,sha256:current.sha256,fileVersion:current.fileVersion,architecture:current.architecture})
 notice.value=l('Build report shared with current room members.','已向当前房间成员分享版本报告。')
})
const withdraw=()=>perform(async()=>{
 if(props.roomId)await props.publishBuild(props.roomId,null)
 notice.value=l('Build report removed.','已撤回版本报告。')
})
const csPeer=ref(''),csPort=ref(27015)
const csPeers=computed(()=>props.networkReady&&props.roomGame==='cs16'?props.networkPeers:[])
watch(csPeers,peers=>{if(!peers.some(p=>p.userId===csPeer.value))csPeer.value=peers[0]?.userId??''},{immediate:true})
const joinCs=()=>perform(async()=>{
 if(!props.roomId||!csPeer.value||!Number.isInteger(csPort.value)||csPort.value<1||csPort.value>65535)throw new Error(l('Select a room peer and valid port.','请选择房间成员及有效端口。'))
 const pid=await joinCsPeer(props.roomId,csPeer.value,csPort.value)
 if(pid!=null)notice.value=l('Game process launched; game connection is not yet confirmed.','游戏进程已启动；尚未确认游戏内连接。')
})
const launch=()=>perform(async()=>{const pid=await launchGame(selected.value);if(pid!=null)notice.value=l('Local process launched (PID ','本地进程已启动（PID ')+pid+')'})
async function copy(text:string){
 try{await navigator.clipboard.writeText(text);notice.value=l('Copied','已复制')}
 catch{error.value=l('Clipboard unavailable; select and copy the text manually.','剪贴板不可用，请手动选中文本复制。')}
}
onMounted(()=>{void refresh();if(nativeAvailable)timer=setInterval(()=>{if(!busy.value)void refresh()},2500)})
onBeforeUnmount(()=>{disposed=true;if(timer)clearInterval(timer)})
</script>
<template>
 <section class="game-library">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / GAMES</div><h1>{{l('Game library','本地游戏库')}}</h1><p class="muted">{{l('Manage installed games and launch them locally.','管理本机安装的游戏并启动。')}}</p></div><button class="secondary" :disabled="busy||!nativeAvailable" @click="refresh">↻ {{l('Refresh','刷新')}}</button></div>
  <div v-if="!nativeAvailable" class="game-note">{{l('Desktop app required. This browser can view profiles but cannot access or launch local files.','需要桌面客户端。浏览器仅可查看游戏目录，无法读取或启动本机文件。')}}</div>
  <div v-else-if="!windowsDesktop" class="game-note">{{l('This first native adapter is Windows-only.','首批原生游戏适配仅支持 Windows。')}}</div>
  <div class="game-library-grid">
   <nav class="game-library-list" :aria-label="l('Game profiles','游戏目录')">
    <button v-for="p in catalog" :key="p.id" class="game-library-item" :class="{active:selected===p.id}" :disabled="busy" @click="select(p.id)">
     <span class="game-icon" :class="p.color" aria-hidden="true">{{p.short}}</span><span><strong>{{language==='zh'?p.zh:p.en}}</strong><small>{{state(entries.find(e=>e.id===p.id))}}</small></span>
    </button>
   </nav>
   <div class="game-library-detail">
    <div class="game-detail-head"><span class="game-icon big" :class="profile.color">{{profile.short}}</span><div><h2>{{language==='zh'?profile.zh:profile.en}}</h2><p class="muted">{{edition}}</p></div><span class="state" :class="entry?.pid?'playing':entry?.state==='configured'?'waiting':'idle'">{{state(entry)}}</span></div>
    <div class="game-detail-body">
     <div class="game-note"><strong>{{l('Compatibility: pending real-game validation','兼容性：等待真实游戏验收')}}</strong><p>{{discovery}}</p></div>
     <div v-if="roomGame===selected" class="game-note">{{networkReady?l('Room virtual network is running. Game compatibility still requires validation.','当前房间虚拟网卡正在运行，仍需验证游戏联机兼容性。'):l('You are in a matching room, but its virtual network is not ready.','当前已加入对应房间，但虚拟网络尚未就绪。')}}</div>
     <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="game-notice" role="status">{{notice}}</div>
     <div class="game-field"><span>{{l('Executable','游戏程序')}}</span><div v-if="installation" class="game-value"><code>{{installation.path}}</code><button class="link" @click="copy(installation.path)">{{l('Copy','复制')}}</button></div><p v-else class="muted">{{l('No executable selected','尚未选择游戏程序')}}</p></div>
     <div class="game-controls"><button class="secondary" :disabled="!windowsDesktop||busy||!!entry?.pid||profile.id==='custom'" @click="detect">{{l('Find installations','检测安装')}}</button><button class="secondary" :disabled="!windowsDesktop||busy||!!entry?.pid" @click="choose">{{l('Choose executable','选择程序')}}</button><button v-if="installation" class="secondary" :disabled="!windowsDesktop||busy||!!entry?.pid" @click="remove">{{l('Remove configuration','移除配置')}}</button></div>
     <div v-if="candidates.length" class="game-candidates">
      <strong>{{l('Detected paths (not automatically approved)','检测到的路径（不会自动信任）')}}</strong>
      <div v-for="path in candidates" :key="path" class="game-value"><code>{{path}}</code><button class="secondary small-button" :disabled="busy||!!entry?.pid" @click="adopt(path)">{{l('Use','使用')}}</button><button class="link" @click="copy(path)">{{l('Copy','复制')}}</button></div>
     </div>
     <template v-if="installation">
      <div class="game-info-grid">
       <div class="game-field"><span>{{l('File version','文件版本')}}</span><strong>{{installation.fileVersion||l('Not provided','未提供')}}</strong></div>
       <div class="game-field"><span>{{l('Architecture','架构')}}</span><strong>{{installation.architecture}}</strong></div>
       <div class="game-field"><span>{{l('File size','文件大小')}}</span><strong>{{(installation.size/1048576).toFixed(2)}} MiB</strong></div>
       <div class="game-field"><span>{{l('Process','进程')}}</span><strong>{{entry?.pid?'PID '+entry.pid:entry?.exitCode!=null?l('Exited: ','已退出：')+entry.exitCode:'—'}}</strong></div>
      </div>
      <div class="game-field"><span>SHA-256</span><div class="game-value"><code>{{installation.sha256}}</code><button class="link" @click="copy(installation.sha256)">{{l('Copy','复制')}}</button></div><p class="muted">{{l('Fingerprint checks the selected executable only, not maps, mods, DLLs or the network protocol.','指纹仅校验选中的可执行文件，不代表地图、MOD、DLL 或联机协议一致。')}}</p></div>
     </template>
     <div v-if="roomGame===selected&&roomId" class="game-note">
      <strong>{{l('Room build comparison','房间版本比对')}}</strong>
      <p>{{l('Sharing is optional. Only the executable fingerprint, file version and architecture are shared with room members. Reports are self-declared, not anti-cheat proof.','分享为可选操作。仅向房间成员共享程序指纹、文件版本与架构；该报告为客户端自行上报，不代表反作弊认证。')}}</p>
      <p v-if="reportedBuild">{{l('Shared fingerprint: ','已分享指纹：')}}<code>{{reportedBuild.sha256.slice(0,12)}}…</code></p><p v-if="reportedBuild&&installation&&reportedBuild.sha256!==installation.sha256">{{l('Your saved executable differs from the shared report. Verify and share again, or withdraw the old report.','当前保存的程序与已分享报告不同。请重新校验并分享，或撤回旧报告。')}}</p>
      <div class="game-controls">
       <button class="secondary" :disabled="!windowsDesktop||busy||!installation||roomState!=='WAITING'" @click="publish">{{l('Verify and share build','校验并分享版本')}}</button>
       <button v-if="reportedBuild" class="secondary" :disabled="busy||roomState!=='WAITING'" @click="withdraw">{{l('Withdraw report','撤回报告')}}</button>
      </div>
     </div>
     <div v-if="selected==='cs16'&&roomGame==='cs16'" class="game-note">
      <strong>{{l('Join a peer-hosted CS server','加入成员的 CS 服务器')}}</strong>
      <p>{{l('The peer must already be hosting a compatible LAN server. Select its room address and game port; the native launcher rechecks the authorized peer.','对方必须已开启兼容的局域网服务器。请选择成员及游戏端口；原生启动器会重新校验该成员的授权地址。')}}</p>
      <div class="game-info-grid">
       <label>{{l('Room peer','房间成员')}}<select v-model="csPeer" :disabled="!csPeers.length||busy"><option v-for="peer in csPeers" :key="peer.userId" :value="peer.userId">{{memberNames[peer.userId]||peer.ip}} · {{peer.ip}}</option></select></label>
       <label>{{l('Game port','游戏端口')}}<input v-model.number="csPort" type="number" min="1" max="65535" step="1"></label>
      </div>
      <button class="secondary" :disabled="!windowsDesktop||busy||!installation||!!entry?.pid||!csPeer||!networkReady" @click="joinCs">{{l('Launch and join','启动并加入')}}</button>
     </div>
     <div class="game-controls bottom"><button class="secondary" :disabled="!windowsDesktop||busy||!installation||entry?.state==='missing'" @click="verify">{{l('Verify file','校验文件')}}</button><button class="primary" :disabled="!windowsDesktop||busy||!installation||!!entry?.pid||entry?.state==='missing'" @click="launch">{{l('Launch game','启动游戏')}}</button></div>
     <p class="muted small">{{l('Game files and paths stay on this computer. Battle EX launches only the selected local executable after a native confirmation. It never distributes games or automatically terminates them.','游戏文件及路径只保存在本机。Battle EX 仅在原生确认后启动本机选中的程序，不分发游戏，也不会自动结束游戏进程。')}}</p>
    </div>
   </div>
  </div>
 </section>
</template>
