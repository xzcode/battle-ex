import {spawnSync} from 'node:child_process'
import {mkdirSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {resolve} from 'node:path'
const desktop=fileURLToPath(new URL('../',import.meta.url))
const result=spawnSync('rustc',['-vV'],{encoding:'utf8'})
const host=result.stdout?.match(/^host:\s*(\S+)/m)?.[1]
const target=process.env.BATTLE_AGENT_TARGET||process.env.TAURI_ENV_TARGET_TRIPLE||host
if(!target)throw new Error('Rust target triple required to build network agent')
const goos=target.includes('windows')?'windows':target.includes('darwin')?'darwin':target.includes('linux')?'linux':null
const goarch=target.startsWith('x86_64')?'amd64':target.startsWith('aarch64')?'arm64':null
if(!goos||!goarch)throw new Error('Unsupported agent target: '+target)
const dir=resolve(desktop,'src-tauri/binaries');mkdirSync(dir,{recursive:true})
const out=resolve(dir,'battle-agent-'+target+(goos==='windows'?'.exe':''))
const run=spawnSync('go',['build','-trimpath','-o',out,'./cmd/agent'],{
 cwd:resolve(desktop,'../../agent'),env:{...process.env,GOOS:goos,GOARCH:goarch,CGO_ENABLED:'0'},stdio:'inherit'
})
if(run.error)throw run.error
if(run.status!==0)process.exit(run.status||1)
