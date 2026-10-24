# QRevy recovery strategy

English | [Japanese](RECOVERY_STRATEGY.ja.md)

QRevy combines image readings, QR structure, and error correction to find recovery candidates for damaged Model 2 QR codes. This document explains how candidates are produced and which conditions cause them to be rejected.

The processing flow is:

`Establish a grid → Read cells → Select a format → Repair codewords → Combine blocks → Validate the complete QR code`

For operations, see the [User Guide](USER_GUIDE.en.md). For diagnostic fields and cell explanations, see [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md).

## 1. Readings and assumptions used in calculation

A cell (module) is one square in a QR code. Cell values are stored as `0 / 1 / ?`, where `?` means unknown. Values read from the image may still contain errors.

The image reading or imported matrix with manual edits applied is kept as the input before any values inferred from structure or temporary search assignments are added. Results are compared against this state, called the **starting input**.

A `*` mark prioritizes trying `0 / 1` combinations for the marked unknown cells. Their input values remain `?`; values tried during calculation are excluded from input-agreement comparisons.

### Value sources, fixed cells, and comparison inputs

For each cell, the app distinguishes the input value, intermediate filling and correction, and the final value.

For example, if structure fills a cell with `0` and error correction then changes it to `1`, the record preserves that sequence: “filled with `0` from structure,” then “changed to `1` by error correction.”

A candidate whose value differs from a fixed `0 / 1` cell is rejected unless correction of fixed values is enabled. Tentative edits and image readings can be corrected within the error limits. When correction of fixed values is enabled, the input still records the original values and their Fixed designation.

An **error-correction code (ECC)** provides information for detecting and correcting errors in the data. For data and ECC bits, the app compares candidate values with the starting input.

| Value | Included in comparison? | Reason |
| --- | --- | --- |
| Known data/ECC bits in the starting input | Yes | They are input values obtained from image readings or manual edits. |
| Starting `?` or `*` | No | The input value is unknown. |
| Values filled from structure | No | They are inferred values, not values in the starting input. |
| Values tried during search | No | They are temporary assignments used to test combinations. |

If a known starting value is changed during calculation, the comparison still uses the original value. For example, if a starting `0` is changed to `1` to fit the structure and the final result is `1`, that counts as one mismatched bit. If the starting value is `?`, the cell is excluded from comparison even if it is filled with `0` and ends up as `1`.

Raising an error limit alone does not permit correction of fixed values. To include fixed values among those that may be corrected, enable the setting that allows correction of fixed values.

## 2. Establishing the grid and reading cells

When an image is imported, the app estimates the version, which determines the grid size, and the positions of the QR code's four corners in the image. During repair, sampling positions are refined around that grid. The selected version and the assignment of the QR code's top-left, top-right, and bottom-left corners, called its logical orientation, stay unchanged.

Recovery requires at least one finder pattern (**Finder**) to be assigned a top-left, top-right, or bottom-left role. Recovery can be attempted even when only one Finder is visible.

The app samples near each cell's center, shifting the sampling position slightly. If the readings consistently classify as black or white, the cell becomes `0 / 1`. If the classification changes or black and white cannot be distinguished, the cell becomes unknown (`?`).

For a manually edited cell, the entered `0 / 1 / ?` replaces the value sampled from the image.

### Initial grid estimation and sampling refinements

Initial estimation uses the QR detection library ZXing-C++ to detect the QR code's outer boundary in the image. The app builds a grid from that boundary and checks whether Finders and other patterns can be read at their expected positions and with their expected shapes.

If that grid cannot be accepted, the app estimates a grid from the repeating cell spacing, then considers an estimate using two Finders if necessary. It may decline an estimate when, for example, boundaries of different QR codes are detected or normal and inverted readings give conflicting estimates. Text decoded by the detection library is not used as the recovery result.

A projective transformation maps the four corners to cell positions in the image. It describes a flat surface viewed at an angle and cannot accurately represent the distortion of a QR code printed on a curved surface.

The basic refinement candidates are:

