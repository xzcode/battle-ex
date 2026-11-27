#!/usr/bin/env bash
# Build/test the actual native game module without GTK/WebKit/Tauri host SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD/apps/desktop"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/apps/desktop/src-tauri/src/games"
cp "$root/game-catalog.json" "$work/apps/desktop/"
cp "$root/src-tauri/src/games.rs" "$work/apps/desktop/src-tauri/src/games.rs"
cp "$root/src-tauri/src/network.rs" "$work/apps/desktop/src-tauri/src/network.rs"
printf 'pub mod games;\npub mod network;\n' > "$work/apps/desktop/src-tauri/src/lib.rs"
cp "$root/src-tauri/src/games/windows.rs" "$work/apps/desktop/src-tauri/src/games/windows.rs"
python3 - "$root/src-tauri/Cargo.toml" "$work/apps/desktop/src-tauri/Cargo.toml" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
s=s[s.index('[dependencies]'):s.index('[profile.release]')]
s='\n'.join(line for line in s.splitlines() if not line.startswith('tauri ='))
Path(sys.argv[2]).write_text('[package]\nname="battle-game-library-check"\nversion="0.1.0"\nedition="2021"\n'+s+'\n')
PY
cd "$work/apps/desktop/src-tauri"
cargo test
if [[ "${1:-}" == "--windows" ]]; then
  # Install this Rust target beforehand. This is a type/compile check, not
  # native Windows execution or a complete Tauri bundle.
  cargo check --target x86_64-pc-windows-gnu
fi
