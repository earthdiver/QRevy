# Study-only dependency review

Reviewed on 2026-09-17. These dependencies belong to the independent benchmark project,
not the Android/iOS product. No downloaded library source or binary is checked in.
No app runtime dependency, permission, publication notice or signing configuration changes.

| Component | Pinned version | Upstream license / review |
|---|---|---|
| Java ZXing | 3.5.4 | Apache-2.0; same version as the app |
| BoofCV | 1.1.4 | Apache-2.0; includes transitive components below |
| quirc | `927d680904dc95fdff4cd9d022eb374b438ff8f2` | ISC; copyright/permission in upstream LICENSE retained in downloaded checkout |
| ZXing-C++ Python wheel | 3.1.1 | Apache-2.0; installed license file inspected |
| OpenCV headless wheel | 4.12.0.88 | Apache-2.0 metadata; ships additional `LICENSE-3RD-PARTY.txt` |
| Gson | 2.13.1 | Apache-2.0 |
| Kotlin runtime | 2.4.10 | Apache-2.0 |
| NumPy | 2.2.6 | BSD; binary distribution contains further notices |
| Pillow | 11.3.0 | MIT-CMU; installed license file inspected |
| Python qrcode | 8.2 | BSD metadata; generated synthetic fixtures only |

The full resolved JVM runtime is recorded in `gradle.lockfile` and the published
POM/parent-POM license declarations are preserved in `maven-licenses.json`.
Most dependencies declare Apache-2.0. Exceptions found:

- `net.sf.trove4j:trove4j:3.0.3`: **LGPL-2.1**. BoofCV's Apache-2.0 label does not
  describe its entire transitive dependency graph.
- `com.google.protobuf:protobuf-java:3.23.2`: BSD-3-Clause.

BoofCV recognition pulls DeepBoof, archive and numerical packages as well as the QR
implementation. The untrimmed JVM distribution size is a development measurement,
not an Android packaging forecast. Before product integration, measure a minimal
configuration and review the licenses of the components actually distributed.

This is a dependency/license inventory, not a vulnerability or store-compliance audit.
The wheel's bundled third-party components have not been approved for redistribution
inside QR Repair. Installing the study environment retains upstream license files.

Primary sources:

- https://github.com/dlbeer/quirc/blob/927d680904dc95fdff4cd9d022eb374b438ff8f2/LICENSE
- https://repo.maven.apache.org/maven2/org/boofcv/boofcv-recognition/1.1.4/boofcv-recognition-1.1.4.pom
- https://repo.maven.apache.org/maven2/net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3.pom
- https://github.com/zxing-cpp/zxing-cpp
- https://github.com/opencv/opencv-python