| Transformation | Candidate count |
| --- | ---: |
| Specified grid | 1 |
| Horizontal, vertical, and diagonal translations | 8 |
| Expansion and contraction | 2 |
| Moving one corner inward or outward | 8 |
| Total | 19 |

The reference displacement is 0.6 cell widths. When exactly one Finder role is assigned, the app also tries small clockwise and counterclockwise rotations around the corresponding corner. These two additions give at most 21 grid candidates. Each translation, scaling, or rotation is applied separately to the specified grid.

For version 2 and above, an alignment pattern, a small pattern used to align the grid, can add at most one corrected grid for each grid candidate. If its observed position differs from its predicted position by 0.1–1.25 cell widths, the nearest grid corner is moved. Corrections that substantially worsen agreement with the timing pattern, the alternating black and white pattern indicating cell spacing, are rejected. The original grid candidate is also retained.

Including corrected grids, there are at most 38 candidates, or 42 with exactly one assigned Finder role. With refinement disabled, only the specified grid is used.

### Measuring brightness and marking unknown cells

For each cell, brightness is measured at a 3×3 set of points offset by −0.16, 0, and +0.16 cells along each axis, and their mean is calculated. The entire set is then shifted by −0.1, 0, and +0.1 cells along each axis. The app examines the minimum and maximum of the means at these nine positions.

The maximum brightness classified as black and the minimum brightness classified as white are the two thresholds. A cell value is assigned only when the measured minimum and maximum both classify as black or both as white. Values between the thresholds, or classifications that change with small position shifts, produce `?`. A cell is also unknown if fewer than five of the nine points can be measured within the image at a sampling position.

Automatic thresholds use cells with known black or white values in Finders, their surrounding white separators, timing patterns, and alignment patterns. The app checks measurements against these patterns across multiple regions and determines two thresholds shared by the entire grid. If measurements are insufficient or do not fit the patterns, it uses provisional values obtained from the whole image.

When thresholds are set manually, the same thresholds are used during grid refinement.

Enabling the option to treat colored areas as unknown marks cells belonging to automatically detected color groups as `?`. These groups are formed by clustering similar colors in the image.

## 3. From format information to RS blocks

A QR code contains **format information** recording its ECC level and mask number. A mask is a pattern that reverses data-cell colors according to a defined rule. In this document, **format** means the combination of ECC level and mask number.

After selecting a format, the app reads data in QR placement order, excluding non-data cells such as Finders and format information. It reverses the mask and groups the bits into eight-bit codewords. It then separates the interleaved codewords into groups corrected together, called RS blocks. **RS** stands for Reed–Solomon.

If an ECC level or mask number is explicitly selected, only matching formats are tried. Fixed format-information cells are also checked for conflicts with those selections. This check applies even when correction of fixed values is enabled.

A **reading attempt** samples cells and attempts recovery using one grid and one set of reading conditions. If format-information error correction confirms a format and the input conditions are satisfied, that reading attempt uses only the confirmed format. A later recovery failure does not cause other formats to be tried.

### Comparing format information with input values

Format-information values are also compared with the starting input. However, fixed cells whose values are determined solely by explicit ECC/mask selections are excluded. This avoids using values determined by a setting to judge whether that setting agrees with the image. Tentative format-information cells are included in the comparison.

For example, selecting ECC level **M** and mask **0** determines whether each format-information cell is `0` or `1`. Consider a cell whose value is determined to be `1` by those settings.

| Input for that cell | Comparison treatment |
| --- | --- |
| Fixed at `1` | Excluded. Matching the setting does not count as a matching input bit. |
| Tentative `1` | Included and counted as one bit matching the candidate. |
| Image reading of `1` | Included and counted as one bit matching the candidate. |

Conflicts between fixed values and settings are handled by the consistency check described above.

### Confirming a format or only prioritizing it

There are up to 32 formats: four ECC levels combined with eight masks. A QR code records two copies of the same 15-bit format information.

Format information uses Bose–Chaudhuri–Hocquenghem (**BCH**) error correction. The main conditions for QRevy to confirm a format are:

- If both copies are fully readable, each can be corrected within three bits to the same format.
- If only one copy is fully readable, that copy can be corrected and every known bit in the other copy agrees with the corrected format.

