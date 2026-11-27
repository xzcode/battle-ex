# M8–M9: teams, single-elimination events and operations

Development baseline, 2026-11-27. The server remains Spring Boot WebFlux.
All domain mutations participate in the existing authoritative state transaction.
No network engine fork or game redistribution.

## Teams

An authenticated account can create one team (unique case-insensitive name
and unique uppercase ASCII tag), browse/search the directory, view members,
submit or withdraw a join application. Membership is exclusive: a concurrent
approval by different teams cannot admit the same account twice. Approval
clears other outstanding applications. An applicant's message is visible
only to authorized managers of that team, not to arbitrary signed-in users.
The team name, tag, description and public member usernames are shown to
signed-in users; the client informs users before creation/join.

The captain (OWNER) edits the team, delegates/removes OFFICER roles,
transfers ownership and disbands the team. Officers may accept/reject
applications and remove ordinary members, not change ownership or expel
other officers. A member may leave; a captain must transfer ownership or
disband. Suspended accounts cannot authenticate or be approved/transferred
to as a captain. Suspension does not automatically delete team history or
membership. Recovery of a team with an unavailable captain is a future
administrative policy.

## Tournaments

Initial events are individual 1v1 single elimination, independent of team
membership and ranked ladder Elo. Authorized event managers publish an
event with game, name, rules/description and entrant capacity. Registration
and withdrawal remain open until the manager generates the bracket.
At least two entrants are required. The bracket size is the next power of
two, seeded in registration order using standard balanced seed positions;
empty positions become byes. The entrants are then immutable. Entrant
usernames, bracket state, manual decisions, reasons and eventual champion
are visible to authenticated users. Private room IDs, invitations, overlay
addresses, network leases and session credentials are not in the public
bracket.

Both entrants must explicitly check in to an eligible slot while signed in,
not suspended, and outside any room/queue. One user's check-in is cleared
when they check into another slot, join/create another room, enqueue, log
out, expire or replace their session. Second check-in revalidates both
entrants and atomically creates one server-managed private room and a
TOURNAMENT mode match in the existing M5 ledger. The regular match queue
cannot request TOURNAMENT mode. Room readiness, start/end, archive, reports
and network revocation reuse existing logic. No game process starts merely
because the room is assigned.

A slot has ordered attempt IDs. Leaving/finishing releases the room and
closes the attempt; an event manager can allow a rematch after an attempt
ends. A ruling must select an entrant and include a written reason.
PLAYED means a human-reviewed started/concluded attempt, not a cryptographic
game result; FORFEIT is an explicit no-show/forfeit ruling. Neither is
inferred from a player's disconnect. No decision is allowed while its room
is active, or by an operator who is one of its two entrants. Parent slots
are populated only after child winners/byes resolve. Final resolution
records the champion. Event cancellation requires a reason and no active
assigned rooms. Manual adjudication affects only the bracket, not Elo.
Tournament match history is labeled TOURNAMENT; the bracket is the source
of manual outcome. Signed real-game tournament results, team-vs-team
brackets, best-of series, scheduling/deadlines and disputes remain open.

## Operators and RBAC

No default root account or plaintext operator secret. Configure:

- `BATTLE_MODERATION_KEY_SHA256`: SHA-256 of a separately generated,
  high-entropy base64url operator credential (existing M7 mechanism).
- `BATTLE_OPERATIONS_ROOT_USERS`: comma-separated UUIDs of existing,
  verified operator accounts. Root identities are immutable runtime policy,
  not a claimable username or a user-supplied role.

Both a valid authenticated account and independent credential
(`X-Battle-Moderator`) are required for all operator routes. When a root
policy is configured, the M7 moderation routes require MODERATOR or root,
even for a holder of the shared credential. Root may grant/revoke individual
account roles: MODERATOR (reports, warnings, suspensions), EVENT_MANAGER
(event management), ANALYST (read-only aggregate overview). All roles can
see aggregate overview; individual session/room lists, forced actions,
delegation and the complete audit are root-only. Root grants resolve a real
account by exact normalized username after authorization, then reauthorize
inside the state transaction. Role changes take effect across instances
immediately. The operations and event consoles keep the credential in
view memory only; leaving/locking clears it. This is role-based access
plus a shared second credential, not individual MFA or a separate hardened
administration portal. Only trusted operators should possess that credential.

