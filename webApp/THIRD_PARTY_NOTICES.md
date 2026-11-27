# Third-party notices

| Component | Version | License | Role |
| --- | --- | --- | --- |
| QRevy | Repository sources | Apache-2.0 | Recovery engine and QRR validation |
| ZXing-C++ | 3.1.1 | Apache-2.0; bundled components retain their licenses | Ordinary QR reading and boundary detection (WebAssembly) |
| Emscripten runtime | 4.0.20 | MIT / NCSA; libc and libc++ retain their licenses | WebAssembly runtime glue |
| ZXing core | 3.5.4 | Apache-2.0 | QR structure, payload codecs, ordinary-reading and verified-geometry fallback |
| Kotlin standard library | 2.4.21 | Apache-2.0 | Compiled runtime |
| TeaVM | 0.16.0 | Apache-2.0 | JVM-to-JavaScript compiler/runtime |
| Playwright | 1.65.0 | Apache-2.0 | Development-only browser tests |

Original license headers remain in the vendored source. The Apache-2.0 license
is in the repository root and is reproduced in the static distribution at licenses/LICENSE. Android-only components mentioned in the upstream
notices (including CameraX) are not shipped by this PWA.
The native reader licenses and runtime notices are bundled under `native/`,
including ZXing third-party, Emscripten, musl, libc++, libc++abi, and compiler-rt notices.
Its version, source revision, and artifact hashes are recorded in `native/manifest.json`.
The PWA has no production npm dependencies, tracking scripts, remote fonts,
analytics, or runtime CDN dependencies.
