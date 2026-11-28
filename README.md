# Battle EX

A self-hostable PC game LAN multiplayer and competitive platform. Shared networking engine: [Network Core EX](https://github.com/xzcode/network-core-ex).

**Status: M10 split MongoDB entity storage, scoped room writes, partitioned matchmaking workers and relay capacity baseline; production-scale and actual commercial-game acceptance remain open. No stable release yet.**

Spring Boot WebFlux / Reactor Netty / Java 21 accounts and lobby, optional reactive MongoDB account persistence,
Vue 3 desktop UI and a Go agent/relay integrating the pinned Network Core EX
candidate. Linux isolated real-room direct/relay, isolation and revocation
tests pass. Multi-relay directory, node heartbeat, automatic migration and quality probes are implemented; see [M3 multi-relay configuration and acceptance](docs/M3_MULTIRELAY.md). Transport-only refresh, hot relay-directory reload and the relay monitor are documented in [M3 refresh and monitoring](docs/M3_PATH_REFRESH_AND_MONITOR.md). Optional shared MongoDB state and multi-instance deployment are documented in [M3.8 shared authority](docs/M3_CLUSTER.md). Local Windows game selection, executable validation, launch adapter and room build comparison are documented in [M4 game library](docs/M4_GAME_LIBRARY.md). The [M4 network service and game discovery](docs/M4_NETWORK_SERVICE_AND_DISCOVERY.md) adds a Windows x64 network-service kit, experimental room-scoped UDP discovery and a CS peer join adapter. See [M2 implementation and deployment configuration](docs/M2_IMPLEMENTATION.md)
and [M1 implementation](docs/M1_IMPLEMENTATION.md). Windows x64 native SCM/Wintun and WSS UDP acceptance passed in GitHub Actions; the subsequent Windows installer smoke run also passed MSI and NSIS installation, native startup, clean exit and uninstall. Two real Windows game clients and complex NAT reliability remain acceptance gates.


The [M5 competition module](docs/M5_COMPETITION.md) adds game/mode queues, managed match rooms, signed-result Elo, rankings and personal match history. Ranked play is disabled until a trusted external result issuer is configured.

The [M6–M7 archive and moderation module](docs/M6_M7_ARCHIVE_AND_MODERATION.md) adds participant match details, opt-in public live status, local replay archives, reports, review, warning and audited account suspension. Real game replay playback, spectating and anti-cheat remain open.

The [M8–M9 community and operations module](docs/M8_M9_COMMUNITY_OPERATIONS.md) adds teams, opt-in 1v1 single-elimination tournaments, role-based operator access, service overview, session/room controls and audit. Tournament adjudication is manual and independent of ranked Elo.

The [MongoDB storage contract](docs/M10_STORAGE_CONTRACT.md) prohibits `_class` type metadata in all persisted collections and governs the M10 collection split.

The [M10 distributed authority](docs/M10_DISTRIBUTED_AUTHORITY.md) introduces optional replica-set entity storage, explicit offline migration, indexed reads, scoped room-local writes, change-stream lobby notifications, partitioned workers and opt-in expanded pool/relay reservations. Cross-entity transactions still use a small shared commit fence.

## Product scope

Windows-first desktop client (Tauri 2 + Vue 3 + TypeScript), game lobby, private/public rooms, player readiness, invite, local game launch, network diagnostics, regional relay selection and configurable LAN discovery. Initial compatibility targets: Warcraft III, Red Alert 2 and CS 1.6; exact editions and versions require real-game verification. Users supply legally acquired games; no bundled game binaries, keys, assets or unauthorized bypass.

## Architecture

- Desktop UI communicates with a separate Go network agent using authenticated local IPC. Virtual adapters are installed with explicit OS permissions.
- Product control plane: Java 21 / Spring Boot WebFlux + Reactor Netty, reactive HTTP/WebSocket and MongoDB Reactive Streams for account persistence; default standalone memory state, legacy shared MongoDB CAS, or opt-in replica-set mongo-split entity storage. Cross-entity fencing and production load validation remain development gates. Keep service/storage interfaces separable; no mandatory distributed cluster for initial development.
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

## Windows CI and development artifacts

Windows-native desktop packaging and SCM/Wintun network acceptance now have separate GitHub Actions workflows. See [Windows CI documentation](docs/WINDOWS_CI.md). The first successful unsigned Windows x64 builds and native network evidence are recorded in the CI documentation. Artifacts remain development builds until the release gates are satisfied.
