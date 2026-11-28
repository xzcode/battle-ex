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
async function start(extra={},binary=jar){
 const p=spawn(java,['-jar',binary],{env:{...env,...extra},stdio:['ignore','pipe','pipe']})
 processes.add(p)
 let log=''
 let base
 await new Promise((ok,fail)=>{
  const timeout=setTimeout(()=>fail(Error('startup timeout')),45000)
  const read=b=>{
   log=(log+b.toString()).slice(-64000)
   const match=log.match(/Netty started on port (\d+)/)
   if(match&&log.includes('Started BattleApplication')){
    clearTimeout(timeout);base='http://127.0.0.1:'+match[1];ok()
   }
  }
  p.stdout.on('data',read);p.stderr.on('data',read)
  p.once('error',e=>{clearTimeout(timeout);fail(e)})
  p.once('exit',code=>{clearTimeout(timeout);fail(Error('server exited '+code+'\n'+log.slice(-32000)))})
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
const v2=process.env.BATTLE_V2_JAR
assert(v2,'set BATTLE_V2_JAR to the previously built, pinned schema-2 JAR')
const u=(user,extra={})=>({token:user.token,...extra})
let old,A
try{
 old=await start({},v2)
 const a=await account(old,'upgradea'),b=await account(old,'upgradeb')
 const room=await api(old,'/rooms',u(a,{body:{name:'Preserved private room',gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201}))
 const invite=await api(old,'/rooms/'+room.id+'/invitation',u(a))
 await api(old,'/rooms/'+room.id+'/join',u(b,{body:invite}))
 await api(old,'/rooms/'+room.id+'/ready',u(b,{body:{ready:true}}))
 const h={node:'relay-test',instance:randomBytes(16).toString('hex'),draining:false,stats:{connections:0,ingress_bytes:100,egress_bytes:200,dropped_packets:0}}
 await api(old,'/network/relay/heartbeat',{body:h,relaySecret:secret})
 const da=device(a.token),db=device(b.token)
 await api(old,'/network/device',u(a,{body:da}));await api(old,'/network/device',u(b,{body:db}))
 await snapshot(old,a.token);await snapshot(old,b.token)
 const before=await snapshot(old,a.token)
 h.stats.ingress_bytes=147;h.stats.egress_bytes=280
 await api(old,'/network/relay/heartbeat',{body:h,relaySecret:secret})
 const originalHistory=await api(old,'/network/relays/relay-test/history',u(a))
 assert(originalHistory.minutes.some(m=>m.ingressBytes===47&&m.egressBytes===80))
 await stop(old.p)
 await assert.rejects(start(),/schema 2 authority present; explicit offline migration required/)
 A=await start({BATTLE_MONGO_MIGRATE_LEGACY:'true'})
 assert.equal((await api(A,'/lobby',u(b))).activeRoom.id,room.id)
 assert.deepEqual(await api(A,'/rooms/'+room.id+'/invitation',u(a)),invite)
 const history=await api(A,'/network/relays/relay-test/history',u(a))
 assert.deepEqual(history.minutes,originalHistory.minutes)
 await assert.rejects(start({},v2),/split authority schema or configuration mismatch/)
 // Refresh the same identity keys; the old data can still be used.
 await api(A,'/network/relay/heartbeat',{body:h,relaySecret:secret})
 await api(A,'/network/device',u(a,{body:da}));await api(A,'/network/device',u(b,{body:db}))
 await snapshot(A,b.token)
 const after=await snapshot(A,a.token)
 assert.equal(after.localIP,before.localIP)
 const lease=leaseOf(after.peers[0])
 await api(A,'/network/relay/authorize',{body:lease,relaySecret:secret,instance:h.instance,status:204})
 await stop(A.p)
 A=await start()
 assert.equal((await api(A,'/lobby',u(a))).activeRoom.id,room.id)
 assert((await api(A,'/network/relays/relay-test/history',u(a))).minutes.some(m=>m.ingressBytes===47))
 console.log('PASS: actual schema-2 binary migration requires opt-in, rejects old binary, preserves sessions, rooms, invitation, allocated IP and telemetry')
}finally{for(const s of sockets)s.close();await Promise.all([...processes].map(p=>stop(p)));rmSync(tmp,{recursive:true,force:true})}
