#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
EXPECTED="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
[ -f "$JAR" ] || { echo "Gradle wrapper JAR is missing; run ./bootstrap-wrapper.sh" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then ACTUAL=$(sha256sum "$JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then ACTUAL=$(shasum -a 256 "$JAR" | awk '{print $1}')
else echo "sha256sum or shasum is required" >&2; exit 1
fi
[ "$ACTUAL" = "$EXPECTED" ] || { echo "Gradle wrapper JAR checksum mismatch: $ACTUAL" >&2; exit 1; }
echo "Gradle wrapper JAR verified."
