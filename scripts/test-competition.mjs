import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {generateKeyPairSync,randomUUID,sign} from 'node:crypto'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const root=fileURLToPath(new URL('../',import.meta.url))
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java'
const jar=resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const cluster=process.env.BATTLE_COMPETITION_CLUSTER_TEST==='true'
if(cluster)assert(process.env.BATTLE_MONGO_URI,'isolated MongoDB required')
const issuer=generateKeyPairSync('ed25519'),stranger=generateKeyPairSync('ed25519')
const publicKey=issuer.publicKey.export({format:'der',type:'spki'}).subarray(-32).toString('hex')
const processes=new Set(),sockets=new Set()
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',
 BATTLE_STATE_MODE:cluster?'mongo':'memory',BATTLE_MONGO_URI:cluster?process.env.BATTLE_MONGO_URI:'',
 BATTLE_COMPETITION_RESULT_ISSUER:publicKey,BATTLE_COMPETITION_RANKED_GAMES:'cs16',
 BATTLE_NETWORK_ISSUER_KEY_FILE:'',BATTLE_NETWORK_RELAY_DIRECTORY_FILE:'',BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:''}
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']})
 processes.add(p);let log='',base
 await new Promise((ok,fail)=>{
  const timer=setTimeout(()=>fail(Error('startup timeout '+log.slice(-1600))),45000)
  const read=b=>{log=(log+b.toString()).slice(-12000);const m=log.match(/Netty started on port (\d+)/);if(m&&log.includes('Started BattleApplication')){clearTimeout(timer);base='http://127.0.0.1:'+m[1];ok()}}
  p.stdout.on('data',read);p.stderr.on('data',read);p.once('error',e=>{clearTimeout(timer);fail(e)});p.once('exit',code=>{clearTimeout(timer);fail(Error('server exited '+code+' '+log.slice(-1600)))})
 })
 return {p,base}
}
async function stop(p,signal='SIGTERM'){
 if(p.exitCode!==null||p.signalCode!==null)return
 await new Promise(ok=>{const timer=setTimeout(()=>p.kill('SIGKILL'),8000);p.once('exit',()=>{clearTimeout(timer);ok()});p.kill(signal)})
}
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200}={}){
 const response=await fetch(s.base+'/api/v1'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(20000)})
 const raw=await response.text();assert.equal(response.status,status,method+' '+path+' '+raw.slice(0,250))
 return raw?JSON.parse(raw):null
}
function report(match,outcome,winnerId,key=issuer.privateKey,overrides={}){
 const payload=Buffer.from(JSON.stringify({matchId:match.id,gameId:match.gameId,outcome,winnerId,issuedAt:Math.floor(Date.now()/1000),...overrides}))
 return {payload:payload.toString('base64url'),signature:sign(null,Buffer.concat([Buffer.from('battle-ex/competition-result/v1\0'),payload]),key).toString('base64url')}
}
const password='fixture-'+randomUUID()
async function register(server,name){return api(server,'/register',{body:{username:name+'_'+randomUUID().slice(0,8),password},status:201})}
const lobby=(server,user)=>api(server,'/lobby',{token:user.token})
const queue=(server,user,gameId,mode='CASUAL',status=200)=>api(server,'/competition/queue',{token:user.token,body:{gameId,mode},status})
async function until(fn,desc,timeout=15000){const end=Date.now()+timeout;while(Date.now()<end){const value=await fn();if(value)return value;await sleep(100)}throw Error('timeout '+desc)}
function watch(server,user){
 const ws=new WebSocket(server.base.replace('http:','ws:')+'/ws/lobby');sockets.add(ws);let last
 ws.onopen=()=>ws.send(JSON.stringify({type:'authenticate',token:user.token}))
 ws.onmessage=e=>{const v=JSON.parse(e.data);if(v.type==='lobby')last=v.data}
 ws.onerror=()=>{};ws.onclose=()=>sockets.delete(ws)
 return predicate=>until(()=>last&&predicate(last)&&last,'websocket match assignment')
}
async function startMatch(A,B,a,b,m){
 const users=new Map([[a.user.id,a],[b.user.id,b]])
 await Promise.all([[A,a],[B,b]].map(([s,u])=>api(s,'/rooms/'+m.roomId+'/ready',{token:u.token,body:{ready:true}})))
 const host=(await lobby(A,a)).activeRoom.host
 await api(B,'/rooms/'+m.roomId+'/start',{token:users.get(host).token,method:'POST'})
 assert.equal((await lobby(A,a)).competition.match.state,'RUNNING')
 return users.get(host)
}
let A,B
try{
 A=await start();B=cluster?await start():A
 const a=await register(A,'a'),b=await register(B,'b'),c=await register(A,'c'),d=await register(B,'d')
 await queue(A,a,'custom','RANKED',409)
 await queue(A,a,'unknown','CASUAL',400)
 const watchA=watch(A,a)
 assert.equal((await queue(A,a,'cs16')).ticket.gameId,'cs16')
 await api(A,'/rooms',{token:a.token,body:{name:'blocked',gameId:'cs16',capacity:2,visibility:'PUBLIC'},status:409})
 await queue(B,c,'warcraft3')
 assert.equal((await lobby(A,a)).competition.match,null)
 await api(B,'/competition/queue',{token:c.token,method:'DELETE'})
 assert.equal((await lobby(A,c)).competition.ticket,null)
 // Concurrent queue requests on two authorities. Exactly two participants,
 // a private assigned room, no invitation or arbitrary third-party join.
 await Promise.all([queue(B,b,'cs16'),queue(A,d,'custom')])
 const v=await watchA(l=>l.competition?.match?.gameId==='cs16')
 const m=v.competition.match
 assert.equal(v.activeRoom.id,m.roomId);assert.equal(v.activeRoom.visibility,'PRIVATE')
 assert.deepEqual(new Set(m.players.map(p=>p.id)),new Set([a.user.id,b.user.id]))
 assert.equal((await lobby(B,b)).competition.match.id,m.id)
 assert.equal((await lobby(A,c)).rooms.length,0)
 await api(A,'/rooms/'+m.roomId+'/join',{token:c.token,body:{},status:403})
 await api(A,'/rooms/'+m.roomId+'/invitation',{token:a.token,status:403})
 const host=await startMatch(A,B,a,b,m)
 await api(B,'/rooms/'+m.roomId+'/finish',{token:host.token,method:'POST'})
 assert.equal((await lobby(A,a)).activeRoom,null);assert.equal((await lobby(B,b)).activeRoom,null)
 assert.equal((await api(A,'/competition/history',{token:a.token})).entries[0].state,'FINISHED')
 assert.equal((await api(B,'/competition/leaderboard/cs16',{token:b.token})).total,0)
 await api(B,'/competition/queue',{token:d.token,method:'DELETE'})
 console.log('PASS: game/mode queue isolation, cancellation, concurrent private assignment, live room/ready/start and unrated completion')
 // Ranked mode is explicitly enabled for cs16 using an ephemeral test issuer.
 await Promise.all([queue(A,a,'cs16','RANKED'),queue(B,b,'cs16','RANKED')])
 const ranked=(await lobby(A,a)).competition.match
 assert.equal(ranked.mode,'RANKED')
 const early=report(ranked,'WIN',a.user.id)
 await api(A,'/competition/trusted-result',{body:early,status:409})
 const rankedHost=await startMatch(A,B,a,b,ranked)
 const valid=report(ranked,'WIN',a.user.id)
 await api(B,'/competition/trusted-result',{body:report(ranked,'WIN',a.user.id,stranger.privateKey),status:403})
 await api(B,'/competition/trusted-result',{body:report(ranked,'WIN',c.user.id),status:400})
 // Finishing a room does not award a win; only the configured issuer may settle.
 await api(A,'/rooms/'+ranked.roomId+'/finish',{token:rankedHost.token,method:'POST'})
 assert.equal((await lobby(B,b)).competition.awaitingResult.id,ranked.id)
 await queue(B,b,'cs16','RANKED',409)
 const settled=await Promise.all([api(A,'/competition/trusted-result',{body:valid}),api(B,'/competition/trusted-result',{body:valid})])
 assert(settled.every(m=>m.state==='RATED'))
 assert.deepEqual(settled[0].ratingChanges,settled[1].ratingChanges)
 let board=await api(A,'/competition/leaderboard/cs16',{token:c.token})
 assert.deepEqual(board.entries.map(r=>[r.rating.user.id,r.rating.points,r.rating.played]),[[a.user.id,1516,1],[b.user.id,1484,1]])
 await api(A,'/competition/trusted-result',{body:report(ranked,'WIN',b.user.id),status:409})
 assert.equal((await api(A,'/competition/history',{token:c.token})).total,0)
 assert.equal((await api(A,'/competition/leaderboard/cs16?offset=1&limit=1',{token:c.token})).entries[0].rank,2)
 console.log('PASS: signed result authentication, roster/timestamp/state checks, pending results, concurrent idempotent settlement and Elo')
 // Second ranked assignment uses the new rating and handles draw correctly.
 await Promise.all([queue(A,a,'cs16','RANKED'),queue(B,b,'cs16','RANKED')])
 const rematch=(await lobby(B,b)).competition.match
 await startMatch(A,B,a,b,rematch)
 const draw=await api(B,'/competition/trusted-result',{body:report(rematch,'DRAW',null)})
 assert.equal(draw.state,'RATED');assert.equal((await lobby(A,a)).activeRoom,null)
 board=await api(B,'/competition/leaderboard/cs16',{token:a.token})
 assert.equal(board.entries[0].rating.played,2);assert.equal(board.entries[0].rating.draws,1)
 assert.equal(board.entries.reduce((n,r)=>n+r.rating.points,0),3000)
 // A waiting assignment is cancelled for BOTH users on member leave.
 await Promise.all([queue(A,a,'custom'),queue(B,b,'custom')])
 const waiting=(await lobby(A,a)).competition.match
 await api(B,'/rooms/'+waiting.roomId+'/leave',{token:b.token,method:'POST',status:204})
 assert.equal((await lobby(A,a)).activeRoom,null);assert.equal((await lobby(B,b)).competition.match,null)
 assert((await api(A,'/competition/history',{token:a.token})).entries.some(m=>m.id===waiting.id&&m.state==='CANCELLED'))
 await queue(B,c,'warcraft3');await api(A,'/logout',{token:c.token,method:'POST',status:204})
 await queue(B,d,'warcraft3')
 assert.equal((await lobby(B,d)).competition.match,null)
 console.log('PASS: draw settlement, waiting match cancellation, queue cleanup on logout and participant-only history')
 if(cluster){
  await stop(A.p,'SIGKILL')
  assert.equal((await api(B,'/competition/leaderboard/cs16',{token:b.token})).entries[0].rating.played,2)
  A=await start()
  assert.deepEqual((await api(A,'/competition/leaderboard/cs16',{token:b.token})).entries,board.entries)
  assert.equal((await lobby(A,d)).competition.ticket.gameId,'warcraft3')
  await Promise.all([stop(A.p),stop(B.p)])
  A=await start();B=A
  assert.deepEqual((await api(A,'/competition/leaderboard/cs16',{token:b.token})).entries,board.entries)
  assert((await api(A,'/competition/history',{token:b.token})).entries.some(m=>m.id===ranked.id&&m.state==='RATED'))
  assert.equal((await lobby(A,d)).competition.ticket.gameId,'warcraft3')
  console.log('PASS: MongoDB competition state and ratings survive cross-instance JVM failure and full restart')
 }
 console.log('M5 COMPETITION INTEGRATION PASS')
} finally {for(const ws of sockets)ws.close();await Promise.all([...processes].map(p=>stop(p)))}
