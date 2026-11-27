# M0/M1 room and network contract

## Product ownership

Battle EX owns identity, lobby, rooms, game adapters, chat and trusted result policy. Network Core EX owns encrypted packet transport. A public room is discoverable, not an unrestricted network. Private invitation tokens authorize admission only; they are never peer keys or relay leases.

## Lifecycle

- WAITING: room owner can invite; authenticated users can join within the room's requested capacity; changing membership resets readiness. An account has one active room in the initial client model. No arbitrary global maximum room count.
- IN_GAME: all members ready, at least two players, owner starts. No new joins until the host ends the match. Existing users can leave; host transfers on departure; if fewer than two remain return to WAITING. Ending a room session never reports a trusted winner.
- Empty room is deleted. Private invitation rotates on owner transfer or explicit rotation.
- Every room has a random room ID and independent random network ID. Room metadata revision and membership revision are monotonically increasing for each room. Snapshots are immutable.

## Network adapter (next milestone; not implemented)

After authenticated room admission, the Battle control plane maps members to enrolled device identity and WireGuard public keys, allocates unique per-room IPv4 overlay addresses, and compiles an authoritative snapshot keyed by the room's network ID and membership revision. A Go sidecar consumes that snapshot via authenticated IPC and uses Network Core EX's engine API. Product-issued relay leases bind node, network, flow, peer identities and expiry. Separate issuers for Battle EX and Network EX. Never accept peer lists, relay issuer keys, room IDs or identity as proof merely because the client supplied them.

On leave, kick, logout, expiry or room deletion, stop refreshing leases and promptly publish a revoked snapshot; remove peers, invalidate membership and stop/reconfigure local overlay when applicable. Revision gaps trigger resync; outdated snapshots cannot reauthorize a revoked device. An offline/stale peer must fail closed at its bounded authorization expiry. This requires actual integration and negative tests; a room ID alone does not enforce isolation. No subnet exports or arbitrary broadcast by default.

## Game adapter

A future catalog entry identifies exact legal user-installed game editions, compatible versions, executable discovery, optional launch arguments and discovery mode (unicast, explicitly scoped UDP broadcast/multicast, supported L2, or dedicated server). Ports and packet requirements must be verified per edition. Do not ship copyrighted game binaries. Initial targets remain Warcraft III, Red Alert 2, CS 1.6 and a custom LAN profile. The core's Linux Ethernet capability does not establish Windows TAP or game compatibility.

## Implementation status

M1 first slice: Java 21 dependency-free synchronized in-memory room domain, protected member/host operations, atomic capacity, readiness, invitation rotation, public lobby summaries and authorized membership snapshots. Test with `JAVA_HOME=/path/to/jdk21 bash scripts/test-room-domain.sh`.

No Spring HTTP/WebSocket endpoints, persistent accounts, authenticated session adapter, database, desktop UI, match result collection or core runtime integration yet. The actor argument is trusted internal data; exposing these methods directly to unauthenticated requests is prohibited. Production persistence, multi-instance concurrency, expiry, user disconnect/rejoin policies and cross-room packet isolation remain pending.
