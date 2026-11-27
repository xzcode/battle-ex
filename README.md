# Battle EX

A self-hostable PC game LAN multiplayer and competitive platform. Shared networking engine: [Network Core EX](https://github.com/xzcode/network-core-ex).

**Status: M0 design; no playable release yet.**

## Product scope

Windows-first desktop client (Tauri 2 + Vue 3 + TypeScript), game lobby, private/public rooms, player readiness, invite, local game launch, network diagnostics, regional relay selection and configurable LAN discovery. Initial compatibility targets: Warcraft III, Red Alert 2 and CS 1.6; exact editions and versions require real-game verification. Users supply legally acquired games; no bundled game binaries, keys, assets or unauthorized bypass.

## Architecture

- Desktop UI communicates with a separate Go network agent using authenticated local IPC. Virtual adapters are installed with explicit OS permissions.
- Product control plane: Java 21 / Spring Boot + WebSocket as the proposed baseline; MongoDB for durable account/room data, Redis optional for multi-instance presence and matchmaking. Keep service/storage interfaces separable; no mandatory distributed cluster for initial development.
- Network Core EX supplies peer transport, encrypted tunnels, NAT traversal, relay, discovery and optional Ethernet. No fork of the network implementation.
- Room admission issues short-lived, room-scoped peer membership; joining/leaving/revocation updates authoritative snapshots. Separate room networks, default-deny forwarding and no unauthenticated public relay.
- A game adapter declares discovery mode (L3 unicast, controlled UDP discovery, L2, dedicated server), version constraints, executable path, launch arguments and optional trusted result integration. No generic claim of anti-cheat or reliable win/loss detection.
- Game packets stay on the encrypted data plane, independent of chat/lobby traffic. Choose direct path per peer, with authorized relay fallback.

## Development sequence

| Phase | Deliverable |
| --- | --- |
| M0 | Requirements, architecture, room/game-adapter contracts, acceptance matrix |
| M1 | Account, desktop shell, lobby, rooms, realtime presence |
| M2 | Room-scoped network integration, diagnostics, join/leave |
| M3 | Direct/relay paths and regional nodes |
| M4 | Game discovery, version detection and launch; test actual Windows games |
| M5 | Matchmaking, rankings; only trusted result sources |
| M6 | Replays and spectating where game supports them |
| M7 | Integrity checks, reports and moderation |
| M8 | Teams and tournaments |
| M9 | Admin, updates and operational observability |
| M10 | Distributed expansion and load testing |

First release gate: two real Windows clients can find and join a game, exchange gameplay packets via direct and forced relay paths, and leave without leaking access or routes. No production release until upstream security/reliability gates and applicable licenses are addressed.

## 中文

Battle EX 是面向 PC 局域网游戏的自托管联机对战平台。首版以 Windows 为主，先打通房间、游戏发现、联机和启动，再增加竞技排名、录像和反作弊。网络核心统一复用 Network Core EX，不复制商业平台代码，不分发游戏本体。游戏适配目标不代表已兼容。
