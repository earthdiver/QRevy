# Limited APK distribution with Firebase

The `Firebase APK distribution` workflow runs manually in the private upstream repository, from its default branch. It downloads an existing signed APK from a published GitHub Release in that same repository and distributes those exact bytes to a configured Firebase tester group. Prereleases are supported; drafts are rejected. It does not build, sign, create tags or releases, or change repository visibility. Testers need no GitHub access. Documents are summarized in the Firebase release notes, not attached to GitHub Releases.

## First manual distribution

1. Register the Android app `io.github.earthdiver.qrrepair` in Firebase and enable App Distribution. No Firebase SDK or Android network permission is needed for distribution alone.
2. Create the intended tester group and add the testers in Firebase.
3. Complete the applicable [release checks](RELEASE_CHECKLIST.md), sign a non-debuggable release APK outside this workflow, and attach it to a release in private upstream. Keep using the same signing key for updates. The ordinary Android CI produces an **unsigned** release APK; it cannot be distributed as-is.
4. Upload that signed APK in Firebase Console, select the group, and paste the tester-facing notes. Confirm invitation, installation, notes visibility, and an update on a tester device before using Actions.

## Actions configuration

Create a GitHub environment named `firebase-distribution` in private upstream. Restrict deployment to the default branch. Configure these **environment variables**, using your actual values outside this repository:

| Variable | Value |
| --- | --- |
| `FIREBASE_APP_ID` | Firebase Android App ID, not the Android package name |
| `FIREBASE_GROUPS` | Existing group aliases, comma-separated without spaces |
| `APK_SIGNER_SHA256` | SHA-256 certificate fingerprint of the trusted APK signing key; colons are accepted |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full Google Cloud Workload Identity provider resource name |
| `GCP_SERVICE_ACCOUNT` | Email of the service account used for distribution |

Obtain the certificate fingerprint from a trusted locally signed APK with `apksigner verify --print-certs`. This workflow does not need the signing key or its password.

Configure Google Cloud Workload Identity Federation for GitHub Actions and service-account impersonation, following [the official authentication action setup](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account). Restrict the provider condition and impersonation binding to this upstream repository (prefer immutable repository/owner IDs), its default branch, and the `firebase-distribution` environment subject. Do not trust every repository under the GitHub issuer. Grant the federated principal `roles/iam.workloadIdentityUser` on the selected service account. Grant that account `roles/firebaseappdistro.admin` in the target Firebase project and enable the Firebase App Distribution API. This uses short-lived credentials, not an uploaded service-account JSON key. Keep these publishing permissions out of ordinary build jobs.

The workflow uses its read-only `GITHUB_TOKEN` to read the same repository's release asset. No cross-repository token is required. Third-party Actions are pinned to commit SHAs. Firebase CLI 15.30.2 is a build-time MIT-licensed tool installed through npm; it adds no Android runtime dependency. npm resolves its transitive dependencies at installation time, so the CLI install is not a fully locked dependency graph. Review CLI and Action updates before changing pins.

## Run a distribution

Open **Actions → Firebase APK distribution → Run workflow** on the default branch and supply:

- `release_tag`: an existing published upstream release tag.
- `apk_asset`: the exact signed APK filename, without a path or wildcard.
- `release_notes`: optional fallback text, used only when the repository root has no `RELEASE_NOTE.txt`.

For the GitHub web interface, commit a UTF-8 `RELEASE_NOTE.txt` beside `README.md` on upstream's default branch before dispatching. The file takes priority over the input, preserving line breaks (an optional UTF-8 BOM is removed). Leave `release_notes` blank when using this file. Notes come from the workflow commit checked out on the default branch, **not** the selected APK release tag; review them for that APK before running. Empty files, invalid UTF-8, directories, and symbolic links fail before upload instead of silently using fallback text. If neither source contains text, the run fails.

Include changes, usage guidance, test requests, known limitations, and an accessible report route. Do not include private upstream links or internal-only release text. The run summary records which source was used.

When `RELEASE_NOTE.txt` is absent, multiline fallback notes can also be supplied using the GitHub CLI from a checkout of private upstream:

```sh
gh workflow run firebase-distribution.yml \
  -f release_tag=YOUR_EXISTING_TAG \
  -f apk_asset=YOUR_SIGNED_APK_FILENAME.apk \
  -F release_notes=@docs/FIREBASE_RELEASE_NOTES.md
```

Review and tailor the [notes example](FIREBASE_RELEASE_NOTES.md) to the selected APK before each run. You can copy the example to root `RELEASE_NOTE.txt` and edit it before committing. The workflow preserves the selected text; it does not automatically copy the upstream release body. Japanese repository documents must use a `.ja.md` filename.

The workflow checks the release asset digest when GitHub provides it, verifies APK signature and expected signing certificate, requires the QR Repair package name, and rejects debuggable APKs before Firebase authentication. The run summary records the asset ID and APK SHA-256. Existing Android/KMP CI remains separate; this distribution job relies on release checks already performed for the selected APK.

Firebase receives the APK and notes, then grants access to the configured groups. Group membership remains managed in Firebase. A rerun may update notes or redistribute the same build; it is not a rollback or a guarantee of a second notification. A failure can occur after upload or distribution, so check Firebase before retrying. Revoking GitHub access does not revoke Firebase access, and neither removes previously downloaded copies.

See [Firebase CLI distribution](https://firebase.google.com/docs/app-distribution/android/distribute-cli) and [service-account authentication](https://firebase.google.com/docs/app-distribution/authenticate-service-account).
