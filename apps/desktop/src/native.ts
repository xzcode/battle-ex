import {invoke,isTauri} from '@tauri-apps/api/core'
export interface PeerNetworkStatus {
 userId:string;ip:string;mode:string;handshakeUnix:string;rxBytes:string;txBytes:string;rttMillis:number|null;relayConnected:boolean;jitterMillis?:number|null;probeLossPct?:number|null;probeSamples?:number
}
export interface NetworkStatus {
 state:string;roomId?:string;networkId?:string;revision?:number;ip?:string;expiresAt?:number;relayNode?:string;relayRegion?:string;gameId?:string;discoveryProfile?:string;discovery?:{sent:number;received:number;dropped:number};relayCandidates?:{id:string;region:string;probeMillis:number|null}[];peers:PeerNetworkStatus[]|null;error?:string
}
export interface NetworkInfo {enabled:boolean;issuer:string}
export const nativeAvailable=isTauri()
export function startNetwork(origin:string,token:string,issuer:string,userId:string){
 return invoke<void>('network_start',{origin,token,issuer,userId})
}
export function stopNetwork(){return invoke<void>('network_stop')}
export function networkStatus(){return invoke<NetworkStatus>('network_status')}

export type GameInstall={path:string;sha256:string;fileVersion:string|null;architecture:string;size:number;modified:number}
export type GameEntry={id:string;install:GameInstall|null;state:'configured'|'changed'|'missing'|'unconfigured';pid:number|null;exitCode:number|null}
export function listGames(){return invoke<GameEntry[]>('game_list')}
export function detectGame(id:string){return invoke<string[]>('game_detect',{id})}
export function chooseGame(id:string){return invoke<GameInstall|null>('game_choose',{id})}
export function adoptGame(id:string,path:string){return invoke<GameInstall|null>('game_adopt',{id,path})}
export function verifyGame(id:string){return invoke<boolean>('game_verify',{id})}
export function removeGame(id:string){return invoke<void>('game_remove',{id})}
export function launchGame(id:string){return invoke<number|null>('game_launch',{id})}

export function joinCsPeer(roomId:string,peerId:string,port:number){return invoke<number|null>('game_join_cs',{roomId,peerId,port})}

export type LocalReplay={id:string;gameId:string;filename:string;sha256:string;bytes:number;importedAt:number}
export function listReplays(origin:string,userId:string){return invoke<LocalReplay[]>('replay_list',{origin,userId})}
export function importReplay(origin:string,userId:string,gameId:string){return invoke<LocalReplay|null>('replay_import',{origin,userId,gameId})}
export function verifyReplay(origin:string,userId:string,id:string){return invoke<LocalReplay>('replay_verify',{origin,userId,id})}
export function exportReplay(origin:string,userId:string,id:string){return invoke<boolean>('replay_export',{origin,userId,id})}
export function removeReplay(origin:string,userId:string,id:string){return invoke<boolean>('replay_remove',{origin,userId,id})}
