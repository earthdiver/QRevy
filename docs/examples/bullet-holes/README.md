# Recovery example: a QR code with holes

English | [日本語](README.ja.md)

| Before repair | After repair | Filled and corrected cells |
| --- | --- | --- |
| ![Synthetic QR with irregular holes and black damage](before.png) | ![Recovered QR](after.png) | ![Filled cells in blue and corrected cells in orange](details.png) |

In this image-first synthetic example, 503 of 841 cells (59.8%) are erased. Holes and black edge damage are drawn onto a clean QR image before deriving erasures or soft-edit values from the damaged image. A cell is erased when all nine centers of its 3×3 subcells lie inside the gray holes. Blue marks filled cells; orange marks the one cell corrected for black damage.

The single-segment assumption and one priority bit return one candidate matching the original QR code. Its complete text is:

```text
QRevy app: recovered QR.
```

## Try it

1. Import [before.png](before.png) as an image.
2. Set version 3 (29×29 cells), then confirm the grid corners and orientation. For this example, confirm or assign the corners manually. The image is 888×888 pixels; corner-cell centers are top left `(108, 108)`, top right `(780, 108)`, bottom right `(780, 780)` and bottom left `(108, 780)`.
3. Keep automatic thresholds enabled. Leave the 503 cells whose nine subcell centers all lie in holes unknown. For the other 62 cells classified as `?`, use the table below to make **soft edits** from the surviving black or white pixels.
4. Mark the unknown cell at **row 29, column 29** for priority search. It is the first bit of the mode indicator. The default four-bit priority limit is sufficient.
5. Enable **Single-segment assumption** and run **Repair**. One candidate is returned.

| Recovery setting | Value |
| --- | --- |
| Single-segment assumption | **On (the only change from defaults)** |
| ECC level and data mask | Automatic |
| Black/white thresholds | Automatic (black at most 4; white at least 229 for this grid) |
| Format-bit error limit | Default (6 bits) |
| Data/ECC error limit | Default (5 bits) |
| Priority-search cell limit | Default (4 bits) |
| Partial-bit search / degrees-of-freedom limit | Default (12 bits) |
| Additional list-decoding errors | Default (1 symbol) |

See the [User Guide](../../USER_GUIDE.en.md) for the controls.

### Soft edits for 62 cells

Rows and columns are numbered from one. Apply each `column=value` as a soft edit. Values follow the majority of surviving black or white pixels in the damaged image.

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

The TEXT matrix [before.txt](before.txt) shows the state before running Repair, after applying the 62 soft edits (`0` / `1`) above and the priority mark (`*`) at row 29, column 29.

### Comparison

| Condition | Candidate count |
| --- | --- |
| Single-segment assumption on; priority search enabled | 1 matching the original |
| Single-segment assumption off; priority search enabled | 0 |
| Single-segment assumption on; no priority marks | 0 |

Conventional RS and conventional RS with the default 12-bit assignment search cannot decode this input; partial-bit constraints recover it. Even after the correct priority assignment and assumed suffix are applied, 35 codewords remain erased. Reducing that to the conventional RS capacity of 26 requires at least 14 assigned bits.
