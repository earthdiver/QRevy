# QR text matrix templates

English | [Japanese](README.ja.md)

Templates for QR Code Model 2 versions 1–40, ready for QR Repair text import.
Choose `version-NN.txt` for the version of the QR code you want to recover. Its side length is `17 + 4 × version` cells (21–177).

Each character represents one cell.

- `0`: white cell.
- `1`: black cell.
- `?`: unknown cell. Replace it with an observed `0` or `1` where readable, and leave uncertain cells as `?`.

Values determined by the QR structure are filled in: Finder patterns, separators, timing patterns, alignment patterns (version 2 and later), the fixed dark module, and version information (version 7 and later).
Both copies of format information and the entire data area, including error-correction codewords and remainder bits, are left as `?` because their values depend on the target QR code. Remainder bits are included because their values also depend on the mask.
The template does not yet contain the data representing the QR's content, so it cannot recover that content as it stands. Fill in the values you can read before using it.

Templates have Finders at top left, top right, and bottom left. Rows run from top to bottom, and columns from left to right, so orient the target QR code the same way before transcribing cells.

The TXT files contain only QR cells, without the surrounding white margin (quiet zone). Keep the row count and the number of characters in each row unchanged when editing, and do not add headings or spaces. Each supplied file ends with one LF newline. Once filled in, select it through **Import text matrix** in QR Repair.

Import compares the three Finders, their surrounding separators, and both timing patterns in all four rotations. For each pattern, the proportion of known cells is calculated using all its cells, including unknown cells. The app automatically assigns Finder roles (top left, top right, and bottom left) and orientation when each pattern meets the required proportion and error limit, and one orientation matches clearly better than the others.

If insufficient evidence or an ambiguous orientation prevents automatic assignment, Finder roles and orientation remain unassigned. In that case, assign a Finder role manually in the pre-repair settings. See the [User Guide](../../docs/USER_GUIDE.en.md) for the steps.

Templates are generated using the existing ZXing Core 3.5.4 dependency's basic-pattern and version-information placement. All 40 files were checked for dimensions, allowed characters, and exact known/unknown function-area coverage. To confirm that prefilled cells agree regardless of error-correction level or mask, they were also compared with symbols using all four error-correction levels and eight masks for each version (1,280 symbols in total).
