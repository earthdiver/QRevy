#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -x ./gradlew ] || ./bootstrap-wrapper.sh
./scripts/verify-wrapper.sh
./scripts/check-shared-boundary.sh
mkdir -p build/androidApp/reports build/shared/reports
./gradlew --no-daemon :androidApp:dependencies --configuration releaseRuntimeClasspath > build/androidApp/reports/release-runtime-dependencies.txt
./gradlew --no-daemon :shared:dependencies > build/shared/reports/shared-dependencies.txt
./gradlew --no-daemon :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleRelease :androidApp:bundleRelease
printf '%s\n' "Android dependency report: build/androidApp/reports/release-runtime-dependencies.txt"
printf '%s\n' "Shared dependency report: build/shared/reports/shared-dependencies.txt"
printf '%s\n' "Review THIRD_PARTY_NOTICES.md and docs/RELEASE_CHECKLIST.md before publication."
