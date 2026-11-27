export type User={id:string;username:string}
export type RoomState='WAITING'|'IN_GAME'
export type Visibility='PUBLIC'|'PRIVATE'
export type GameBuild={gameId:string;sha256:string;fileVersion:string|null;architecture:string}
export type Member={userId:string;ready:boolean;gameBuild?:GameBuild|null}
export type RoomSummary={id:string;name:string;gameId:string;capacity:number;occupancy:number;state:RoomState}
export type Room={id:string;networkId:string;name:string;gameId:string;capacity:number;visibility:Visibility;host:string;state:RoomState;revision:number;membershipRevision:number;members:Member[]}
export type Lobby={user:User;revision:number;signedInUsers:number;rooms:RoomSummary[];activeRoom:Room|null;memberNames:Record<string,string>;competition?:CompetitionView;moderationConfigured?:boolean}
export type Login={token:string;expiresAt:string;user:User}
export class ApiError extends Error {constructor(public status:number,message:string){super(message)}}
export function serverOrigin(raw:string):string{
  const u=new URL(raw.trim())
  const loopback=['localhost','127.0.0.1','[::1]'].includes(u.hostname)
  if(u.protocol!=='https:' && !(u.protocol==='http:'&&loopback)) throw new Error('Use HTTPS for a remote server.')
  if(u.username||u.password||u.search||u.hash||u.pathname!=='/')throw new Error('Enter the server origin, without a path or credentials.')
  return u.origin
}
export async function request<T>(origin:string,path:string,token:string|null=null,method='GET',body?:unknown,moderatorKey?:string):Promise<T>{
  const res=await fetch(origin+'/api/v1'+path,{
    method,credentials:'omit',cache:'no-store',redirect:'error',
    headers:{...(token?{Authorization:'Bearer '+token}:{}),...(moderatorKey?{'X-Battle-Moderator':moderatorKey}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},
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


export type MatchMode='CASUAL'|'RANKED'
export type MatchRecord={id:string;roomId:string;gameId:string;mode:MatchMode;players:User[];state:string;createdAt:number;startedAt:number|null;endedAt:number|null;resultDeadline:number|null;outcome:'WIN'|'DRAW'|null;winnerId:string|null;ratingChanges:{userId:string;before:number;after:number}[]}
export type CompetitionView={ticket:{user:User;gameId:string;mode:MatchMode;queuedAt:number}|null;match:MatchRecord|null;awaitingResult:MatchRecord|null;rankedGames:string[]}
export type Leaderboard={gameId:string;offset:number;total:number;entries:{rank:number;rating:{gameId:string;user:User;points:number;played:number;wins:number;draws:number;losses:number}}[]}
export type MatchHistory={offset:number;total:number;entries:MatchRecord[]}


export type ReplayRecord={id:string;matchId:string;uploader:User;gameId:string;filename:string;sha256:string;bytes:number;createdAt:number}
export type MatchDetail={match:MatchRecord;replays:ReplayRecord[];sharingStatus:boolean;publicStatus:boolean}
export type LiveMatch={matchId:string;gameId:string;mode:MatchMode;players:User[];startedAt:number}
export type Page<T>={offset:number;total:number;entries:T[]}
export type ModerationReport={id:string;matchId:string;reporter:User;target:User;reason:string;details:string;replaySha256:string|null;createdAt:number;status:string;reviewer:User|null;reviewedAt:number|null;resolution:string|null;suspensionId:string|null}
export type Suspension={id:string;target:User;reviewer:User;reportId:string;reason:string;createdAt:number;until:number|null;revokedAt:number|null;revokedBy:User|null;revokeReason:string|null}
export type WarningRecord={id:string;matchId:string;message:string;at:number}
