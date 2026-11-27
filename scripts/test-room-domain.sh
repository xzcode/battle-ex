#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mapfile -d '' sources < <(find server/src/main/java server/src/test/java -name '*.java' -print0)
"$javac_bin" --release 21 -Xlint:all -d "$work" "${sources[@]}"
"$java_bin" -cp "$work" com.battleex.room.RoomDirectoryTest
