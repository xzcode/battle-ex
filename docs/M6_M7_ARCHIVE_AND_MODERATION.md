# M6–M7 match archive, replay library and manual moderation

Historical baseline: [M8–M9 operations](M8_M9_COMMUNITY_OPERATIONS.md) adds configured root UUIDs and per-account operator roles. When roots are configured, moderation requires MODERATOR or root in addition to the independent credential.

Development baseline, 2026-11-27. This slice builds on the M5 assigned 1v1
match ledger. It does not certify gameplay compatibility or an anti-cheat
engine.

## Match center

The participant-authenticated match detail includes the original M5 roster,
timestamps, state and verified rating changes. Concluded played matches can
register replay metadata. Only the authenticated uploader can remove their
record; filenames are basenames, no paths or remote URLs. Records are
deduplicated per participant/match/digest. Reports retain their submitted
fingerprint even if the player later removes the catalogue entry.

Each participant may independently consent to publishing a running match's
status. The public live list (authenticated users only) contains the match
ID, game, mode, player display identities and start time. Both participants
must consent; either can withdraw immediately. Ending/leaving/settling the
match removes consent. No invitation, room address, peer key, access token,
network lease, private match detail or game video is exposed. This is
status viewing, **not** native game spectating or streaming.

## Windows local replay library

The native replay library supports user-selected W3G (Warcraft III) and
DEM (CS 1.6) files. Extension association is not validation of the actual
game's replay codec. Import uses a native file chooser, a streaming copy
into app-local storage, SHA-256 and byte count, and content-addressed
deduplication. The archive is scoped by the local server/account identifier
(convenience separation on one OS account, not an OS authorization boundary).
It does not read arbitrary remote paths or execute a replay. Verify
re-hashes the stored copy. Export uses a native save chooser and creates
a new file only; a mismatched digest removes the incomplete export.
Removing the local copy requires native confirmation and never deletes
the original game file. No automatic game-directory scanning, playback,
remote binary upload or replay distribution is implemented.

A player explicitly verifies a local replay before registering its basename,
size and digest against a matching concluded match. Other participants see
metadata, not the file bytes or original local path. The record is
self-reported, not proof of a match result or cheating. A replay may be
deleted locally without removing its server record, and vice versa.

## Moderation

A participant in a started assigned match can report the other participant
for suspected cheating, abusive behavior, disconnection abuse or other
reasons, with a written description and optional own registered replay
digest. Duplicate identical submissions are idempotent; conflicting repeat
reports for that reporter/target/match are rejected. Reports are visible
only to their reporter and an authorized operator, not the general lobby.
A report alone never changes access, a match result or Elo.

An explicit privileged operator credential plus a normal authenticated
account is required to list all reports, review, view suspension history and
revoke a suspension. There is no default admin. Configure
`BATTLE_MODERATION_KEY_SHA256` with the lowercase SHA-256 of a randomly
generated high-entropy base64url credential (at least 32 random bytes).
Only its digest is configured on the server. Send the actual secret in
`X-Battle-Moderator` over HTTPS (loopback HTTP is for isolated development).
The separate Review Console keeps it in view memory only, never localStorage.
Leaving/locking the view clears it. Reviewers cannot review reports involving
themselves. This initial shared-operator credential is not individual RBAC
or MFA; deploy only to trusted operators. Fine-grained individual admin roles
are a later M9 gate. Rotate credentials via coordinated configuration
migration; do not store plaintext credentials in Git or logs.

Human decisions are dismiss, warn, or suspend. Suspensions can be time-limited
or permanent and require a written reason. Review records identify the logged
in reviewer and timestamp. A suspension removes the target's session, queue
and active room in the same shared authority state change; other authorities
deny old bearer tokens and relay grants. Network snapshots/leases expire or
are revoked through the existing M2/M3 mechanisms. Re-login is blocked until
expiry or explicit audited revocation. No automatic forfeits, Elo edits,
automatic cheat verdicts, kernel monitoring, process injection or invasive
anti-cheat are implemented. Warnings are visible to the affected account.

## API

Bearer authentication is required for all endpoints below. Admin requests
also require the independent moderator credential.

- GET /api/v1/competition/matches/{matchId}
- POST /api/v1/competition/matches/{matchId}/replays
- DELETE /api/v1/competition/matches/{matchId}/replays/{replayId}
- POST /api/v1/competition/matches/{matchId}/share-status (share boolean)
- GET /api/v1/competition/live
- POST /api/v1/moderation/reports
- GET /api/v1/moderation/reports/mine
- GET /api/v1/moderation/warnings
- GET /api/v1/moderation/admin/reports (optional status filter)
- POST /api/v1/moderation/admin/reports/{id}/review
- GET /api/v1/moderation/admin/suspensions
- POST /api/v1/moderation/admin/suspensions/{id}/revoke

Archive/report/suspension data use the existing versioned MongoDB authority
aggregate. Shared Mongo mode persists through restart; memory mode loses
these records on restart, even if accounts have separate Mongo storage.
The old snapshot fields are optional for migration to this binary. Stop all
old binaries before upgrading. The operator credential digest participates in
the cluster configuration fingerprint; adding or rotating it in an existing
shared authority requires a coordinated migration (not implemented here).
A fresh isolated database can be used to exercise enabled moderation.
The aggregate's BSON size/serialization bottleneck remains; partitioned
persistent history/ban/audit stores and authenticated shared replay storage
are future work.

## Acceptance

```sh
mvn -f server/pom.xml test package
bash scripts/test-mongo.sh --archive-moderation
bash scripts/test-game-library.sh --windows
node scripts/test-platform.mjs
node scripts/test-competition.mjs
cd apps/desktop
npm run build
npm run test:e2e
```

- Real two-instance WebFlux/MongoDB integration passed: mutual consent
  publication/withdrawal, match access control, replay metadata validation
  and deduplication, report privacy and review authorization, warnings,
  permanent suspension revoking a live room and actual signed relay lease,
  WebSocket close, blocked login, all-JVM restart persistence, audited
  revocation and re-login.
- Six Java JUnit tests passed, including time-limited suspension expiry and
  idempotent review. Rust isolated module tests (4) and Windows x64 target
  compile check passed, including replay copying/deduplication, wrong
  extension and traversal rejection, export non-overwrite and tampering
  detection. Synthetic files, not game-validated replay content.
- Vue production build and three-browser regression passed: opt-in live status,
  withdrawal, match report submission, authenticated operator review,
  private warnings and existing M1–M5 room/matchmaking regression. The first
  browser run exposed an ambiguous accessible label on the review selector;
  fixed the label and reran successfully. Native
  Windows file chooser interaction, remote replay transfer, actual native
  spectating, game-specific anti-cheat and operator RBAC remain open.
- Added Platform business acceptance GitHub Actions workflow; subsequent
  draft-release jobs require its success as well as native Windows workflows.
  First execution of the new workflow is pending.
