# QR Repair

English | [Japanese](README.ja.md)

QR Repair is an Android application for analyzing and recovering damaged QR Code Model 2 symbols that conventional readers cannot decode. It lets the user assist with geometry, tone thresholds, finder patterns, and individual cells while inspecting the evidence behind the recovered result.

Recovery does not guarantee correctness. Independently verify important recovered information.

For compactness, the UI uses “Finder” for a finder pattern, “cell” for a module, and “Format” for format information.

## Current status

| Area | Status |
| --- | --- |
| Android | Builds and tests from source; API 26 and newer |
| iOS | Shared-core XCFramework output and bootstrap examples only; no complete Xcode app |
| Supported QR | QR Code Model 2, versions 1–40 |
| Unsupported | Model 1, Micro QR, and rMQR |
| Distribution | No release-signing configuration or official signed package is provided |
| Network | The Android app does not use the `INTERNET` permission |

## Features

- Image-file import and camera capture
- Strict QR matrix text import using only `0 / 1 / ? / *`
- Rotation, crop, corner, tone, and QR version adjustment
- Initial corner and version estimation using ZXing-C++, grid periodicity, and two Finders
- Automatic Finder positions and roles when image agreement is sufficient, with manual clearing, assignment, and local refinement
- Automatic top-right/bottom-left assignment for two diagonal Finders using both timing axes (version 2 and later; no alignment pattern required); ambiguous roles remain unassigned, and assignments can be cleared manually
- Cell editing with `0 / 1 / ? / *`, Undo, and Redo
- Recovery using QR structure and error correction
- Binary, hexadecimal, and decoded-content inspection
- Diagnostic mode/count and the confirmed leading content in numeric, alphanumeric, byte, and Kanji modes when full decoding fails
- Per-cell repair explanations and same-codeword highlighting
- Recovered-image export
- Work history and session storage
- Diagnostic analysis of additional QR segments after the terminator, including raw tail bits and correction provenance
- Offline, on-device processing

The supported symbol family is QR Code Model 2, versions 1 through 40. Model 1, Micro QR, and rMQR are unsupported.

## Basic workflow

1. Load an image, capture one with the camera, or import a QR matrix text file containing `0 / 1 / ? / *`.
2. Rotate the image and crop the QR region when necessary.
3. Review the estimated corners, version, and two-/three-class tone thresholds; adjust them if needed. Colored-unknown detection can be enabled in basic settings when needed and starts off.
4. Review Finder positions and roles, which are assigned automatically when image agreement is sufficient. To correct an assignment, press **Clear**, tap the green Finder, and assign the intended role.
5. Adjust cells and search options in advanced settings when needed.
6. Start recovery and inspect the decoded content and per-cell explanations.

See the [User Guide](docs/USER_GUIDE.en.md) for cell editing, advanced settings, result review, saved work, and Classic/Modern navigation. The search order, constraints, Reed–Solomon processing, and candidate-validation rules are collected in [Recovery Strategy](docs/RECOVERY_STRATEGY.en.md).

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
- [User guide](docs/USER_GUIDE.en.md)
- [Detector library comparison (Japanese)](docs/DETECTOR_COMPARISON.ja.md)
- [Initial grid estimation and automatic Finder assignment (Japanese)](docs/ZXING_CPP_INTEGRATION.ja.md)
- [Synthetic geometry-search comparison](docs/GEOMETRY_SEARCH_STUDY.en.md)
- [Understanding recovery result fields](docs/RECOVERY_RESULT_FIELDS.en.md)
- [Understanding decode and repair reasons](docs/REPAIR_REASONS.en.md)
- [Kotlin Multiplatform boundary](docs/KMP_MIGRATION.md)
- [iOS integration plan](iosApp/README.md)
- [Privacy](PRIVACY.md)
- [Security](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Third-party licenses](THIRD_PARTY_NOTICES.md)

## License

This project is licensed under the [Apache License 2.0](LICENSE). See [NOTICE.md](NOTICE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and `licenses/` for copyright and dependency-license information.

The recovery approach was inspired by Robert Xiao’s “MMA CTF 2015 - QR Code Recovery” write-up, but its prose, images, and code are not reproduced; the implementation was independently developed. “QR Code” is a registered trademark of DENSO WAVE INCORPORATED. This project is not affiliated with or endorsed by DENSO WAVE INCORPORATED.
