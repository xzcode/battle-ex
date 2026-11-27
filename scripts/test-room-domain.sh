#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$javac_bin" --release 21 -Xlint:all -d "$work" server/src/main/java/com/battleex/room/RoomDirectory.java server/src/test/java/com/battleex/room/RoomDirectoryTest.java
"$java_bin" -cp "$work" com.battleex.room.RoomDirectoryTest
