#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
failed=0

blocker() {
  echo "BLOCKER: $*" >&2
  failed=1
}
review() {
  echo "REVIEW REQUIRED: $*" >&2
  failed=1
}

if grep -q 'applicationId = "com.example.qrrepair"' androidApp/build.gradle.kts; then
  blocker 'replace placeholder applicationId com.example.qrrepair before public store release.'
fi
if grep -q 'namespace = "com.example.qrrepair"' androidApp/build.gradle.kts; then
  blocker 'replace placeholder namespace com.example.qrrepair before public store release.'
fi
if grep -q '<uses-permission android:name="android.permission.INTERNET"' androidApp/src/main/AndroidManifest.xml; then
  review 'INTERNET permission is present; re-review privacy/data safety.'
fi
if ! grep -q 'android:allowBackup="false"' androidApp/src/main/AndroidManifest.xml; then
  blocker 'backup is not explicitly disabled.'
fi
if ! grep -q 'noBackupFilesDir' androidApp/src/main/java/io/github/earthdiver/qrrepair/session/RecoverySessionStore.kt; then
  blocker 'repair sessions are not visibly rooted in noBackupFilesDir.'
fi

if ! grep -q 'include(":shared")' settings.gradle.kts; then
  blocker 'KMP shared module is not included in settings.gradle.kts.'
fi
if ! grep -q 'id("org.jetbrains.kotlin.multiplatform") version "2.4.10"' build.gradle.kts; then
  blocker 'Kotlin Multiplatform 2.4.10 pin is missing or changed.'
fi
if ! grep -q 'id("com.android.kotlin.multiplatform.library") version "9.3.1"' build.gradle.kts; then
  blocker 'Android-KMP library plugin 9.3.1 pin is missing or changed.'
fi
if ! grep -q 'baseName = "QRRepairCore"' shared/build.gradle.kts; then
  blocker 'QRRepairCore iOS framework configuration is missing.'
fi
if ! grep -q 'implementation(project(":shared"))' androidApp/build.gradle.kts; then
  blocker 'Android app no longer consumes the shared module.'
fi

if [ ! -f LICENSE ] || [ ! -f THIRD_PARTY_NOTICES.md ] || [ ! -f PRIVACY.md ]; then
  blocker 'LICENSE, THIRD_PARTY_NOTICES.md, and PRIVACY.md must be present.'
fi

EXPECTED_DIST='553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'
EXPECTED_WRAPPER='497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7'
if ! grep -q "distributionSha256Sum=$EXPECTED_DIST" gradle/wrapper/gradle-wrapper.properties; then
  blocker 'Gradle 9.5.0 distribution SHA-256 pin is missing or changed.'
fi
if ! grep -q "WRAPPER_JAR_SHA256=\"$EXPECTED_WRAPPER\"" bootstrap-wrapper.sh; then
  blocker 'Gradle wrapper-JAR SHA-256 verification is missing or changed.'
fi

# GitHub recommends immutable full commit SHA pins for third-party Actions.
for workflow in .github/workflows/*.yml .github/workflows/*.yaml; do
  [ -f "$workflow" ] || continue
  if grep -E '^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]+[^[:space:]#]+@' "$workflow" \
    | grep -Ev '@[0-9a-f]{40}([[:space:]]|$)' >/dev/null 2>&1; then
    blocker "external GitHub Action is not pinned to a full commit SHA in $workflow."
  fi
done

if [ "$failed" -ne 0 ]; then exit 1; fi
echo 'Static publication-readiness checks passed. Complete docs/RELEASE_CHECKLIST.md manually.'
