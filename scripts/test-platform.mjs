import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java') : 'java'
const jar = resolve(root,'server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const password = 'dev-test-' + randomUUID()
const suffix = randomUUID().slice(0,8)
const aliceName = 'alice_' + suffix
const bobName = 'bob_' + suffix
let server
const sockets = new Set()
let base
async function start() {
  server = spawn(java, ['-jar',jar], {env:{...process.env,BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0'},stdio:['ignore','pipe','pipe']})
  let logs = ''
  await new Promise((resolve,reject)=>{
    const timer = setTimeout(()=>reject(new Error('server startup timeout: '+logs.slice(-3000))),45000)
    const done = (err)=>{clearTimeout(timer);err?reject(err):resolve()}
    const read = chunk=>{
      logs = (logs + chunk.toString()).slice(-16000)
      const match = logs.match(/Tomcat started on port (\d+)/)
      if (match && /Started BattleApplication/.test(logs)) {base='http://127.0.0.1:'+match[1];done()}
    }
    server.stdout.on('data',read); server.stderr.on('data',read)
    server.once('error',done)
    server.once('exit',(code)=>done(new Error('server exited '+code+': '+logs.slice(-3000))))
  })
}
async function stop() {
  for(const ws of sockets) ws.close()
  sockets.clear()
  if(!server || server.exitCode!==null || server.signalCode!==null)return
  const p=server
  await new Promise(resolve=>{
    const timer=setTimeout(()=>p.kill('SIGKILL'),5000)
    p.once('exit',()=>{clearTimeout(timer);resolve()})
    p.kill('SIGTERM')
  })
}
async function request(path,{method='GET',token,body,status=200,headers={}}={}) {
  const response=await fetch(base+'/api/v1'+path,{method,headers:{...headers,...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)})
  assert.equal(response.status,status,method+' '+path+' HTTP status')
  assert.equal(response.headers.get('cache-control'),'no-store')
  if(status===204)return
  return response.json()
}
function wsSubscribe(token) {
  const ws=new WebSocket(base.replace('http:','ws:')+'/ws/lobby')
  sockets.add(ws)
  const snapshots=[]
  let closed=false
  ws.addEventListener('open',()=>ws.send(JSON.stringify({type:'authenticate',token})))
  ws.addEventListener('message',e=>{
    const v=JSON.parse(e.data); assert.equal(v.type,'lobby');snapshots.push(v.data)
  })
  ws.addEventListener('close',()=>{closed=true;sockets.delete(ws)})
  ws.addEventListener('error',()=>{})
  const wait=async predicate=>{
    const until=Date.now()+9000
    while(Date.now()<until){const result=snapshots.find(predicate);if(result)return result;await new Promise(r=>setTimeout(r,50))}
    throw new Error('lobby snapshot timeout')
  }
  return {wait, async waitClosed(){const until=Date.now()+9000;while(Date.now()<until){if(closed)return;await new Promise(r=>setTimeout(r,50))}throw new Error('websocket not revoked')}}
}

try {
  await start()
  await request('/lobby',{status:401})
  const a=await request('/register',{method:'POST',body:{username:aliceName,password},status:201})
  const b=await request('/register',{method:'POST',body:{username:bobName,password},status:201})
  assert.equal(a.user.username,aliceName)
  assert(!JSON.stringify(a).includes(password))
  await request('/register',{method:'POST',body:{username:aliceName.toUpperCase(),password},status:409})
  await request('/login',{method:'POST',body:{username:aliceName,password:'bad-password'},status:401})
  const fake=wsSubscribe('a'.repeat(43)); await fake.waitClosed()
  const ws=wsSubscribe(a.token)
  await ws.wait(s=>s.user.id===a.user.id && s.rooms.length===0)
  const r=await request('/rooms',{method:'POST',token:a.token,body:{name:'Private test',gameId:'custom',capacity:3,visibility:'PRIVATE',actor:b.user.id},status:201})
  assert.equal(r.host,a.user.id)
  await ws.wait(s=>s.activeRoom?.id===r.id)
  const path='/rooms/'+r.id
  const lobby=await request('/lobby',{token:b.token});assert.equal(lobby.rooms.length,0);assert.equal(lobby.activeRoom,null)
  await request(path+'/join',{method:'POST',token:b.token,body:{invite:'invalid'},status:403})
  await request(path+'/invitation',{token:b.token,status:403})
  const invite=await request(path+'/invitation',{token:a.token})
  await request(path+'/join',{method:'POST',token:b.token,body:invite})
  await ws.wait(s=>s.activeRoom?.members.length===2)
  await request(path+'/start',{method:'POST',token:b.token,status:403})
  await request(path+'/start',{method:'POST',token:a.token,status:409})
  await request(path+'/ready',{method:'POST',token:a.token,body:{ready:true}})
  await request(path+'/ready',{method:'POST',token:b.token,body:{ready:true}})
  await request(path+'/start',{method:'POST',token:a.token})
  await ws.wait(s=>s.activeRoom?.state==='IN_GAME')
  await request(path+'/finish',{method:'POST',token:a.token})
  await request('/logout',{method:'POST',token:a.token,status:204})
  await ws.waitClosed()
  await request('/lobby',{token:a.token,status:401})
  const transferred=await request('/lobby',{token:b.token})
  assert.equal(transferred.activeRoom.host,b.user.id);assert.equal(transferred.activeRoom.members.length,1)
  const rotated=await request(path+'/invitation',{token:b.token});assert.notEqual(rotated.invite,invite.invite)
  await request(path+'/leave',{method:'POST',token:b.token,status:204})
  assert.equal((await request('/lobby',{token:b.token})).activeRoom,null)
  const relogin=await request('/login',{method:'POST',body:{username:bobName,password}})
  await request('/lobby',{token:b.token,status:401})
  const pub=await request('/rooms',{method:'POST',token:relogin.token,body:{name:'Public test',gameId:'custom',capacity:8,visibility:'PUBLIC'},status:201})
  assert.equal((await request('/lobby',{token:relogin.token})).rooms[0].id,pub.id)
  const badOrigin=await fetch(base+'/api/v1/lobby',{headers:{Origin:'https://invalid.example',Authorization:'Bearer '+relogin.token}})
  assert.equal(badOrigin.headers.get('access-control-allow-origin'),null)
  console.log('PASS: real HTTP authentication, private/public rooms, readiness, host authorization/transfer, invitation rotation, WebSocket updates and revocation')
  if(process.env.BATTLE_TEST_PERSISTENCE==='true'){
    assert(process.env.BATTLE_MONGO_URI,'persistence test requires disposable MongoDB')
    await stop();await start()
    const after=await request('/login',{method:'POST',body:{username:aliceName,password}})
    assert.equal(after.user.id,a.user.id)
    await request('/lobby',{token:relogin.token,status:401})
    const view=await request('/lobby',{token:after.token});assert.equal(view.rooms.length,0)
    console.log('PASS: MongoDB account persisted across process restart; sessions and rooms intentionally reset')
  }
} finally { await stop() }
