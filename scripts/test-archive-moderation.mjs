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
const moderatorKey=randomBytes(32).toString('base64url')
writeFileSync(issuerFile,Buffer.concat([seed,pub]).toString('hex')+'\n',{mode:0o600})
writeFileSync(secretFile,secret+'\n',{mode:0o600})
writeFileSync(directoryFile,JSON.stringify([{id:'relay-test',region:'test',relayURL:'wss://127.0.0.1:19444/relay/v1',stunURL:'stun:127.0.0.1:3478',secretFile,priority:0,maxConnections:256,bytesPerSecond:10000000}]),{mode:0o600})
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:'mongo',
 BATTLE_NETWORK_ISSUER_KEY_FILE:issuerFile,BATTLE_NETWORK_RELAY_DIRECTORY_FILE:directoryFile,
 BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',BATTLE_NETWORK_ICE:'false',
 BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:'',
 BATTLE_MODERATION_KEY_SHA256:createHash('sha256').update(moderatorKey).digest('hex')}
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
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200,relaySecret,moderator}={}){
 const r=await fetch(s.base+'/api/v1'+path,{method,
  headers:{...(token?{Authorization:'Bearer '+token}:{}),...(relaySecret?{'X-Relay-Secret':relaySecret}:{}),...(moderator?{'X-Battle-Moderator':moderator}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
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
const lobby=(s,u)=>api(s,'/lobby',{token:u.token})
async function match(a,b){
 await Promise.all([api(A,'/competition/queue',{token:a.token,body:{gameId:'cs16',mode:'CASUAL'}}),
  api(B,'/competition/queue',{token:b.token,body:{gameId:'cs16',mode:'CASUAL'}})])
 const view=await lobby(A,a),m=view.competition.match
 assert(m&&m.gameId==='cs16')
 await Promise.all([api(A,'/rooms/'+m.roomId+'/ready',{token:a.token,body:{ready:true}}),
  api(B,'/rooms/'+m.roomId+'/ready',{token:b.token,body:{ready:true}})])
 const host=view.activeRoom.host===a.user.id?a:b
 await api(B,'/rooms/'+m.roomId+'/start',{token:host.token,method:'POST'})
 return {m,host}
}
try{
 ;[A,B]=await Promise.all([start(),start()])
 const a=await account(A,'a'),b=await account(B,'b'),outsider=await account(A,'outsider'),operator=await account(B,'operator')
 const admin={token:operator.token,moderator:moderatorKey}
 await api(A,'/moderation/admin/reports',{token:operator.token,status:403})
 await api(A,'/moderation/admin/reports',{token:operator.token,moderator:randomBytes(32).toString('base64url'),status:403})
 assert.equal((await api(B,'/moderation/admin/reports',admin)).total,0)
 const first=await match(a,b),m=first.m
 const detail='/competition/matches/'+m.id
 await api(B,detail,{token:outsider.token,status:403})
 await api(A,detail+'/share-status',{token:a.token,body:{share:true}})
 assert.equal((await api(B,'/competition/live',{token:outsider.token})).length,0)
 await api(B,detail+'/share-status',{token:b.token,body:{share:true}})
 const live=await api(A,'/competition/live',{token:outsider.token})
 assert.equal(live.length,1);assert.equal(live[0].matchId,m.id)
 assert.deepEqual(Object.keys(live[0]).sort(),['gameId','matchId','mode','players','startedAt'].sort())
 await api(A,detail+'/share-status',{token:a.token,body:{share:false}})
 assert.equal((await api(B,'/competition/live',{token:outsider.token})).length,0)
 await api(A,detail+'/share-status',{token:a.token,body:{share:true}})
 const metadata={filename:'fixture.dem',sha256:createHash('sha256').update('synthetic replay fixture').digest('hex'),bytes:24}
 await api(A,detail+'/replays',{token:a.token,body:metadata,status:409})
 await api(A,'/rooms/'+m.roomId+'/finish',{token:first.host.token,method:'POST'})
 assert.equal((await api(B,'/competition/live',{token:outsider.token})).length,0)
 await api(A,detail+'/replays',{token:outsider.token,body:metadata,status:403})
 await api(A,detail+'/replays',{token:a.token,body:{...metadata,filename:'../fixture.dem'},status:400})
 await api(A,detail+'/replays',{token:a.token,body:{...metadata,filename:'wrong.w3g'},status:400})
 const registered=await api(A,detail+'/replays',{token:a.token,body:metadata})
 const duplicate=await api(B,detail+'/replays',{token:a.token,body:metadata})
 assert.equal(registered.id,duplicate.id)
 assert.equal((await api(B,detail,{token:b.token})).replays[0].sha256,metadata.sha256)
 await api(B,detail+'/replays/'+registered.id,{token:b.token,method:'DELETE',status:403})
 console.log('PASS: mutual live-status consent, private match detail, validated participant replay metadata and deduplication')
 const report={matchId:m.id,targetId:b.user.id,reason:'CHEATING',details:'Synthetic review fixture',replaySha256:metadata.sha256}
 await api(A,'/moderation/reports',{token:outsider.token,body:report,status:403})
 await api(B,'/moderation/reports',{token:b.token,body:{...report,targetId:a.user.id},status:400})
 await api(A,'/moderation/reports',{token:a.token,body:{...report,targetId:a.user.id},status:400})
 const filed=await api(A,'/moderation/reports',{token:a.token,body:report})
 const duplicateReport=await api(B,'/moderation/reports',{token:a.token,body:report})
 assert.equal(duplicateReport.id,filed.id)
 assert.equal((await api(B,'/moderation/reports/mine',{token:b.token})).total,0)
 assert.equal((await api(A,'/moderation/reports/mine',{token:a.token})).total,1)
 await api(A,'/moderation/admin/reports/'+filed.id+'/review',{token:a.token,moderator:moderatorKey,body:{decision:'WARN',resolution:'Fixture warning',until:null},status:403})
 await api(B,'/moderation/admin/reports/'+filed.id+'/review',{...admin,body:{decision:'WARN',resolution:'Fixture warning',until:null}})
 assert.equal((await api(A,'/moderation/warnings',{token:b.token}))[0].message,'Fixture warning')
 assert.equal((await api(B,'/moderation/warnings',{token:outsider.token})).length,0)
 assert.equal((await api(A,'/moderation/reports/mine',{token:a.token})).entries[0].status,'WARNED')
 assert.equal((await api(A,'/competition/leaderboard/cs16',{token:a.token})).total,0)
 console.log('PASS: participant-only reports, replay ownership, deduplication, privileged human review and private warnings')
 // Suspend a participant while an actual room authorization is in use.
 const next=await match(a,b),m2=next.m,d2='/competition/matches/'+m2.id
 await api(A,d2+'/share-status',{token:a.token,body:{share:true}})
 await api(B,d2+'/share-status',{token:b.token,body:{share:true}})
 const hb={node:'relay-test',instance:randomBytes(16).toString('hex'),draining:false,stats:{connections:2,ingress_bytes:0,egress_bytes:0,dropped_packets:0}}
 await api(A,'/network/relay/heartbeat',{body:hb,relaySecret:secret})
 await api(A,'/network/device',{token:a.token,body:device(a.token)})
 await api(B,'/network/device',{token:b.token,body:device(b.token)})
 const sa=await snapshot(B,a.token),sb=await snapshot(A,b.token)
 assert.equal(sa.peers[0].lease,sb.peers[0].lease)
 const lease=leaseOf(sa.peers[0])
 await api(A,'/network/relay/authorize',{body:lease,relaySecret:secret,status:204})
 const watch=ws(B,b.token);await watch.wait(v=>v.activeRoom?.id===m2.roomId)
 const report2=await api(A,'/moderation/reports',{token:a.token,body:{matchId:m2.id,targetId:b.user.id,reason:'DISCONNECTION_ABUSE',details:'Synthetic suspension fixture',replaySha256:null}})
 const reviewed=await api(A,'/moderation/admin/reports/'+report2.id+'/review',{...admin,body:{decision:'SUSPEND',resolution:'Synthetic manual decision',until:null}})
 assert.equal(reviewed.status,'SUSPENDED')
 await watch.closed()
 await api(B,'/lobby',{token:b.token,status:401})
 await api(B,'/network/relay/authorize',{body:lease,relaySecret:secret,status:403})
 await api(B,'/login',{body:{username:b.user.username,password},status:403})
 assert.equal((await lobby(B,a)).activeRoom,null)
 assert.equal((await api(A,'/competition/live',{token:outsider.token})).length,0)
 console.log('PASS: account suspension atomically revokes cross-instance login, WebSocket, assigned room and old relay lease')
 const banned=await api(B,'/moderation/admin/suspensions',admin)
 assert.equal(banned.total,1);assert.equal(banned.entries[0].id,reviewed.suspensionId)
 await Promise.all([stop(A.p),stop(B.p)])
 A=await start();B=await start()
 await api(B,'/login',{body:{username:b.user.username,password},status:403})
 assert.equal((await api(B,detail,{token:a.token})).replays[0].id,registered.id)
 assert.equal((await api(A,'/moderation/admin/reports',admin)).total,2)
 const revoked=await api(B,'/moderation/admin/suspensions/'+reviewed.suspensionId+'/revoke',{...admin,body:{reason:'Synthetic review reversal'}})
 assert(revoked.revokedAt&&revoked.revokedBy.id===operator.user.id)
 const login=await api(A,'/login',{body:{username:b.user.username,password}})
 assert.equal(login.user.id,b.user.id)
 await api(A,detail+'/replays/'+registered.id,{token:a.token,method:'DELETE',status:204})
 assert.equal((await api(B,detail,{token:login.token})).replays.length,0)
 assert.equal((await api(A,'/moderation/reports/mine',{token:a.token})).entries.find(x=>x.id===filed.id).replaySha256,metadata.sha256)
 console.log('PASS: replay/report/suspension persistence across all-JVM restart, audited revoke, re-login, immutable report fingerprint')
 console.log('M6–M7 ARCHIVE AND MODERATION INTEGRATION PASS')
} finally {
 for(const s of sockets)s.close()
 await Promise.all([...processes].map(p=>stop(p)))
 rmSync(tmp,{recursive:true,force:true})
}
