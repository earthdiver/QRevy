# Recovery example: a QR code with holes

English | [日本語](README.ja.md)

| Before repair | After repair | Filled and corrected cells |
| --- | --- | --- |
| ![Synthetic QR with irregular holes and black damage](before.png) | ![Recovered QR](after.png) | ![Filled cells in blue and corrected cells in orange](details.png) |

This synthetic example adds holes and black edge damage to a clean QR image. Of its 841 cells, 503 (59.8%) are erased. Blue marks filled cells; orange marks the one cell corrected for black damage.

Forcing the single-segment terminator and padding, together with one priority bit, returns one candidate matching the original QR code. Its complete text is:

```text
QRevy app: recovered QR.
```

## Try it

1. Import [before.png](before.png) as an image. During Quick automatic repair or after it fails, choose **Detailed analysis**. If it has found a candidate, choose **Analyze in detail**.
2. Set version 3 (29×29 cells), then confirm the grid corners and orientation. For this example, confirm or assign the corners manually. The image is 888×888 pixels; corner-cell centers are top left `(108, 108)`, top right `(780, 108)`, bottom right `(780, 780)` and bottom left `(108, 780)`.
3. Keep automatic thresholds enabled. Enter the values for the 62 cells listed below, then choose **Tentative (correctable)** for those edits. Leave the other 503 unknown cells as `?`.
4. Mark the unknown cell at **row 29, column 29** with `*` for priority search. The default limit of four cells is sufficient.
5. Leave **Force single-segment terminator and padding** off and run repair: **Save & repair** in Classic or **Repair** in Modern. The initial search returns no candidates and offers a retry.
6. Choose **Retry this time only**. The retry returns one candidate matching the original QR.

You can also enable **Force single-segment terminator and padding** manually before running repair to obtain the same QR. The settings below describe detailed analysis with the one-time retry.

QR codes include error-correction code (**ECC**) data based on **Reed–Solomon (RS)** codes. A codeword, also called a symbol, contains eight bits; an RS block is a group of codewords corrected together.

| Recovery setting | Value |
| --- | --- |
| Force single-segment terminator and padding | **off** |
| ECC level and data mask | Automatic |
| Black/white thresholds | Automatic (black at most 4; white at least 229 for this grid) |
| Format-information error limit | Default (6 bits) |
| Known data/ECC bit error limit | Default (5%, rounded up to 28 bits) |
| Priority search cell limit | Default (4 cells) |
| Partial-bit search / dimension limit | Default (12 bits) |
| List-decoding extension (symbols) | Default (+1 symbol per RS block) |
| Known-bit error search | Default (off) |

See the [User Guide](../../USER_GUIDE.en.md) for the controls.

### Tentative edits for 62 cells

Rows and columns are numbered from one. Enter each `column=value`, then choose **Tentative (correctable)**. Values follow the majority of surviving black or white pixels in the damaged image.

| Row | Column=value |
| --- | --- |
| 1 | 18=0 |
| 2 | 18=0, 19=0 |
| 3 | 20=1 |
| 4 | 21=1 |
| 6 | 20=1 |
| 7 | 8=0, 20=0 |
| 8 | 6=0, 9=1 |
| 9 | 1=1, 5=0, 8=1 |
| 10 | 3=0, 7=0, 20=1 |
| 11 | 6=1, 18=0, 19=1, 21=1, 22=1, 23=0, 24=1, 25=1 |
| 12 | 18=1, 20=1, 26=0 |
| 13 | 6=1, 18=0, 20=0 |
| 14 | 6=1 |
| 15 | 6=0 |
| 16 | 27=0 |
| 17 | 7=1, 26=0 |
| 18 | 8=1, 24=0, 25=0 |
| 19 | 9=1, 10=1, 21=0, 22=0 |
| 20 | 9=0, 21=1 |
| 21 | 1=0, 2=1, 3=0, 4=1, 6=1, 8=1, 21=1 |
| 22 | 7=0, 21=1 |
| 23 | 21=1 |
| 24 | 21=1 |
| 25 | 21=1 |
| 26 | 21=1 |
| 27 | 21=0 |
| 28 | 20=0, 29=0 |
| 29 | 20=1, 28=1 |

The text matrix [before.txt](before.txt) shows the cell values before repair, after applying the 62 tentative edits (`0` / `1`) above and the priority mark (`*`) at row 29, column 29. Text matrices do not record whether an edit is fixed or tentative.

### Comparison

These results use detailed analysis with the input and settings above.

| Condition | Candidate count |
| --- | --- |
| Force single-segment terminator and padding on; priority search enabled | 1 matching the original |
| Force single-segment terminator and padding off; priority search enabled | 0 in the initial search; retry offered |
| Retry this time only; priority search enabled | 1 matching the original; setting remains off |
| Force single-segment terminator and padding on; no priority marks | 0 |

### Verification conditions

Erasures and tentative values are derived from the damaged image. A cell is erased when all nine centers of its 3×3 subcells lie inside the gray holes. The priority cell is the first bit of the mode indicator, which identifies how the content is encoded.

Conventional RS and conventional RS with the default 12-bit assignment search cannot decode this input; partial-bit constraints recover it. Even after the correct priority assignment and forced terminator and padding are applied, 35 codewords remain erased. Reducing that to the conventional RS capacity of 26 requires at least 14 assigned bits. See the [recovery strategy](../../RECOVERY_STRATEGY.en.md) for these methods.
