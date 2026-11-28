import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
assert(process.env.BATTLE_MONGO_URI&&process.env.BATTLE_TEST_STATE_MODE==='mongo-split','disposable replica set required')
const root=fileURLToPath(new URL('../',import.meta.url)),jar=resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java'
const children=new Set(),password='migration-'+randomUUID()
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:'mongo',
 BATTLE_MONGO_MIGRATE_LEGACY:'false',BATTLE_NETWORK_ISSUER_KEY_FILE:'',BATTLE_NETWORK_RELAY_DIRECTORY_FILE:'',BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',
 BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:'',BATTLE_MODERATION_KEY_SHA256:'',BATTLE_OPERATIONS_ROOT_USERS:'',
 BATTLE_MATCH_SCHEDULING:'inline'}
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']});children.add(p);let log='',base
 await new Promise((ok,fail)=>{
  const timer=setTimeout(()=>{p.kill();fail(Error('startup timeout '+log.slice(-2500)))},90000)
  const read=b=>{log=(log+b.toString()).slice(-64000);const m=log.match(/Netty started on port (\d+)/);if(m&&log.includes('Started BattleApplication')){clearTimeout(timer);base='http://127.0.0.1:'+m[1];ok()}}
  p.stdout.on('data',read);p.stderr.on('data',read);p.once('error',e=>{clearTimeout(timer);fail(e)});p.once('exit',code=>{clearTimeout(timer);fail(Error('server exited '+code+' '+log.slice(-32000)))})
 });return {p,base}
}
async function stop(p,signal='SIGTERM'){if(p.exitCode!==null||p.signalCode!==null)return;await new Promise(ok=>{const timer=setTimeout(()=>p.kill('SIGKILL'),8000);p.once('exit',()=>{clearTimeout(timer);ok()});p.kill(signal)})}
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200}={}){
 const r=await fetch(s.base+'/api/v1'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(20000)})
 const raw=await r.text();assert.equal(r.status,status,method+' '+path+' '+raw.slice(0,250));return raw?JSON.parse(raw):null
}
const u=(x,extra={})=>({token:x.token,...extra})
const account=(s,n)=>api(s,'/register',{body:{username:n+'_'+randomUUID().slice(0,8),password},status:201})
const lobby=(s,x)=>api(s,'/lobby',u(x))
try{
 const old=await start(),a=await account(old,'migratea'),b=await account(old,'migrateb'),c=await account(old,'migratec')
 const team=await api(old,'/community/teams',u(a,{body:{name:'Migration fixture',tag:'MIG',description:'preserved'},status:201}))
 await api(old,'/community/teams/'+team.id+'/applications',u(b,{body:{message:'preserve pending application'}}))
 const room=await api(old,'/rooms',u(a,{body:{name:'Preserved private room',gameId:'custom',visibility:'PRIVATE',capacity:2},status:201}))
 const invite=await api(old,'/rooms/'+room.id+'/invitation',u(a))
 await api(old,'/rooms/'+room.id+'/join',u(b,{body:invite}))
 await api(old,'/rooms/'+room.id+'/ready',u(b,{body:{ready:true}}))
 await api(old,'/competition/queue',u(c,{body:{gameId:'cs16',mode:'CASUAL',region:'asia'}}))
 const before=await lobby(old,a)
 await stop(old.p)
 await assert.rejects(start({BATTLE_STATE_MODE:'mongo-split'}),/legacy authority present/)
 const migrated=await start({BATTLE_STATE_MODE:'mongo-split',BATTLE_MONGO_MIGRATE_LEGACY:'true'})
 const newView=await lobby(migrated,a)
 assert.deepEqual(newView.activeRoom,before.activeRoom)
 assert.deepEqual(await api(migrated,'/rooms/'+room.id+'/invitation',u(a)),invite)
 assert.equal((await api(migrated,'/community/teams/'+team.id,u(a))).applications[0].message,'preserve pending application')
 assert.equal((await lobby(migrated,c)).competition.ticket.region,'asia')
 await assert.rejects(start(),/schema or network configuration mismatch/)
 console.log('PASS: explicit offline migration, old writer/startup fenced, session/room/invite/team application/region preserved')
 const other=await start({BATTLE_STATE_MODE:'mongo-split'})
 await api(other,'/community/teams/'+team.id+'/applications/'+b.user.id+'/review',u(a,{body:{accept:true}}))
 assert.equal((await api(migrated,'/community/teams/mine',u(b))).team.id,team.id)
 await api(migrated,'/rooms/'+room.id+'/leave',u(b,{method:'POST',status:204}))
 assert.equal((await lobby(other,b)).activeRoom,null)
 await Promise.all([stop(migrated.p),stop(other.p)])
 const restarted=await start({BATTLE_STATE_MODE:'mongo-split'})
 assert.equal((await api(restarted,'/community/teams/mine',u(b))).team.id,team.id)
 assert.equal((await lobby(restarted,a)).activeRoom.members.length,1)
 console.log('PASS: migrated data remains writable and persistent after full restart')
}finally{await Promise.all([...children].map(p=>stop(p)))}
