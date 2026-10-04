# Recovery example: multiple bullet-like holes

| Before repair | After repair | Filled cells |
| --- | --- | --- |
| ![QR with nine irregular holes](before.png) | ![Actual recovery result](after.png) | ![Filled cells highlighted in blue](details.png) |

**A synthetic QR with 56.8% missing yields one candidate matching the original.**

```text
QR Repair: recovered QR.
```

Settings: Version 3 / M [TEXT matrix](before.txt); single-segment assumption on; four mode bits and one byte-count bit marked `*`; zero allowed data/ECC bit differences; partial-bit search limit 12.

Of 841 cells, 478 are unknown: 145 function cells and 333 data/ECC cells. Missing cells are counted at module centers within the symbol. Before illustrates the damage; Filled cells visualizes the result. After is rendered from actual corrected codewords and verified against the full original matrix and payload. ZXing-C++ and ZXing Java cannot decode the before image but both decode the result.

This synthetic example runs the current core through a desktop evaluation harness. Some calculations remain unresolved, so one returned candidate is not proof of mathematical uniqueness.

[Results and generation parameters](result.json) · [Input matrix visualization](input-matrix.png) · [Generator](../../../scripts/generate-bullet-hole-example.py)

Generation requires Pillow 11.3.0, qrcode 8.2, zxing-cpp 3.1.1, a separately supplied local QR Repair evaluation executable (`--harness`), and the StandardReader Java classpath (`--java-reader-classpath`). Images and generator code are independently created project assets under the repository license.
