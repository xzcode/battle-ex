# M3.1–M3.5, M3.9 first slice: regional relay directory

Historical baseline: the later [M3 transport refresh and monitoring](M3_PATH_REFRESH_AND_MONITOR.md) replaces the restart-only directory and full-interface recreation behavior for unchanged peer topology.

2026-11-27. Battle EX control plane remains Java 21 / Spring Boot WebFlux / Reactor Netty. The Go agent and relay continue using the pinned Network Core EX candidate; no fork of its packet engine. This is a development baseline, not a production deployment.

## Architecture and configuration

One authoritative control plane with several independently deployed Go relay/STUN processes. A room shares one selected relay for fallback/signaling; individual peers still negotiate direct ICE when possible. The directory is an administrator-provisioned allowlist, not anonymous node registration. Configured nodes can join and leave without restarting the authority. Adding a new ID or editing credentials/URLs requires a configuration restart in this slice; live provisioning is pending.

1. Copy and customize `deploy/relays.example.json` outside the source checkout. Each configured node ID and WSS endpoint is unique. Use actual TLS hostnames and a distinct 32-byte hex service credential per node, kept in a private file (0600). Copy only its own credential to each relay; never distribute the Battle signing private key to relays.
2. Configure the authority with `BATTLE_NETWORK_ISSUER_KEY_FILE` and `BATTLE_NETWORK_RELAY_DIRECTORY_FILE`; leave legacy `BATTLE_NETWORK_RELAY_URL` and `BATTLE_NETWORK_RELAY_SECRET_FILE` unset. Enable ICE and set a STUN endpoint for each node, or use `BATTLE_NETWORK_ICE=false` for relay-only diagnostics. The existing static single-relay configuration remains supported without directory mode.
3. Build the Java server and the Go binaries as in M2. Run each configured relay with its own node ID, service secret file, TLS certificate, authority HTTPS origin and STUN listener. Add `--register` and optionally `--drain-file /private/path/maintenance`. Use the trusted public issuer hex; private CA flags are for trusted development/test setups. Do not expose credentials or disable TLS verification.
4. Heartbeat/registration starts automatically every two seconds. The server accepts only the configured node identity and its credential; URLs and region never come from node heartbeats. It returns the configured core connection and byte-rate limits, which the relay applies. Connection ceilings are the existing engine's actual 256-connection bound, not a commercial account quota.

## Selection, monitoring and failure behavior

- Node state: online, offline (10 seconds without heartbeat), draining, or legacy static. Actual core counters include active connections, cumulative ingress/egress bytes and dropped packets. Restarting a relay resets its counters.
- Signed room snapshots include healthy candidate IDs, regions and WSS endpoints. A background client probe uses HEAD /health/v1 over verified HTTPS, checks the relay identity header and reports response duration without attaching any player token. A missing sample is unknown; failed probe is -1. The duration includes HTTP/TLS overhead and is not an ICMP RTT.
- A room uses a common authoritative selection. With complete recent samples from all active devices, minimize worst-player probe time plus average probe time and load penalty. Otherwise use configured priority, relative load and stable ID. New rooms avoid a reported full node. Client reports are advisory, not authoritative proof. In waiting rooms, quality-driven switching requires a 30-second cooldown and at least 25 ms / 20% score improvement. Mid-match routine optimization is suppressed; reported reachability failure or node failure can migrate. No eligible node means 503; the honest agent stops when its short snapshot authorization expires.
- Heartbeat expiry or maintenance removes a node from selection, generates a new room revision and new pair leases. Old node authorization is denied. The agent rebuilds the tunnel with the same virtual IP and room membership. This is automatic recovery, not interruption-free migration. Removing the drain marker allows registration again; existing healthy assignments are sticky.
- Authenticated clients can GET /api/v1/network/relays for node states and aggregate counters. No secrets or per-user addresses in that response. Dedicated admin RBAC/monitoring UI remains pending.
- Native network panel shows selected node/region, measured HTTPS probe times and actual overlay UDP echo quality (last RTT, jitter = mean absolute change between consecutive successful samples, loss percentage in last 20 attempts, sample count). These are probe metrics, not measured game packet loss. Unmeasured values remain null.
- Setting the drain file requests room migration; it does not wait for a match to finish. Regional deployment requires individually verified TLS, routing, NAT behavior and capacity. All acceptance tests here are isolated on one Linux host; no public listeners or actual geographic latency measurements.

## Verification

Build:
```sh
mvn -f server/pom.xml test package
cd agent
go test -race ./...
go vet ./...
go build -o bin/battle-agent ./cmd/agent
go build -o bin/battle-relay ./cmd/relay
cd ..
python3 scripts/test-room-network.py --ice --multi
```

The real-network test runs entirely inside isolated Linux network namespaces with ephemeral TLS material and temporary private credentials. The original M2 single-node and relay-only variants remain available.

Initial acceptance on Tokyo host: Java directory policy tests passed; mixed direct + WSS TCP/UDP succeeded; two independently registered relay processes reported actual traffic; terminating the primary caused heartbeat expiry and automatic migration to the backup, retaining virtual IPs and restoring packets in 9.3 seconds in this run. Restart registration and maintenance migration passed, as did cross-room isolation, old lease denial, room leave/switch and fail-closed interface cleanup.

Final client metrics checks: Go race-enabled probe unit tests passed, including rolling loss/jitter arithmetic and a real local TLS health endpoint. Java room-domain and HTTP/WebSocket regression passed; Vue/TypeScript production build and dual-browser live lobby regression passed. The expanded mixed-path integration also measured UDP probe quality and both relay HTTPS response times. Native Tauri UI and Windows installation were not run.

## Remaining M3 gates

M3.6–M3.8 are not complete. Core path switching is reused, but hot lease renewal and peer updates still recreate the interface; existing game TCP connections may break. Strict double NAT reliability, stable per-peer multi-region selection, service authority horizontal scaling and distributed room/session state are pending. M3.9 has baseline telemetry only, not historical metrics/dashboard. M3.10 real geographically separate node deployment, production load and resilience remain pending. Native Windows/Tauri/game acceptance still open. No release or public deployment.
