# WebFlux control-plane migration

2026-11-27. M2 protocol-compatible migration, not an M3 distributed-room implementation.

## Runtime

- Java 21, Spring Boot 3.5.16, Spring WebFlux 6.2.19, Reactor Netty 1.2.18.
- No Spring MVC, embedded Tomcat, servlet WebSocket adapter or synchronous MongoDB driver.
- HTTP and room-network API controllers return Mono; existing URLs, JSON, bearer tokens, status codes, CORS and cache rules remain unchanged.
- Reactive WebSocket handler uses one inbound and outbound stream per session, authenticates the first text frame within five seconds, rejects extra commands, and closes on invalid/revoked credentials. Periodic snapshots remain revision-based and coalesce ticks for backpressure.
- MongoDB Reactive Streams 5.5.2: reactive find and insert. Unique username index initialization waits only during application startup, never during a request. Existing account documents and uniqueness constraints are preserved; configured database failure still fails startup.
- Password hashing and synchronized room/session/network-signing operations execute on dedicated bounded Reactor workers, outside the Netty event loops. Queue capacity is worker backpressure, not a user/room quota. A saturated worker queue returns HTTP 503 rather than growing indefinitely.
- Existing in-memory room authority and synchronized mutations are intentionally retained to preserve admission, membership revisions and authorization atomicity. Scheduled expiration runs off the I/O event loop. This is not yet a lock-free or distributed authority.

## Verification

Build and run with JDK 21 and Maven:

```sh
mvn -B -ntp -f server/pom.xml -DskipTests package
node scripts/test-platform.mjs
bash scripts/test-mongo.sh
python3 scripts/test-room-network.py --ice
```

The network test requires prebuilt Go agent/relay binaries and Linux network namespace privileges as described in M2_IMPLEMENTATION.md. The disposable MongoDB test requires Docker.

Tokyo server results: package build succeeded, archive inspection confirmed WebFlux/Reactor Netty and MongoDB reactive driver, HTTP/WS platform regression passed with both memory and authenticated MongoDB; MongoDB account persisted across restart. M2 mixed ICE direct/WSS relay real network test passed including TCP/UDP, latency, isolation, lease denial, room switching, and interface cleanup after authority loss.

No public deployment or release. Windows native adapter and complex NAT reliability gates are unchanged. High-concurrency/load/HA acceptance remains pending.
