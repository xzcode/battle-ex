# M1 application baseline — 2026-11-27

## Scope

- Java 21 / Spring Boot 3.5.16 WebFlux server with Reactor Netty, single-process room authority (see [WebFlux migration](WEBFLUX_MIGRATION.md)).
- Registration and login: normalized unique username, BCrypt cost 12, password validation (8 characters minimum, 72 UTF-8 bytes maximum), 256-bit opaque bearer tokens, hashed token lookup, 12-hour expiry and one active session per account. Replacement revokes the old token; logout/expiry removes the user's room membership. No game launch or trusted match result claim.
- AccountStore.Memory (default, development only) and MongoAccounts (configured via BATTLE_MONGO_URI). Configured database failure fails startup; no silent memory fallback. MongoDB unique username index; only password hashes persisted. Rooms/session state intentionally resets across server restart.
- Authenticated REST room actions and lobby snapshots; membership/host checks, private invitation reveal/rotate, atomic capacity, readiness, start/finish, leave and host transfer. Signed-in count means valid sessions, not actual online sockets.
- WebSocket /ws/lobby authenticates via first JSON message within 5 seconds; no URL token. Sends changed full snapshots on a one-second tick, closes revoked/expired sessions. Server-wide snapshot revision and client stale-snapshot rejection.
- Vue 3 / TypeScript / Vite client: English/Chinese, server configuration, account views, filter/search/public lobby, create/private join, room roster, ready/start/finish/leave, invitation copy, live status and reconnect. Game catalog is a compatibility target list, not tested game support. Token stays in memory; endpoint/language only saved locally.
- Tauri 2 native wrapper source and Cargo.lock; no native privileges, game launching or adapter install yet. Packaging disabled until icons/native platform acceptance. Frontend build is not a Windows/macOS/Linux native installation.

## Run

Requires JDK 21, Maven and Node 22.14+. For local development:
```sh
mvn -f server/pom.xml -DskipTests package
java -jar server/target/battle-ex-server-0.1.0-SNAPSHOT.jar
cd apps/desktop
npm ci
npm run dev
```
Open http://127.0.0.1:1420; default API origin http://127.0.0.1:8080. For persistent accounts supply BATTLE_MONGO_URI (including authentication in a secret environment variable) and optional BATTLE_MONGO_DB (default battle_ex). Server binds loopback by default. Remote access requires a correctly configured TLS reverse proxy, explicit permitted web origins (BATTLE_ALLOWED_ORIGINS), rate limiting and operational review. Do not expose this development build directly to the public Internet.

## API

POST /api/v1/register, /login; POST /logout; GET /lobby.
POST /rooms, /rooms/{id}/join, /leave, /ready, /start, /finish.
GET and POST /rooms/{id}/invitation (host-only). Except register/login, require Authorization: Bearer <token>. IDs and actor identity come from authenticated server state. No client-supplied actor authorization.
WS /ws/lobby: first frame {"type":"authenticate","token":"..."}; server sends {"type":"lobby","data":{...}}; client must reconnect with a valid token. No other inbound commands.

## Verification

On isolated Tokyo Linux, JDK 21.0.9 and Maven 3.9.11:
- server package compiled, room domain test passed.
- `node scripts/test-platform.mjs` passed against the real server using ephemeral loopback listener; HTTP permissions, credential rejection, duplicate account, room lifecycle, live WebSocket update and revoke, malicious origin.
- `bash scripts/test-mongo.sh` passed with disposable authenticated MongoDB 8.0.15, localhost-only dynamically allocated port. Account identity survived server restart; sessions/rooms reset as specified. Container removed on exit.
- `cd apps/desktop && npm run build` passed (TypeScript and Vite production bundle).
- Browser test: `cd apps/desktop && npm run test:e2e` after installing Playwright Chromium and its platform dependencies. The first attempt was blocked by missing system libraries; a retry discovered a registration-page reset defect, then patched it. Final two-browser end-to-end run passed: registration, live room create/join, readiness, start/end, Chinese/English toggle, logout, host transfer and leave. Existing private browser libraries were supplied via LD_LIBRARY_PATH; no system packages installed.
- Cargo.lock generated offline; native Tauri build NOT RUN (host lacks GTK/WebKit development dependencies). No native installers.

## Remaining gates

M1 still needs native Windows/macOS/Linux launch and installation acceptance, friendship/social functionality, production auth abuse controls and multi-instance deployment. MongoDB stores accounts only; persistence/HA design for room events needs separate decision. Current WebSocket snapshot scheduler is single-instance development code; load/connection limits and scaling are not verified. Network Core EX has not been wired into this application, room network IDs do not enforce packet isolation yet, and no real game compatibility or anti-cheat is claimed. No public deployment/release. Project license pending owner decision.

Next: M2 trusted device enrollment and room-scoped snapshots/leases, authenticated local IPC, revocation/route isolation and Windows real-game validation. Use a pinned Network Core EX candidate only in development until upstream dual-NAT reliability gate passes.
