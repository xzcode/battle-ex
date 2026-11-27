export type User={id:string;username:string}
export type RoomState='WAITING'|'IN_GAME'
export type Visibility='PUBLIC'|'PRIVATE'
export type Member={userId:string;ready:boolean}
export type RoomSummary={id:string;name:string;gameId:string;capacity:number;occupancy:number;state:RoomState}
export type Room={id:string;networkId:string;name:string;gameId:string;capacity:number;visibility:Visibility;host:string;state:RoomState;revision:number;membershipRevision:number;members:Member[]}
export type Lobby={user:User;revision:number;signedInUsers:number;rooms:RoomSummary[];activeRoom:Room|null;memberNames:Record<string,string>}
export type Login={token:string;expiresAt:string;user:User}
export class ApiError extends Error {constructor(public status:number,message:string){super(message)}}
export function serverOrigin(raw:string):string{
  const u=new URL(raw.trim())
  const loopback=['localhost','127.0.0.1','[::1]'].includes(u.hostname)
  if(u.protocol!=='https:' && !(u.protocol==='http:'&&loopback)) throw new Error('Use HTTPS for a remote server.')
  if(u.username||u.password||u.search||u.hash||u.pathname!=='/')throw new Error('Enter the server origin, without a path or credentials.')
  return u.origin
}
export async function request<T>(origin:string,path:string,token:string|null=null,method='GET',body?:unknown):Promise<T>{
  const res=await fetch(origin+'/api/v1'+path,{
    method,credentials:'omit',cache:'no-store',redirect:'error',
    headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
    ...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)
  })
  if(!res.ok){
    const result=await res.json().catch(()=>({}))
    throw new ApiError(res.status,typeof result.error==='string'?result.error:'HTTP '+res.status)
  }
  return (res.status===204?undefined:await res.json()) as T
}
export function lobbySocket(origin:string,token:string,receive:(lobby:Lobby)=>void,onStatus:(connected:boolean)=>void):WebSocket{
  const url=new URL('/ws/lobby',origin);url.protocol=url.protocol==='https:'?'wss:':'ws:'
  const ws=new WebSocket(url)
  ws.onopen=()=>{ws.send(JSON.stringify({type:'authenticate',token}))}
  ws.onmessage=e=>{try{const msg=JSON.parse(e.data);if(msg.type==='lobby'&&msg.data?.user){onStatus(true);receive(msg.data)}}catch{ws.close(1002)}}
  ws.onclose=()=>onStatus(false)
  ws.onerror=()=>{ws.close()}
  return ws
}
