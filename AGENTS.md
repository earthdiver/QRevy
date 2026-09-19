# AGENTS.md — QR Repair

## Mission
Maintain and extend QR Repair, an offline Android/iOS-oriented tool for recovering damaged QR codes while preserving explainability, reproducibility, and user control.

## Repository layout
- `androidApp/`: Android application, CameraX UI, ZXing adapter, session persistence, Android-specific tests.
- `shared/`: Kotlin Multiplatform recovery primitives. `commonMain` must stay free of Android APIs, JVM-only APIs, and ZXing.
- `iosApp/`: iOS handoff/bootstrap material. A real SwiftUI Xcode project is intentionally a later milestone.
- `.github/workflows/`: pinned-SHA CI for Android and KMP artifacts.
- `scripts/`: publication, wrapper, and dependency-audit checks.

## Non-negotiable correctness rules
- Never treat Reed-Solomon success alone as proof that recovery is correct.
- Preserve provenance for inferred or repaired modules.
- Preserve manual `0 / 1 / ?` edits and hard FIXED constraints.
- Fixed cells are protected constraints unless the user explicitly permits conflicting recovery candidates. Explicit ECC/mask selections and the selected version/logical orientation remain enforced separately.
- Keep one-finder recovery attempts available even when additional finder-like candidates exist.
- Do not silently reduce validation of QRR session files, backup restrictions, or source-image integrity.
- Confidence scores are explanatory heuristics, not statistical probabilities.

## Shared-core boundary
Code in `shared/src/commonMain` must not import:
- `android.*`
- `java.*` / `javax.*`
- `com.google.zxing.*`
- Apple platform frameworks directly

If shared logic needs a platform service, define a small common interface and implement it outside `commonMain`.

Current shared components include:
- QR matrix and geometry types
- luminance image representation
- finder detection
- homography / geometry fitting
- GF(256) Reed-Solomon errors+erasures
- partial-codeword search
- payload-structure constraint propagation

ZXing-backed QR version/format/block interleave and final payload decode remain in `androidApp` until replaced with common implementations.

## Required checks before completing a code change
Run the narrowest relevant checks while developing. Before handing off a substantial change, run:

```bash
./bootstrap-wrapper.sh
./scripts/verify-wrapper.sh
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleDebug
```

For release-affecting changes also run:

```bash
./gradlew :androidApp:assembleRelease :androidApp:bundleRelease
./scripts/check-publication-readiness.sh
./scripts/release-audit.sh
```

On macOS, shared iOS compatibility must also pass:

```bash
./gradlew :shared:assembleXCFramework
```

## CI rules
- Keep third-party GitHub Actions pinned to full 40-character commit SHAs.
- Do not add unverified wrapper binaries.
- Do not add release-signing secrets to source or Codex instructions.
- Android CI must continue producing a debug APK artifact.
- KMP CI must continue producing `QRRepairCore.xcframework` on macOS.

## Privacy / security
- The app is offline by default. Do not add `INTERNET` permission without explicit approval and a privacy review.
- Repair sessions belong in Android `noBackupFilesDir`.
- Camera temporary files must be deleted after import and purged on startup.
- Do not store user-attached images in the repository, including copies, crops, resized or converted derivatives, or encoded/embedded image data.
- Do not commit user-specific attachment filenames or local filesystem paths. External-image diagnostics must be explicitly opt-in and receive their path at runtime.
- Regression tests derived from user-attached images must use independently generated synthetic fixtures, not the original image data.
- URLs recovered from QR payloads must never open automatically.
- Display and compare recovered text without masking or redacting its contents; copying must preserve the complete recovered text.

## Licensing
- Preserve `LICENSE`, `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, and `SECURITY.md`.
- Do not copy prose, images, or code from Robert Xiao's article. The recovery technique may be independently implemented and cited as inspiration.
- Re-run dependency/license review after changing dependencies.

## Language policy
- English is the default language for repository content, including documentation, code comments, identifiers, commit messages, pull requests, tags, and release notes.
- Japanese is allowed in documentation only in files whose names end in `.ja.md`.
- `README.md` is the default English README; `README.ja.md` is its Japanese counterpart.
- Product localization resources, user-facing Japanese strings, and tests that verify Japanese localization are exempt from the English-only rule.
- Keep paired English and Japanese documentation aligned when behavior or user guidance changes.

## Documentation and test scope
- Describe current behavior and UI, not the sequence of past implementation changes. Remove obsolete transition notes and redundant interaction details when updating guidance.
- Use product terminology in documentation rather than internal enum names or implementation identifiers.
- Name and structure tests around current observable behavior and supported guarantees. Keep compatibility and validation tests for formats and inputs the application still supports; remove obsolete UI assumptions rather than preserving change history in tests.
- Keep release history in release notes instead of embedding it in current usage guidance.

## Publication blockers
The permanent package/namespace is `io.github.earthdiver.qrrepair`. Do not invent signing credentials, store identifiers, or a public privacy-policy URL; those values must be supplied by a project maintainer before store release.

## Collaboration and maintainer signing
- If a user instruction is ambiguous, ask the user to clarify it before proceeding instead of silently choosing an assumption.
- Commits and tags created on behalf of a project maintainer must use cryptographic signatures (`git commit -S` and `git tag -s`). This does not impose a signing requirement on external contributors unless the repository contribution policy says otherwise.
- Before creating the first signed commit or tag in a working session, if the signing-key passphrase is not already cached, ask the maintainer to unlock the key and prime the passphrase cache in their own terminal. Never ask anyone to disclose a passphrase or store signing credentials, key identifiers, or private publication details in the repository or its instructions.

## Preferred change style
- Small, reviewable commits/PRs.
- Add regression tests for every bug fix.
- Avoid broad rewrites of recovery math unless a failing test demonstrates the need.
- Keep Android behavior unchanged while extracting code to `shared`.
