import assert from 'node:assert/strict'
import {spawn,execFileSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
assert(process.env.BATTLE_TEST_STATE_MODE==='mongo-split'&&process.env.BATTLE_MONGO_URI,'disposable replica set required')
const root=fileURLToPath(new URL('../',import.meta.url)),jar=resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java'
const logs=new Map();
const children=new Set(),password='worker-'+randomUUID()
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:'mongo-split',
 BATTLE_MATCH_SCHEDULING:'inline',BATTLE_MATCH_WORKER_ENABLED:'true',BATTLE_MONGO_MIGRATE_LEGACY:'false',
 BATTLE_NETWORK_ISSUER_KEY_FILE:'',BATTLE_NETWORK_RELAY_DIRECTORY_FILE:'',BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',
 BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:'',BATTLE_MODERATION_KEY_SHA256:'',BATTLE_OPERATIONS_ROOT_USERS:''}
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']});children.add(p);let log='',base
 await new Promise((ok,fail)=>{
  const timer=setTimeout(()=>{p.kill();fail(Error('startup timeout '+log.slice(-2500)))},90000)
  const read=b=>{log=(log+b.toString()).slice(-64000);logs.set(p,log);const m=log.match(/Netty started on port (\d+)/);if(m&&log.includes('Started BattleApplication')){clearTimeout(timer);base='http://127.0.0.1:'+m[1];ok()}}
  p.stdout.on('data',read);p.stderr.on('data',read);p.once('error',e=>{clearTimeout(timer);fail(e)});p.once('exit',code=>{clearTimeout(timer);fail(Error('server exited '+code+' '+log.slice(-32000)))})
 });return {p,base}
}
async function stop(p,signal='SIGTERM'){if(p.exitCode!==null||p.signalCode!==null)return;await new Promise(ok=>{const timer=setTimeout(()=>p.kill('SIGKILL'),8000);p.once('exit',()=>{clearTimeout(timer);ok()});p.kill(signal)})}
const latencies=[]
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200}={}){
 const began=performance.now()
 const r=await fetch(s.base+'/api/v1'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(20000)})
 const raw=await r.text();latencies.push(performance.now()-began);assert.equal(r.status,status,method+' '+path+' '+raw.slice(0,250));return raw?JSON.parse(raw):null
}
const u=(x,extra={})=>({token:x.token,...extra}),lobby=(s,x)=>api(s,'/lobby',u(x))
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function wait(fn){const end=Date.now()+25000;while(Date.now()<end){const v=await fn();if(v)return v;await sleep(150)}throw Error('worker timeout')}
const queue=(s,x,region)=>api(s,'/competition/queue',u(x,{body:{gameId:'cs16',mode:'CASUAL',region}}))
const account=(s,n)=>api(s,'/register',{body:{username:n+'_'+randomUUID().slice(0,8),password},status:201})
const names=(process.env.BATTLE_HA_NAMES||'').split(','),ports=(process.env.BATTLE_HA_PORTS||'').split(',')
assert(names.length===3&&ports.length===3&&names.every(n=>/^battle-ex-ha-[0-9]+-[0-9]+-[012]$/.test(n))&&ports.every(p=>/^\d+$/.test(p)), 'disposable HA containers required')
const alive=new Set([0,1,2])
function primary(){
 for(const i of alive){
  try{
   const raw=execFileSync('docker',['exec',names[i],'mongosh','--quiet','--port',ports[i],'--eval','print(db.hello().isWritablePrimary)'],{encoding:'utf8',timeout:5000,stdio:['ignore','pipe','ignore']})
   if(raw.trim()==='true')return i
  }catch{}
 }
 return -1
}
async function elected(){
 const end=Date.now()+45000
 while(Date.now()<end){const i=primary();if(i>=0)return i;await sleep(500)}
 throw Error('no replica-set primary elected')
}
function killMember(i){
 assert(alive.has(i));execFileSync('docker',['kill',names[i]],{stdio:'ignore',timeout:15000});alive.delete(i)
}
try{
 await elected()
 const A=await start(),B=await start(),a=await account(A,'ha'),b=await account(B,'peer')
 const room=await api(A,'/rooms',u(a,{body:{name:'HA fixture',gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201}))
 const invite=await api(B,'/rooms/'+room.id+'/invitation',u(a))
 await api(B,'/rooms/'+room.id+'/join',u(b,{body:invite}))
 const lost=await elected();killMember(lost)
 const successor=await elected();assert.notEqual(successor,lost)
 assert.equal((await lobby(B,b)).activeRoom.id,room.id)
 await api(B,'/rooms/'+room.id+'/ready',u(b,{body:{ready:true}}))
 assert((await lobby(A,a)).activeRoom.members.find(m=>m.userId===b.user.id).ready)
 console.log('PASS: real MongoDB primary loss, new primary elected, acknowledged room state retained, writes resume')
 await api(A,'/logout',u(b,{method:'POST',status:204}))
 await api(B,'/lobby',u(b,{status:401}))
 assert.equal((await lobby(A,a)).activeRoom.members.length,1)
 killMember(successor)
 // Let the driver observe the vanished primary before expecting 503.
 await sleep(2500)
 await api(A,'/lobby',u(a,{status:503}))
 const readiness=await fetch(B.base+'/health/ready',{signal:AbortSignal.timeout(15000)})
 assert.equal(readiness.status,503)
 console.log('PASS: loss of majority fails closed; readiness rejects traffic')
}finally{await Promise.all([...children].map(p=>stop(p)))}
