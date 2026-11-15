# Recovery example: priority search, single-segment terminator and padding forcing, and list decoding

English | [Japanese](README.ja.md)

| Before repair | After repair | Repaired cells |
| --- | --- | --- |
| ![Missing cells and two incorrect bits](before.png) | ![Recovered QR](after.png) | ![Filled cells in blue and corrected cells in orange](details.png) |

This independently generated QR has 53 unknown cells out of 441 (12.0%) and two incorrect known data bits. With the default 5% data/ECC limit, the [recorded result](result.json) contains two candidates:

```text
QRevy repaired.
QBefy repair`d.
```

The first matches every module of the known original and changes two known bits; the second changes six known bits. The images show the first candidate. In the details image, blue identifies the 53 filled cells and orange identifies the two corrected known cells. The changed known cells are column 20 / row 10 and column 18 / row 17. Successful error correction and QR structure validation alone do not establish which candidate is correct.

## Try it

Import [before.txt](before.txt) using **Import text matrix**. Enable **Force single-segment terminator and padding**, keep the existing `*` mark, check the Finder roles and run **Repair**. The version is 1 and is obtained from the input. ECC and mask remain automatic; the recovered QR uses ECC L and mask 0.

All other recovery settings keep their initial values:

| Setting | Value |
| --- | --- |
| Format bit error limit | Default (6 bits) |
| Known data/ECC bit error limit | Default (5%, rounded up to 11 bits for version 1) |
| Priority search cell limit | 4; this input uses only 1 |
| Partial-bit search / dimension limit | 12 bits |
| List-decoding extension limit | +1 symbol per RS block |
| Known-bit error search | Off |

You can also import [before.png](before.png) as an image. Keep automatic thresholds and the initialized outline, version and orientation. In **Cell editing**, select the bottom-row cell immediately left of the rightmost cell (column 20, row 21, counting from 1) and mark it `*`. Enable **Force single-segment terminator and padding**, confirm the Finder roles if needed, then run **Repair**. See the [User Guide](../../USER_GUIDE.en.md) for the controls.

## Why the three methods are needed to recover the original

The priority cell is an unknown mode-header bit. Its tentative assignment allows single-segment terminator and padding forcing; this is a hypothesis, not an image observation. Forcing fills 20 missing terminator/alignment/padding bits. Four whole ECC codewords remain erased, and two payload codewords each contain one incorrect bit. Ordinary errors-and-erasures RS needs `2 × 2 + 4 = 8` ECC symbols, exceeding this QR's 7. Wu list decoding recovers the original at radius 2, one symbol beyond the ordinary radius of 1, while respecting the default 11-bit mismatch cap. Keep this default; the example does not require a unique candidate.

### Comparison results

The recorded controls use the same input and default limits, with known-bit error search **off**:

| Condition | TEXT candidates | PNG candidates |
| --- | --- | --- |
| Priority + single-segment terminator and padding forcing + list decoding | 2, including the original | 2, including the original |
| Force single-segment terminator and padding off | 5, no decoded text or original match | 5, no decoded text or original match |
| List stages deliberately disabled | 0 | 0 |
| Priority mark removed | 0 | 0 |

Without single-segment forcing, all five candidates pass QR structure validation, but their text cannot be decoded and none matches the original. The result JSON records structure validation and decoded-text counts separately, together with the complete data bytes and correction records.

## Alternative steps: known-bit error search

For the TEXT input, leave **Force single-segment terminator and padding** off, set **Known-bit error search** to **up to 2 bits** in Detailed settings. The existing `*` mark can remain as it is. Leave the known data/ECC error limit at **Default** (11 bits for version 1). Run **Repair**. This produces a candidate matching the original QR and containing `QRevy repaired.` without forcing the suffix.

This route searches for errors in known bits and solves RS bit constraints. Conventional errors-and-erasures RS alone cannot recover the input: eight codewords contain unknown bits, exceeding its seven ECC symbols. With known-bit error search enabled, the original candidate is recovered even when list decoding is disabled. Keeping or removing the `*` mark yields the same result.

The result is not unique: another text candidate, `QBefY repaired.y`, and additional candidates without decoded text are also produced. Compare the candidates; successful error correction alone does not establish which content is correct.

The recorded images, result JSON and comparison table above describe the priority-search and list-decoding procedure with known-bit error search **off**. They are not measurements of these alternative steps.
