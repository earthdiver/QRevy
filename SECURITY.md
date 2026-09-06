# Security and privacy notes

English | [日本語](SECURITY.ja.md)

QR Repair is designed for offline, on-device processing. The current Android manifest requests camera access only and does not request Internet access. Security-sensitive behavior is summarized below; implementation details remain subject to the tests and release checks in this repository.

| Area | Current protection | Important limitation |
| --- | --- | --- |
| Network | No Android `INTERNET` permission | Re-review this document before adding any network-capable SDK or permission |
| Camera hand-off | Private cache JPEG, deleted after success or failure; stale files purged on startup | A crash can leave a temporary file until the next startup purge |
| Saved sessions | App-private `noBackupFilesDir`; Android backup and transfer excluded | Does not protect against a compromised device or app process |
| Session integrity | Bounded QRR schema, SHA-256 integrity check, strict field validation | Integrity checking does not encrypt session contents |
| Recovered content | URLs never open automatically; Wi-Fi passwords are masked by default | Users must still verify recovered content before acting on it |

## Sensitive data

QR payloads can contain Wi-Fi credentials, contact data, URLs, authentication tokens, or other secrets. Repair sessions therefore remain in internal app storage, Android backup is disabled, backup/data-extraction rules exclude app data, and Wi-Fi passwords are masked in the preview by default.

Do not attach a real sensitive QR image, decoded payload, QRR session, application data directory, or unredacted diagnostic output to a public issue. Prefer an independently generated synthetic QR that reproduces the behavior.

## Session file hardening

Current sessions use bounded QRR schema 12 with SHA-256 integrity verification and GZIP compression. The decoder retains explicit compatibility with schemas 9 through 11; earlier schemas are rejected. Decoding validates QR dimensions, enums, coordinates, confidence values, geometry values, counts, priority-search markers, preprocessing settings, and total sizes before reconstructing state. Invalid session files are quarantined rather than loaded.

Source images are stored under `noBackupFilesDir` as content-addressed lossless PNG files. The PNG is written and synced first; an atomic `source.ref` pointer is switched only after the content is durable. Restore verifies the pointer format, expected filename, SHA-256, byte-size limit, image dimensions, and pixel count before decoding. Early-v0.6 `source.png + source.sha256` and v0.5 `source.jpg` are read only for migration compatibility.

GZIP compression is not encryption. A QRR file or exported image must be handled as sensitive whenever its source QR may contain sensitive data.

## Reporting a vulnerability

| Report type | Where to report | Include |
| --- | --- | --- |
| Security or privacy vulnerability | GitHub private vulnerability reporting under the repository's **Security** tab | A concise impact statement, affected version or commit, safe reproduction steps, and a synthetic fixture when possible |
| Ordinary reproducible bug without sensitive data | A normal GitHub issue | Expected and actual behavior, environment, and a synthetic reproduction |
| Question without a reproducible defect | GitHub Discussions or an issue category selected by the maintainers | The goal and relevant non-sensitive context |

GitHub permits repository owners to enable private vulnerability reporting only for public repositories. Prepare this reporting path while the repository is private, then enable and test it immediately after changing visibility and before announcing the repository or publishing a release. Until that channel is available, do not disclose vulnerability details in a public issue. Maintainers should acknowledge a private report before discussing disclosure timing; no fixed response or remediation deadline is promised by this document.

## Supported versions

Before the first public release, only the current `main` branch is maintained. After releases begin, this section must identify which release lines receive security fixes.
