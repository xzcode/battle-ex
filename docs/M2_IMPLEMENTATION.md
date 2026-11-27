# M2 — authenticated room networking (2026-11-27)

## Scope and architecture

Battle EX owns account/room/device authority; the pinned Network Core EX candidate
(`9941a2f9255f41fb80e9d15275153a28c02607f8`,
Go module version `v0.0.0-20261127080738-9941a2f9255f`) owns the
encrypted tunnel, packet guard, ICE/STUN, WSS relay and signaling. There is no
forked packet engine. The upstream candidate is development-only; its strict
double-NAT reliability remains unapproved.

- An agent generates and persists separate Ed25519 identity and WireGuard keys
  in a private per-server/account directory; a process lock prevents duplicate
  use. Enrollment proves possession of the identity private key by signing
  the current authenticated session digest and both public keys. Registration
  is idempotent for the same device/session; duplicate keys are rejected.
- The authoritative active room has an independent network ID, allocated /24
  inside 10.160.0.0/12 and stable host addresses for its members. Distinct
  active room networks do not overlap. Pool exhaustion is explicit; deleted
  subnets are quarantined. The current engine supports 64 peers per local
  device, so the room capacity is 2–65. These are actual engine/address-space
  limits, not a commercial account quota.
- Each agent requests a signed room snapshot every two seconds. The snapshot
  is nonce-, session-, local-device- and room-bound, expires after 12 seconds,
  includes the authorized peer keys/IPs only, and is verified against the
  pinned Battle issuer before engine configuration. Old or inconsistent
  revisions and invalid signatures/leases fail closed. No user-provided peer
  list, arbitrary subnet forwarding, unrestricted broadcast or plaintext fallback.
- Pair-scoped Ed25519 relay leases are compatible with Network Core EX; the
  Battle relay also checks the live authority through an authenticated TLS
  endpoint on connection and once per second for active connections. It rejects
  old topology generations, expired grants and failed authority requests. A
  15-second heartbeat bounds peer eligibility; explicit leave, logout, device
  disconnect and login replacement invalidate membership immediately at the
  authority. Agent cancels and tears down local TUN/routes when the snapshot
  expires or the room membership is lost.
- The initial snapshot lease duration is 840 seconds (below the core's 15-minute
  maximum); snapshots are refreshed independently of relay leases. Peer or lease
  generation change currently recreates the tunnel; there is no seamless hot
  reconfiguration. Established traffic may be interrupted when a member leaves
  or grants rotate. Do not claim long-session competitive stability; M3 must
  implement non-disruptive refresh and wider NAT/quality acceptance.
- The local UDP echo probe binds only to the assigned overlay IP, permits
  only current peer IPs, echoes a 32-byte challenge and reports measured
  round-trip latency. Unavailable measurements are null. It is not a synthetic
  ping value or an assertion of actual game compatibility.
- Native desktop bridge starts only the installed local agent executable
  (explicit absolute developer override in debug builds), sends the bearer
  token through the private parent/child stdin pipe, reads real JSON status,
  and closes stdin for graceful stop. No remote command execution, shell
  interpolation, automatic elevation, token in argv or token persisted to disk.
  UI shows local IP, per-peer path and measured latency; browser UI explicitly
  states that a native desktop is required. On first use the user verifies and
  pins the issuer; unexpected key change requires explicit replacement.

## Configuration and developer operation

Build the services:
```sh
mvn -f server/pom.xml -DskipTests package
cd agent
go build -o bin/battle-agent ./cmd/agent
go build -o bin/battle-relay ./cmd/relay
```

Keep server and relay private keys/secrets outside the repository.
Use `battle-relay keygen -out /private/path/issuer.key` to create a dedicated
Battle signing key; save its printed public hex for the relay and clients.
Prepare a 32-byte random hex relay service secret file (0600) shared only by the
authority and its trusted relay. Use valid TLS certificates; remote endpoints
require HTTPS/WSS. The platform remains loopback-bound by default.

Authority environment:

