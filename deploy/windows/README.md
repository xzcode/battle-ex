# Windows x64 network service (development kit)

This is not a signed production installer. It is an explicit administrator
installation procedure for the network agent built from this repository.
Use only a trusted build and an authentic, validly signed matching x64 Wintun
DLL from the official Wintun distribution. No driver or game is bundled.

Build with `bash scripts/build-windows-network-kit.sh` and verify the resulting
agent SHA-256 against SHA256SUMS. Obtain the actual platform HTTPS origin and
Ed25519 issuer public key from your server administrator over a trusted
channel. From the intended normal Windows user's terminal, run `whoami /user`
to identify that user's SID. Install from an elevated 64-bit Windows
PowerShell session, passing the explicit paths and known hashes:

```powershell
.\install-network-service.ps1 -AgentPath .\battle-agent.exe -AgentSha256 '<verified-agent-hash>' -WintunPath .\wintun.dll -WintunSha256 '<verified-official-dll-hash>' -Origin 'https://your-platform.example' -Issuer '<trusted-issuer-public-hex>' -AllowedUserSid '<normal-Windows-user-SID>'
```

The script requires a genuine signed driver DLL and verifies both file hashes.
It creates an administrator/SYSTEM-only Program Files directory and a fixed
SCM service. Start the desktop normally (not as Administrator); the bundled
agent is a pipe bridge. Service/client versions must match. No renderer-driven
elevation, arbitrary service commands, custom route input or private key file
input is allowed. Only the configured Windows user (and machine administrators)
can connect. A session is authorized against the installed HTTPS origin and
issuer. Use the service's status/error output to diagnose a failed connection.

Uninstall with `uninstall-network-service.ps1`. It stops and deletes the SCM
service, but intentionally retains protected files and identities. This first
kit refuses overwrite/upgrade of a retained directory. Back up identity state
and explicitly handle future migration with an administrator. Do not delete
state while the service runs. Do not point an existing service at a different
origin or issuer without a coordinated reinstall.

Current gate: source and Windows cross-compilation only. Actual Windows SCM,
pipe ACL, Wintun loading, route cleanup, game and installer acceptance are
still pending.
