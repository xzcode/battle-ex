# Windows native CI and development artifacts

Use GitHub-hosted `windows-2025` x64 VMs. The repository is public; standard runner usage is subject to GitHub's public-repository Actions policy.

## Workflows

- `windows-build.yml`: Go native tests and vet, Node/Vue production build, full native Rust/Tauri tests, unsigned x64 desktop EXE, NSIS and MSI installers, separate network-service kit ZIP, SHA256SUMS, artifact upload. Separate fresh-VM MSI and NSIS smoke jobs verify all hashes, actually install the application, check the installed sidecar, open/close the native main window and uninstall.
- `windows-network-test.yml`: JDK 21 WebFlux server and Go agent/relay native build; real Windows service installation and controlled loopback TLS integration, native interface, UDP relay echo and directed-discovery sender validation.
- `release.yml`: manually confirmed development RC draft on master only, after both Windows workflows and the platform business-acceptance workflow pass for the same commit. It does not automatically publish a release or bypass game compatibility/license gates.

The Windows-specific Tauri configuration enables MSI and current-user NSIS packaging. The icon generator creates an original local multi-resolution ICO; no third-party asset is bundled. The desktop installer does not automatically elevate, install the network service or redistribute Wintun. Use the separate administrator network-service installation procedure.

## Network fixture

Run only on a disposable GitHub-hosted Windows VM. The script refuses an existing Battle EX service/state directory, retrieves the official Wintun 0.14.1 archive from https://www.wintun.net/builds/wintun-0.14.1.zip and checks the official archive digest from https://www.wintun.net/. The actual installation script additionally verifies the DLL's Authenticode signature and hashes of copied files.

The fixture uses a short-lived loopback TLS certificate, random ephemeral issuer and credentials, two temporary ordinary Windows users, the real WebFlux authority and real relay. It uses their Windows logon tokens to exercise allowed/denied pipe access. One peer uses the actual shared encrypted engine with a test packet queue (no second OS adapter in the same VM), while the other uses the installed Windows service/Wintun. The test checks actual UDP echo over WSS and native directed broadcast sender counters; this is **not** two Windows game clients or native broadcast reception compatibility. Cleanup removes the test service, temporary users and root certificate. The VM is discarded by GitHub. Only logs and result evidence are uploaded, not private material.

## Gates

Windows native build and synthetic SCM/Wintun acceptance passed (see evidence below). The new actual desktop installer-smoke jobs must also pass before claiming install/start/uninstall acceptance. Actual commercial-game launches, native two-client LAN discovery, IPX/L2 compatibility and production signing still require separate verification. Do not publish a stable release based on this synthetic fixture.

## Verified baseline — 2026-11-27

| Check | Exact commit | Runner result |
| --- | --- | --- |
| Windows desktop EXE, MSI, NSIS, service kit and checksums uploaded | `4b7c0719aa74142e63a02df3ffb72a7c558edea9` | [success](https://github.com/xzcode/battle-ex/actions/runs/45985385618) |
| Windows SCM/Wintun synthetic network acceptance | `285c8ba33e50157f01dda281228a32cf156fbd0b` | [success](https://github.com/xzcode/battle-ex/actions/runs/45985708668) |

- [Windows x64 artifact](https://github.com/xzcode/battle-ex/actions/runs/45985385618/artifacts/15190969730): 38,025,701 bytes; SHA-256 digest of uploaded archive `ae2617e829c2f6d33afb390f58d4c38a2023b7a6cb17993a0a6c556324d52238`. GitHub retention expires 2026-12-11. The MSI is 10.66 MiB; NSIS setup is 8.43 MiB.
- [Network evidence](https://github.com/xzcode/battle-ex/actions/runs/45985708668/artifacts/15191407902): verified signed Wintun archive, real permitted and denied ordinary Windows user pipe access, SCM service and Wintun address, WSS UDP echo, directed broadcast transmit capture, unapproved port rejection, leave revocation and pipe close.
- First unsuccessful runs exposed test-only POSIX file permissions, legacy PowerShell module/environment casing and artifact-path concatenation problems. They were fixed without skipping the tests.
- The network fixture has one real Windows TUN and one shared-engine packet-queue peer, not two independently installed Windows games. The new MSI/NSIS smoke jobs exercise the exact artifact of the current build on fresh VMs; no installed service/game or production signing is implied. No stable Release has been published.

The workflow's draft-release job depends on the complete reusable Windows build workflow (including installer smoke jobs) and network acceptance. License approval and real-game gates remain mandatory before any public production release.

### Per-format installer payloads

The first MSI and NSIS smoke jobs in [45991240003](https://github.com/xzcode/battle-ex/actions/runs/45991240003) installed the files but correctly rejected the desktop hash comparison. Build logs show Tauri patches the application executable with NSIS/MSI-specific bundle-type information. A standalone Cargo-target executable is therefore not the correct reference for installed bytes. The build now independently extracts the MSI administrative image and NSIS archive, records each exact desktop/sidecar SHA-256 in `installer-payloads.json`, verifies the sidecar against the staged Go binary, and includes the manifest in SHA256SUMS. Smoke jobs still compare exact installed bytes to the correct installer payload before launching. The corrected [run 45992771052](https://github.com/xzcode/battle-ex/actions/runs/45992771052), commit 3d3efe425ab968afab0fc6084daced64592331ed, completed the build and both fresh-VM MSI/NSIS smoke jobs successfully: exact installed executable hashes, native window startup, clean exit and uninstall. This is installer acceptance of that commit, not a real game test.

### M6–M7 regression baseline

The exact M6–M7 commit e21e06badd502301da6e390665be50152b6dba31 passed [platform business acceptance](https://github.com/xzcode/battle-ex/actions/runs/45994853166), [Windows desktop build plus both installer smoke jobs](https://github.com/xzcode/battle-ex/actions/runs/45994853140) and [native Windows network acceptance](https://github.com/xzcode/battle-ex/actions/runs/45994853101). These are prior-commit acceptance records, not real-game certification.
