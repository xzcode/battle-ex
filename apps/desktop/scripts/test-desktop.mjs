import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const app = fileURLToPath(new URL('../', import.meta.url))
const dist = resolve(app,'dist')
const jar = resolve(app,'../../server/target/battle-ex-server-0.1.0-SNAPSHOT.jar')
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME,'bin/java') : 'java'
const serve = createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost')
  const p=resolve(dist,'.'+(url.pathname==='/'?'/index.html':url.pathname))
  if(!p.startsWith(dist+sep)){res.writeHead(404).end();return}
  try{
    const body=await readFile(p)
    const type=p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':'text/html'
    res.writeHead(200,{'Content-Type':type});res.end(body)
  }catch{res.writeHead(404).end()}
})
let backend,browser
async function start(){
  await new Promise((resolve,reject)=>{serve.once('error',reject);serve.listen(0,'127.0.0.1',resolve)})
  const origin='http://127.0.0.1:'+serve.address().port
  backend=spawn(java,['-jar',jar],{env:{...process.env,BATTLE_MONGO_URI:'',BATTLE_BIND:'127.0.0.1',BATTLE_PORT:'0',BATTLE_ALLOWED_ORIGINS:origin},stdio:['ignore','pipe','pipe']})
  let logs=''
  const address=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error('startup timeout: '+logs.slice(-2000))),45000)
    function read(chunk){
      logs=(logs+chunk.toString()).slice(-10000)
      const m=logs.match(/Netty started on port (\d+)/)
      if(m&&logs.includes('Started BattleApplication')){clearTimeout(timer);resolve('http://127.0.0.1:'+m[1])}
    }
    backend.stdout.on('data',read);backend.stderr.on('data',read)
    backend.once('error',e=>{clearTimeout(timer);reject(e)})
    backend.once('exit',code=>{clearTimeout(timer);reject(new Error('server exited '+code))})
  })
  return {origin,address}
}
async function register(page,origin,address,name,password){
  await page.goto(origin)
  await page.getByRole('button',{name:'Create account',exact:true}).click()
  await page.getByLabel('Server address').fill(address)
  await page.getByLabel('Username').fill(name)
  await page.getByLabel('Password',{exact:true}).fill(password)
  await page.getByRole('button',{name:'Create account',exact:true}).click()
  await page.getByRole('heading',{name:'Rooms',exact:true}).waitFor()
  await page.getByText('Live',{exact:true}).waitFor()
}
async function untilEnabled(locator){
  for(let i=0;i<100;i++){if(await locator.isEnabled())return;await new Promise(r=>setTimeout(r,100))}
  throw new Error('button not enabled')
}
try{
  const {origin,address}=await start()
  browser=await chromium.launch({headless:true,args:['--no-sandbox']})
  const a=await browser.newPage({viewport:{width:1280,height:800}})
  const b=await browser.newPage({viewport:{width:1280,height:800}})
  const suffix=randomUUID().slice(0,8)
  const pwd='dev-test-'+randomUUID()
  await register(a,origin,address,'alice_'+suffix,pwd)
  await register(b,origin,address,'bob_'+suffix,pwd)
  await a.getByRole('button',{name:/Create room/}).click()
  const dialog=a.locator('.modal')
  await dialog.getByLabel('Room name').fill('Browser LAN test')
  await dialog.getByRole('button',{name:'Create room',exact:true}).click()
  await a.locator('.room-heading').getByRole('heading',{name:'Browser LAN test'}).waitFor()
  const row=b.getByRole('row').filter({hasText:'Browser LAN test'})
  await row.waitFor()
  await row.getByRole('button',{name:'Join',exact:true}).click()
  await b.locator('.room-heading').getByRole('heading',{name:'Browser LAN test'}).waitFor()
  await a.locator('.member').filter({hasText:'bob_'+suffix}).waitFor()
  await a.locator('.room-actions').getByRole('button',{name:'Ready',exact:true}).click()
  await b.locator('.room-actions').getByRole('button',{name:'Ready',exact:true}).click()
  const startButton=a.getByRole('button',{name:'Start match',exact:true})
  await untilEnabled(startButton)
  await startButton.click()
  await b.locator('.room-heading').getByText('In game',{exact:true}).waitFor()
  await a.getByRole('button',{name:'End session',exact:true}).click()
  await b.locator('.room-heading').getByText('Waiting',{exact:true}).waitFor()
  await a.getByRole('button',{name:'中文',exact:true}).click()
  await a.getByRole('heading',{name:'房间列表',exact:true}).waitFor()
  await a.getByRole('button',{name:'English',exact:true}).click()
  await a.getByRole('button',{name:'Sign out',exact:true}).click()
  await a.getByRole('heading',{name:'Sign in',exact:true}).waitFor()
  await b.locator('.member').filter({hasText:'bob_'+suffix}).getByText('Host',{exact:true}).waitFor()
  assert.equal(await b.locator('.members .member').count(),1)
  await b.getByRole('button',{name:'Leave',exact:true}).click()
  await b.getByRole('heading',{name:'You have not joined a room',exact:true}).waitFor()
  console.log('PASS: built Vue UI against real Spring Boot server: two browser sessions, room creation/join, live readiness, start/end, language toggle, logout and host transfer')
}finally{
  if(browser)await browser.close()
  if(backend&&backend.exitCode===null&&backend.signalCode===null){
    const p=backend;await new Promise(resolve=>{const timer=setTimeout(()=>p.kill('SIGKILL'),5000);p.once('exit',()=>{clearTimeout(timer);resolve()});p.kill('SIGTERM')})
  }
  serve.close()
}
