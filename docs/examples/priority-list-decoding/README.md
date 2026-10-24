# Recovery example: priority search, single-segment terminator and padding forcing, and list decoding

English | [Japanese](README.ja.md)

| Before repair | After repair | Repaired cells |
| --- | --- | --- |
| ![Missing cells and two incorrect bits](before.png) | ![Recovered QR](after.png) | ![Filled cells in blue and corrected cells in orange](details.png) |

This independently generated QR has 53 unknown cells out of 441 (12.0%) and two incorrect known data bits. The [recorded result](result.json) contains one candidate matching every module of the original QR and this text:

```text
QRevy repaired.
```

## Try it

Import [before.txt](before.txt) using **Import text matrix**. Enable **Force single-segment terminator and padding**, keep the existing `*` mark, check the Finder roles and run **Repair**. The version is 1 and is obtained from the input. ECC and mask remain automatic; the recovered QR uses ECC L and mask 0.

All other recovery settings keep their initial values:

| Setting | Value |
| --- | --- |
| Format bit error limit | Automatic (6 bits) |
| Known data/ECC bit error limit | Automatic (2 bits for version 1) |
| Priority search cell limit | 4; this input uses only 1 |
| Partial-bit search / dimension limit | 12 bits |
| List-decoding extension limit | +1 symbol per RS block |

You can also import [before.png](before.png) as an image. Keep automatic thresholds and the initialized outline, version and orientation. In **Cell editing**, select the bottom-row cell immediately left of the rightmost cell (column 20, row 21, counting from 1) and mark it `*`. Enable **Force single-segment terminator and padding**, confirm the Finder roles if needed, then run **Repair**. See the [User Guide](../../USER_GUIDE.en.md) for the controls.

In the details image, blue identifies the 53 filled cells and orange identifies the two corrected known cells. The changed known cells are column 20 / row 10 and column 18 / row 17.

## Why the three methods are needed

The priority cell is an unknown mode-header bit. Its tentative assignment allows single-segment terminator and padding forcing; this is a hypothesis, not an image observation. Forcing fills 20 missing terminator/alignment/padding bits. Four whole ECC codewords remain erased, and two payload codewords each contain one incorrect bit. Ordinary errors-and-erasures RS needs `2 × 2 + 4 = 8` ECC symbols, exceeding this QR's 7. Wu list decoding recovers the original at radius 2, one symbol beyond the ordinary radius of 1, while respecting the default two-bit mismatch cap.

The recorded controls use the same input and default limits:

| Condition | TEXT candidates | PNG candidates |
| --- | --- | --- |
| Priority + single-segment terminator and padding forcing + list decoding | 1, matching the original | 1, matching the original |
| Force single-segment terminator and padding off | 0 | 0 |
| List stages deliberately disabled | 0 | 0 |
| Priority mark removed | 0 | 0 |
