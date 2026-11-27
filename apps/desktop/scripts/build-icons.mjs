// Generate an original simple monochrome/teal application ICO locally.
import {mkdirSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
const dir=resolve(fileURLToPath(new URL('../',import.meta.url)),'src-tauri/icons')
mkdirSync(dir,{recursive:true})
const sizes=[16,32,48,64,128,256]
const images=sizes.map(n=>{
 const maskStride=Math.ceil(n/32)*4
 const b=Buffer.alloc(40+n*n*4+maskStride*n)
 b.writeUInt32LE(40,0);b.writeInt32LE(n,4);b.writeInt32LE(2*n,8)
 b.writeUInt16LE(1,12);b.writeUInt16LE(32,14);b.writeUInt32LE(n*n*4,20)
 for(let y=0;y<n;y++)for(let x=0;x<n;x++){
  const u=(x+.5)/n,v=(y+.5)/n
  const edge=Math.hypot(Math.max(Math.abs(u-.5)-.35,0),Math.max(Math.abs(v-.5)-.35,0))
  const opaque=edge<.13
  const left=u>.22&&u<.36&&v>.25&&v<.75
  const top=u>.35&&u<.57&&v>.25&&v<.37
  const mid=u>.35&&u<.57&&v>.44&&v<.56
  const bottom=u>.35&&u<.57&&v>.63&&v<.75
  const right=u>.54&&u<.65&&((v>.29&&v<.49)||(v>.52&&v<.71))
  const cross=u>.63&&u<.83&&v>.36&&v<.64&&(Math.abs((u-.73)-(v-.50))<.055||Math.abs((u-.73)+(v-.50))<.055)
  const [r,g,bl]=left||top||mid||bottom||right?[225,244,239]:cross?[105,215,184]:[22,41,47]
  const p=40+((n-1-y)*n+x)*4
  b[p]=bl;b[p+1]=g;b[p+2]=r;b[p+3]=opaque?255:0
  if(!opaque)b[40+n*n*4+(n-1-y)*maskStride+Math.floor(x/8)]|=1<<(7-x%8)
 }
 return b
})
const header=Buffer.alloc(6+16*sizes.length);header.writeUInt16LE(1,2);header.writeUInt16LE(sizes.length,4)
let offset=header.length
for(let i=0;i<sizes.length;i++){
 const p=6+16*i,n=sizes[i]
 header[p]=n===256?0:n;header[p+1]=header[p]
 header.writeUInt16LE(1,p+4);header.writeUInt16LE(32,p+6)
 header.writeUInt32LE(images[i].length,p+8);header.writeUInt32LE(offset,p+12);offset+=images[i].length
}
writeFileSync(resolve(dir,'icon.ico'),Buffer.concat([header,...images]))
