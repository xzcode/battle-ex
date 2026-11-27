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
assert(process.env.BATTLE_CLUSTER_TEST==='true','explicit isolated cluster test required')
const tmp=mkdtempSync(join(tmpdir(),'battle-cluster-'))
const processes=new Set(),sockets=new Set()
const keypair=generateKeyPairSync('ed25519')
const pub=keypair.publicKey.export({format:'der',type:'spki'}).subarray(-32)
const seed=keypair.privateKey.export({format:'der',type:'pkcs8'}).subarray(-32)
const issuerFile=join(tmp,'issuer.key'),secretFile=join(tmp,'relay.secret'),directoryFile=join(tmp,'relays.json')
const secret=randomBytes(32).toString('hex')
writeFileSync(issuerFile,Buffer.concat([seed,pub]).toString('hex')+'\n',{mode:0o600})
writeFileSync(secretFile,secret+'\n',{mode:0o600})
writeFileSync(directoryFile,JSON.stringify([{id:'relay-test',region:'test',relayURL:'wss://127.0.0.1:19444/relay/v1',stunURL:'stun:127.0.0.1:3478',secretFile,priority:0,maxConnections:256,bytesPerSecond:10000000}]),{mode:0o600})
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:'mongo',
 BATTLE_NETWORK_ISSUER_KEY_FILE:issuerFile,BATTLE_NETWORK_RELAY_DIRECTORY_FILE:directoryFile,
 BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',BATTLE_NETWORK_ICE:'false'}
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
   log=(log+b.toString()).slice(-18000)
   const match=log.match(/Netty started on port (\d+)/)
   if(match&&log.includes('Started BattleApplication')){
    clearTimeout(timeout);base='http://127.0.0.1:'+match[1];ok()
   }
  }
  p.stdout.on('data',read);p.stderr.on('data',read)
  p.once('error',e=>{clearTimeout(timeout);fail(e)})
  p.once('exit',code=>{clearTimeout(timeout);fail(Error('server exited '+code+'\n'+log.slice(-2500)))})
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
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200,relaySecret}={}){
 const r=await fetch(s.base+'/api/v1'+path,{method,
  headers:{...(token?{Authorization:'Bearer '+token}:{}),...(relaySecret?{'X-Relay-Secret':relaySecret}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
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
async function snapshot(s,token){
 const nonce=randomBytes(32).toString('hex')
 const result=await api(s,'/network/snapshot',{token,body:{nonce}})
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
let A,B
try{
 ;[A,B]=await Promise.all([start(),start()])
 const a=await account(A,'a'),b=await account(B,'b'),c=await account(A,'c')
 const view=ws(B,a.token)
 await view.wait(v=>v.user.id===a.user.id)
 const room=await api(A,'/rooms',{token:a.token,body:{name:'Shared private room',gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201})
 await view.wait(v=>v.activeRoom?.id===room.id)
 const invite=await api(B,'/rooms/'+room.id+'/invitation',{token:a.token})
 // Race two different instances for the last seat. Exactly one may commit.
 const results=await Promise.all([b,c].map(async(user,i)=>{
  const server=i?A:B
  const response=await fetch(server.base+'/api/v1/rooms/'+room.id+'/join',{method:'POST',headers:{Authorization:'Bearer '+user.token,'Content-Type':'application/json'},body:JSON.stringify(invite)})
  await response.text();return {user,status:response.status}
 }))
 assert.deepEqual(results.map(r=>r.status).sort((a,b)=>a-b),[200,409])
 const joined=results.find(r=>r.status===200).user
 const rejected=results.find(r=>r.status===409).user
 const lobbyA=await api(A,'/lobby',{token:a.token}),lobbyB=await api(B,'/lobby',{token:joined.token})
 assert.equal(lobbyA.activeRoom.id,lobbyB.activeRoom.id)
 assert.deepEqual(lobbyA.activeRoom.members,lobbyB.activeRoom.members)
 assert.equal(lobbyA.activeRoom.members.length,2)
 assert.equal((await api(B,'/lobby',{token:rejected.token})).activeRoom,null)
 await view.wait(v=>v.activeRoom?.members.length===2)
 console.log('PASS: shared sessions, invitations, concurrent last-seat admission and cross-instance WebSocket')
 const hb={node:'relay-test',instance:randomBytes(16).toString('hex'),draining:false,stats:{connections:2,ingress_bytes:100,egress_bytes:200,dropped_packets:0}}
 await api(A,'/network/relay/heartbeat',{body:hb,relaySecret:secret})
 assert.equal((await api(B,'/network/relays',{token:a.token}))[0].state,'online')
 const da=device(a.token),dj=device(joined.token)
 await api(A,'/network/device',{token:a.token,body:da})
 await api(B,'/network/device',{token:joined.token,body:dj})
 let sa=await snapshot(B,a.token),sb=await snapshot(A,joined.token)
 sa=await snapshot(A,a.token);sb=await snapshot(B,joined.token)
 assert.equal(sa.networkId,sb.networkId);assert.equal(sa.revision,sb.revision)
 assert.equal(sa.peers[0].ip,sb.localIP);assert.equal(sb.peers[0].ip,sa.localIP)
 assert.equal(sa.peers[0].lease,sb.peers[0].lease)
 const lease=leaseOf(sa.peers[0])
 await api(B,'/network/relay/authorize',{body:lease,relaySecret:secret,status:204})
 hb.stats.ingress_bytes=150;hb.stats.egress_bytes=280
 await api(B,'/network/relay/heartbeat',{body:hb,relaySecret:secret})
 const history=await api(A,'/network/relays/relay-test/history',{token:a.token})
 assert(history.minutes.some(m=>m.ingressBytes===50&&m.egressBytes===80))
 console.log('PASS: shared device enrollment, signed peer snapshot/lease, IP allocation and relay history')
 // Terminate one JVM without graceful state flush; the other already owns
 // the committed state and must continue to validate current grants.
 await stop(A.p,'SIGKILL')
 assert.equal((await api(B,'/lobby',{token:a.token})).activeRoom.id,room.id)
 await api(B,'/network/relay/heartbeat',{body:hb,relaySecret:secret})
 await snapshot(B,a.token);await snapshot(B,joined.token)
 await api(B,'/network/relay/authorize',{body:lease,relaySecret:secret,status:204})
 A=await start()
 await api(A,'/network/relay/heartbeat',{body:hb,relaySecret:secret})
 const again=await snapshot(A,a.token)
 assert.equal(again.localIP,sa.localIP);assert.equal(again.revision,sa.revision)
 assert.equal((await api(A,'/lobby',{token:joined.token})).activeRoom.id,room.id)
 console.log('PASS: abrupt JVM loss, surviving instance and restarted instance preserve committed state')
 // Revocation on one instance must immediately affect authorization on the other.
 await api(A,'/logout',{token:a.token,method:'POST',status:204})
 await view.closed()
 await api(B,'/lobby',{token:a.token,status:401})
 await api(B,'/network/relay/authorize',{body:lease,relaySecret:secret,status:403})
 assert.equal((await api(B,'/lobby',{token:joined.token})).activeRoom.host,joined.user.id)
 console.log('PASS: cross-instance logout, old lease denial and host transfer')
 // Both instances restart: the shared store, not process memory, is authoritative.
 await Promise.all([stop(A.p),stop(B.p)])
 A=await start()
 assert.equal((await api(A,'/lobby',{token:joined.token})).activeRoom.id,room.id)
 assert((await api(A,'/network/relays/relay-test/history',{token:joined.token})).minutes.length>0)
 console.log('PASS: shared sessions, room and relay history survive all JVM restarts')
 await api(A,'/rooms/'+room.id+'/leave',{token:joined.token,method:'POST',status:204})
 assert.equal((await api(A,'/lobby',{token:joined.token})).activeRoom,null)
 // Reject a process configured with a different authority/signing policy.
 await assert.rejects(start({BATTLE_NETWORK_LEASE_SECONDS:'35'}),/shared authority schema or network configuration mismatch/)
 console.log('PASS: mismatched cluster configuration rejected at startup')
 // Only the wrapper-created disposable test database may be stopped.
 const container=process.env.BATTLE_CLUSTER_TEST_MONGO_CONTAINER
 assert(container&&/^battle-ex-test-[0-9]+-[0-9]+$/.test(container),'isolated container required')
 execFileSync('docker',['stop','--time','2',container],{stdio:'ignore'})
 await api(A,'/lobby',{token:joined.token,status:503})
 await api(A,'/network/relay/authorize',{body:lease,relaySecret:secret,status:503})
 console.log('PASS: shared database outage fails closed; no local-state fallback')
 console.log('M3.8 CLUSTER INTEGRATION PASS')
} finally {
 for(const s of sockets)s.close()
 await Promise.all([...processes].map(p=>stop(p)))
 rmSync(tmp,{recursive:true,force:true})
}
