# Contributing to QR Repair

Thank you for helping improve QR Repair. Contributions should preserve explainability, reproducibility, offline operation, and explicit user control.

## Before opening a change

1. Read [AGENTS.md](AGENTS.md) for correctness, platform-boundary, privacy, licensing, and test requirements.
2. Keep a change focused enough to review and test independently.
3. For a bug fix, add a regression test that fails without the fix.
4. Use independently generated synthetic fixtures. Never commit a user's QR image, derivative image, payload, attachment filename, or local filesystem path.

## Correctness expectations

- Reed–Solomon consistency alone is never proof of a correct recovery.
- Preserve provenance for inferred, corrected, and user-edited modules.
- Preserve manual `0 / 1 / ?` edits and hard constraints.
- Do not silently weaken QRR validation, backup exclusions, source-image integrity checks, or bounded-search diagnostics.
- When changing the QRR schema or supported versions, update both `SECURITY.md` and `SECURITY.ja.md`, both User Guides, and the release notes, and retain compatibility/validation tests for every supported input format.
- Confidence values are explanatory heuristics, not probabilities.

## Documentation changes

Keep paired English/Japanese documents and localized in-app notices aligned with the current UI. Quote actual control labels; use consistent product terms in explanatory prose. Describe selectable, structure-validated candidates separately from candidates decoded as text, and report search completion separately from candidate count.

Maintain dependency versions and license texts in `THIRD_PARTY_NOTICES.md`, then copy it unchanged to `androidApp/src/main/res/raw/third_party_notices.txt`. `NOTICE.md` points to that inventory. Check local links and the relevant resource-display tests after updating app notices.

## Platform boundary

`shared/src/commonMain` must remain free of Android, Java/JVM-only, ZXing, and direct Apple-framework imports. Put platform services behind a small common interface and implement them in the appropriate platform layer. See [Kotlin Multiplatform migration boundary](docs/KMP_MIGRATION.md).

## Validation

Run the narrowest relevant tests while developing. Before submitting a substantial change, run:

```bash
./bootstrap-wrapper.sh
./scripts/verify-wrapper.sh
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleDebug
```

For release-affecting changes, also run:

```bash
./gradlew :androidApp:assembleRelease :androidApp:bundleRelease
./scripts/check-publication-readiness.sh
./scripts/release-audit.sh
```

Shared iOS compatibility must also be checked on macOS with `./gradlew :shared:assembleXCFramework`.

## Pull requests

Describe what changed, why it changed, recovery/correctness impact, tests run, and remaining limitations. Complete the repository pull-request template. Screenshots and fixtures must contain only synthetic, non-sensitive data.

External contributors are not required by this document to sign every commit. Commits and release tags created on behalf of a project maintainer use cryptographic signatures under the maintainer workflow in [AGENTS.md](AGENTS.md).

## Security and sensitive reports

Do not open a public issue containing a security vulnerability, real QR payload, source image, or QRR session. Follow [SECURITY.md](SECURITY.md) and use private vulnerability reporting once it is enabled.