| Variable | Meaning |
| --- | --- |
| BATTLE_NETWORK_ISSUER_KEY_FILE | Private 64-byte Ed25519 key encoded as hex; empty disables room networking |
| BATTLE_NETWORK_RELAY_SECRET_FILE | Private 32-byte hex relay authority credential |
| BATTLE_NETWORK_RELAY_NODE | Node ID, default battle-relay |
| BATTLE_NETWORK_RELAY_URL | wss://host:port/relay/v1 |
| BATTLE_NETWORK_ICE | true by default; false forces relay for diagnosis |
| BATTLE_NETWORK_STUN_URL | stun:host:port, required when ICE enabled |

Set Spring TLS or use a trusted reverse proxy. Start a Battle relay with
`--issuer <public-hex> --secret-file <private-file> --authority <https-origin>
--listen <address> --cert <TLS-cert> --key <TLS-key> --stun-listen <UDP-address>`.
Use `--authority-ca` for a private test CA. The relay's authority endpoint is
`POST /api/v1/network/relay/authorize` with X-Relay-Secret; do not expose service
credentials to clients. Restrict ingress and add operational rate limiting
before public deployment.

Desktop development requires Go 1.26.11, Node 22.14+, Rust/Tauri platform build
dependencies. In `apps/desktop`, run `npm ci`, then
`npm run desktop:dev`. The Tauri build hook builds the platform-targeted
sidecar. For an isolated agent run, use an absolute private state directory,
pinned public issuer, optional test CA files and a JSON line
`{"origin":"https://...","token":"<session-token>"}` on a private stdin pipe;
keep stdin open during use. Closing it gracefully removes the virtual network.

Windows source uses the core's Wintun and route implementation; provide an
official matching-architecture Wintun DLL in a trusted installation directory.
Do not load an unverified driver. OS network administration privileges are
required for TUN/route creation; no service/elevation installer is implemented
in M2. macOS route permissions and native launch remain unverified. Production
must use a minimally privileged local service and an authenticated service IPC,
not run an entire renderer as administrator.

## Real verification

On the Tokyo Linux host, in a fresh isolated network namespace with four
independent client network namespaces and a private TLS authority/relay:
```sh
python3 scripts/test-room-network.py
python3 scripts/test-room-network.py --ice
```
Both initial integration modes completed with exit 0. Relay-only and mixed
ICE-selected/direct + WSS relay fallback exchanged real ICMP and overlay UDP
probes; the mixed test blocked C's physical UDP only. Two distinct room networks
were assigned; cross-room ping was denied. Invalid device proof and unauthorized
snapshot were rejected, valid relay grant accepted, invalid relay secret and
old grant denied after departure. C's old interface/route was removed, C joined
another room and exchanged packets with its new peer, old room access failed.
Stopping the authority caused all agents to remove their interfaces after
snapshot expiry; parent pipe close stopped the agents. All test processes and
generated temporary credentials were scoped to the isolated test.

The expanded final run also checks TCP/UDP application echoes over both peer
paths and the longer relay grant duration; the expanded final mixed-path integration run completed with exit 0, including real TCP and UDP application payloads on both paths, cross-room rejection, stale lease denial and interface cleanup after authority loss. M1 domain, HTTP/WebSocket, authenticated disposable MongoDB restart and two-browser Vue regression tests also passed on the final implementation. Go package compilation/vet, Linux native binaries and
Windows amd64/macOS arm64 cross-compilation passed for the agent and relay.
The Rust process bridge module was compiled separately against its actual
serde_json/sha2 dependencies. Native Tauri compilation/installation is not
verified (no GTK/WebKit dev libraries on host); Vue TS/Vite build passed.
No external deployment or release.

## Gates still open

M2 implementation is usable for isolated L3 IPv4 room networking; Windows
Wintun installation, native desktop IPC end-to-end, actual Windows games, L2/
game broadcast adapters and commercial-grade NAT/latency performance are NOT
verified. The Linux test is not a Windows game playtest. Neither complex
double-NAT traversal nor seamless lease rotation was proven. No stable
network-core release, production security review, multi-instance control
plane, regional relay selection, or native installer. The current implementation
uses full snapshots and one central relay configuration; M3 is the scalability,
connection-quality, hot-refresh and multi-node stage; M4 handles game-specific
discovery and launch. Project license still awaits owner decision.
