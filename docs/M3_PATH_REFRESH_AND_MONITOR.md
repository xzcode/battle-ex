# M3.6 / M3.7 / M3.9: transport refresh, live relay configuration and monitoring

2026-11-27. Development baseline. Java 21 / Spring Boot WebFlux / Reactor Netty remains the control plane. The Go agent pins Network Core EX candidate `0c5a014ae732e8dd67d1517263d952905dec7843` (`v0.0.0-20261127090744-0c5a014ae732`), branch `codex/nc-m3-path-refresh`. This is not a stable upstream release and Network EX is unchanged.

## Transport-only updates

A valid new signed snapshot with unchanged room, local IP, identity, peer keys and peer addresses is eligible for the new shared core transport update. The core reopens the relay/ICE transport bind while retaining TUN, WireGuard device/peer state and routes. A failed update tears down the stale tunnel; the agent may start a fresh tunnel only while the new verified authorization is valid. Membership, route or key changes continue to require the previous teardown path. Short authorization expiry still fails closed. The transport update is break-before-reconnect; it does not promise zero packet loss, uninterrupted WSS, or a stable ICE path during lease rollover.

The authority supports `BATTLE_NETWORK_LEASE_SECONDS` (default 840, supported 30–840 seconds). Renewal begins before expiry (min(20s, one third of duration)). Snapshot validity remains 12 seconds. The setting exists for protocol timing and short-duration acceptance; old lease validation still uses the authoritative generation, so departed peers cannot keep a stale grant. Deleted room subnets retain the previous maximum quarantine interval.

## Live relay directory

In dynamic directory mode, Spring checks the administrator-owned relay configuration and private node credentials every three seconds, outside Reactor Netty event loops and outside the room authority lock during file reading/validation. Write a complete valid JSON file and atomically replace the configured file; do not edit it partially in place. A valid replacement is applied atomically; a malformed or unreadable update preserves the last valid directory. An empty directory remains invalid; use maintenance mode for planned shutdown of all nodes.

New configured nodes register with their own credential. Removed nodes lose authorization; unchanged nodes preserve live status. A change to endpoint/STUN/credential clears that node's previous heartbeat and increments its route generation, requiring a new heartbeat and newly signed leases. No anonymous registration or user-controlled configuration endpoint is exposed. Endpoint and credential provisioning is still administrator file-based, not a write-capable admin GUI.

## Monitoring

Authenticated read-only `GET /api/v1/network/relays/{node}/history` returns per-minute peak connection count, observed byte/drop deltas, samples and counter discontinuities. History uses authority-memory minute buckets. `BATTLE_NETWORK_METRICS_RETENTION_MINUTES` defaults to 1440 (24 hours) and can be configured; it is operational retention, not a player/room cap. Counter resets, process-instance changes and heartbeat gaps are not interpreted as new traffic. History does not survive authority restart. Missing periods are absent; the UI charts the last 60 recorded minute buckets, not fabricated zero readings.

The desktop Network > Relay monitor view reads current status and history, updates every five seconds while open, displays actual counters, connection peaks, retention and last heartbeat, and has Chinese/English labels. It does not expose credentials, user IPs, or mutation controls. Authentication is the existing user bearer session; operator RBAC and persistent historical storage remain pending.

## Evidence

- Core: Go package tests and vet; targeted transport/tunnel race tests.
- Tokyo isolated Linux namespace integration (`python3 scripts/test-room-network.py --ice --multi --refresh`): four agents and two relay processes, actual ICE direct/WSS fallback, TCP/UDP payloads and probe quality; forced primary loss recovered traffic in 9.2 seconds in this run. Native adapter indices and virtual IPs stayed unchanged through node failure and maintenance migration.
- Actual relay counters generated minute history; removing/re-adding a configured relay succeeded without authority restart; invalid JSON preserved the prior configuration.
- A shortened 35-second lease was renewed while an established TCP connection remained open. A and C retained their native adapter indices and exchanged another payload through the same TCP socket after renewal. This is continuity of that TCP session, not proof of zero-loss application/game traffic.
- Room switching, old lease denial, cross-room isolation, and fail-closed interface cleanup after authority loss all passed; expanded integration exited 0.

No public service deployed, no Release or stable SDK. The original M3 multi-relay document is historical where it describes full adapter recreation and restart-only configuration.

## Remaining work

Full ICE path recovery/strict double-NAT reliability and seamless make-before-break WSS renewals are still open (M3.6/M3.7). M3.8 now has an optional shared MongoDB authority development baseline; see [M3.8 shared authority](M3_CLUSTER.md). Production scale, replica-set failover and coordinated configuration migration are still open. M3.9 persistent monitoring, operator RBAC and alerting are pending. M3.10 real geographically separated nodes and load/resilience acceptance remain pending. Windows native TUN/Tauri/game acceptance remains open.

Final build checks: the published core commit is pinned without a local Go replace. Agent race tests, vet, Linux native builds, Windows amd64 and macOS arm64 cross-builds passed. Maven relay directory tests passed (3 tests including history deltas/reset/retention); Vue production build passed. Actual native Tauri and Windows installation remain unverified.
Browser verification: initial navigation test revealed decorative sidebar glyphs entering accessible button names; marking these glyphs aria-hidden corrected the selector and accessibility behavior. Rebuilt Vue and reran the dual-browser end-to-end test, including relay monitor navigation and the real empty-directory API response, successfully (exit 0).
