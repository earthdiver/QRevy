# QRevy

English | [Japanese](README.ja.md)

QRevy is an offline Android app for recovering damaged QR codes that ordinary readers cannot decode. Adjust the image and individual cells, compare recovery candidates, and inspect the reasons for each repair.

Supports Android 8.0 or later and QR Code Model 2, versions 1–40. Model 1, Micro QR, and rMQR are unsupported. This repository does not include a complete iOS app.

The [browser app](webApp/README.md) runs on iPhone, Android, and desktop browsers, with offline use after its initial download.

Recovery does not guarantee that the result matches the original content. Independently verify important information.

## Recovery example

| Before repair | After repair | Filled and corrected cells |
| --- | --- | --- |
| ![Synthetic QR with irregular holes and black damage](docs/examples/bullet-holes/before.png) | ![Recovered QR](docs/examples/bullet-holes/after.png) | ![Filled cells in blue and corrected cells in orange](docs/examples/bullet-holes/details.png) |

This synthetic example has 503 of 841 cells erased (59.8%). See the [image input and steps](docs/examples/bullet-holes/README.md) to try it.

## Get started

1. Import an image or text matrix, or take a photo.
2. Review the reading or automatic-repair result. Quick automatic repair is enabled by default and runs if ordinary reading fails.
3. If needed, open detailed analysis, adjust the grid or individual cells, and run **Repair**. Review the candidates before copying decoded text or exporting a recovered QR image. You can save your work to resume later.

See the [User Guide](docs/USER_GUIDE.en.md) for the steps and settings.

## Build and install

To build from this repository, install JDK 17 (Java Development Kit) and the Android SDK (software development kit), including SDK Platform 37. Then run:

```bash
bash bootstrap-wrapper.sh
bash scripts/verify-wrapper.sh
./gradlew :androidApp:assembleDebug
```

Install the generated Android package (APK), `build/androidApp/outputs/apk/debug/androidApp-debug.apk`, on your device.

## Reference

- [Understanding repair results](docs/RECOVERY_RESULT_FIELDS.en.md)
- [QRevy recovery strategy](docs/RECOVERY_STRATEGY.en.md)
- [Text matrix templates](templates/text-matrices/README.md)
- [Privacy](PRIVACY.md)
- [Security](SECURITY.md)

## License

Licensed under the [Apache License 2.0](LICENSE). See [Notices](NOTICE.md) and [Third-party notices](THIRD_PARTY_NOTICES.md) for attribution, trademarks, and dependency licenses.
