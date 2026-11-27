import {invoke,isTauri} from '@tauri-apps/api/core'
export interface PeerNetworkStatus {
 userId:string;ip:string;mode:string;handshakeUnix:string;rxBytes:string;txBytes:string;rttMillis:number|null;relayConnected:boolean;jitterMillis?:number|null;probeLossPct?:number|null;probeSamples?:number
}
export interface NetworkStatus {
 state:string;roomId?:string;networkId?:string;revision?:number;ip?:string;expiresAt?:number;relayNode?:string;relayRegion?:string;relayCandidates?:{id:string;region:string;probeMillis:number|null}[];peers:PeerNetworkStatus[]|null;error?:string
}
export interface NetworkInfo {enabled:boolean;issuer:string}
export const nativeAvailable=isTauri()
export function startNetwork(origin:string,token:string,issuer:string,userId:string){
 return invoke<void>('network_start',{origin,token,issuer,userId})
}
export function stopNetwork(){return invoke<void>('network_stop')}
export function networkStatus(){return invoke<NetworkStatus>('network_status')}
