# Release checklist

This checklist is a technical release gate, not legal advice.

## Repository publication

- [ ] Confirm the public repository contains only the intended clean history, branches, tags, releases, pull requests, Actions runs, and artifacts.
- [ ] Confirm the repository description, topics, default branch, license detection, and visibility are correct.
- [ ] While the repository is private, prepare the exact post-visibility steps for GitHub private vulnerability reporting. GitHub exposes this setting only for public repositories.
- [ ] Immediately after changing visibility to public, enable and test private vulnerability reporting and verify `SECURITY.md` points to the available channel; do this before announcing the repository or publishing a release.
- [ ] Review repository Actions permissions and confirm no publisher or signing secrets are present in ordinary build environments.
- [ ] Before requiring CI checks on pull requests, add reviewed `pull_request` triggers to the intended workflows and configure `main` protection or a ruleset. The current workflows are manual-only.
- [ ] Review Dependabot pull requests and configuration; close or supersede proposals intentionally rather than merging updates without tests.

## Build identity / signing

- [ ] Confirm `io.github.earthdiver.qrrepair` remains the final `namespace` and `applicationId` before store publication.
- [ ] Set the final app name, icon, versionCode, and versionName.
- [ ] Configure a release signing key outside the repository; never commit keystores or passwords.
- [ ] Build from a clean checkout with JDK 17 and the verified Gradle 9.5.0 wrapper.

## Automated quality gates

- [ ] `./bootstrap-wrapper.sh` and `./scripts/verify-wrapper.sh`; verify the expected Gradle distribution and wrapper-JAR SHA-256 checksums.
- [ ] `./scripts/check-publication-readiness.sh` and `./scripts/check-shared-boundary.sh`.
- [ ] Commit the verified Gradle Wrapper files (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, `gradle-wrapper.properties`) in the public repository.
- [ ] `./gradlew --no-daemon :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:bundleRelease`
- [ ] Review `build/androidApp/reports/` and fix all release-blocking lint/test failures.
- [ ] Manually run the `Extended recovery fuzz` workflow before a public release.
- [ ] Verify `KMP Shared Core` succeeds on macOS and produces `QRRepairCore.xcframework`.
- [ ] Keep `shared/commonMain` free of Android, Java/JVM-only, ZXing, and Apple-framework imports.
- [ ] Verify every external GitHub Action in `.github/workflows/` is pinned to a full 40-character commit SHA; review Dependabot updates before merging.
- [ ] Install the release APK on at least one API 26 device/emulator and one current API 37 device/emulator.
- [ ] Test camera rotation, permission denial, process death, corrupt/truncated session restore, and low-storage save failure.

## Privacy / data handling

- [ ] Keep `INTERNET` permission absent unless the privacy model is intentionally changed.
- [ ] Verify app backup remains disabled, current sessions are under `noBackupFilesDir/qr_sessions`, and backup/data-extraction rules remain defense-in-depth exclusions.
- [ ] Confirm recovered URLs are never opened automatically.
- [ ] Confirm recovered text is displayed and compared without masking or redaction, and copying preserves the complete recovered text.
- [ ] Host `PRIVACY.md` at a stable public HTTPS URL and provide that URL to the app store listing.
- [ ] If telemetry, crash reporting, ads, cloud processing, or sharing SDKs are added, re-review the privacy policy and Data Safety declarations before release.

## Licensing / notices

- [ ] Keep the project `LICENSE` and `THIRD_PARTY_NOTICES.md` in source distributions.
- [ ] Verify the in-app Licenses/Privacy screen renders Apache-2.0 and third-party notices.
- [ ] Generate `releaseRuntimeClasspath` with `scripts/release-audit.sh` and reconcile **every resolved runtime/transitive artifact** with its license/NOTICE obligations before release; do not assume the hand-maintained direct-dependency list is exhaustive.
- [ ] Re-run the license audit whenever dependency versions change. Maintain `THIRD_PARTY_NOTICES.md` as the canonical inventory and synchronize `androidApp/src/main/res/raw/third_party_notices.txt` byte for byte. Keep the paired privacy documents and localized app notices aligned.
- [ ] Keep the QR Code trademark attribution in notices/About materials.
- [ ] Do not copy text, figures, or screenshots from Robert Xiao's article unless separate permission/license permits it; citation/reference is sufficient for the algorithmic inspiration used here.

## Tester-facing documentation

- [ ] Match `RELEASE_NOTE.txt` to the APK version and current changes, including supported saved-work schemas and any compatibility break.
- [ ] Check result messages, settings, and saved-work instructions in both User Guides and result references against the packaged app.
- [ ] For Firebase distribution, review the notes at the workflow commit; they may differ from the selected APK release tag.

## Store publication

- [ ] Supply a public Privacy Policy URL.
- [ ] Complete the current store Data Safety / privacy declarations based on the actual release artifact and SDKs.
- [ ] Confirm package ID and signing key are final; changing either later may affect upgrades/publication continuity.
- [ ] If publishing commercially or under an organization, obtain a final legal/licensing review for that release.