The app also checks that mismatches against the format-information input are within the error limit and that the format does not conflict with fixed cells whose correction is disallowed. When these conditions are met, the format for that reading attempt is confirmed.

Even without confirmation, a format can sometimes be tried first because, for example, the bits identifying its ECC level and mask are available. If that format does not produce decoded content, other formats compatible with the settings and error limit are tried.

Codeword extraction also preserves the positions of unknown bits. **Deinterleaving** returns the codewords interleaved in the QR code to their original error-correction blocks. The version and ECC level determine the number of data and ECC codewords in each block.

## 4. Search order for each reading attempt

| Stage | Processing |
| --- | --- |
| 1 | Conventional RS on the input before filling or temporary assignments |
| 2 | Initial list decoding with a short time allowance |
| 3 | RS partial-bit constraints, narrowed using QR structure |
| 4 | Partial-bit search with conventional RS |
| 5 | Additional list decoding, combined with temporary assignments where useful |

If stage 1 passes QR-structure and input-condition checks and decodes the content, that reading attempt ends. Otherwise, processing proceeds to later stages. Candidates found in stage 2 or later are retained while the search continues.

When the data/ECC error limit is 0 bits, stages 2, 4, and 5 are skipped. The search looks for solutions in which every compared known bit matches the input.

## 5. Conventional RS, partial-bit constraints, and partial-bit search

Conventional RS treats a codeword containing even one unknown bit as an **erasure**, meaning that the value of the whole codeword is unknown. Partial-bit constraints also use the known bits remaining in that codeword. Partial-bit search assigns temporary values to unknown bits and retries conventional RS.

For example, eight codewords with one unknown bit each become eight erasures for conventional RS. Partial-bit constraints can still use the other 56 known bits.

### Conventional RS calculations and correction range

RS operates on eight-bit values in the finite field GF(256), which contains 256 values. A finite field is a finite set of values supporting addition, subtraction, multiplication, and division by nonzero values. **GF** stands for Galois Field, another name for a finite field.

The app calculates **syndromes**, values used to check whether the received codeword sequence satisfies RS conditions. The positions of incomplete codewords are known from the input and are used to find error locations and correction values. After correction, all syndromes must be zero.

The conventional correction range for each block is:

`2e + s ≤ r`

Here, `e` is the number of erroneous codewords at unknown locations, `s` is the number of erasures, and `r` is the number of ECC codewords. All three are counted in codewords.

With ten ECC codewords, two errors and six erasures are within range, but three errors and six erasures are outside it. If conventional RS cannot recover the data, methods such as partial-bit constraints and list decoding are also tried. If correction succeeds, the resulting codeword sequence is checked against QR structure and input conditions.

### Solving for unknown bits with linear equations

RS partial-bit constraints hold known bits fixed and use only unknown bits as variables.

Expanding the RS conditions over GF(256) into individual bits gives linear conditions over GF(2), the finite field containing 0 and 1. Addition in GF(2) is exclusive OR: equal values give 0 and different values give 1. The effect of each unknown bit on the syndromes is arranged as a column in a system of linear equations:

`A x = b`

- `x`: values of the unknown bits
- `A`: effects of each unknown bit on the RS conditions
- `b`: values determined by the known bits

The equations are transformed to eliminate variables in turn, identifying contradictions, bits with determined values, and variables that remain free. Incorrect known bits can cause contradictions; this method cannot correct those known bits. With `d` free variables, the RS conditions alone allow `2^d` solutions. The number of free variables is called the **degrees of freedom**.

Encoding mode, character or byte count, terminator, and other QR rules add constraints. The app checks whether the remaining degrees of freedom in each block fit the search limit. It also checks valid value ranges, such as the numbers representable by a group of digits.

With zero degrees of freedom, the selected format and known bits determine one RS solution. That solution undergoes the same QR-structure and input-condition checks as other candidates.

### Reducing erasures by trying temporary values

Partial-bit search selects codewords with one to four unknown bits, prioritizing those with fewer unknown bits. It assigns values within the per-block limit and tries conventional RS.

