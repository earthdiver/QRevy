# Security

English | [Japanese](SECURITY.ja.md)

A recovered QR code is not proof that its content matches the original. Independently verify important information before using it.

## Saved files

QR Repair checks saved work before loading it, including file size, content integrity, settings, and cell values. Invalid work files are set aside rather than loaded.

Source images are checked separately for integrity, dimensions, and file size. Missing images and images that fail these checks are not used. Valid work history and QR matrices can still be opened without the source image.

These checks do not encrypt the files or prevent tampering if an attacker gains control of the device or app. See [Privacy](PRIVACY.md) for storage, deletion, backup exclusions, and exports, and the [User Guide](docs/USER_GUIDE.en.md#7-save-resume-and-export) for saved-work compatibility.

## Reporting a vulnerability

If GitHub private vulnerability reporting is available under this repository's **Security** tab, use it to report a suspected security or privacy vulnerability. Include the affected version or commit, the impact, and safe reproduction steps. If no private reporting channel is available, do not post vulnerability details publicly.

QR images, recovered content, saved work, and diagnostic output can contain passwords or personal information. Do not include sensitive material in public reports. Use an independently generated synthetic QR code to demonstrate the issue when possible.

Ordinary bugs that do not expose a vulnerability or sensitive data may be reported in a GitHub issue. Include the app version, expected behavior, actual behavior, and reproduction steps.
