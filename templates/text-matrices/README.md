# QR text matrix templates

Templates for QR Code Model 2 versions 1–40, ready for QR Repair text import.
Choose `version-NN.txt` for the required version. Its side length is `17 + 4 × version` modules (21–177).

- `0`: white module.
- `1`: black module.
- `?`: unknown module; replace with an observed `0` or `1` when available.

Finder patterns, separators, timing patterns, alignment patterns, the fixed dark module, and version information (versions 7–40) are filled in.
Both copies of format information and the entire data area, including error-correction codewords and remainder bits, are `?`. Remainder bits depend on the unknown mask.

Rows run from top to bottom, and columns from left to right, in the standard orientation with finders at top left, top right, and bottom left. There is no quiet zone, header, or spacing inside the TXT files; each ends with one LF newline. Keep the matrix square when editing. A blank template alone does not contain a recoverable payload.

Generated using the existing ZXing Core 3.5.4 dependency's basic-pattern and version-information placement. All 40 files were checked for dimensions and allowed characters, exact known/unknown function-area coverage, and agreement of every fixed module with all four ECC levels and eight masks (1,280 encoded symbols).
