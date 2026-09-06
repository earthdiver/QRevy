# Third-party notices

This project uses the following third-party components.

> **Release-audit note:** The entries below cover direct runtime dependencies and known test dependencies in this source tree. Android/Gradle may resolve additional transitive runtime artifacts. Before distributing an APK/AAB, generate the actual `releaseRuntimeClasspath` and reconcile every resolved artifact and required NOTICE/license text. `scripts/release-audit.sh` and CI produce that dependency report; this hand-maintained file must not be treated as exhaustive until that gate has passed.

## Runtime dependencies included in the Android application

### ZXing core 3.5.4

- Artifact: `com.google.zxing:core:3.5.4`
- License: Apache License 2.0
- Upstream: https://github.com/zxing/zxing

ZXing upstream NOTICE (release `zxing-3.5.4`):

> NOTICES FOR BARCODE4J
>
> Barcode4J
> Copyright 2002-2010 Jeremias Märki
> Copyright 2005-2006 Dietmar Bürkle
>
> Portions of this software were contributed under section 5 of the
> Apache License. Contributors are listed under:
> http://barcode4j.sourceforge.net/contributors.html
>
> NOTICES FOR JCOMMANDER
>
> Copyright 2010 Cedric Beust cedric@beust.com

### AndroidX Activity / Core / ExifInterface / CameraX

- Artifacts: `androidx.activity:activity-ktx`, `androidx.core:core`, `androidx.exifinterface:exifinterface`, `androidx.camera:*`
- License: Apache License 2.0
- Upstream: https://github.com/androidx/androidx

A copy of Apache License 2.0 is included at `licenses/Apache-2.0.txt`.

### Kotlin / Kotlin Multiplatform 2.4.10

- Component: Kotlin standard/native runtime and Kotlin Multiplatform framework output used by `shared`
- License: Apache License 2.0
- Upstream: https://github.com/JetBrains/kotlin
- Kotlin/Native runtime code may be included in `QRRepairCore.xcframework`.

A copy of Apache License 2.0 is included at `licenses/Apache-2.0.txt`.

## Build/test-only dependencies

### Robolectric 4.16.1

- Artifact: `org.robolectric:robolectric:4.16.1`
- License: primarily MIT License; a subset of upstream files is Apache License 2.0
- Upstream: https://github.com/robolectric/robolectric
- Scope: Android unit tests only; it is not packaged into the application APK/AAB.
- License copies: `licenses/MIT-Robolectric.txt`, `licenses/Apache-2.0.txt`

Robolectric has transitive test dependencies with their own license obligations. The resolved test dependency graph must be reviewed separately when test artifacts or source distributions are published.

### JUnit 4.13.2

- Artifact: `junit:junit:4.13.2`
- License: Eclipse Public License 1.0
- Upstream license: https://junit.org/junit4/license.html
- Scope: unit tests only; it is not packaged into the application APK/AAB.
- License copy: `licenses/EPL-1.0.txt`

### Hamcrest Core 1.3

- Artifact: `org.hamcrest:hamcrest-core:1.3` (transitive test dependency of JUnit 4.13.2)
- License: BSD 3-Clause / New BSD License
- Scope: unit tests only; it is not packaged into the application APK/AAB.
- License copy: `licenses/BSD-3-Clause-Hamcrest.txt`

### Gradle Wrapper/bootstrap

Gradle-generated wrapper scripts and wrapper components are licensed under Apache License 2.0. The repository pins Gradle 9.5.0 and verifies the official distribution SHA-256 before bootstrapping.

## QR Code trademark

“QR Code” is a registered trademark of DENSO WAVE INCORPORATED. This project is not affiliated with or endorsed by DENSO WAVE INCORPORATED.

## Reference material

The recovery approach was informed by Robert Xiao's write-up “MMA CTF 2015 - QR Code Recovery”. The project does not reproduce the article text or images and implements the recovery logic independently in Kotlin.
