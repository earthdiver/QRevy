# Recovery example: missing cells and incorrect readings

English | [Japanese](README.ja.md)

| Before repair | After repair | Repaired cells |
| --- | --- | --- |
| ![Missing cells and two incorrect bits](before.png) | ![Recovered QR](after.png) | ![Filled cells in blue and corrected cells in orange](details.png) |

This independently generated QR has 53 unknown cells out of 441 (12.0%) and two incorrect known data bits. With the default 5% error limit, the [recorded result](result.json) contains two candidates:

```text
QRevy repaired.
QBefy repair`d.
```

The first matches every cell of the known original and changes two known bits; the second changes six known bits. The images show the first candidate. In the details image, blue identifies the 53 filled cells and orange identifies the two corrected known cells. The changed known cells are column 20 / row 10 and column 18 / row 17. Successful error correction and QR structure validation alone do not establish which candidate is correct.

## Try it

1. Import [before.txt](before.txt) using **Import text matrix**. During Quick automatic repair or after it fails, choose **Detailed analysis**. If it has found a candidate, choose **Analyze in detail**.
2. Enable **Force single-segment terminator and padding** and keep the existing `*` mark. Check the roles of the square Finder patterns. The version is 1 and is obtained from the input; leave **ECC: Auto** and **Mask: Auto** selected.
3. Run repair: **Save & repair** in Classic or **Repair** in Modern. Compare the resulting candidates.

QR codes include error-correction code (**ECC**) data based on **Reed–Solomon (RS)** codes. A codeword, also called a symbol, contains eight bits; an RS block is a group of codewords corrected together. The recovered QR uses ECC L and mask 0. All other recovery settings keep their initial values:

| Setting | Value |
| --- | --- |
| Format-information error limit | Default (6 bits) |
| Known data/ECC bit error limit | Default (5%, rounded up to 11 bits for version 1) |
| Priority search cell limit | 4; this input uses only 1 |
| Partial-bit search / dimension limit | 12 bits |
| List-decoding extension (symbols) | +1 symbol per RS block |
| Known-bit error search | Off |

You can also import [before.png](before.png) as an image and open detailed analysis as in step 1. Keep automatic thresholds and the initialized outline, version and orientation. Open cell editing, select the bottom-row cell immediately left of the rightmost cell (column 20, row 21, counting from 1), and mark it `*`. Enable **Force single-segment terminator and padding**, confirm the Finder roles if needed, then run repair. See the [User Guide](../../USER_GUIDE.en.md) for the controls.

## Alternative steps: known-bit error search

Import the text matrix and open detailed analysis as in step 1. Leave **Force single-segment terminator and padding** off, then choose **Known-bit error search: up to 2 bits** in **Details**. The existing `*` mark can remain. Leave **Known data/ECC bit error limit** at **Default** (11 bits for version 1) and run repair.

This produces a candidate matching the original QR and containing `QRevy repaired.` without forcing the terminator and padding. The result is not unique: another text candidate, `QBefY repaired.y`, and additional candidates without decoded text are also produced. Compare the candidates; successful error correction alone does not establish which content is correct.

## Methods used by these settings

The priority cell is an unknown bit in the mode indicator, which identifies how the content is encoded. Its temporary assignment allows single-segment terminator and padding forcing; this is a hypothesis, not an image observation. Forcing fills 20 missing bits in the terminator, byte-boundary alignment, and padding. Four whole ECC codewords remain erased, and two content codewords each contain one incorrect bit. Ordinary errors-and-erasures RS needs `2 × 2 + 4 = 8` ECC codewords, exceeding this QR's 7.

List decoding searches for candidates beyond the ordinary RS correction range. Here, the Wu method recovers the original at a correction radius of 2 codewords, one beyond the ordinary radius of 1, while respecting the default 11-bit error limit. The radius limits how many non-erased codewords may differ from the values used in that calculation. See the [recovery strategy](../../RECOVERY_STRATEGY.en.md) for these methods.

### Comparison results

These detailed-analysis results use the same input and default limits, with **Known-bit error search** off:

| Condition | Text-matrix candidates | Image candidates |
| --- | --- | --- |
| Priority + single-segment terminator and padding forcing + list decoding | 2, including the original | 2, including the original |
| Force single-segment terminator and padding off | 5, no decoded text or original match | 5, no decoded text or original match |
| List stages deliberately disabled | 0 | 0 |
| Priority mark removed | 0 | 0 |

With **Force single-segment terminator and padding** off, all five candidates pass QR structure validation, but their text cannot be decoded and none matches the original. The [result record](result.json) records structure validation and decoded-text counts separately, together with the complete data bytes and correction records.

### Known-bit error search

This route searches for errors in known bits and solves RS bit constraints. Conventional errors-and-erasures RS alone cannot recover the input: eight codewords contain unknown bits, exceeding its seven ECC symbols. With known-bit error search enabled, the original candidate is recovered even when list decoding is disabled. Keeping or removing the `*` mark yields the same result.

The recorded images, result file, and comparison table above describe the priority-search and list-decoding procedure with known-bit error search **off**. They are not measurements of the alternative steps.
