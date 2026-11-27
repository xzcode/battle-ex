<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref} from 'vue'
import {request} from './api'
import type {Language} from './i18n'
const props=defineProps<{origin:string;token:string;language:Language}>()
type Stats={connections:number;ingress_bytes:number;egress_bytes:number;dropped_packets:number}
type Node={id:string;region:string;state:string;seenAt:number;stats:Stats|null;maxConnections:number;bytesPerSecond:number}
type Minute={minute:number;peakConnections:number;ingressBytes:number;egressBytes:number;droppedPackets:number;samples:number;discontinuities:number}
type History={node:string;retentionMinutes:number;minutes:Minute[]}
const nodes=ref<Node[]>([]),selected=ref(''),history=ref<History|null>(null),loading=ref(false),error=ref(''),updated=ref(0)
let timer:ReturnType<typeof setInterval>|undefined
let disposed=false
const l=(en:string,zh:string)=>props.language==='zh'?zh:en
const online=computed(()=>nodes.value.filter(n=>n.state==='online').length)
const active=computed(()=>nodes.value.filter(r=>r.state==='online'||r.state==='draining').reduce((n,r)=>n+(r.stats?.connections??0),0))
const current=computed(()=>nodes.value.find(n=>n.id===selected.value))
const minutes=computed(()=>[...(history.value?.minutes??[])].reverse())
const chart=computed(()=>history.value?.minutes.slice(-60)??[])
const maximum=computed(()=>Math.max(1,...chart.value.map(m=>m.peakConnections)))
function bytes(n:number|null|undefined){
 if(n==null)return '—'
 const units=['B','KiB','MiB','GiB','TiB'];let i=0
 while(n>=1024&&i<units.length-1){n/=1024;i++}
 return n.toFixed(i?1:0)+' '+units[i]
}
const time=(v:number)=>v?new Date(v*1000).toLocaleString(props.language==='zh'?'zh-CN':'en-GB'):'—'
function state(s:string){return ({online:l('Online','在线'),offline:l('Offline','离线'),draining:l('Maintenance','维护中'),static:l('Static','静态配置')} as Record<string,string>)[s]||s}
let requestGeneration=0
async function refresh(){
 if(loading.value||disposed)return
 const g=++requestGeneration;loading.value=true;error.value=''
 try{
  const result=await request<Node[]>(props.origin,'/network/relays',props.token)
  if(disposed||g!==requestGeneration)return
  nodes.value=result
  if(!result.some(n=>n.id===selected.value)){selected.value=result[0]?.id??'';history.value=null}
  const id=selected.value
  const next=id?await request<History>(props.origin,'/network/relays/'+encodeURIComponent(id)+'/history',props.token):null
  if(!disposed&&g===requestGeneration&&id===selected.value){history.value=next;updated.value=Date.now()}
 }catch(e){if(!disposed&&g===requestGeneration)error.value=e instanceof Error?e.message:String(e)}
 finally{if(!disposed&&g===requestGeneration)loading.value=false}
}
function select(id:string){
 if(id===selected.value)return
 selected.value=id;history.value=null;++requestGeneration;loading.value=false;void refresh()
}
onMounted(()=>{void refresh();timer=setInterval(()=>void refresh(),5000)})
onBeforeUnmount(()=>{disposed=true;++requestGeneration;if(timer)clearInterval(timer)})
</script>
<template>
 <section class="relay-monitor">
  <div class="headline"><div><div class="eyebrow">BATTLE EX / NETWORK</div><h1>{{l('Relay monitor','中继节点监控')}}</h1><p class="muted">{{l('Live node counters and minute-level history.','节点实时状态及分钟级流量历史。')}}</p></div><button class="secondary" :disabled="loading" @click="refresh">↻ {{l('Refresh','刷新')}}</button></div>
  <div v-if="error" class="error" role="alert">{{error}}</div>
  <div class="relay-summary"><div><span>{{l('Configured nodes','已配置节点')}}</span><strong>{{nodes.length}}</strong></div><div><span>{{l('Online nodes','在线节点')}}</span><strong>{{online}}</strong></div><div><span>{{l('Reported connections','上报连接数')}}</span><strong>{{active}}</strong></div></div>
  <div class="relay-monitor-panel"><div class="panel-title">{{l('Nodes','节点列表')}} <small class="muted">{{updated?new Date(updated).toLocaleTimeString():'—'}}</small></div>
   <div class="relay-scroll"><table><thead><tr><th>{{l('Node','节点')}}</th><th>{{l('Region','区域')}}</th><th>{{l('Status','状态')}}</th><th>{{l('Connections','连接数')}}</th><th>{{l('Ingress','接收')}}</th><th>{{l('Egress','发送')}}</th><th>{{l('Dropped','丢弃包')}}</th></tr></thead><tbody>
    <tr v-for="n in nodes" :key="n.id" :class="{active:n.id===selected}" @click="select(n.id)"><td><button class="link" @click.stop="select(n.id)">{{n.id}}</button></td><td>{{n.region}}</td><td><span class="state" :class="n.state==='online'?'waiting':'idle'">{{state(n.state)}}</span></td><td>{{n.stats?.connections??'—'}} / {{n.maxConnections}}</td><td>{{bytes(n.stats?.ingress_bytes)}}</td><td>{{bytes(n.stats?.egress_bytes)}}</td><td>{{n.stats?.dropped_packets??'—'}}</td></tr>
   </tbody></table></div>
   <p v-if="!nodes.length&&!loading" class="muted relay-empty">{{l('No relay nodes configured.','暂无已配置的中继节点。')}}</p>
  </div>
  <div v-if="current" class="relay-monitor-panel"><div class="panel-title"><span>{{current.id}} · {{l('History','历史统计')}}</span><small class="muted">{{l('Last heartbeat','最近心跳')}}: {{time(current.seenAt)}}</small></div>
   <div class="relay-history-content">
    <p class="muted">{{l('Peak connections, latest 60 recorded minutes','连接峰值 · 最近 60 个有记录的分钟')}}</p>
    <div v-if="chart.length" class="relay-chart" role="img" :aria-label="l('Peak connections per recorded minute','各记录分钟的连接峰值')">
      <div class="relay-chart-scale"><span>{{maximum}}</span><span>0</span></div><div class="relay-chart-bars"><div v-for="m in chart" :key="m.minute" :title="time(m.minute)+' · '+m.peakConnections" :style="{height:m.peakConnections/maximum*100+'%'}"></div></div>
    </div>
    <div v-if="chart.length" class="relay-chart-range"><span>{{time(chart[0].minute)}}</span><span>{{time(chart[chart.length-1].minute)}}</span></div>
    <p v-if="!minutes.length" class="muted">{{l('No heartbeat history yet. Static nodes do not report history.','暂无心跳历史；静态节点不会上报历史。')}}</p>
   </div>
   <div v-if="minutes.length" class="relay-scroll relay-history"><table><thead><tr><th>{{l('Minute','时间')}}</th><th>{{l('Peak connections','连接峰值')}}</th><th>{{l('Received','接收增量')}}</th><th>{{l('Sent','发送增量')}}</th><th>{{l('Dropped','丢弃包增量')}}</th><th>{{l('Samples','样本')}}</th><th>{{l('Gaps / resets','间断/重置')}}</th></tr></thead><tbody>
    <tr v-for="m in minutes" :key="m.minute"><td>{{time(m.minute)}}</td><td>{{m.peakConnections}}</td><td>{{bytes(m.ingressBytes)}}</td><td>{{bytes(m.egressBytes)}}</td><td>{{m.droppedPackets}}</td><td>{{m.samples}}</td><td>{{m.discontinuities}}</td></tr>
   </tbody></table></div>
   <div class="relay-history-content muted small">{{l('Observed counter deltas; gaps and restarts are excluded. In-memory retention: ','已观测的计数增量，不包含间断或重启期间的未知流量。内存保留：')}}{{history?.retentionMinutes??'—'}} {{l('minutes; history resets when the authority restarts.','分钟；中心服务重启后历史重置。')}}</div>
  </div>
 </section>
</template>
