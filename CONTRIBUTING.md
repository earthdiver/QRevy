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

Use Python 3.10 or newer to select checks for the complete local change set:

```bash
python3 -B scripts/check-changes.py --dry-run
python3 -B scripts/check-changes.py
```

The default includes staged, unstaged, and untracked files. To include committed work, use `--base <ref>`; changes are measured from the merge base with that ref, plus all local edits. For example, `--base HEAD~1` includes the most recent commit. An empty working tree without `--base` selects no tests.

The script prints each changed path, its classification, and the commands it will run. Renames include both the old and new paths. An invalid base or unresolved merge conflict stops validation.

| Changed area | Selected checks |
| --- | --- |
| Root/docs/iOS/template Markdown, except validation-policy files | Whitespace checks; manually review content, links, and paired translations |
| Android Kotlin in the application package or its `ui`/`session` subpackages, their tests, and display resources | Android app scope, lint, debug build |
| `shared/src/commonTest/` only | All shared JVM tests |
| Recovery core or its tests/fixtures, shared production code, build/dependency/CI/check configuration, manifest/backup rules, or anything unclassified | Full development validation |

The app scope runs all Android unit tests except the exhaustive geometry displacement sweep and expanded width/rotation study. It retains 48 default-width geometry recovery cases, coordinate checks, the manual-bit comparison, UI integration, session compatibility, and source-image validation. The script combines scopes for mixed changes and selects full validation if any path requires it. The rules use repository areas, not a computed call graph; choose full validation when a change affects dependencies beyond its area.

For intermediate feedback while editing recovery code:

```bash
python3 -B scripts/check-changes.py --quick
```

Quick feedback runs the development checks even on a clean tree and omits only the expanded width/rotation study. It keeps the displacement sweep, all 48 default-width cases, and all other checks. Use `--dry-run` to preview it. Before submitting, run without `--quick` using the appropriate base, or use `--full`; quick feedback does not satisfy final validation. `--quick` and `--full` are mutually exclusive.

For full development validation, including on a clean working tree:

```bash
python3 -B scripts/check-changes.py --full
```

Full validation verifies the wrapper and shared boundary, runs Python script regressions, shared JVM tests, all Android unit tests, lint, and a debug build. On macOS it also builds the shared XCFramework. Android CI and extended recovery fuzz always run the full suites. Direct Gradle test runs default to full coverage; the script selects `-Pqr.testScope=app` for app changes and `-Pqr.testScope=quick` for intermediate feedback.

During development, individual tests can also be selected with Gradle, for example:

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ButtonLayoutTest'
```

For release-affecting changes, run full development validation and also:

```bash
./gradlew :androidApp:assembleRelease :androidApp:bundleRelease
./scripts/check-publication-readiness.sh
./scripts/release-audit.sh
```

The release audit forces full test coverage. Describe the selected scope and base reference in the pull request so reviewers can distinguish reduced checks from a full run.

## Pull requests

Describe what changed, why it changed, recovery/correctness impact, tests run, and remaining limitations. Complete the repository pull-request template. Screenshots and fixtures must contain only synthetic, non-sensitive data.

External contributors are not required by this document to sign every commit. Commits and release tags created on behalf of a project maintainer use cryptographic signatures under the maintainer workflow in [AGENTS.md](AGENTS.md).

## Security and sensitive reports

Do not open a public issue containing a security vulnerability, real QR payload, source image, or QRR session. Follow [SECURITY.md](SECURITY.md) and use private vulnerability reporting once it is enabled.
