# M10 distributed authority — development baseline

## Modes and migration

The same Java 21 WebFlux JAR supports memory (single-process development),
mongo (the original v1 whole-state CAS baseline), and **mongo-split** (v2).
Use mongo-split for new multi-instance deployments. It requires a MongoDB
replica set; standalone MongoDB is rejected at startup. All authority nodes
must share the same database, network configuration, signing policy and
matching scheduling policy. There is no local fallback on database failure.

For an existing v1 shared authority, stop **all** old JVMs, stop relay
traffic and take a consistent database backup. Keep the same configuration
fingerprint (including issuer, roles and matching policy). Start one new
JVM in mongo-split mode with BATTLE_MONGO_MIGRATE_LEGACY=true. It validates
the old snapshot, creates explicit entity rows and v2 metadata in a
replica-set transaction, and seals the old authority document by setting
its schema to 2. Old binaries reject it and their original schema-1 CAS
cannot update it. Old data is retained as a sealed migration backup, not
read for new runtime decisions. On startup without the explicit migration
flag, an existing v1 database is rejected. Stray destination rows and
forbidden _class fields cause rejection rather than silent adoption.

After successful validation, turn the flag off and start the other
identically configured nodes. Changing network/role/matching policy while
migrating is intentionally rejected. A separate coordinated policy migration
is still required for an existing authority to enable new fingerprinted
features. Do not edit the fingerprint or remove the sealed document in a
live database. Rolling back after v2 has accepted new mutations requires
a consistent backup or an explicit reverse migration (not implemented).
No production data was modified by the implementation or fixtures.

## Explicit storage

Accounts remain in accounts. The v2 authority metadata contains only
schema/configuration/version and small revision/counter fields, not an
aggregate payload. The explicit mapping stores one BSON row per entity:

| Domain | Collections |
| --- | --- |
| Session and rooms | sessions, rooms |
| Queue and competitive ledger | matchmaking_tickets, matches, ratings |
| Archive and moderation | replays, match_consents, reports, suspensions |
| Community | teams, team_members, team_applications |
| Events | tournaments, tournament_entries, tournament_matches |
| Operators | operator_roles, audit_logs |
| Network | network_devices, network_allocations, network_quarantine, network_revisions |
| Relays | relay_nodes, relay_samples, relay_generations, relay_metrics |
| Commit coordination | authority_meta, outbox_events |

Team roster rows and individual tournament entries/slots are separate
from parent records. Ordering that affects bracket seeding/audit is
explicitly preserved. Some bounded nested records remain (room membership,
match roster, per-slot attempts). Unique indexes protect session digest,
room network ID, team tag and allocated subnet; other indexes support
match history, rankings, queue partitions, moderation, child records and
relay history. Domain validation still enforces invariants such as exclusive
membership and name uniqueness. No Spring Data type mapping or Jackson
default typing; _class is rejected recursively. The real MongoDB test wrapper
inspects all persisted document fields and the sealed legacy payload.

## Transactions and scaling boundaries

All cross-entity mutations run with snapshot read concern, majority write
concern and primary read preference. Only changed entity rows are written,
plus a small compare-and-set metadata fence and a durable change journal.
Retries are restricted to transient transaction errors / commit conflicts;
an unknown commit result retries the **commit**, never blindly replays the
business action. Failed database reads/writes return unavailable rather
than using stale local authority.

The per-JVM versioned cache reconstructs the domain model from consistent
rows. Consecutive journal events identify changed entities; tombstones
are represented by missing rows. Journal expiry (24 hours), gaps or very
large changes trigger a full reload. The journal contains row identifiers,
not copies of business documents. The lobby, ranked leaderboard and
personal match history have direct indexed snapshot projections that
authenticate the session and suspension before returning data; they do not
restore the complete platform or acquire its JVM lock.

