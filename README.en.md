# QR Repair

English | [日本語](README.md)

QR Repair is an Android application for analyzing and recovering damaged QR Code Model 2 symbols that conventional readers cannot decode. It lets the user assist with geometry, tone thresholds, finder patterns, and individual cells while inspecting the evidence behind the recovered result.

Recovery does not guarantee correctness. Independently verify important recovered information.

For compactness, the UI uses “Finder” for a finder pattern, “cell” for a module, and “Format” for format information.

## Features

- Image-file import and camera capture
- Strict QR matrix text import using only `0 / 1 / ? / *`
- Rotation, crop, corner, tone, and QR version adjustment
- Manual Finder-role assignment with local position refinement
- Cell editing with `0 / 1 / ? / *`, Undo, and Redo
- Recovery using QR structure and error correction
- Binary, hexadecimal, and decoded-content inspection
- Diagnostic mode/count and the confirmed leading content in numeric, alphanumeric, byte, and Kanji modes when full decoding fails
- Per-cell repair explanations and same-codeword highlighting
- Recovered-image export
- Work history and session storage
- Offline, on-device processing

The supported symbol family is QR Code Model 2, versions 1 through 40. Model 1, Micro QR, and rMQR are unsupported.

## Basic workflow

1. Load an image, capture one with the camera, or import a QR matrix text file containing `0 / 1 / ? / *`.
2. Rotate the image and crop the QR region when necessary.
3. Review the estimated corners, version, and two-/three-class tone thresholds; adjust them if needed. Colored-unknown detection can be enabled in basic settings when needed and starts off.
4. If the QR orientation is wrong, manually assign its top-left, top-right, and bottom-left Finder roles.
5. Adjust cells and search options in advanced settings when needed.
6. Start recovery and inspect the decoded content and per-cell explanations.

Use **Make manual / Restore image** in the cell editor to switch the selected cells' source. Entering an unchanged 0/1 does not make it manual. Once a value is changed, it remains manual until Restore image or Undo removes the override. The control shows the current source and value alongside the value sampled from the current image settings. Multiple-cell selections are supported. In the regular editor (including its 5x magnifier) and the large editor, cyan squares identify manual cells and yellow circles identify selected cells. Manual markers remain visible when the value matches the image or the cell is selected.

The search order, constraints, Reed–Solomon processing, and candidate-validation rules are collected in [Recovery Strategy](docs/RECOVERY_STRATEGY.en.md).

Choose Classic or Modern under Menu → Appearance (next launch), then save. Classic is the default. The choice takes effect after exiting and launching the app again, and persists until changed. Rotation and language changes keep the active style. Modern uses a dark background, lime accents, and a typographic “QR Repair” title.

In Modern, resume previous work from Home and navigate with the 01 Repair settings / 02 Edit cells / 03 Result tabs. The result tab is enabled when a result exists; switching tabs preserves the draft and undo history. The settings and cell-editor footer contains Home, a page switch, Data, Save, and Repair. Save stores the draft without repairing; Repair saves and then runs recovery. Home asks before discarding unsaved changes. Workspace offers import choices when no image is loaded. Image previews are square, and manual-source controls sit below the brushes. Highlight manual cells only changes overlays without changing classification counts or edit history. The large editor keeps its counts, Undo, Redo, and Close at the bottom. Data uses a shared popup, and Show detailed results opens repair evidence. Saved work lists sessions; Operation history shows the current editing history.

## Local build

Requirements:

- JDK 17
- Android SDK with compile/target SDK 37
- Android API 26 or newer

```bash
bash bootstrap-wrapper.sh
bash scripts/verify-wrapper.sh
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest :androidApp:lint :androidApp:assembleDebug
```

The debug APK is written to `build/androidApp/outputs/apk/debug/androidApp-debug.apk`.

The permanent application ID is `io.github.earthdiver.qrrepair`. Release signing, store identifiers, and a public privacy-policy URL are not included. See the [release checklist](docs/RELEASE_CHECKLIST.md) before publication.

## Documentation

- [Recovery strategy](docs/RECOVERY_STRATEGY.en.md)
- [Synthetic geometry-search comparison (Japanese)](docs/GEOMETRY_SEARCH_STUDY.ja.md)
- [Understanding recovery result fields](docs/RECOVERY_RESULT_FIELDS.en.md)
- [Understanding decode and repair reasons](docs/REPAIR_REASONS.en.md)
- [Kotlin Multiplatform boundary](docs/KMP_MIGRATION.md)
- [iOS integration plan](iosApp/README.md)
- [Privacy](PRIVACY.md)
- [Security](SECURITY.md)
- [Third-party licenses](THIRD_PARTY_NOTICES.md)

## License

This project is licensed under the [Apache License 2.0](LICENSE). See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and `licenses/` for copyright and dependency-license information.

The recovery approach was inspired by Robert Xiao’s “MMA CTF 2015 - QR Code Recovery” write-up, but its prose, images, and code are not reproduced; the implementation was independently developed. “QR Code” is a registered trademark of DENSO WAVE INCORPORATED. This project is not affiliated with or endorsed by DENSO WAVE INCORPORATED.
