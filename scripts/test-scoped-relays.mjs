import assert from 'node:assert/strict'
import {spawn,execFileSync} from 'node:child_process'
import {generateKeyPairSync,randomBytes,randomUUID,createHash,sign,verify} from 'node:crypto'
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {resolve,join} from 'node:path'
import {fileURLToPath} from 'node:url'

const root=fileURLToPath(new URL('../',import.meta.url))
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java'
const jar=resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
assert(process.env.BATTLE_MONGO_URI,'run only against a disposable MongoDB database')
assert(process.env.BATTLE_TEST_STATE_MODE==='mongo-split','disposable split fixture required')
const tmp=mkdtempSync(join(tmpdir(),'battle-cluster-'))
const processes=new Set(),sockets=new Set()
const keypair=generateKeyPairSync('ed25519')
const pub=keypair.publicKey.export({format:'der',type:'spki'}).subarray(-32)
const seed=keypair.privateKey.export({format:'der',type:'pkcs8'}).subarray(-32)
const issuerFile=join(tmp,'issuer.key'),secretFile=join(tmp,'relay.secret'),directoryFile=join(tmp,'relays.json')
const secret=randomBytes(32).toString('hex'),secret2=randomBytes(32).toString('hex')
const secret2File=join(tmp,"relay2.secret")
writeFileSync(issuerFile,Buffer.concat([seed,pub]).toString('hex')+'\n',{mode:0o600})
writeFileSync(secretFile,secret+'\n',{mode:0o600})
writeFileSync(secret2File,secret2+'\n',{mode:0o600})
writeFileSync(directoryFile,JSON.stringify([
 {id:'relay-test',region:'test',relayURL:'wss://127.0.0.1:19444/relay/v1',stunURL:'stun:127.0.0.1:3478',secretFile,priority:0,maxConnections:2,bytesPerSecond:10000000},
 {id:'relay-backup',region:'test',relayURL:'wss://127.0.0.1:19445/relay/v1',stunURL:'stun:127.0.0.1:3479',secretFile:secret2File,priority:1,maxConnections:2,bytesPerSecond:10000000}
]),{mode:0o600})
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:process.env.BATTLE_TEST_STATE_MODE||'mongo',
 BATTLE_NETWORK_ISSUER_KEY_FILE:issuerFile,BATTLE_NETWORK_RELAY_DIRECTORY_FILE:directoryFile,
 BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',BATTLE_NETWORK_ICE:'false',BATTLE_NETWORK_INSTANCE_FENCE:'true',BATTLE_NETWORK_CAPACITY_RESERVATIONS:'true',BATTLE_NETWORK_POOL_SUBNETS:'16384',BATTLE_MATCH_SCHEDULING:'inline',BATTLE_MATCH_WORKER_ENABLED:'false',BATTLE_MODERATION_KEY_SHA256:'',BATTLE_OPERATIONS_ROOT_USERS:'',BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:''}
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function until(fn,desc,timeout=15000){
 const end=Date.now()+timeout
 while(Date.now()<end){const x=await fn();if(x)return x;await sleep(100)}
 throw Error('timeout: '+desc)
}
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']})
 processes.add(p)
 let log=''
 let base
 await new Promise((ok,fail)=>{
  const timeout=setTimeout(()=>fail(Error('startup timeout')),45000)
  const read=b=>{
   log=(log+b.toString()).slice(-30000)
   const match=log.match(/Netty started on port (\d+)/)
   if(match&&log.includes('Started BattleApplication')){
    clearTimeout(timeout);base='http://127.0.0.1:'+match[1];ok()
   }
  }
  p.stdout.on('data',read);p.stderr.on('data',read)
  p.once('error',e=>{clearTimeout(timeout);fail(e)})
  p.once('exit',code=>{clearTimeout(timeout);fail(Error('server exited '+code+'\n'+log.slice(-9000)))})
 })
 return {p,base}
}
async function stop(p,signal='SIGTERM'){
 if(p.exitCode!==null||p.signalCode!==null)return
 await new Promise(resolve=>{
  const timer=setTimeout(()=>p.kill('SIGKILL'),8000)
  p.once('exit',()=>{clearTimeout(timer);resolve()});p.kill(signal)
 })
}
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200,relaySecret,instance}={}){
 const r=await fetch(s.base+'/api/v1'+path,{method,
  headers:{...(token?{Authorization:'Bearer '+token}:{}),...(relaySecret?{'X-Relay-Secret':relaySecret}:{}),...(instance?{'X-Relay-Instance':instance}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
  ...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(20000)})
 const text=await r.text()
 assert.equal(r.status,status,method+' '+path+' '+text.slice(0,250))
 return text?JSON.parse(text):null
}
function ws(s,token){
 const socket=new WebSocket(s.base.replace('http:','ws:')+'/ws/lobby')
 sockets.add(socket);let last=null,closed=false
 socket.onopen=()=>socket.send(JSON.stringify({type:'authenticate',token}))
 socket.onmessage=e=>{const v=JSON.parse(e.data);if(v.type==='lobby')last=v.data}
 socket.onclose=()=>{closed=true;sockets.delete(socket)};socket.onerror=()=>{}
 return {wait:predicate=>until(()=>last&&predicate(last)&&last,'cross-instance websocket'),closed:()=>until(()=>closed,'websocket revoke')}
}
const password='test-'+randomUUID()
async function account(s,i){return api(s,'/register',{body:{username:'cluster_'+i+'_'+randomUUID().slice(0,8),password},status:201})}
function device(token){
 const id=generateKeyPairSync('ed25519'),wg=generateKeyPairSync('x25519')
 const identityKey=id.publicKey.export({format:'der',type:'spki'}).subarray(-32).toString('hex')
 const wireguardKey=wg.publicKey.export({format:'der',type:'spki'}).subarray(-32).toString('hex')
 const digest=createHash('sha256').update(token).digest('hex')
 const message=Buffer.from('battle-ex/device/v1\0'+digest+'\n'+identityKey+'\n'+wireguardKey)
 return {identityKey,wireguardKey,proof:sign(null,message,id.privateKey).toString('base64url')}
}
async function snapshot(s,token,relayMeasurements){
 const nonce=randomBytes(32).toString('hex')
 const result=await api(s,'/network/snapshot',{token,body:{nonce,relayMeasurements}})
 const raw=Buffer.from(result.payload,'base64url')
 assert(verify(null,Buffer.concat([Buffer.from('battle-ex/snapshot/v1\0'),raw]),keypair.publicKey,Buffer.from(result.signature,'base64url')))
 const v=JSON.parse(raw)
 assert.equal(v.nonce,nonce)
 return v
}
function leaseOf(peer){
 const [encoded,sig]=peer.lease.split('.')
 const raw=Buffer.from(encoded,'base64url')
 assert(verify(null,Buffer.concat([Buffer.from('network-ex/lease/v1\0'),raw]),keypair.publicKey,Buffer.from(sig,'base64url')))
 return JSON.parse(raw)
}
const container=process.env.BATTLE_SPLIT_FIXTURE_CONTAINER
assert(container&&/^battle-ex-split-[0-9]+-[0-9]+$/.test(container),'disposable fixture required')
function version(){
 return execFileSync('docker',['exec',container,'mongosh','--quiet','--norc','battle_ex_isolated_test','--eval','print(String(db.authority_meta.findOne({_id:"battle-authority-v2"}).version))'],{encoding:'utf8',timeout:10000}).trim()
}
const u=(user,extra={})=>({token:user.token,...extra})
const heartbeat=(s,h,key,status=200)=>api(s,'/network/relay/heartbeat',{body:h,relaySecret:key,status})
const auth=(s,lease,key,instance,status=204)=>api(s,'/network/relay/authorize',{body:lease,relaySecret:key,instance,status})
const makeHB=node=>({node,instance:randomBytes(16).toString('hex'),draining:false,stats:{connections:0,ingress_bytes:100,egress_bytes:200,dropped_packets:0}})
let A,B
try{
 A=await start();B=await start()
 const users=[]
 for(let i=0;i<6;i++)users.push(await account(i%2?A:B,'r'+i))
 const [a,b,c,d,e,f]=users
 async function makeRoom(first,second,name){
  const room=await api(A,'/rooms',u(first,{body:{name,gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201}))
  const invite=await api(B,'/rooms/'+room.id+'/invitation',u(first))
  await api(B,'/rooms/'+room.id+'/join',u(second,{body:invite}))
  await api(A,'/network/device',u(first,{body:device(first.token)}))
  await api(B,'/network/device',u(second,{body:device(second.token)}))
  return room
 }
 // Set up the three rooms first so password hashing cannot expire the grants.
 const rooms=[]
 for(const [first,second,label] of [[a,b,'First'],[c,d,'Second'],[e,f,'Third']])rooms.push(await makeRoom(first,second,label))
 const h1=makeHB('relay-test'),h2=makeHB('relay-backup')
 await Promise.all([heartbeat(A,h1,secret),heartbeat(B,h2,secret2)])
 // Device TTL may elapse during setup; refresh enrollment before assigning.
 // Same session/device keys cannot be replaced, so refresh seen via snapshots
 // with both nodes active, then obtain the final committed grants.
 const snapshots=async(first,second)=>{
  let x=await snapshot(A,first.token);let y=await snapshot(B,second.token)
  x=await snapshot(B,first.token);y=await snapshot(A,second.token)
  assert.equal(x.peers[0].lease,y.peers[0].lease)
  return [x,y,leaseOf(x.peers[0])]
 }
 const [sa,sb,l1]=await snapshots(a,b)
 assert.equal(sa.relayNode,'relay-test')
 const [sc,sd,l2]=await snapshots(c,d)
 assert.equal(sc.relayNode,'relay-backup')
 assert.notEqual(sa.localIP.split('.').slice(0,3).join('.'),sc.localIP.split('.').slice(0,3).join('.'))
 assert(Number(sa.localIP.split('.')[1])>=128&&Number(sa.localIP.split('.')[1])<=191)
 let denied=false
 for(const x of [e,f]){
  const r=await fetch(A.base+'/api/v1/network/snapshot',{method:'POST',headers:{Authorization:'Bearer '+x.token,'Content-Type':'application/json'},body:JSON.stringify({nonce:randomBytes(32).toString('hex')})})
  await r.text();if(r.status===503)denied=true;else assert.equal(r.status,200)
 }
 assert(denied,'both relay capacities must reject additional two-peer reservation')
 await auth(A,l1,secret,h1.instance);await auth(B,l2,secret2,h2.instance)
 await auth(B,l1,secret,h2.instance,403)
 await auth(B,l1,secret2,h1.instance,403)
 await auth(B,{...l1,flow:'0'.repeat(64)},secret,h1.instance,403)
 const before=version()
 const rival=makeHB('relay-test')
 await heartbeat(B,rival,secret,403)
 h1.stats.ingress_bytes=155;h1.stats.egress_bytes=285
 h2.stats.ingress_bytes=170;h2.stats.egress_bytes=290
 await Promise.all([heartbeat(A,h1,secret),heartbeat(B,h2,secret2)])
 const h=await api(B,'/network/relays/relay-test/history',u(a))
 assert(h.minutes.some(m=>m.ingressBytes===55&&m.egressBytes===85))
 const began=performance.now(),latencies=[]
 // Moderate concurrent real requests, keeping below the heartbeat/device
 // lease windows. No synthetic benchmark claims about production capacity.
 for(let wave=0;wave<8;wave++){
  await Promise.all(Array.from({length:8},async(_,i)=>{
   const t=performance.now()
   await auth(i%2?A:B,i%2?l1:l2,i%2?secret:secret2,i%2?h1.instance:h2.instance)
   latencies.push(performance.now()-t)
  }))
 }
 const sorted=latencies.sort((x,y)=>x-y)
 assert.equal(version(),before,'scoped relay heartbeat/history and lease reads must not write the global fence')
 console.log(JSON.stringify({fixture:'64 scoped relay authorizations, concurrency 8',errors:0,elapsedMs:Math.round(performance.now()-began),p50Ms:Math.round(sorted[32]),p95Ms:Math.round(sorted[60])}))
 console.log('PASS: independent relay writes, exact lease reads, capacity reservations and no global commit advancement')
 // Snapshot renewal of the same topology must only touch the actor device
 // and its probe sample. Keep the existing pair lease and fresh signed nonce.
 await Promise.all([heartbeat(A,h1,secret),heartbeat(B,h2,secret2)])
 await snapshots(a,b);await snapshots(c,d)
 const stableFence=version(),stableStart=performance.now(),durations=[]
 for(let wave=0;wave<6;wave++){
  await Promise.all([a,b,c,d].map(async(user,i)=>{
   const started=performance.now()
   const v=await snapshot(i%2?A:B,user.token,{'relay-test':10,'relay-backup':20})
   const original=[sa,sb,sc,sd][i]
   assert.equal(v.revision,original.revision)
   assert.equal(v.localIP,original.localIP)
   assert.equal(v.peers[0].lease,original.peers[0].lease)
   durations.push(performance.now()-started)
  }))
 }
 assert.equal(version(),stableFence,'stable network snapshots must not write the global authority fence')
 durations.sort((x,y)=>x-y)
 console.log(JSON.stringify({fixture:'24 signed stable snapshots with probes, concurrency 4',errors:0,elapsedMs:Math.round(performance.now()-stableStart),p50Ms:Math.round(durations[12]),p95Ms:Math.round(durations[22])}))
 console.log('PASS: scoped signed snapshots preserve peer leases, addresses, nonce validation and global commit version')
 await api(A,'/network/snapshot',u(a,{body:{nonce:randomBytes(32).toString('hex'),relayMeasurements:{'relay-test':-2}},status:400}))

 // Refresh both room grants, then logout on a different instance. The old
 // relay authorization cannot silently recalculate a new valid lease.
 await Promise.all([heartbeat(B,h1,secret),heartbeat(A,h2,secret2)])
 const [latestA,,latest1]=await snapshots(a,b)
 const [latestC,,latest2]=await snapshots(c,d)
 await api(A,'/logout',u(b,{method:'POST',status:204}))
 await auth(B,latest1,secret,h1.instance,403)
 await auth(A,latest2,secret2,h2.instance)
 // Explicit drain prevents authorization before the allocation is refreshed.
 h2.draining=true;await heartbeat(B,h2,secret2)
 await auth(A,latest2,secret2,h2.instance,403)
 h2.draining=false;await heartbeat(B,h2,secret2)
 await auth(A,latest2,secret2,h2.instance)
 // New instance ownership after old heartbeat expires, and generation
 // change invalidates the previous grant even when device identities match.
 await sleep(10500)
 await heartbeat(A,rival,secret)
 const replacement=makeHB('relay-backup')
 await heartbeat(B,replacement,secret2)
 await auth(B,latest1,secret,rival.instance,403)
 await heartbeat(B,h1,secret,403)
 await heartbeat(A,h2,secret2,403)
 // Both members remain enrolled and in the same room. Only the relay
 // generation changed: the old lease must fail under the new instance.
 await auth(A,latest2,secret2,replacement.instance,403)
 const [newC,,newLease]=await snapshots(c,d)
 assert.equal(newC.localIP,latestC.localIP)
 assert(newC.revision>latestC.revision)
 await auth(B,newLease,secret2,replacement.instance)
 await auth(A,newLease,secret2,h2.instance,403)
 await api(B,'/rooms/'+rooms[1].id+'/leave',u(c,{method:'POST',status:204}))
 await auth(B,newLease,secret2,replacement.instance,403)
 console.log('PASS: cross-instance logout, drain, instance takeover and departed-room revocation')
 await Promise.all([stop(A.p),stop(B.p)])
 A=await start()
 const persisted=await api(A,'/network/relays/relay-test/history',u(a))
 assert(persisted.minutes.some(m=>m.ingressBytes>=55&&m.egressBytes>=85))
 const statuses=await api(A,'/network/relays',u(a))
 assert(statuses.some(n=>n.id==='relay-test'))
 console.log('PASS: isolated telemetry survives restart; no retained full-state history required')
 console.log('SCOPED RELAY ACCEPTANCE PASS')
}finally{for(const s of sockets)s.close();await Promise.all([...processes].map(p=>stop(p)));rmSync(tmp,{recursive:true,force:true})}