Readiness and game-build reports are a special high-frequency room-local
transaction: authentication and membership are checked, then only that
room document is replaced under its revision. Different rooms do not
update or contend on the global metadata version for these operations.
General transactions refresh current room rows within their snapshot;
a conflicting general start/leave/close writes the same room and is
retried by MongoDB. The client also rejects older same-room revisions.
Room-local writes have a separate change stream and do not generate a
global journal entry.

**Remaining scale boundary:** cross-entity operations still restore the
domain snapshot and serialize through a small global commit fence.
This is a transactional entity-store and partial scoped-write baseline,
not a fully independently partitioned authority. Full-room scans for
general decisions and in-process historical state reconstruction still
have CPU/heap cost. Public lobby room listing is not paginated. Further
domain-specific repositories, scoped invariant transactions and targeted
event fan-out are required before claiming large-scale horizontal write
capacity. The old single-document 16 MiB ceiling no longer bounds all
platform records combined, but individual BSON documents, transaction
size/time and available memory remain constraints.

## Match worker deployment

BATTLE_MATCH_SCHEDULING=inline (default) retains the previous behavior.
Use worker for an independent scheduled matching tier; all authority
nodes must agree on this policy.

- BATTLE_MATCH_WORKER_ENABLED=false for API-only nodes.
- BATTLE_MATCH_WORKER_ENABLED=true on worker nodes; their HTTP listener
  need not be exposed or included in the load balancer.
- BATTLE_MATCH_PARTITIONS=* or a comma-separated list of exact
  game:MODE:region keys, e.g. cs16:CASUAL:asia,warcraft3:RANKED:europe.
- A ticket uses an explicit selected region, default global. All region
  keys are normalized ASCII slugs. It is a queue preference, not inferred
  player geography, and does not override measured relay selection.
- Match only equal game/mode/region; multiple workers may cover the same
  key for redundancy. The authority transaction prevents double assignment.
  At least one worker must cover every offered region/key. The bounded
  per-tick batch is scheduling backpressure, not a queue-size quota.
  Standby workers can take over when one exits. No Redis/Kafka dependency.
  There is no separate worker election/fencing service; overlapping
  workers are coordinated by the existing authority transaction.

## WebSocket and operational health

mongo-split uses MongoDB change streams on metadata and rooms to wake
subscribed lobbies, with a bounded 5-second periodic resync. A stream outage
cannot grant permissions: each snapshot reauthenticates from MongoDB.
The durable journal supports cache catch-up after node loss, not a guaranteed
per-client replay of every intermediate UI event. Legacy modes retain
their one-second polling path. Monitor local process counters for
transaction attempts/retries, dirty-row commits, changed rows, full/delta
loads, cache hits, indexed reads and scoped room writes in Operations.
These counters are node-local and reset on restart; they are not summed
cluster telemetry. /health/live returns 204 for a live HTTP process;
/health/ready checks the configured authority's access to committed
metadata and returns 204/503 (memory mode does not check an optional
external account-only database). Keep these probes internal. Existing
Nginx example balances API/WebSocket with no unsafe mutation replay.
The environment and systemd examples are templates, not a deployment.

## Network expansion

Default pool preserves all original 4096 /24 allocations
(10.160.0.0/12). BATTLE_NETWORK_POOL_SUBNETS can extend the slot count up
to 16384, inside 10.128.0.0/10. Old slot-to-IP mapping is unchanged; slots
4096–8191 use 10.176–191 and slots 8192–16383 use 10.128–159. The updated
Go and Rust client validators accept this range; do not enable expanded
pools before upgrading every client. Each room retains a stable allocation
and freed networks are quarantined for the maximum lease lifetime. Actual
available capacity depends on physical route overlaps, membership and
quarantine, not merely the number of slots.

