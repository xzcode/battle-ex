# M4.1–M4.3: local game library and room build reports

2026-11-27. Development implementation. Windows native acceptance remains
open; no claim that these games already work over the virtual network.

## Game catalog and local adapter

A single built-in JSON catalog drives the Vue game categories and Rust
adapter IDs. Initial entries are Warcraft III (classic/expansion candidates),
Red Alert 2 (base game/Yuri's Revenge candidates), CS 1.6 (GoldSrc) and a
user-selected custom Windows executable. These are installation candidates,
not certified version lists. Each profile currently holds one user-selected
installation; alternative versions can be selected again.

The Windows adapter checks common local folders without recursive disk
scans. The user can browse with a native file dialog or explicitly confirm a
detected candidate. The adapter validates the executable name/profile, PE
header/architecture and, for CS 1.6, presence of the local cstrike directory.
Windows version-resource metadata is optional. It records canonical local
path, SHA-256, version, architecture, size and modification time in the
application's local game-library.json. Configuration writes replace a synced
temporary file. Local file changes are detected, and actual SHA-256 is
rechecked before launch. Configuration removal never deletes game files.

Launch invokes the selected executable directly, with its own working
directory, fixed local adapter arguments only (hl.exe uses -game cstrike),
and native confirmation. No remote executable paths, arbitrary shell
commands, downloaded scripts, game assets or bypasses. Elevated application
processes refuse launch. Only the child process started by Battle EX is
tracked; a launcher which spawns another game process may exit early.
The launch does not automatically control the game's own matchmaking,
connect to an address, terminate another process or prove a game is running.

## Optional room build reports

POST /api/v1/rooms/{roomId}/game-build accepts
`{"build":{"gameId":"custom","sha256":"<64 lowercase hex>","fileVersion":"1.0.0.0","architecture":"x86"}}`.
Use `{"build":null}` to withdraw. The authenticated room member can modify
only their own report while the room is waiting. A different game ID or
malformed metadata is rejected. Changing a report clears room readiness
and increments the lobby/room revision; it does not change the network
membership revision or invalidate a network lease. Leaving removes the
report. Existing persisted rooms without the optional member field load
with unknown builds.

The local game library explicitly verifies and shares the selected executable
fingerprint, file version (or null) and architecture with the current room.
No filesystem paths, file contents, private keys or game files are sent.
Members receive reports in the private active-room view and WebSocket lobby
snapshot; public room summaries do not expose them. Mongo shared authority
persists them across instances/restarts. The lobby distinguishes missing
reports, differing fingerprints and matching reported fingerprints.
Different hashes are a warning, not a mandatory match-start block.
A report is client-declared and may be stale or forged. Matching executable
hashes do not establish compatible DLLs, maps, mods, editions or protocol;
this is not anti-cheat or authoritative game-result verification.

## Discovery and acceptance boundaries

| Profile | Current network guidance | Outstanding |
|---|---|---|
| Warcraft III | local launch candidate | Windows broadcast/LAN discovery and exact build verification |
| Red Alert 2 | local launch candidate | edition-specific network components and discovery |
| CS 1.6 | manual peer IPv4, if supported by installed game | actual two-client gameplay and server compatibility |
| Custom | manual peer IPv4 for supported games | game-specific discovery and actual compatibility |

Room network panel can copy a verified local agent-reported peer IP. No
broadcast forwarding or unrestricted L2 support is added by this M4 slice.
Network Core EX's existing controlled discovery/TAP is Linux-only; Windows
Wintun provides L3 unicast, not transparent Ethernet. Windows privileged
network service and secure service IPC/installation remain required; do not
run the entire desktop renderer/game as administrator. No native Tauri
installer or real Windows game session has been tested here.

## Reproducible checks

```sh
# Isolated Rust game module, optional Windows target type check
bash scripts/test-game-library.sh --windows
mvn -f server/pom.xml test package
node scripts/test-platform.mjs
bash scripts/test-mongo.sh --cluster
cd apps/desktop
npm ci
npm run build
npm run test:e2e
```

The native module test uses synthetic PE headers and temporary files; it
does not execute commercial games. The browser test checks the catalog
and room UI, not the native executable chooser. The cluster test uses a
disposable authenticated MongoDB container.

## Acceptance evidence

Tokyo Linux, JDK 21, Maven, Rust and Node 22:
- Isolated Rust module tests passed (2 tests), including local JSON persistence, changed executable detection, profile and PE validation, and fixed CS launch arguments. Windows x64 target compile/type check passed for the native module. Full native Tauri build and Windows execution were not run.
- Maven package and Java tests passed. Real HTTP/WebSocket platform regression passed with invalid-report rejection, authenticated member binding, non-member denial, readiness reset, report withdrawal and match-state checks.
- Two real WebFlux JVMs backed by disposable MongoDB synchronized build reports; non-member denial and report persistence after abrupt JVM termination/restart passed.
- Vue TypeScript/production build passed; dual-browser lobby test passed, including mismatched build warnings, room game-library navigation and disabled native controls in a browser. A final stale-report warning was added and rebuilt after that browser run.
