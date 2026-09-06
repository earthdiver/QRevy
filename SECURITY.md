# Security and privacy notes

QR Repair is designed for on-device processing. The current manifest requests camera access only and does not request Internet access. CameraX capture uses a private cache JPEG only as an activity hand-off; MainActivity deletes that temporary file after decoding on both success and failure paths and purges stale `qr-capture-*.jpg` files on next startup.

## Sensitive data

QR payloads can contain Wi-Fi credentials, contact data, URLs, tokens, or other secrets. Repair sessions therefore remain in internal app storage, Android backup is disabled, backup/data-extraction rules exclude app data, and Wi-Fi passwords are masked in the preview by default.

## Session file hardening

Current sessions use bounded QRR schema 12 with SHA-256 integrity verification and GZIP compression. The decoder retains explicit compatibility with schemas 9 through 11; earlier schemas are rejected. Decoding validates QR dimensions, enums, coordinates, confidence values, geometry values, counts, priority-search markers, preprocessing settings, and total sizes before reconstructing state. Invalid session files are quarantined rather than loaded.

Source images are stored under `noBackupFilesDir` as content-addressed lossless PNG files. The PNG is written and synced first; an atomic `source.ref` pointer is switched only after the content is durable. Restore verifies the pointer format, expected filename, SHA-256, byte-size limit, image dimensions, and pixel count before decoding. Early-v0.6 `source.png + source.sha256` and v0.5 `source.jpg` are read only for migration compatibility.

## Reporting

Do not include sensitive QR payloads or source images in a public bug report. Reproduce with a synthetic QR when possible. If a public repository is created, configure a private security-reporting channel before release.
