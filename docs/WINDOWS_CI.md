# Windows native CI and development artifacts

Use GitHub-hosted `windows-2025` x64 VMs. The repository is public; standard runner usage is subject to GitHub's public-repository Actions policy.

## Workflows

- `windows-build.yml`: Go native tests and vet, Node/Vue production build, full native Rust/Tauri tests, unsigned x64 desktop EXE, NSIS and MSI installers, separate network-service kit ZIP, SHA256SUMS, artifact upload.
- `windows-network-test.yml`: JDK 21 WebFlux server and Go agent/relay native build; real Windows service installation and controlled loopback TLS integration, native interface, UDP relay echo and directed-discovery sender validation.
- `release.yml`: manually confirmed development RC draft on master only, after both workflows pass for the same commit. It does not automatically publish a release or bypass game compatibility/license gates.

The Windows-specific Tauri configuration enables MSI and current-user NSIS packaging. The icon generator creates an original local multi-resolution ICO; no third-party asset is bundled. The desktop installer does not automatically elevate, install the network service or redistribute Wintun. Use the separate administrator network-service installation procedure.

## Network fixture

Run only on a disposable GitHub-hosted Windows VM. The script refuses an existing Battle EX service/state directory, retrieves the official Wintun 0.14.1 archive from https://www.wintun.net/builds/wintun-0.14.1.zip and checks the official archive digest from https://www.wintun.net/. The actual installation script additionally verifies the DLL's Authenticode signature and hashes of copied files.

The fixture uses a short-lived loopback TLS certificate, random ephemeral issuer and credentials, two temporary ordinary Windows users, the real WebFlux authority and real relay. It uses their Windows logon tokens to exercise allowed/denied pipe access. One peer uses the actual shared encrypted engine with a test packet queue (no second OS adapter in the same VM), while the other uses the installed Windows service/Wintun. The test checks actual UDP echo over WSS and native directed broadcast sender counters; this is **not** two Windows game clients or native broadcast reception compatibility. Cleanup removes the test service, temporary users and root certificate. The VM is discarded by GitHub. Only logs and result evidence are uploaded, not private material.

## Gates

Windows source/Go test executable cross-compilation, workflow YAML parsing and ICO generation were checked locally on Linux. A successful Windows Runner execution is required before describing native service/driver or installer acceptance as passed. Actual commercial-game launches, native two-client LAN discovery, IPX/L2 compatibility and production signing still require separate verification. Do not publish a stable release based on this synthetic fixture.
