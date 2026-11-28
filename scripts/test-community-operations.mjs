import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {createHash,randomBytes,randomUUID} from 'node:crypto'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

assert(process.env.BATTLE_COMMUNITY_CLUSTER_TEST==='true'&&process.env.BATTLE_MONGO_URI,'disposable shared MongoDB required')
const root=fileURLToPath(new URL('../',import.meta.url)),jar=resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java'
const processes=new Set(),sockets=new Set(),key=randomBytes(32).toString('base64url'),password='fixture-'+randomUUID()
const env={...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_STATE_MODE:process.env.BATTLE_TEST_STATE_MODE||'mongo',
 BATTLE_NETWORK_ISSUER_KEY_FILE:'',BATTLE_NETWORK_RELAY_DIRECTORY_FILE:'',BATTLE_NETWORK_RELAY_URL:'',BATTLE_NETWORK_RELAY_SECRET_FILE:'',
 BATTLE_COMPETITION_RESULT_ISSUER:'',BATTLE_COMPETITION_RANKED_GAMES:'',
 BATTLE_MODERATION_KEY_SHA256:createHash('sha256').update(key).digest('hex'),BATTLE_OPERATIONS_ROOT_USERS:''}
async function start(extra={}){
 const p=spawn(java,['-jar',jar],{env:{...env,...extra},stdio:['ignore','pipe','pipe']});processes.add(p);let log='',base
 await new Promise((ok,fail)=>{
  const timer=setTimeout(()=>fail(Error('startup timeout '+log.slice(-1800))),45000)
  const read=b=>{log=(log+b.toString()).slice(-14000);const m=log.match(/Netty started on port (\d+)/);if(m&&log.includes('Started BattleApplication')){clearTimeout(timer);base='http://127.0.0.1:'+m[1];ok()}}
  p.stdout.on('data',read);p.stderr.on('data',read);p.once('error',e=>{clearTimeout(timer);fail(e)});p.once('exit',code=>{clearTimeout(timer);fail(Error('server exited '+code+' '+log.slice(-1800)))})
 });return {p,base}
}
async function stop(p,signal='SIGTERM'){
 if(p.exitCode!==null||p.signalCode!==null)return
 await new Promise(ok=>{const timer=setTimeout(()=>p.kill('SIGKILL'),8000);p.once('exit',()=>{clearTimeout(timer);ok()});p.kill(signal)})
}
async function api(s,path,{token,body,method=body===undefined?'GET':'POST',status=200,operator=false}={}){
 const response=await fetch(s.base+'/api/v1'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(operator?{'X-Battle-Moderator':key}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(20000)})
 const raw=await response.text();assert.equal(response.status,status,method+' '+path+' '+raw.slice(0,250))
 return raw?JSON.parse(raw):null
}
const account=(s,n)=>api(s,'/register',{body:{username:n+'_'+randomUUID().slice(0,8),password},status:201})
const login=(s,u)=>api(s,'/login',{body:{username:u.user.username,password}})
const lobby=(s,u)=>api(s,'/lobby',{token:u.token})
const u=(user,extra={})=>({token:user.token,...extra})
const admin=(user,extra={})=>u(user,{operator:true,...extra})
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
function watch(s,user){
 const ws=new WebSocket(s.base.replace('http:','ws:')+'/ws/lobby');sockets.add(ws);let last
 ws.onopen=()=>ws.send(JSON.stringify({type:'authenticate',token:user.token}))
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.type==='lobby')last=m.data}
 ws.onclose=()=>sockets.delete(ws);ws.onerror=()=>{}
 return async predicate=>{const end=Date.now()+15000;while(Date.now()<end){if(last&&predicate(last))return last;await sleep(100)}throw Error('websocket assignment timeout')}
}
let A,B,rootUser
try{
 // Bootstrap an actual existing MongoDB account, without creating a shared
 // authority document under a different operator fingerprint.
 const bootstrap=await start({BATTLE_STATE_MODE:'memory'})
 const initialRoot=await account(bootstrap,'root')
 await stop(bootstrap.p)
 env.BATTLE_OPERATIONS_ROOT_USERS=initialRoot.user.id
 A=await start();B=await start()
 rootUser=await login(A,initialRoot)
 const manager=await account(B,'event'),analyst=await account(A,'analyst'),moderator=await account(B,'reviewer')
 const a=await account(A,'alice'),b=await account(B,'bob'),c=await account(A,'carol'),d=await account(B,'dave')
 assert.equal((await api(B,'/operations/access',admin(rootUser))).root,true)
 assert.deepEqual((await api(A,'/operations/access',admin(a))).roles,[])
 await api(A,'/operations/overview',admin(a,{status:403}))
 await api(B,'/operations/roles',admin(a,{status:403}))
 const grant=(target,role)=>api(A,'/operations/roles',admin(rootUser,{body:{username:target.user.username,role,reason:'Isolated CI fixture'}}))
 await grant(manager,'EVENT_MANAGER');await grant(analyst,'ANALYST');await grant(moderator,'MODERATOR')
 assert.deepEqual((await api(B,'/operations/access',admin(manager))).roles,['EVENT_MANAGER'])
 await api(B,'/moderation/admin/reports',admin(manager,{status:403}))
 assert.equal((await api(A,'/moderation/admin/reports',admin(moderator))).total,0)
 await api(B,'/community/admin/events',admin(analyst,{body:{name:'Denied',gameId:'cs16',capacity:4},status:403}))
 await api(B,'/operations/roles',admin(manager,{status:403}))
 assert((await api(B,'/operations/overview',admin(analyst))).persistent)
 await api(B,'/operations/roles/'+analyst.user.id+'/revoke',admin(rootUser,{body:{role:'ANALYST',reason:'Fixture revoke'},status:204}))
 await api(A,'/operations/overview',admin(analyst,{status:403}))
 console.log('PASS: immutable root UUID bootstrap, per-account operator roles, immediate cross-instance revoke, legacy key-only access denied in RBAC mode')

 const ta=await api(A,'/community/teams',u(a,{body:{name:'Alpha',tag:'A1',description:'Alpha team'},status:201}))
 const td=await api(B,'/community/teams',u(d,{body:{name:'Delta',tag:'D1',description:'Delta team'},status:201}))
 assert.equal((await api(B,'/community/teams/mine',u(a))).team.id,ta.id)
 await api(A,'/community/teams',u(c,{body:{name:'ALPHA',tag:'A2'},status:409}))
 await api(A,'/community/teams',u(c,{body:{name:'Another',tag:'a1'},status:409}))
 const apply=(s,id,user)=>api(s,'/community/teams/'+id+'/applications',u(user,{body:{message:'Join fixture'}}))
 await apply(A,ta.id,b);await apply(B,td.id,b)
 assert.equal((await api(B,'/community/teams/'+ta.id,u(c))).applications.length,0)
 assert.equal((await api(B,'/community/teams/'+ta.id,u(a))).applications.length,1)
 const review=(s,id,owner,target,accept=true,status=200)=>api(s,'/community/teams/'+id+'/applications/'+target.user.id+'/review',u(owner,{body:{accept},status}))
 await review(B,ta.id,c,b,true,403)
 // Race two captains to accept the same applicant. One membership wins.
 const rr=await Promise.all([[A,ta,a],[B,td,d]].map(async([s,t,owner])=>{
  const r=await fetch(s.base+'/api/v1/community/teams/'+t.id+'/applications/'+b.user.id+'/review',{method:'POST',headers:{Authorization:'Bearer '+owner.token,'Content-Type':'application/json'},body:JSON.stringify({accept:true})});await r.text();return {id:t.id,status:r.status}
 }))
 assert.equal(rr.filter(x=>x.status===200).length,1)
 assert(rr.every(x=>[200,400,409].includes(x.status)))
 const won=rr.find(x=>x.status===200).id, owner=won===ta.id?a:d
 assert.equal((await api(A,'/community/teams/mine',u(b))).team.id,won)
 await api(A,'/community/teams/'+won+'/members/'+b.user.id+'/role',u(owner,{body:{role:'OFFICER'}}))
 await apply(B,won,c);await review(A,won,b,c)
 await api(B,'/community/teams/'+won+'/transfer',u(b,{body:{targetId:c.user.id},status:403}))
 await api(B,'/community/teams/'+won+'/transfer',u(owner,{body:{targetId:c.user.id}}))
 const current=(await api(B,'/community/teams/'+won,u(c))).team
 assert.equal(current.members.find(x=>x.role==='OWNER').user.id,c.user.id)
 await api(A,'/community/teams/'+won+'/members/'+c.user.id,u(c,{method:'DELETE',status:409}))
 await api(A,'/community/teams/'+won+'/edit',u(c,{body:{name:'Fixture renamed',tag:'FIX',description:'Updated'}}))
 assert.equal((await api(B,'/community/teams?search=FIX',u(b))).total,1)
 console.log('PASS: team uniqueness, applicant privacy, concurrent single membership, officer review, role and captain transfer')

 const e=await api(A,'/community/admin/events',admin(manager,{body:{name:'Fixture cup',gameId:'cs16',description:'Manual adjudication test',capacity:3},status:201}))
 const path='/community/events/'+e.id,operatorPath='/community/admin/events/'+e.id
 for(const player of [a,b,c])await api(B,path+'/register',u(player,{method:'POST'}))
 await api(A,path+'/register',u(d,{method:'POST',status:409}))
 await api(B,path+'/register',u(c,{method:'DELETE'}));await api(A,path+'/register',u(c,{method:'POST'}))
 const bracket=await api(B,operatorPath+'/start',admin(manager,{method:'POST'}))
 assert.equal(bracket.slots.length,3);assert.equal(bracket.slots.filter(s=>s.state==='BYE').length,1)
 let slot=bracket.slots.find(s=>s.state==='READY')
 assert(slot)
 const players=new Map([a,b,c,d].map(x=>[x.user.id,x]))
 const first=players.get(slot.first),second=players.get(slot.second)
 await api(A,path+'/slots/'+slot.id+'/check-in',u(d,{body:{ready:true},status:403}))
 await api(A,'/competition/queue',u(first,{body:{gameId:'cs16',mode:'TOURNAMENT'},status:400}))
 const watchFirst=watch(B,first)
 await api(A,path+'/slots/'+slot.id+'/check-in',u(first,{body:{ready:true}}))
 // Entering a manual queue invalidates prior event check-in.
 await api(B,'/competition/queue',u(first,{body:{gameId:'warcraft3',mode:'CASUAL'}}))
 assert.equal((await api(A,path,u(d))).slots.find(s=>s.id===slot.id).checkedIn.length,0)
 await api(B,'/competition/queue',u(first,{method:'DELETE'}))
 // Concurrent check-in assigns exactly one managed private room.
 await Promise.all([api(A,path+'/slots/'+slot.id+'/check-in',u(first,{body:{ready:true}})),api(B,path+'/slots/'+slot.id+'/check-in',u(second,{body:{ready:true}}))])
 let match=(await watchFirst(l=>l.competition?.match?.mode==='TOURNAMENT')).competition.match
 assert.equal((await lobby(A,second)).competition.match.id,match.id)
 slot=(await api(B,path,u(d))).slots.find(s=>s.id===slot.id)
 assert.equal(slot.attempts.length,1);assert.equal(slot.matchId,match.id)
 assert.equal((await lobby(B,first)).activeRoom.visibility,'PRIVATE')
 await api(A,operatorPath+'/slots/'+slot.id+'/decide',admin(manager,{body:{winner:first.user.id,source:'PLAYED',reason:'Early ruling'},status:409}))
 await api(A,operatorPath+'/cancel',admin(manager,{body:{reason:'Active room'},status:409}))
 // Cancelled pre-start attempt, explicit retry, second attempt finished.
 await api(B,'/rooms/'+match.roomId+'/leave',u(second,{method:'POST',status:204}))
 await api(A,operatorPath+'/slots/'+slot.id+'/retry',admin(manager,{body:{reason:'Fixture replay'}}))
 await Promise.all([api(A,path+'/slots/'+slot.id+'/check-in',u(first,{body:{ready:true}})),api(B,path+'/slots/'+slot.id+'/check-in',u(second,{body:{ready:true}}))])
 let view=await lobby(B,first);match=view.competition.match
 slot=(await api(A,path,u(d))).slots.find(s=>s.id===slot.id)
 assert.equal(slot.attempts.length,2)
 await Promise.all([api(A,'/rooms/'+match.roomId+'/ready',u(first,{body:{ready:true}})),api(B,'/rooms/'+match.roomId+'/ready',u(second,{body:{ready:true}}))])
 const host=view.activeRoom.host===first.user.id?first:second
 await api(A,'/rooms/'+match.roomId+'/start',u(host,{method:'POST'}))
 await api(B,'/rooms/'+match.roomId+'/finish',u(host,{method:'POST'}))
 await api(B,operatorPath+'/slots/'+slot.id+'/decide',admin(manager,{body:{winner:d.user.id,source:'PLAYED',reason:'Wrong roster'},status:400}))
 let updated=await api(A,operatorPath+'/slots/'+slot.id+'/decide',admin(manager,{body:{winner:first.user.id,source:'PLAYED',reason:'Manual fixture review'}}))
 const final=updated.slots.find(s=>s.id===1);assert.equal(final.state,'READY')
 assert([final.first,final.second].includes(first.user.id))
 // Final explicit no-show ruling, not a fabricated played match.
 updated=await api(B,operatorPath+'/slots/1/decide',admin(manager,{body:{winner:first.user.id,source:'FORFEIT',reason:'Fixture opponent no-show'}}))
 assert.equal(updated.state,'FINISHED');assert.equal(updated.champion,first.user.id)
 assert.equal((await api(A,'/competition/leaderboard/cs16',u(a))).total,0)
 assert((await api(B,'/competition/history',u(first))).entries.some(m=>m.id===match.id&&m.mode==='TOURNAMENT'&&m.state==='FINISHED'))
 console.log('PASS: three-entrant bracket/byes, check-in invalidation, concurrent private assignment, retry, played ruling, forfeit, champion and no Elo change')
 // Root-only session and room administration, with no bearer digests or invitations in responses.
 await api(B,'/operations/sessions',admin(manager,{status:403}))
 await api(B,'/operations/rooms',admin(manager,{status:403}))
 const manual=await api(A,'/rooms',u(d,{body:{name:'Private operations fixture',gameId:'custom',capacity:2,visibility:'PRIVATE'},status:201}))
 const roomRows=await api(B,'/operations/rooms',admin(rootUser))
 const row=roomRows.entries.find(r=>r.id===manual.id)
 assert(row&&row.visibility==='PRIVATE'&&!('networkId' in row)&&!('invite' in row))
 await api(B,'/operations/rooms/'+manual.id+'/close',admin(rootUser,{body:{reason:'Fixture close'},status:204}))
 assert.equal((await lobby(A,d)).activeRoom,null)
 const disposable=await account(A,'disconnect')
 await api(B,'/competition/queue',u(disposable,{body:{gameId:'redalert2',mode:'CASUAL'}}))
 const sessionRows=await api(A,'/operations/sessions',admin(rootUser))
 assert(sessionRows.entries.some(s=>s.user.id===disposable.user.id&&s.queued&&!('digest' in s)&&!('token' in s)))
 await api(A,'/operations/sessions/'+disposable.user.id+'/disconnect',admin(manager,{body:{reason:'Denied'},status:403}))
 await api(B,'/operations/sessions/'+disposable.user.id+'/disconnect',admin(rootUser,{body:{reason:'Fixture disconnect'},status:204}))
 await api(A,'/lobby',u(disposable,{status:401}))
 assert.equal((await lobby(B,await login(B,disposable))).competition.ticket,null)
 console.log('PASS: root-only redacted session/room lists, forced room close and session disconnect across instances')
 const stats=await api(B,'/operations/overview',admin(rootUser))
 if(process.env.BATTLE_TEST_STATE_MODE==="mongo-split"){assert.equal(stats.storage.mode,"mongo-split");assert(stats.storage.scopedRoomWrites>0);assert(stats.storage.projectedReads>0)}
 assert.equal(stats.teams,2);assert.equal(stats.tournaments,1);assert.equal(stats.runningMatches,0)
 const audit=await api(A,'/operations/audit?limit=100',admin(rootUser))
 for(const type of ['ROLE_GRANTED','ROLE_REVOKED','EVENT_CREATED','EVENT_STARTED','EVENT_RETRY','EVENT_DECIDED','ROOM_CLOSED','SESSION_DISCONNECTED'])assert(audit.entries.some(x=>x.action===type))
 await api(A,'/operations/audit',admin(manager,{status:403}))
 await stop(A.p,'SIGKILL');assert.equal((await api(B,path,u(d))).champion,first.user.id)
 A=await start()
 assert.equal((await api(A,path,u(d))).champion,first.user.id)
 await Promise.all([stop(A.p),stop(B.p)]);A=await start();B=A
 assert.equal((await api(A,path,u(d))).champion,first.user.id)
 assert.equal((await api(B,'/community/teams/mine',u(b))).team.id,won)
 assert.equal((await api(B,'/operations/audit?limit=100',admin(rootUser))).total,audit.total)
 assert.deepEqual((await api(A,'/operations/access',admin(manager))).roles,['EVENT_MANAGER'])
 await api(A,'/community/teams/'+won,u(c,{method:'DELETE',status:204}))
 assert.equal((await api(B,'/community/teams/mine',u(b))).team,null)
 console.log('PASS: teams, event attempts/champion, delegated roles and audit persist across authority loss and all-JVM restart')
 console.log('M8–M9 COMMUNITY AND OPERATIONS INTEGRATION PASS')
}finally{for(const socket of sockets)socket.close();await Promise.all([...processes].map(p=>stop(p)))}