For example, a codeword with two unknown bits has four possible values. Assigning temporary values completes all eight bits, reducing the erasure count for conventional RS by one. This can make it possible to correct the remaining erasures and errors.

The partial-bit search limit also bounds the degrees of freedom when enumerating solutions from RS partial-bit constraints.

When `*` cells are marked, the app first attempts recovery with those cells left unknown. If the content cannot be decoded, it tries assigning `0 / 1` to the marked cells. Two cells, for example, give four combinations: `00 / 01 / 10 / 11`. The trial values are recorded in the calculation history.

## 6. Using QR data structure

QR data follows rules for encoding mode, character or byte count, content, termination, and filling unused space. QRevy uses these rules both to eliminate impossible solutions during calculation and to validate completed candidates.

A **segment** is a continuous portion of data encoded in one mode. Automatic filling assumes the data consists of one segment and examines structures compatible with the read mode and character or byte count.

### Filling, constraints during search, and final validation

QR structure serves three purposes:

| Purpose | Processing |
| --- | --- |
| Filling before error correction | Derive unknown bit values from an assumed mode and character or byte count. |
| Narrowing the search | Add conditions on RS free variables and combinations of block candidates. |
| Validating completed candidates | Check that the complete data stream follows QR rules. |

For example, three decimal digits use ten bits and must represent a value from 0 to 999. Even if the bits satisfy RS conditions, a value of 1,000 or more is invalid for three decimal digits.

After the terminator marking the end of the data, zero bits align the stream to the next eight-bit boundary. The remaining space is filled with alternating hexadecimal `EC` and `11` padding codewords. At the end of the available capacity, the app also checks cases where the terminator is shortened or omitted according to the remaining bit count.

If several mode and character/byte-count candidates remain, the app can fill bits that have the same value under every one of those candidates. For example, if a particular bit is a zero after the terminator under every candidate being considered, it can be filled with `0`.

### Terminator and padding filling and error limits

Here, **suffix** means the region from the terminator through the padding. **Force single-segment terminator and padding** changes the conditions for filling it.

| Setting | Behavior |
| --- | --- |
| Off | Tries suffix filling only when at least six known suffix bits match its rules. Also searches without filling and supports candidates with multiple segments. |
| On | Corrects the suffix using the mode and character or byte count read from the input or assumed through `*` trials. Rejects candidates if correction changes those values. |

Even with forcing off, suffix errors can be corrected within the **Known data/ECC bit error limit** when the mode and character or byte count are known. With either setting, errors are counted **against the input before repair**, including suffix corrections. For example, two suffix corrections, two payload corrections and two ECC corrections total six bits, so a five-bit limit excludes that candidate. Fixed-cell protection and specified ECC, mask and other conditions also apply.

### Retry with forcing for this run only

If no candidate decodes and forcing the suffix appears to allow decoding, the app offers **Retry this time only**. Accepting enables forcing for that run. The advanced setting stays off, and the runtime condition is recorded in history and saved work.

The offer is not shown after you interrupt a search or repeated for the same input and settings. You can also enable forcing manually in advanced settings when no offer appears.

## 7. List decoding

List decoding searches for multiple candidates beyond the conventional RS correction range. QRevy uses Guruswami–Sudan (**GS**), Koetter–Vardy (**KV**), and Wu decoding.

The **correction radius** is the maximum number of codewords allowed to differ from the values supplied to that trial, excluding erasures. For the same correction radius, the app estimates work and memory for GS and Wu and selects one of them. KV uses weights to make use of partially known codewords.

### GS/Wu correction radii and candidate generation

Let `n` be the number of codewords used after excluding erasures and `k` the number of data codewords. The conventional RS error-correction radius is `floor((n − k) / 2)`, where `floor` rounds down to an integer.

GS and Wu consider radii one, two, or three codewords beyond the conventional RS radius, up to the selected extension limit. The app also applies a mathematical condition called the Johnson bound, allowing only radii `t` that satisfy:

`(n − t)² > n(k − 1)`

Even when the mathematical conditions hold, work or memory limits can prevent a trial from running.

