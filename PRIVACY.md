# Privacy

English | [Japanese](PRIVACY.ja.md)

QR Repair is designed for local, on-device processing.

| Data | When it exists | Storage or destination | Removal or retention |
| --- | --- | --- | --- |
| Imported image | While editing; persisted only when a repair session is saved | App-private `noBackupFilesDir` as a lossless content-addressed PNG | Removed with the saved session through the app's deletion flow |
| Camera hand-off JPEG | Between camera capture and import | App-private cache | Deleted after import or failure; stale matching files are purged on next startup |
| QRR repair session | After the user saves work | App-private `noBackupFilesDir` | Retained until the user deletes the saved work or app data |
| Recovered image or other export | Only after an explicit export action | Destination selected or authorized by the user | Controlled by the destination rather than QR Repair's private storage |

- The app does not request the Internet permission.
- Repair sessions and source images are stored under Android's app-private `noBackupFilesDir`. Imported/captured source images are persisted as lossless, content-addressed PNG files only when a repair session is saved.
- Legacy version 0.5 sessions previously stored under app-private `filesDir` are migrated to the no-backup area on a best-effort basis when opened.
- Automatic Android cloud backup and device-transfer backup are disabled/excluded because QR content may contain sensitive data such as Wi-Fi credentials, contact information, authentication URLs, or other private text. Manifest backup settings and XML exclusion rules remain defense-in-depth in addition to the no-backup storage location.
- Recovered URLs are never opened automatically.
- Recovered text is displayed and compared without masking or redaction. Copying content preserves the complete recovered text.
- Camera capture uses a private cache JPEG only as a hand-off between activities; the app deletes that temporary file after decoding (including failure paths) and purges stale `qr-capture-*.jpg` files on the next app start after an interrupted/crashed hand-off.
- Results leave app-private storage only when the user explicitly exports/saves them.

If telemetry, crash reporting, advertising, cloud sync, sharing SDKs, or any network feature is added in the future, this document and the store privacy disclosures must be updated before release.

GZIP compression and SHA-256 integrity checks used by session storage do not encrypt QR content. Device-level and operating-system protections remain relevant to saved sensitive data.
