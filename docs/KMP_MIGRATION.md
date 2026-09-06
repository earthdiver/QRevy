# Kotlin Multiplatform migration boundary

This document describes the current boundary between the Kotlin Multiplatform shared core and platform-specific code.

## What moved to commonMain
The following components are now compiled as Kotlin Multiplatform common code:
- `QrTypes.kt`
- `LumaImage.kt`
- `Homography.kt`
- `FinderDetector.kt`
- `GeometryFitter.kt`
- `ReedSolomonErasures.kt`
- `QrBlockModels.kt`
- `PartialCodewordSolver.kt`
- `PayloadConstraintSolver.kt`
- `RsBitConstraintSolver.kt`
- `RecoveryCancellation.kt` (platform-independent cancellation callback)
- `GridPeriodicityEstimator.kt`
- `QrTextMatrixParser.kt`
- `QRRepairCoreApi.kt`

They intentionally have no Android, Java/JVM, ZXing, or Apple-framework imports.

## What remains Android-specific
The following areas are not yet shared because they currently depend on ZXing or Android APIs:
- QR Version / ErrorCorrectionLevel tables
- format decoding and masks tied to ZXing types
- QR block deinterleave metadata obtained from ZXing Version tables
- final payload decoding through `ZxingPayloadBridge`
- CameraX and Android UI
- Android session storage

## Android target
`shared` uses the dedicated `com.android.kotlin.multiplatform.library` plugin for its Android variant and also declares a plain JVM target for fast algorithm tests. Android-specific APIs still do not belong in `commonMain`; the Android target exists only so `androidApp` can consume a native Android library variant cleanly.

## iOS output
The shared module declares device and Apple-silicon simulator frameworks and assembles them into:

`QRRepairCore.xcframework`

CI verifies this on macOS. The framework is the bridge that the future SwiftUI app will import.

## Next extraction order
1. Replace ZXing `ErrorCorrectionLevel` with a common enum and adapter.
2. Move QR Version/EC block tables into common generated/static data.
3. Move format/BCH/mask logic to common.
4. Move block interleave/deinterleave fully to common.
5. Replace `ZxingPayloadBridge` with a common segment parser.
   Extract the existing `StrictQrPayloadValidator` prefix/full-stream validation separately, preserving its rejection rules before replacing final text decoding.
6. Move provenance/guidance/session model portions that have no UI/storage dependencies.

Each extraction must preserve the Android regression suite.
