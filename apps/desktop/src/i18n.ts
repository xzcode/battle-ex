export const messages = {
 en: {
  tagline:'LAN multiplayer, together.', signIn:'Sign in', register:'Create account', username:'Username', password:'Password',
  server:'Server address', signOut:'Sign out', lobby:'Lobby', games:'Games', all:'All games', custom:'Custom LAN', rooms:'Rooms',
  search:'Search rooms…', create:'Create room', privateJoin:'Join by invitation', refresh:'Refresh', signedIn:'signed in', live:'Live',
  reconnecting:'Reconnecting', room:'Room', game:'Game', players:'Players', status:'Status', waiting:'Waiting', playing:'In game', join:'Join',
  noRooms:'No matching public rooms', noRoomsBody:'Create a room or join one using an invitation.', activeRoom:'Your room',
  noActive:'You have not joined a room', noActiveBody:'Choose a room or create your own to get started.', ready:'Ready', notReady:'Not ready',
  host:'Host', leave:'Leave', start:'Start match', finish:'End session', invite:'Invitation', rotate:'Rotate', reveal:'Show invitation',
  copy:'Copy', copied:'Copied', roomName:'Room name', capacity:'Player capacity', visibility:'Visibility', publicRoom:'Public',
  privateRoom:'Private', cancel:'Cancel', roomId:'Room ID', inviteCode:'Invitation code', networkPending:'Game networking is not connected yet.',
  devBuild:'Development build', adapterNote:'Game adapters are planned; launching and LAN discovery are not yet implemented.',
  authHint:'Connect to your own Battle EX server. Remote servers require HTTPS.', needAccount:'New player?', haveAccount:'Already registered?',
  noLogin:'Session expired. Sign in again.', gameCatalog:'Game catalog', language:'中文', allReady:'All players must be ready.',
  roomInfo:'Room details', selectedGame:'Selected game', noPassword:'Password is never saved on this device.', invalidServer:'Invalid server address',
  joinHint:'Ask the host for the room ID and invitation code.', pending:'Working…', more:'More', session:'Session', roomCount:'public rooms',
  selectRoom:'Select a room to join', privateLabel:'Private room', publicLabel:'Public room', player:'Player',
 },
 zh: {
  tagline:'畅享局域网联机对战。', signIn:'登录', register:'注册账号', username:'用户名', password:'密码',
  server:'服务器地址', signOut:'退出登录', lobby:'游戏大厅', games:'游戏列表', all:'全部游戏', custom:'自定义局域网', rooms:'房间列表',
  search:'搜索房间…', create:'创建房间', privateJoin:'邀请加入', refresh:'刷新', signedIn:'已登录', live:'实时连接',
  reconnecting:'正在重连', room:'房间', game:'游戏', players:'玩家', status:'状态', waiting:'等待中', playing:'对战中', join:'加入',
  noRooms:'没有符合条件的公开房间', noRoomsBody:'创建一个房间，或通过邀请加入。', activeRoom:'我的房间',
  noActive:'尚未加入房间', noActiveBody:'从列表加入，或创建自己的房间。', ready:'已准备', notReady:'未准备',
  host:'房主', leave:'退出房间', start:'开始对战', finish:'结束对战', invite:'邀请信息', rotate:'更新邀请', reveal:'查看邀请',
  copy:'复制', copied:'已复制', roomName:'房间名称', capacity:'玩家容量', visibility:'可见性', publicRoom:'公开',
  privateRoom:'私人', cancel:'取消', roomId:'房间 ID', inviteCode:'邀请码', networkPending:'游戏网络尚未接入。',
  devBuild:'开发版本', adapterNote:'游戏适配正在规划，暂未实现游戏启动及局域网发现。',
  authHint:'连接你自己的 Battle EX 服务。远程服务必须使用 HTTPS。', needAccount:'新玩家？', haveAccount:'已有账号？',
  noLogin:'会话已失效，请重新登录。', gameCatalog:'游戏目录', language:'English', allReady:'所有玩家必须准备。',
  roomInfo:'房间详情', selectedGame:'已选游戏', noPassword:'密码不会保存在此设备上。', invalidServer:'服务器地址无效',
  joinHint:'请向房主获取房间 ID 及邀请码。', pending:'处理中…', more:'更多', session:'会话', roomCount:'公开房间',
  selectRoom:'选择房间加入', privateLabel:'私人房间', publicLabel:'公开房间', player:'玩家',
 }
} as const
export type Language=keyof typeof messages
export type Message=keyof typeof messages.en
