# Text matrix templates

English | [Japanese](README.ja.md)

Use these templates to enter a damaged QR code's cells by hand and import them into QRevy.

1. Choose `version-NN.txt` for the QR code's version (1–40). For example, version 3 uses [version-03.txt](version-03.txt), a 29 × 29 grid. The side length is `17 + 4 × version` cells.
2. Orient the QR code with its three square finder patterns (**Finder** in the app) at the top left, top right, and bottom left. Enter rows from top to bottom and cells from left to right.
3. Replace `?` with `0` or `1` where you can read the cells. Leave uncertain cells as `?`.
4. Save the file and select **Import text matrix** in QRevy. Check the orientation; if Finder roles were not assigned automatically, assign at least one in the pre-repair settings. See the [User Guide](../../docs/USER_GUIDE.en.md).

| Character | Meaning |
| --- | --- |
| `0` | White cell |
| `1` | Black cell |
| `?` | Unknown cell |
| `*` | Unknown cell selected for priority search; see the User Guide for eligible cells and limits |

Patterns determined by the selected version are filled in. Format information and the data area, including error-correction data, remain `?` because their values depend on the QR code. A template alone cannot recover the content; enter the cells you can read first.

Keep the row count and each row's length unchanged. Include only the four characters above and line breaks: no spaces, headings, blank lines, or surrounding white margin (quiet zone). Use Unix-style (LF) or Windows-style (CRLF) line breaks, with at most one final line break.
