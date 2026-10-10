# Recovery example: a QR code with holes

English | [Japanese](README.ja.md)

| Before repair | After repair | Filled cells |
| --- | --- | --- |
| ![Synthetic QR with nine irregular holes](before.png) | ![Recovered QR](after.png) | ![Filled cells highlighted in blue](details.png) |

In this synthetic example, 478 of 841 cells (56.8%) are unknown. The [recorded result](result.json) contains one candidate matching the original QR code and this text:

```text
QR Repair: recovered QR.
```

## Try it

Import [before.txt](before.txt) using **Import text matrix**, then set the following in the pre-repair settings. Use the text file as input; the images above illustrate the damage and result.

| Setting | Value |
| --- | --- |
| QR version | 3 |
| Error correction (ECC) level | M |
| Single-segment assumption | On |
| Priority search cell limit | **5** (the default is 4) |
| Known data/ECC bit error limit | 0 bits (exact match) |
| Partial-bit search / dimension limit | 12 bits |

The file already marks five unknown cells with `*` for priority search. Keep those marks. Check the roles assigned to the square finder patterns (**Finder** in the app); if none are assigned, assign at least one before running **Repair**. See the [User Guide](../../USER_GUIDE.en.md) for the controls.

The repaired matrix and content match the original in the recorded run. However, one returned candidate does not establish that no other answers are possible, and the missing-cell percentage is not a general recovery limit. The example images were independently generated for this project.