GS represents codeword positions and values with two variables and builds a polynomial satisfying specified conditions at the read positions and values. This operation is called **interpolation**. It then finds relationships that make the polynomial zero, called roots, and constructs candidate codeword sequences.

Wu uses an error-location polynomial to find possible error positions and calculates correction values.

Both methods recheck that the number of codewords differing from the trial input is within the correction radius and that the RS conditions hold.

These methods can also be applied after assigning temporary values to unknown bits. Codewords still containing unknown bits are erasures. Differences here are measured against the values supplied to that trial. Since those may include temporary assignments, this count is distinct from mismatches against the starting input.

### How KV uses partially known codewords

For a codeword with `u` unknown bits, KV gives equal weight to the `2^u` values compatible with its known bits.

For example, `1010?011` is compatible with `10100011` and `10101011`. Each receives half the base weight.

Codewords with at most four unknown bits receive weights; those with five or more receive zero weight. These calculation weights are called **multiplicities**. The app interpolates a polynomial satisfying conditions determined by the multiplicities and obtains candidate sequences from its roots. Whenever a candidate codeword agrees with the known input bits, its assigned weight contributes to the agreement score. The score must exceed the required value based on the interpolation polynomial's degree, and the RS conditions are checked as well.

The extension limit restricts the GS/Wu correction radius. KV uses an agreement-score condition.

## 8. Validating the complete QR code and merging candidates

Solutions from individual blocks are combined into a data stream. If blocks have multiple solutions, their combinations are examined. When a partially assembled stream already violates QR structure, the app stops exploring continuations of that combination.

The completed data stream is checked against QR structure. If **Force single-segment terminator and padding** is on, that condition is checked too. The codewords are then returned to their QR positions to check agreement with fixed cells whose correction is disallowed and to check that input mismatches stay within the error limit. Candidates failing any condition are rejected.

Candidates that pass validation undergo final content decoding. Even when the content cannot be decoded, a structurally validated candidate can be retained.

### Candidate identity and ranking

Results with the same version, ECC level, mask, and corrected codewords are merged into one candidate. Even if their text is the same, results differing in any of these properties remain separate candidates.

Candidates are compared in this order, using the next criterion when the preceding ones tie:

1. Successfully decoded content.
2. Fewer format-information mismatches.
3. More format-information matches.
4. Fewer mismatches with known data/ECC bits.
5. More known bits compared.
6. A larger minimum conventional RS margin across blocks.
7. Fewer bits assigned trial values during search.
8. Fewer codewords affected by correction.
9. Fewer erased codewords.

The conventional RS margin for a calculation is `ECC codewords − erasures − 2 × erroneous codewords`. A margin calculated after filling or temporary assignments differs from the margin obtained by applying conventional RS directly to the starting input. Candidates using partial-bit constraints or list decoding are treated as having a margin of zero for ranking.

Cell confidence is an explanatory indicator based on value sources, image assessments, and agreement among the compared candidates.

The app separately records calculation methods that produced the same candidate, alternative calculations on the same input, and different reading attempts. One candidate can have several calculation records.

## 9. Search limits and the meaning of results

Search limits apply to the number of unknown bits assigned at once, combinations examined across blocks, calculation time, working memory, and the number of candidates and calculation records retained.

List decoding has a total allowance of ten minutes across all list-decoding calculations in one repair. Time spent on other methods is excluded. The allowed number of mismatches with known data/ECC bits for list decoding is the smaller of the selected error limit and 5% of the total data/ECC bit count, rounded up.

If the search is interrupted, reaches the time limit, or runs out of memory, validated candidates already obtained remain in the results. The app distinguishes stopping an unfinished search from omitting found candidates or calculation records because of retention limits.

Search completion means the planned processing has finished under the settings and limits. Some combinations may remain unexamined because of those limits. Finding no candidates therefore does not establish that recovery is impossible, and finding one does not establish that no other solution exists.

Passing RS, QR-structure, and input-condition checks and decoding the content does not guarantee that it matches the original QR code. Candidate ranking and cell confidence help compare and review results. Neither these measures nor KV weights are probabilities of correctness.