When no root UUID is configured, M7 legacy moderator-key authorization
continues for existing deployments. New root/role/event administration
is unavailable; ordinary teams still work. Configuring roots without
the credential digest fails startup.

For a **new shared-authority installation**, provision the root account
using loopback-only memory authority with the same MongoDB account store,
record its real UUID from the authenticated response, stop that process,
then configure the root IDs and key digest before initializing shared
authority. Do not use this procedure to replace a live authority document.
Root policy is part of the configuration fingerprint; existing shared
authority upgrades that change this policy or the existing moderator key
require a coordinated offline migration, which is not supplied here.
With unchanged policy, absent new optional fields in a previous snapshot
load empty. Stop old binaries first; mixed schema versions are unsupported.

## Operations console

Aggregate counts: signed-in accounts, rooms, running assigned matches,
queue tickets, teams, tournaments, pending reports and active suspensions.
The overview labels memory vs shared MongoDB authority. Root session rows
contain only account identity, expiry, room ID and queued flag. Room rows
contain room ID/name/game/visibility/state/host/count; no invitation,
network configuration, bearer or password hash. A root may disconnect
another current session or close a room only with a reason. The same
authority transaction removes room membership/queue/session as needed,
ending an assigned match through its normal lifecycle and revoking old
network authorization. A forced close or disconnect is not a suspension,
automatic forfeit or result. Audit entries record operator identity,
action, subject, reason and timestamp. Moderation reviews/revocations,
role changes, event actions and forced disconnect/close are audited.
Audit is application append-only, not tamper-proof against database
administrators. The existing Relay Monitor and Fair Play console remain.

## API surface

All paths below are under /api/v1. All require bearer authentication;
operator routes also require the independent credential and appropriate role.

Team routes:
- GET /community/teams, /community/teams/mine, /community/teams/{id}
- POST /community/teams, /community/teams/{id}/edit
- POST /community/teams/{id}/applications
- DELETE /community/teams/{id}/applications/mine
- POST /community/teams/{id}/applications/{user}/review
- POST /community/teams/{id}/members/{user}/role, /community/teams/{id}/transfer
- DELETE /community/teams/{id}/members/{user}, /community/teams/{id}

Event routes:
- GET /community/events, /community/events/{id}
- POST /community/events/{id}/register and /slots/{slot}/check-in
- DELETE /community/events/{id}/register
- POST /community/admin/events, /community/admin/events/{id}/start and /cancel
- POST /community/admin/events/{id}/slots/{slot}/retry and /decide

Operator routes:
- GET /operations/access, /overview, /roles, /audit, /sessions, /rooms
- POST /operations/roles, /operations/roles/{id}/revoke
- POST /operations/sessions/{id}/disconnect, /operations/rooms/{id}/close

## Consistency, deployment and remaining work

Team membership, applications, tournament bracket/attempts, roles and audit
are serialized in the shared MongoDB aggregate. Memory authority loses
them on process restart. Shared-state CAS protects concurrent admission,
room assignment and adjudication. It is not a scalable production data
model: history/audit and brackets share a document and inherit MongoDB's
BSON size and global-write contention. Split/paginate storage and add
migration/backup/observability before production. No account-wide data
management, automatic client updates, payment/registration fees, team
league engine or release signing is claimed.

## Acceptance

Run `mvn -f server/pom.xml test package`, then
`bash scripts/test-mongo.sh --community-operations`. The test bootstraps an
actual account in a disposable MongoDB, runs two independent WebFlux JVMs,
and exercises root UUID/role gating, cross-instance role revocation,
team application privacy, concurrent admission, officer privileges, captain
transfer, three-entrant bye, concurrent event check-in, retry, manual
played and forfeit rulings, champion and no Elo change, and restart
persistence. The business CI also runs this fixture.
The Vue production build and three-session browser regression include team
creation, application, approval, promotion and transfer; the event and
operations API lifecycle is tested via actual HTTP/WebSocket. The full
event-manager/root UI is build-checked, not yet separately end-to-end driven.

Final 2026-11-27 verification: six JUnit tests passed; the final two-JVM
community/operations fixture passed including root-only redacted room and
session lists, forced room close, forced session disconnect, old-token
rejection, re-login without a stale queue, and persisted enforcement audit.
Vue production build passed after those controls were added. Three-browser
team and existing M1–M7 UI regression, standalone platform and competition
HTTP regression passed before the additive root management controls.
The full root/event management UI was not independently browser-driven.
