# iosApp

QR Repair does not yet ship a production Xcode project. The shared recovery primitives are exported as `QRRepairCore.xcframework`; this directory records the intended SwiftUI integration boundary and contains small illustrative bootstrap sources. `Bootstrap/` is not an Xcode project.

## Next milestone

1. Create a SwiftUI application and unit-test target in Xcode.
2. Use a bundle identifier and Apple Team ID supplied by the publisher.
3. Link `QRRepairCore.xcframework` from `build/shared/XCFrameworks/release/`, or adopt Gradle direct integration later.
4. Call `QRRepairCoreApi.reedSolomonSelfTest()` from an iOS smoke test.
5. Add `PhotosPicker` image import and grayscale conversion.
6. Implement the first recovery screen using shared geometry, finder, and Reed–Solomon primitives.
7. Test rotation, large and corrupt images, and process relaunch in Simulator.
8. Add AVFoundation camera support only after the Simulator and unit-test baseline is green.
9. Produce and upload one manually signed archive before automating TestFlight.

## Signing and release boundary

Apple signing and App Store Connect credentials must not be exposed to normal development jobs. TestFlight credentials belong only in a protected deployment environment or workflow. The publisher must supply the Apple Team ID, bundle identifier, App Store Connect app record, signing strategy, and public Privacy Policy URL.

The intended progression is an unsigned Simulator build and tests, followed by a manually signed archive and TestFlight baseline, and only then protected automated distribution through Xcode Cloud or a dedicated GitHub Actions workflow.
