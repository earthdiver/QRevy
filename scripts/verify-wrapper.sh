#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
EXPECTED="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
[ -f "$JAR" ] || { echo "Gradle wrapper JAR is missing; run ./bootstrap-wrapper.sh" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then ACTUAL=$(sha256sum "$JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then ACTUAL=$(shasum -a 256 "$JAR" | awk '{print $1}')
else echo "sha256sum or shasum is required" >&2; exit 1
fi
[ "$ACTUAL" = "$EXPECTED" ] || { echo "Gradle wrapper JAR checksum mismatch: $ACTUAL" >&2; exit 1; }
echo "Gradle wrapper JAR verified."
