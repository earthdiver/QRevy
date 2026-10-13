#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.8.1"
DIST_SHA256="dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"
WRAPPER_JAR_SHA256="3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98"
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$ROOT/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$PROPERTIES" ]; then
  if ! grep -Fxq "distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" "$PROPERTIES" \
    || ! grep -Fxq "distributionSha256Sum=$DIST_SHA256" "$PROPERTIES"; then
    echo "Gradle wrapper distribution pins disagree with bootstrap-wrapper.sh; update both together before bootstrapping." >&2
    exit 1
  fi
fi
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
