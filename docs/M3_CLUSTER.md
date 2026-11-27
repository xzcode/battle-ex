# M3.8 shared control-plane authority — development baseline

The Java 21 Spring Boot WebFlux / Reactor Netty API instances can share a
versioned authority state in the existing MongoDB database. No Redis is
required for this baseline. The Go agent, relay and shared network core remain
unchanged.

## Configuration

- `BATTLE_STATE_MODE=memory` (default): existing standalone behavior; optional
  MongoDB account persistence, transient sessions/rooms/network state.
- `BATTLE_STATE_MODE=mongo`: require `BATTLE_MONGO_URI` and
  `BATTLE_MONGO_DB`. Accounts and shared authority state are stored in
  MongoDB; a missing/unavailable authority returns an error, never a local
  fallback.
- All instances must use the **same database, software/schema, issuer key,
  relay endpoints and credentials, ICE settings, lease duration and telemetry
  retention**. Private secret files can have different local paths.
- Set a separate `BATTLE_PORT` on each process. Keep bind addresses private
  and expose the HTTP and WebSocket paths through an HTTPS reverse proxy.
  A loopback-only Nginx example is in `deploy/cluster/nginx.conf.example`.
- Stop old standalone instances before enabling shared mode. Live in-memory
  rooms are not migrated. Do not mix memory mode or older binaries with
  shared mode behind one endpoint. Protect the database and key files; the
  authority contains session digests and private-room invitation data.
- Cluster mode intentionally disables per-process relay-directory file hot
  reload: configuration mismatches are rejected at startup. Runtime live
  directory management needs a coordinated shared configuration migration;
  do not change the database fingerprint by hand.

## Consistency model

The `authority_state` collection holds a versioned, schema-tagged authority
aggregate. Each operation reads the primary with majority read concern,
restores that snapshot into a locally synchronized domain model, applies the
operation on the dedicated state worker, and serializes the new snapshot.
Mutations commit only if the observed database version still matches, using
majority write concern. A confirmed version conflict retries with bounded
backoff; an uncertain database write is not replayed automatically. Read-only
operations can return from their observed snapshot without writing.

The aggregate includes sessions (token digests, not bearer tokens), room
membership/invitations/readiness/revisions, device identity, overlay IP
allocation, network lease generation, relay runtime/probes and retained
telemetry. Raw relay secrets and issuer private keys remain in local
operator-owned files. The account collection uses primary/majority reads and
majority writes. Request-path database access uses the reactive driver;
blocking initialization occurs only during startup.

WebSockets read the shared authority through the existing periodic reactive
snapshot stream. Room capacity and membership mutations share the same
compare-and-set boundary across JVMs. JVM-local state is only a working copy;
a terminated JVM does not need to flush authority state. Existing sessions and
rooms survive JVM restarts until their normal expiration/removal. A database
failure returns HTTP 503 and relay authorization fails closed.

## Acceptance

Build the server using JDK 21 and Maven. Run:

```sh
mvn -f server/pom.xml test package
bash scripts/test-mongo.sh --cluster
```

The wrapper creates an isolated authenticated MongoDB Docker container with
temporary credentials and removes it on exit. The cluster script starts two
real WebFlux JVMs, races last-seat admission across instances, checks
cross-instance HTTP/WebSocket state, signed Ed25519 device enrollment and
network grants, IP/lease consistency, relay heartbeat/history, abrupt JVM
termination and restart, logout/old-grant denial and all-JVM restart
persistence. No production deployment is performed.

## Remaining scale and HA gates

This is the first multi-instance consistency implementation, not a sharded
high-throughput authority. The aggregate uses one MongoDB document (subject
to the database BSON document size limit) and one CAS version. It serializes
writes across rooms and incurs full-state read/serialization overhead; it is
not an artificial player/room quota, but a real storage/throughput bottleneck.
Partitioned room transactions, IP allocation, separate metrics collections,
distributed events and load testing are required before large-scale use.

The integration test uses one disposable MongoDB instance. A production
replica-set election/failover, multi-region database latency and partition
recovery, proxy failover, rolling schema/configuration upgrades and native
game networking with multiple authorities remain unverified. A shared
database outage deliberately rejects operations. The optional proxy example
is not a deployed or tested HA load balancer.

Tokyo acceptance results (2026-11-27):

- Two-instance integration completed successfully twice, including real MongoDB
  operations, cross-instance HTTP/WebSocket snapshots, concurrent room admission,
  signed grant consistency, JVM loss/restart and persistence.
- The second run also rejected a mismatched cluster configuration at startup
  and stopped the disposable MongoDB container; HTTP and relay authorization
  returned 503 rather than using a stale local copy.
- Final Maven tests/package, standalone memory-mode HTTP/WebSocket regression,
  and Vue TypeScript/production build passed after adding the history persistence
  indicator. Native game/adapter and populated native monitoring UI were not
  tested in this round.
