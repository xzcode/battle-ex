# M5 — 1v1 matchmaking, competitive ratings and match history

Development baseline. Games: Warcraft III, Red Alert 2, CS 1.6 and Custom LAN.
Actual game compatibility remains a separate gate.

## Behavior

- One queue ticket per authenticated account; a queued account cannot create
  or join a manual room. Game ID and CASUAL/RANKED mode must match.
- Casual queues select the oldest compatible pair. Ranked pairs start within
  a 100-point Elo gap, increasing by 50 every 30 seconds that each player has
  waited. Both players must tolerate the gap. Initial Elo is 1500, K=32.
- Matching creates a private, capacity-two room with the fixed participant
  roster. No third-party join or invitation rotation is permitted. Both
  players ready and the host starts. A waiting match cancels if either
  leaves; starting a match does not launch a game automatically.
- An assigned room is single-use: finish/leave/logout/session expiry closes
  both members' room and revokes the overlay through the existing authority.
  Casual completion/abandonment is unrated. A started ranked match awaits
  a signed result for 600 seconds after room closure; expiry records NO_RESULT
  without changing Elo. A pending ranked result prevents another ranked
  queue ticket. Casual play remains possible.
- The authenticated player can read only their own assigned-match history.
  Leaderboards list verified ratings per game and use paginated queries.
  Manually created room sessions are not part of the assigned-match ledger.

## Trusted result provider contract

Ranked mode is disabled by default. Enable only with a genuinely trusted
external integration that can attest to the real game and immutable Battle EX
match ID, not a player-controlled desktop signer. Configure the public
Ed25519 key and the precise supported game IDs:

- BATTLE_COMPETITION_RESULT_ISSUER: raw 32-byte Ed25519 public key in
  lowercase hexadecimal.
- BATTLE_COMPETITION_RANKED_GAMES: comma-separated IDs.

The private key must remain in the independent result provider. No commercial
game result integration is implemented by this stage. The tests use generated,
disposable fixture keys; their results do not certify any game.

POST /api/v1/competition/trusted-result accepts JSON fields payload and
signature, both unpadded base64url. The payload bytes encode a JSON report
with matchId, gameId, outcome (WIN or DRAW), winnerId (participant UUID for
WIN, null for DRAW), and issuedAt (Unix seconds). Sign exactly the UTF-8
domain string battle-ex/competition-result/v1 followed by a NUL byte and the
raw payload bytes. The signature is verified before settlement. The server
validates match state, game, roster and timestamp; identical signed payloads
are idempotent, conflicting results are rejected. Elo uses symmetric deltas
so a result cannot apply twice or introduce a client-selected rating.
No player API grants rating from a host finishing a session.

Endpoints (authenticated bearer except the signed-result endpoint):
- POST /api/v1/competition/queue, body {gameId, mode}
- DELETE /api/v1/competition/queue
- GET /api/v1/competition/leaderboard/{game}?offset=0&limit=50
- GET /api/v1/competition/history?offset=0&limit=50

The lobby HTTP/WS snapshot includes ticket, current match, awaiting result
and ranked-enabled game IDs. The Vue competition view includes matchmaking,
queue wait, cancel, rankings and personal history. The lobby opens the
assigned room automatically. English and Chinese labels are provided.

## Consistency and deployment

Queue tickets, matches, ratings and room mutations are included in the
existing versioned shared-authority MongoDB aggregate. The same serialized
state change assigns the room and roster or settles the match and Elo.
Standalone memory mode is supported but does not persist on restart.
The new optional competition field can read a previous v1 snapshot with no
competition data. Stop all old server instances before upgrading; mixed
versions are not supported. The ranked key/game policy participates in
the configuration fingerprint. Use a fresh isolated database for testing
enabled ranked mode; changing the policy for an existing shared database
needs a coordinated migration, not a manual fingerprint edit.

This is a correctness baseline, not a large-scale queue: matchmaking scans
the waiting tickets and the aggregate serializes writes globally. Match history
and ratings are included in the same document and inherit MongoDB's BSON
size limit. Partitioned queue/room transactions, separate durable result
ledger and leaderboard indexes, seasons, parties and team-size matching
remain future work. No replay viewer, game anti-cheat or real trusted
commercial-game result adapter is claimed.

## Acceptance commands

```sh
mvn -f server/pom.xml test package
node scripts/test-competition.mjs
bash scripts/test-mongo.sh --competition
node scripts/test-platform.mjs
cd apps/desktop
npm run build
npm run test:e2e
```

## Acceptance evidence (2026-11-27)

- Maven JDK 21 compilation/package and five JUnit tests passed, including
  queue isolation, widening Elo window and pending-result expiry.
- Real HTTP/WebSocket integration passed in standalone memory mode and
  two-JVM MongoDB shared-authority mode. Verified matching/cancel, private
  managed rooms, roster/state and signature rejection, unrated completion,
  duplicate concurrent result settlement (one Elo update), draw, history
  privacy, logout cleanup and state/ratings restoration after JVM failure
  and all-JVM restart.
- Vue TypeScript/production build and dual-browser integration passed,
  including queue cancellation, automatic room assignment, readiness,
  start/end and unrated history. The browser run found a missing DELETE
  method in the API CORS allowlist; fixed and reran successfully.
- No game-provided trusted result integration, real game result or
  Windows game compatibility was tested by this stage.
