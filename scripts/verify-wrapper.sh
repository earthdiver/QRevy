#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
EXPECTED="3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
[ -f "$JAR" ] || { echo "Gradle wrapper JAR is missing; run ./bootstrap-wrapper.sh" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then ACTUAL=$(sha256sum "$JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then ACTUAL=$(shasum -a 256 "$JAR" | awk '{print $1}')
else echo "sha256sum or shasum is required" >&2; exit 1
fi
[ "$ACTUAL" = "$EXPECTED" ] || { echo "Gradle wrapper JAR checksum mismatch: $ACTUAL" >&2; exit 1; }
echo "Gradle wrapper JAR verified."
