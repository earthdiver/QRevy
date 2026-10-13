#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.7.1"
DIST_SHA256="acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
WRAPPER_JAR_SHA256="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CACHE="${TMPDIR:-/tmp}/qr-repair-gradle-${GRADLE_VERSION}"
ZIP="$CACHE/gradle-${GRADLE_VERSION}-bin.zip"
HOME_DIR="$CACHE/gradle-${GRADLE_VERSION}"
mkdir -p "$CACHE"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else echo "sha256sum or shasum is required" >&2; exit 1
  fi
}

if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  if [ ! -f "$ZIP" ] || [ "$(sha256_file "$ZIP")" != "$DIST_SHA256" ]; then
    rm -f "$ZIP"
    command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
    curl --fail --location --proto '=https' --proto-redir '=https' --tlsv1.2 --output "$ZIP" "$DIST_URL"
  fi
  [ "$(sha256_file "$ZIP")" = "$DIST_SHA256" ] || { echo "Gradle distribution checksum mismatch" >&2; exit 1; }
  rm -rf "$HOME_DIR"
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required" >&2; exit 1; }
  unzip -q "$ZIP" -d "$CACHE"
fi

cd "$ROOT"
"$HOME_DIR/bin/gradle" wrapper \
  --gradle-version "$GRADLE_VERSION" \
  --distribution-type bin \
  --gradle-distribution-sha256-sum "$DIST_SHA256"

JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
[ -f "$JAR" ] || { echo "wrapper jar was not generated" >&2; exit 1; }
ACTUAL=$(sha256_file "$JAR")
[ "$ACTUAL" = "$WRAPPER_JAR_SHA256" ] || {
  echo "Gradle wrapper JAR checksum mismatch: $ACTUAL" >&2
  exit 1
}
chmod +x "$ROOT/gradlew"
echo "Verified Gradle $GRADLE_VERSION wrapper generated."
