# M10 follow-up — scoped network transactions (storage protocol 3)

Baseline: master f56eb56077d57dc906f81f47cac651a8da266196 passed all
11 business, replica-set, Windows network and installer CI jobs.

## Scope and concurrency

Only `mongo-split` uses these paths. Memory mode and the historical
whole-state `mongo` mode retain their existing authority behavior.

- **Relay authorization** reads the committed room, allocation, selected
  node/generation, enrolled devices and corresponding sessions/suspensions
  in one MongoDB snapshot transaction. It verifies the exact pair lease,
  membership and device fingerprint, expiry, node liveness and configured
  instance fence. It does not allocate an address, choose a new node,
  advance a lease or restore the historical domain state. A missing or
  mismatching grant is denied. A valid request with database failure fails
  closed. Authorization after a committed logout or topology change
  observes the new state; an overlapping transaction may observe the
  preceding consistent snapshot, and grants remain time-bounded.
- **Relay heartbeat** independently writes only that node's live row,
  current minute metric, and generation when ownership changes. Conflicts
  retry the transaction, not an unknown commit's business action.
  Retention deletion is node-local on the next minute's heartbeat.
  Offline nodes' old records are filtered at read time; a separate
  background retention cleanup is a future improvement.
- **Stable signed snapshots** authenticate the device and session, read
  the room, allocation, active peers, node state and fresh probe samples.
  If the complete topology/generation fingerprint and reservation still
  match, the selected relay remains healthy, no potential migration
  requires reevaluation, and the old lease has sufficient remaining time,
  the server signs a new nonce-bound snapshot with the existing IPs,
  revision and pair leases. Only the requesting device heartbeat and its
  validated probe sample may be written. No new lease or capacity
  reservation is issued by this path. Initial allocation, membership or
  identity changes, expiration/renewal, relay takeover/drain or possible
  path migration fall back to the original globally fenced capacity
  transaction. The reuse guard is deliberately conservative.
- **Independent relay history** reads only the requested node's retained
  minute rows. General domain restoration does not load this history.
  Room, relay live/generation, device and probe rows are refreshed directly
  within a general transaction so its cache cannot overwrite independent
  committed changes. This still requires full scans of those active
  collections for general decisions.
- **Targeted WebSocket updates** for room-local changes use the room's
  member IDs. Global business revision notifications remain broadcast;
  a five-second periodic resync handles missed events/deleted rooms.
  Authentication is an indexed session/suspension projection. Each
  delivered lobby is reauthenticated; the stream is a notification,
  not authorization or exactly-once event delivery.

Operations shows local-process scoped relay writes, lease reads, reused
signed snapshots and snapshot fallbacks. These are not cluster totals.
All persisted rows use the explicit BSON codec, with no automatic Java
type metadata.

## Offline upgrade from schema 2

The metadata document retains the stable _id battle-authority-v2;
its schema field is now 3. The original sealed v1 migration marker
remains schema 2, so the historical v1 writer still refuses it.

Stop ALL old authority and worker instances and pause relay traffic.
Take a consistent MongoDB backup. Preserve the exact configuration
fingerprint; changing policy is not part of this upgrade. Start one new
authority with BATTLE_STATE_MODE=mongo-split and
BATTLE_MONGO_MIGRATE_LEGACY=true. The upgrade validates the active entity
state and atomically advances the schema and commit fence. Relay history,
sessions, rooms, addresses and other entity rows are retained. Then turn
the migration flag off and start the remaining upgraded instances.
A schema-2 database without the explicit flag is rejected; the old
schema-2 binary cannot start or make general schema-2 commits afterward.
Do not run mixed versions: a transaction begun before migration could
overlap it, and old scoped writes are not fenced by the global metadata
write. The maintenance procedure is mandatory. There is no automatic
downgrade or live policy/issuer rotation.

A new database initializes schema 3. The original explicit schema-1
migration also initializes schema 3. The 24-hour journal has a migration
version gap; cache readers reload instead of guessing changes.

## Acceptance

Tokyo isolated Linux host, 2 vCPU / approximately 3.5 GiB memory:

- Previous and current two-JVM replica-set relay fixtures verified two
  capacity-limited nodes, full-node reservation rejection, exact pair
  leases, invalid credentials/instance/flow rejection, instance takeover,
  old-generation lease denial, cross-instance logout and departed-room
  revocation, and historical counters after all-JVM restart.
- Current fixture: 64 scoped lease authorization requests (concurrency 8),
  zero errors; p50 142 ms, p95 212 ms. 24 fresh nonce-bound signed snapshots
  with probe samples (concurrency 4), zero errors; p50 149 ms, p95 177 ms.
  The global commit fence stayed unchanged during these waves. The
  address, revision and pair lease remained unchanged. 60 persisted
  documents across 29 collections passed the recursive no-class check.
  These are small local fixtures, not production throughput guarantees.
- Actual previous schema-2 JAR upgrade fixture passed: explicit opt-in,
  old-binary rejection, retained session/room/invitation/allocation/history
  and restart. 26 documents passed the no-class check.

Run `bash scripts/test-mongo-split.sh relays` and
`BATTLE_V2_JAR=/path/to/pinned-v2.jar bash scripts/test-mongo-split.sh upgrade`.
The business CI builds the pinned prior version separately and runs both
fixtures against disposable replica sets. No live user database is modified.

## Remaining boundaries

Initial network allocation, lease rotation, topology/membership changes,
matchmaking decisions, session changes and cross-entity business invariants
still use the global commit fence and restore active domain state. Network
capacity reservation still uses a single relay per room and the shared
core's existing node connection limits. Direct P2P, actual commercial
games, high-load/long-duration multi-host capacity, independently
partitioned business writes and live configuration migration are not
certified by these fixtures.

Final regression: eight JUnit tests (including conservative snapshot
reuse and migration guards) and Vue TypeScript/production build passed.
The split-mode moderation fixture passed cross-instance suspension,
WebSocket closure, room/old-lease revocation, persisted replay/report
and suspension state after all-JVM restart, and audited re-login.
55 persisted documents passed the no-class check. Full latest-commit
Windows and business CI remain release gates.
