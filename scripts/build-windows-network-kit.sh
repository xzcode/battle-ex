#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
out="${1:-$root/agent/bin/windows-network-kit}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
(cd agent && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -o "$out/battle-agent.exe" ./cmd/agent)
cp deploy/windows/{install-network-service.ps1,uninstall-network-service.ps1,README.md} "$out/"
(cd "$out" && sha256sum battle-agent.exe > SHA256SUMS)
printf 'Windows x64 development network-service kit: %s\n' "$out"
printf 'Requires separately acquired verified official Wintun x64 DLL; no native install acceptance.\n'
