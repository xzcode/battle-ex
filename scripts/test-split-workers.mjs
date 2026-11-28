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
 BATTLE_MATCH_SCHEDULING:'worker',BATTLE_MATCH_WORKER_ENABLED:'false',BATTLE_MONGO_MIGRATE_LEGACY:'false',
 BATTLE_NETWORK_ISSUER_KEY_FILE:'',BATTLE_NETWORK_RELAY_DIRECTORY_FILE:'',BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',
 BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:'',BATTLE_MODERATION_KEY_SHA256:'',BATTLE_OPERATIONS_ROOT_USERS:''}
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']});children.add(p);let log='',base
 await new Promise((ok,fail)=>{
  const timer=setTimeout(()=>{p.kill();fail(Error('startup timeout '+log.slice(-2500)))},90000)
  const read=b=>{log=(log+b.toString()).slice(-18000);logs.set(p,log);const m=log.match(/Netty started on port (\d+)/);if(m&&log.includes('Started BattleApplication')){clearTimeout(timer);base='http://127.0.0.1:'+m[1];ok()}}
  p.stdout.on('data',read);p.stderr.on('data',read);p.once('error',e=>{clearTimeout(timer);fail(e)});p.once('exit',code=>{clearTimeout(timer);fail(Error('server exited '+code+' '+log.slice(-3500)))})
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
try{
 const A=await start(),B=await start()
 const a=await account(A,'asiaa'),b=await account(B,'asiab'),c=await account(A,'europea'),d=await account(B,'europeb')
 await Promise.all([queue(A,a,'asia'),queue(B,b,'asia'),queue(A,c,'europe'),queue(B,d,'europe')])
 for(const x of [a,b,c,d])assert((await lobby(A,x)).competition.ticket)
 const asia=await start({BATTLE_MATCH_WORKER_ENABLED:'true',BATTLE_MATCH_PARTITIONS:'cs16:CASUAL:asia'})
 const ma=await wait(async()=> (await lobby(B,a)).competition.match)
 assert.deepEqual(new Set(ma.players.map(x=>x.id)),new Set([a.user.id,b.user.id]))
 assert.equal((await lobby(B,c)).competition.match,null)
 // A second worker can claim the same partition; CAS prevents duplicate matches.
 const spare=await start({BATTLE_MATCH_WORKER_ENABLED:'true',BATTLE_MATCH_PARTITIONS:'cs16:CASUAL:asia,cs16:CASUAL:europe'})
 const mc=await wait(async()=> (await lobby(A,c)).competition.match)
 assert.deepEqual(new Set(mc.players.map(x=>x.id)),new Set([c.user.id,d.user.id]))
 assert.equal((await lobby(A,d)).competition.match.id,mc.id)
 await stop(asia.p,'SIGKILL')
 await Promise.all([api(A,'/rooms/'+ma.roomId+'/leave',u(a,{method:'POST',status:204})),api(B,'/rooms/'+mc.roomId+'/leave',u(c,{method:'POST',status:204}))])
 await Promise.all([queue(A,a,'asia'),queue(B,b,'asia'),queue(A,c,'europe'),queue(B,d,'europe')])
 const nextA=await wait(async()=> (await lobby(A,a)).competition.match)
 const nextC=await wait(async()=> (await lobby(B,c)).competition.match)
 assert.notEqual(nextA.id,ma.id);assert.notEqual(nextC.id,mc.id)
 assert.equal((await lobby(B,b)).competition.match.id,nextA.id)
 assert.equal((await lobby(A,d)).competition.match.id,nextC.id)
 console.log('PASS: API-only nodes, game/mode/region worker isolation, overlapping worker ownership and failover')
 // A modest real concurrent workload; explicitly not a production capacity claim.
 const before=performance.now(),samples=[]
 const container=process.env.BATTLE_SPLIT_FIXTURE_CONTAINER
 assert(container&&/^battle-ex-split-[0-9]+-[0-9]+$/.test(container),'disposable split fixture required')
 const version=()=>execFileSync('docker',['exec',container,'mongosh','--quiet','--norc','battle_ex_isolated_test','--eval','print(String(db.authority_meta.findOne({_id:"battle-authority-v2"}).version))'],{encoding:'utf8',timeout:10000}).trim()
 const users=await Promise.all(Array.from({length:8},(_,i)=>account(i%2?A:B,'load'+i)))
 const rooms=await Promise.all(users.map((x,i)=>api(i%2?A:B,'/rooms',u(x,{body:{name:'Load fixture '+i,gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201}))))
 const fenceBefore=version()
 for(let wave=0;wave<5;wave++){
  const started=performance.now()
  await Promise.all(users.map((x,i)=>api(i%2?A:B,'/rooms/'+rooms[i].id+'/ready',u(x,{body:{ready:wave%2===0}}))))
  samples.push(performance.now()-started)
 }
 assert.equal(version(),fenceBefore,'room-local readiness must not write the global commit fence')
 for(let i=0;i<users.length;i++)assert.equal((await lobby(A,users[i])).activeRoom.members[0].ready,true)
 console.log('PASS: independent room readiness writes leave the global authority version unchanged')
 await Promise.all(users.map((x,i)=>api(i%2?A:B,'/rooms/'+rooms[i].id+'/leave',u(x,{method:'POST',status:204}))))
 for(const x of users)assert.equal((await lobby(A,x)).activeRoom,null)
 const sorted=[...latencies].sort((a,b)=>a-b)
 console.log(JSON.stringify({fixture:'mixed concurrent API, 8 extra accounts, 40 readiness writes',requests:sorted.length,p50Ms:Math.round(sorted[Math.floor(sorted.length*.5)]),p95Ms:Math.round(sorted[Math.floor(sorted.length*.95)]),elapsedMs:Math.round(performance.now()-before),waveMs:samples.map(Math.round),errors:0}))
 console.log('PASS: bounded concurrency and room invariants (not a scale certification)')
}catch(e){for(const [p,log] of logs)console.error('Fixture JVM '+p.pid+': '+log.split('\n').filter(l=>/ERROR|Exception|Caused by|Split MongoDB/.test(l)).slice(-12).join('\n'));throw e}finally{await Promise.all([...children].map(p=>stop(p)))}