BATTLE_NETWORK_CAPACITY_RESERVATIONS=true enables worst-case relay
connection reservations (active peer count n, n*(n-1) connection slots
for the full mesh). Selection considers existing reservations plus measured
load without double counting reservations already in use. Reservations
are updated transactionally with allocation and expire/release when the
room or lease expires. The current Network Core EX relay supports at
most 256 connection slots per node; oversized full-relay demand is rejected
with no-capacity rather than claiming guaranteed service. Direct P2P
remains preferred, but this policy conservatively reserves fallback
capacity. Multiple relay nodes can expand total capacity; one room still
uses one relay node. No per-room multi-relay sharding is claimed.

BATTLE_NETWORK_INSTANCE_FENCE=true enables dynamic-node instance fencing.
A second process with the same node ID cannot replace a still-live
heartbeat; takeover after the heartbeat TTL increments node generation.
New registered Go relays send the same random instance ID for heartbeat
and relay/signal authorization. Old instances, missing instance IDs and
drained/expired instances are denied. Static legacy nodes retain their
existing credential-only authorization. Use separate credentials/node
IDs, upgrade registered relays before enabling this flag. The fence
prevents accidental duplicate process ownership, not a malicious holder
of a stolen relay secret. Policy and pool settings are fingerprinted;
fresh installations can enable them immediately, existing ones require
coordinated policy migration.

## Acceptance and limitations

Run Maven tests, Go tests/vet, the Vue production build, and the isolated
fixtures:
```sh
bash scripts/test-mongo-split.sh competition
bash scripts/test-mongo-split.sh migration
bash scripts/test-mongo-split.sh workers
bash scripts/test-mongo-split.sh community
bash scripts/test-mongo-split.sh moderation
bash scripts/test-mongo-failover.sh
```
The single-member replica-set wrapper binds a random loopback port; the
three-member failover fixture uses disposable host-network containers
bound only to distinct loopback ports. No production service or network
is stopped. CI runs each split fixture independently, plus a dedicated
three-member failure scenario and existing Windows and business
acceptance. No large-scale production capacity, cross-continent multi-
primary database, live schema-policy reconfiguration, production-grade
observability, or actual commercial-game compatibility is certified.

Observed during development on a 2-vCPU / 3.5-GiB Linux host: the
transactional split competition fixture (50 persisted documents), explicit
migration fixture (13), and community/operations fixture after child-table
split (95) passed. No _class keys were present. The initial worker fixture
after deferred startup fix exercised 103 actual HTTP requests, including
eight concurrent accounts/rooms and forty readiness writes, with zero
errors, p50 338 ms and p95 1207 ms across the mixed scenario. This is a
small fixture including account registration, not a capacity benchmark.
See the final acceptance records for the later room-local write and
replica failover fixtures.

### Final local acceptance, 2026-11-28

- Seven JUnit tests, Go tests/vet and native Linux/Windows x64 Go builds
  passed. Four isolated Rust module tests and Windows x64 Rust target
  check passed. Vue TypeScript and production build passed.
- The final scoped-worker fixture passed: API-only instances, region
  separation, overlapping worker ownership, worker loss and takeover,
  8 parallel rooms and 40 readiness mutations. The global commit version
  remained unchanged during all readiness waves. 110 actual HTTP requests,
  zero errors, mixed-scenario p50 158 ms and p95 1215 ms. The five readiness
  waves took 247/168/206/153/172 ms versus the earlier observed
  797/829/710/617/674 ms. 73 persisted documents across 29 collections
  passed the recursive no-class gate. This is a bounded fixture, not a
  production throughput claim.
- The actual three-member MongoDB replica-set test passed: kill primary,
  elect successor, preserve acknowledged room state, resume writes,
  revoke session, kill successor and lose majority. API and readiness
  returned unavailable. The first two fixture attempts failed because
  the disposable 512 MiB filesystem was below MongoDB's 500 MiB free
  space index-build requirement; increasing fixture capacity and using
  a small oplog corrected the environment without disabling the check.
- Migration, signed-result competition and community fixtures passed on
  preceding iterations of this candidate. The final GitHub CI matrix
  reruns those, moderation, replica failover and existing Windows checks
  against the exact submitted source. No production deployment or
  actual-game certification was performed.
