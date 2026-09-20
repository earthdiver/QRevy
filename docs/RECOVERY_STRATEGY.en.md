# QR Repair Recovery Strategy

English | [Japanese](RECOVERY_STRATEGY.ja.md)

This document describes how QR Repair currently builds, validates, and explains recovery candidates for damaged QR Code Model 2 images. It is an implementation overview, not a replacement for the QR specification.

## Start with the big picture

For a first reading, this section, the tri-state example in section 1, the pipeline and scenarios in section 2, and the limitations in section 7 provide the operating model without requiring every implementation detail. Sections 3–5 cover format information, Reed–Solomon (RS), and candidate ranking. UI fields and cell-level evidence are documented separately in [Understanding recovery result fields](RECOVERY_RESULT_FIELDS.en.md) and [Understanding decode and repair reasons](REPAIR_REASONS.en.md).

QR Repair does not guess one answer from an image and immediately declare it correct. It combines independent evidence from geometry, format information, error correction, and QR payload structure, then presents validated candidates and any remaining ambiguity to the user.

| Display or state | What has been established | What it does not establish by itself |
| --- | --- | --- |
| RS-consistent | The RS equations hold under the selected version, format, and known bits | The original QR content, correct geometry, or correct format |
| Payload-validated candidate | QR payload structure and final decoding succeeded in addition to RS | The real-world author's intended content or a unique solution |
| Multiple candidates | Distinct solutions passed validation | Which candidate was the original content |
| No candidate | No validated candidate was found within the finite search performed | That no correct solution exists |
| Candidate agreement | The cell agrees across the validated candidates selected for comparison | Mathematical uniqueness across unretained or unexplored solutions |
| Confidence | An explanatory comparison of image evidence and provenance | A statistical probability of correctness |

Recovery checks described as requiring fixed-value agreement in this document apply when **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** is OFF. ON allows correction while retaining fixed designations. Consistency with explicit ECC/mask selections remains enforced (section 3.1).

## 1. Principles

A conventional reader is fast when it can obtain reliable geometry and a binary image. QR Repair targets cases where that assumption fails, so every module is retained as one of three values:

- `0`: observed as logical white/zero
- `1`: observed as logical black/one
- `?`: unknown; a codeword containing it can be supplied to Reed–Solomon as an erasure

Manual `*` is not a fourth logical value. The matrix value remains `?`; the marker asks the engine to prioritize a small bounded 0/1 assignment search if the initial ordinary erasure attempt fails.

The result preserves provenance from image sampling, fixed QR structures, payload constraints, Reed–Solomon repair, and manual editing. Confidence is an explanatory heuristic, not a statistical probability.

### Example of tri-state and manual input

The following is an illustrative data-bit fragment, not a complete codeword from a real QR symbol.

```text
Image observation:       1 0 ? 1 ? 0 ? 1
Manually set third to 0: 1 0 0 1 ? 0 ? 1
Mark fifth with *:       1 0 0 1 ? 0 ? 1   (the value stays ?, with priority-search metadata)
One possible candidate:  1 0 0 1 1 0 0 1
```

| Action | Matrix value | Effect on provenance or constraints |
| --- | --- | --- |
| Image is clearly read as black or white | `0` or `1` | Known image-derived value; it can still be corrected within configured limits |
| Image is ambiguous | `?` | Retained as unknown for erasure decoding or partial-bit constraints |
| User enters `0` or `1` | `0` or `1` | Retain the input value and its fixed or tentative designation |
| User applies `*` | `?` | Prioritizes bounded 0/1 enumeration for that cell if the ordinary route fails |

Even if the last line in the example is RS-consistent, it is not accepted on that basis alone. It becomes a validated candidate only after format, manual constraints, QR payload structure, and final decoding also pass.

## 2. Pipeline

### Pipeline at a glance

| Stage | Main input | Operation | Output for the next stage | Not established by this stage alone |
| --- | --- | --- | --- | --- |
| 1. Normalize | Image, EXIF, crop, rotation | Normalize orientation and luminance; prepare polarity alternatives when needed | Working luminance image | QR orientation or content |
| 2. Initial geometry | ZXing-C++ boundary, grid period, two Finders, user input | Propose editable version and corner starting values | Editable geometry | Correct version or corners |
| 3. Roles and geometry hypotheses | Corners, version, assigned Finder roles | Build baseline, neighborhood, and alignment-refined hypotheses | Finite sampling geometries | Best geometry |
| 4. Sample modules | Luminance, thresholds, color clusters | Classify every cell as `0 / 1 / ?` | Tri-state matrix with provenance | Values of unknown cells |
| 5. Format and structure | Version, format cells, function modules | Apply ECC-level/mask candidates and fixed QR structure | Codeword candidates per format | Correct payload |
| 6. Constraints and RS | Partial codewords, known bits, structure hypotheses | Apply erasures/errors, bounded searches, and binary constraints | RS-consistent candidates | Agreement with the original data |
| 7. Final validation | Corrected codewords | Check manual constraints, error limits, QR structure, and final decode | Validated candidates or diagnostics | Uniqueness across unexplored space |
| 8. Explain | Candidates and route history | Present ranking, cell provenance, and useful additional checks | Evidence the user can review | Probability of correctness |

### 2.1 Input and normalization

Image files have their EXIF orientation applied, while camera captures pass through an explicit crop step. The user can rotate by 90°, 1°, or 0.1°.

The working image is converted to luminance. When polarity is unknown, normal and inverted interpretations may be considered. Once a confirmed finder establishes polarity, redundant inverted trials are skipped. Optional quiet-zone correction adds logical-white margin to the finder-detection image.

### 2.2 Initial version and corner estimation

On new image import, the Android adapter obtains Model 2 boundaries from ZXing-C++ 3.1.1. Shared code tests versions 1–40 and four rotations against a single boundary. Finder and timing mismatch must each be at most 20%, with a runner-up gap of at least 0.03 in the score weighted 0.55/0.45. Accepted boundaries are converted to outer module centers. Decoded native text is not used as a recovery result.

Multiple boundaries are validated individually. They are treated as duplicates only when every pair agrees in version and logical orientation, with each corresponding corner within 0.25 module. A representative that passes the automatic-role gate is preferred; coordinates are not averaged and repetition does not increase confidence. An unverified boundary or a distinct QR candidate prevents merging.

If the boundary is rejected, multiple boundaries cannot be merged, or JNI loading or execution fails, the existing grid-period estimator is tried. Luminance gradients identify two orthogonal edge families, and their projected coordinates are searched for a phase and module pitch that align with an evenly spaced boundary grid. When a half-period or another harmonic has similar agreement, the fundamental period is preferred so that the harmonic is not mistaken for a different version.

An initial version is set from `dimension = 17 + 4 × version` only when the pitches on both axes agree, the extent supported by definite-black pixels is approximately square, and it sufficiently matches one of the permitted dimensions `21, 25, 29, …, 177`. A phase-aligned window spanning that dimension also supplies initial corners at the centers of the outer modules. Large gray damaged regions and isolated dark background pixels are excluded from definite-black boundary evidence so that they do not enlarge the symbol by several modules.

If periodicity also fails, a fallback uses individual Finder detection and two-Finder geometry hypotheses. It checks both Finders’ pitch, axes, and patterns. For a diagonal pair, both timing axes can establish the observed top-right and bottom-left roles without an alignment pattern. Each axis requires at least eight known cells covering at least 60% of its length, at least three black and three white observations, and at most 5% mismatches. These checks must also pass at offsets of 0.2 module in each axial direction. Unknown cells do not count as matches. A score gap of at least 0.12 against competing hypotheses and white-separator checks are required.

If timing cannot establish roles, a verified lower-right alignment pattern may still support provisional corners without role assignment. The fallback covers version 2 and later symbols approximately described by a similarity transform. Version 1 has only five timing cells per axis, below the evidence requirement. Multiple native boundaries disable the fallback. A hidden Finder is never treated as observed.

These estimates are editable starting values, not proof of recovery. If every estimator fails, the UI falls back to version 1 and default corners. Recovery treats the displayed corners and selected version as confirmed inputs.

Physical corner positions and the choice of logical coordinate `(0, 0)` are separate. A grid derived from ZXing-C++ can automatically assign Finder centers and roles through the following gate: at most 2/49 mismatches per Finder, 1/15 per white separator, 5% timing mismatch, a score gap of at least 0.08 including rejected alternatives, and at least two pixels per module along every edge. Samples offset by 0.25 module in each axial direction must also have at most 2/49 mismatches per Finder. Weaker native grids and periodicity estimates do not assign roles automatically. Two-Finder estimates use the separate conditions above.

Automatic assignment runs only on new image import and is recorded in history. Saved Finder assignments are not re-estimated. Press **Clear** and tap a green Finder to remove its assignment; clearing all roles also unassigns logical orientation. Corners and cell edits are retained, and cells are not marked as manually specified. The thresholds are heuristics, not probabilities. See the [implementation and validation record](ZXING_CPP_INTEGRATION.en.md) for details.

### 2.3 Manual Finder-role assignment

Roles can also be assigned manually, whether or not automatic assignment was available. The user chooses a top-left, top-right, or bottom-left role and taps its approximate center. Only that neighborhood is checked for Finder image features; when evidence is sufficient, the center and polarity are refined. If no Finder is detected, the tapped point remains in use. Once a Finder role has established the logical grid, however, a subsequent Finder assignment is rejected when it is far from the expected position. This local refinement does not decide Finder roles automatically.

Automatic Model 1 classification is not implemented yet. Input must therefore be limited to Model 2 symbols.

The following table counts currently assigned Finder roles during recovery, including automatic and manual assignments that have not been cleared. It does not count Finder-like shapes visible in the image.

| Assigned roles | Logical orientation | Base hypotheses with neighborhood search | Important qualification |
| --- | --- | --- | --- |
| 0 | Use the displayed corners and default logical orientation | At most 19 | No global Finder search or automatic role assignment during recovery |
| 1 | Treat the assigned logical corner as a pivot | At most 21 | Add clockwise and counterclockwise rotations to the ordinary 19 |
| 2 | Fix orientation from the two roles and corners | At most 19 | Do not add the two rotation hypotheses |
| 3 | Use the fully explicit role assignment | At most 19 | Incorrect roles can prevent the correct recovery |

For version 2 and later, at most one alignment-refined variant may be added for each base hypothesis. The next section gives the complete bounds.

### 2.4 Geometry hypotheses

Recovery fixes the logically assigned corners and selected version. It first creates the selected geometry as one base hypothesis. Enabling “Automatically fine-tune sampling position” in advanced settings expands the base set to at most 19 hypotheses without changing logical orientation or version: one baseline, four axial translations, four diagonal translations, two overall-scale variants, and eight variants that move one corner inward or outward. Only the 0.6-module width is used. Diagonal components are `0.6 / √2`, and scale factors are chosen so the mean outer displacement of the four corners is 0.6 module; axial, diagonal, and scale comparisons therefore use the same displacement magnitude.

If exactly one Finder role is assigned, two more base hypotheses rotate clockwise and counterclockwise around its logical corner, for 21 base hypotheses in total. The angle is chosen so the farthest outer corner moves by 0.6 module. No rotation hypothesis is added with zero or at least two assigned Finder roles. These neighborhood transformations are not combined with one another, and logical orientation and version remain unchanged.

For each base hypothesis, when an interior alignment pattern available in version 2 or later has sufficient image evidence, the engine adds one alignment-refined geometry. The observed alignment pattern must differ from its predicted position by 0.1 to 1.25 modules; refinement moves only the nearest logical corner to fit that observation. A refinement is rejected if it materially worsens the independent timing pattern score. It never replaces the user-selected geometry: both the base and refined hypotheses are evaluated. If alignment refinement succeeds for every base hypothesis, the resulting maxima are two hypotheses normally, 38 with neighborhood search, and 42 with neighborhood search plus exactly one assigned Finder role. The engine-wide bound is 72 geometry hypotheses.

Every geometry hypothesis uses fixed and tentative edits as input at the same cell coordinates. Sampled image values do not need to match the edits. With **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** OFF, candidates that contradict fixed values are rejected. ON permits correction, subject to error limits and explicit QR settings.

### 2.5 Module sampling

On image import, the whole-image two-/three-class luminance estimate supplies provisional thresholds, with 96/160 as the fallback. This analysis is reused until the analysis image changes. Automatic mode estimates one global pair for each grid, including corner adjustments and recovery sampling fine-tuning. Known finder, separator, timing and alignment cells provide black/white evidence; user edits and repaired bits are not training labels. Support is checked in a 3×3 partition, without applying regional luminance correction. Separated observation envelopes produce adjacent thresholds; overlapping envelopes leave an unknown band. A stable middle histogram group is associated with black or white only when function-pattern evidence supports it across regions; otherwise its interval is included in the unknown band. Sparse or unreliable function evidence uses the image-wide provisional pair, independently of earlier geometry trials.

The **Set thresholds automatically** checkbox starts checked for a new image. Changing either threshold slider unchecks it and makes the whole pair manual. Unchecking it directly preserves the current pair. That pair is kept through corner movement and repair fine-tuning; checking the box again recalculates the thresholds and resumes automatic mode. The automatic/manual choice, effective thresholds and provisional fallback are saved with the session. Sessions without an automatic/manual choice retain their saved thresholds as manual values.

Colored unknown regions are initially estimated as approximate clusters in Lab color space. Cluster acceptance uses color frequency, color-density, and separation from black and white; it does not use spatial grouping, module size, finders, or version. “Treat detected color clusters as unknown (?),” directly below the Version button in basic settings, starts disabled and applies detected clusters only when enabled. Achromatic gray regions are handled by the luminance thresholds instead.

For each module, a central 3×3 sample at offsets −0.16/0/+0.16 module is measured at nine center positions shifted by −0.1/0/+0.1 module per axis. The resulting observation range is classified with the same global pair in preview and repair. Luminance is rounded to an integer; values in the threshold gap, or observations whose classification changes under these small shifts, become `?`. The stability check also applies to manual thresholds. Adjacent thresholds do not introduce a gap solely from a fractional mean. A configured color cluster also produces `?` when its pixels occupy at least half of the central sampling area. Fixed and tentative `0 / 1 / ?` edits override resampling and retain their provenance. Tentative values remain correctable by recovery; when fixed-value correction is disabled, candidates contradicting fixed values are rejected.

The automatic estimator requires at least four black and four white observations per supporting region, black-before-white agreement of at least 0.8, and a median separation greater than twice the observation allowance. At least eight observations of each class must be supported overall. The allowance is the larger of 0.75 luminance units and the maximum center-shift deviation; envelopes are not trimmed. Middle-group detection requires at least six observations and 2% of all observed cells per group, valleys no higher than half the lower neighboring peak at smoothing scales 1/2/4, adjacent peaks more than two luminance units apart, and middle-peak movement no greater than six. Associating that group with a class requires eight supporting function observations in at least two regions and none of the opposite class. These fixed heuristics provide editable initial values; overlapping lighting and damage can still require manual adjustment.

### 2.6 Reading three common scenarios

These synthetic scenarios explain how to use the mechanisms. They are not test vectors derived from a particular image and do not promise recovery.

#### Example A: only one Finder can be trusted

1. The user confirms the version and corners, then assigns the correct logical role to the one trusted Finder.
2. Enabling **Automatically fine-tune sampling position** adds two pivot rotations to the ordinary 19 base hypotheses.
3. Every hypothesis retains the same version and logical orientation. An alignment-refined variant is evaluated separately when supported by image evidence.
4. If multiple validated candidates remain, the app retains them and reports their agreement instead of choosing one silently.

Seeing one Finder-like shape does not trigger this route automatically. Role assignment is a user decision, and the rotation hypotheses do not change the selected version or logical orientation.

#### Example B: gray damage covers several cells

1. Black/unknown/white thresholds preserve the gray region as `?` instead of forcing an unreliable known value.
2. Codewords containing `?` can enter ordinary erasure decoding, bounded partial-bit search, and partial-bit RS constraints.
3. Even a unique RS solution is conditional on the format and known bits. QR payload structure and final decoding still run.
4. If the solution space remains ambiguous, the app may recommend a cell whose visual confirmation reduces RS freedom by one bit.

#### Example C: a manual pin was wrong

1. When conflicts with fixed values are not allowed, any candidate that contradicts the incorrect manual 0/1 is rejected even when it is RS-consistent.
2. When possible, a direct format conflict or the opposite value required by a candidate is reported with coordinates.
3. Change a weakly supported pin to **Tentative** to retain its value, or choose `?` / **From image**, then retry.

Changing an error limit alone does not permit correction of fixed values. Change the cell to Tentative or allow conflicting candidates in advanced settings to permit correction.

## 3. QR structures and format information

Function modules determined by version, including finders, separators, timing and alignment patterns, and the fixed dark module, are filled from structure. Displayed remainder bits depend on the selected mask. Format candidates are assessed using BCH correction. For version 7 and later, version-information BCH codewords are generated from the user-selected version. The engine does not BCH-decode image-observed version information to validate that selection. When conflicts with fixed values are not allowed, manually fixed version cells are checked against the generated expected values.

Format information has 32 combinations: four ECC levels times eight masks. A BCH-trusted candidate is tried alone first when:

- both copies are complete and BCH-correct to the same format; or
- one copy is complete and BCH-correctable, while every known bit in the other copy agrees with that ECC-level/mask candidate.

Even when neither rule applies, the engine creates a **provisional candidate** in either of these cases:

- the complete five information bits for ECC level and mask agree in both copies while known BCH parity bits conflict with that candidate, strongly suggesting damage in at least one BCH area; or
- both five-bit information fields contain `?`, their overlapping known bits agree, together they determine all five bits, and every available BCH parity bit in both copies agrees with the resulting candidate.

Agreement or complementary reconstruction of the five information bits is not treated as BCH correction and does not confirm the format.

A BCH-trusted candidate becomes the confirmed format for that geometry when it satisfies the manual-value consistency conditions in section 3.2. Failure of RS recovery or final payload validation does not cause a fallback to the other 31 formats. A provisional candidate is not confirmed: it stops the search only after final payload validation; otherwise the engine falls back to the remaining candidate set, at most 31 formats after excluding the attempted one.

The BCH-confirmed and provisional single-format fast paths apply when both ECC and mask are automatic. With either selection explicit, recovery tries the permitted set in rank order: at most eight formats for ECC only, four for mask only, and one for both. A partial explicit selection does not narrow the set to a BCH-trusted format alone. Format-error limits and manual pins further restrict the attempted set.

### 3.1 Manual format edits and ECC/mask selections

Fixed and Tentative in the tables below are per-cell states. Recovery behavior in the Fixed column assumes **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** is OFF (the default). ON retains fixed designations, input values, and solid outlines while allowing correction as for tentative input. The edit-time consistency check between fixed cells and explicit ECC/mask selections, and the comparison exclusion for format positions determined by explicit selections alone, remain enforced. Switching OFF protects fixed values again.

Consistency between **fixed** format 0/1 values and explicit ECC/mask selections is checked when editing, changing source, or changing selections. Both 15-bit copies, including BCH parity, are checked together. A conflict means that no format permitted by the explicit selections matches all manual values. Image-derived and tentative values do not participate in this check; explicit ECC/mask selections still constrain all recovery candidates.

| ECC selection | Mask selection | Formats checked when editing or changing selections |
| --- | --- | --- |
| Auto | Auto | No selection/edit consistency check |
| Explicit | Auto | Eight formats with the selected ECC |
| Auto | Explicit | Four formats with the selected mask |
| Explicit | Explicit | One format; every bit in both copies is determined |

With only one selection explicit, individually possible bits can still contradict one another collectively. The error identifies a group that cannot be satisfied together, without claiming that each bit is individually wrong.

| Edit or ECC/mask change | Fixed | Tentative |
| --- | --- | --- |
| Manual 0/1 values agree with the resulting selections | Accept | Same as left |
| Manual 0/1 values conflict with the resulting selections | Reject the whole change and retain prior state/history | Accept the observation; recovery must still satisfy the explicit selection |
| Only image-derived 0/1 values conflict | Accept | Same as left |
| Set a format cell to `?` | Accept; removes its manual 0/1 value | Same as left |
| Return ECC or mask to Auto | Accept | Same as left |
| Set a format cell to `*` | Reject; `*` is restricted to data/ECC cells | Same as left |

Entering the currently displayed 0/1/`?` does not change its source (`*` to `?` clears priority search). Changing an image-derived cell to a different 0/1 creates a fixed override; entering the image value later keeps it fixed. **Fixed** can pin an image-derived 0/1 directly. **Tentative (correctable)** retains the current value while allowing correction; changing a tentative 0/1 to another 0/1 retains that mode. Changing `?` or `*` to 0/1 makes it Fixed. When **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** in advanced settings is ON, the menu labels fixed values **Fixed (correction allowed)**.

**From image** removes selected overrides and priority marks and resamples with the current thresholds and geometry. It does not alter ECC/mask selections. Source changes can apply to multiple selected cells and participate in undo/redo and saved work. `?` is image-derived or tentative; `*` is always tentative.

Undo/redo is checked against the current selections; an operation that reintroduces a conflict is rejected without consuming history. Batch edits are atomic.

Existing work containing a conflict loads its saved values unchanged, displays an error, and stops automatic recomputation/recovery. Set affected cells to `?` or return ECC/mask to Auto to correct it. Conflicts introduced by version/orientation changes are also checked before saving or repairing.

### 3.2 Format cells during recovery

A position determined by **explicit ECC/mask selections alone** has the same value across every format permitted by those selections. Positions determined only after narrowing candidates with manual edits do not qualify. Fixed 0/1 values at selection-determined positions are protected by the consistency check above and excluded from format matches, mismatches, and compared-bit counts.

| Format-cell state | Fixed (conflicts not allowed) | Tentative |
| --- | --- | --- |
| Manual 0/1 at a selection-determined position | Require agreement; exclude from format error comparison | Allow correction; include in format comparison |
| Other manual 0/1 | Require matching recovery candidates; include in format comparison | Allow changes and count differences as format errors |
| Image-derived 0/1 | Allow changes and count differences as format errors | Same as left |
| `?` | Cannot be Fixed | Fill from the candidate format; exclude from comparison |
| `*` | Not permitted | Same as left |

A format error limit of zero requires exact agreement at compared known positions. When **both** ECC and mask are explicit, this limit is bypassed and the selected format is used. Fixed-value conflicts are still rejected by the consistency check. Format cells never count toward the data/ECC error limit.

When ECC and mask are automatic and conflicts with fixed values are not allowed, a BCH-correctable candidate is checked against fixed 0/1 values in format, version, function, and remainder modules before confirmation. A conflict prevents confirmation and leaves other compatible formats eligible. Tentative values do not create this veto, but format-error limits still apply.

When conflicts with fixed values are not allowed, fixed data/ECC values are checked against corrected candidates, rather than during format confirmation. Once a format is confirmed, RS or final payload failure does not reopen format search. Explicit ECC/mask selections are enforced for every candidate.

## 4. Codewords, constraints, and Reed–Solomon

After unmasking, placement-order bits are grouped into eight-bit codewords and deinterleaved into RS blocks according to version and ECC level. A codeword with any unknown bit is partial.

Before RS decoding, safe constraints are propagated from a known payload prefix:

- mode indicator and mode-specific character-count width
- a known character count when the explicit option is enabled
- 10/7/4-bit numeric-group value ranges
- 11/6-bit alphanumeric-group value ranges
- terminator bits
- zero bits used for byte alignment
- alternating padding codewords `0xEC / 0x11`

Ordinary structure propagation enumerates single-segment Numeric, Alphanumeric, Byte, and Kanji hypotheses. Among plans compatible with known bits, it selects those with the most known terminator, byte-alignment, and padding matches; fewer than six matches disables filling. It fills only values shared by all selected plans. This is inference within selected single-segment hypotheses, not proof of uniqueness across all QR streams, including multiple segments. Reaching the plan-enumeration limit also disables filling. Every mutation by these constraints retains an unconstrained retry, even after a successful constrained result.

The explicit known-count option is valid only when the QR content consists of exactly one Numeric, Alphanumeric, Byte, or Kanji segment and its mode and character count (byte count in Byte mode) are fully and correctly observed. The option forces the area immediately after that segment to the terminator, byte-alignment, and padding values, replacing conflicting known image-derived 0/1 values as well. Fixed 0/1 edits remain protected unless conflicting candidates are allowed; tentative edits may be corrected. It must not be used with ECI, FNC1, structured append, or multiple segments.

The conventional route decodes each block as GF(256) errors plus erasures, with the capacity relation `2 × errors + erasures <= ECC symbols`. A complementary binary constraint route also uses known bits inside partial codewords, as described below. Mathematical RS success alone does not make a candidate correct.

Trust applies to the mode and character/byte count. An observed numeric or alphanumeric body outside its valid range does not disable suffix forcing; RS correction and final validation still handle the body itself.

This option is selectable when an eligible format has a fully observed leading Numeric, Alphanumeric, Byte, or Kanji mode/count header. Automatic BCH confirmation restricts this check to the confirmed format. General diagnostics may recognize ECI, FNC1, structured-append prefixes, or Hanzi, but those do not qualify for suffix forcing. An ineligible input disables and unchecks the option. Absence of another segment in an unknown region cannot be established automatically; the user must still confirm the single-segment assumption.

### 4.1 Data/ECC error limit and manual input values

Enter an error limit directly and adjust it one bit at a time with −/＋. Apply confirms the value; Unlimited and Cancel are also available. Format limits range from 0 to 30 bits. Data/ECC limits range from 0 to eight times the total codewords of the selected version, independent of ECC level. Blank, non-integer, and out-of-range values cannot be applied. If a version change makes the existing limit too large, the app requests a new value without changing it automatically. Cancel retains the value; saving settings or starting repair requires a valid limit or Unlimited.

For every RS-corrected candidate, the final codewords are mapped back to QR modules with the selected mask reapplied and compared bit by bit against input `0 / 1` values known in data/ECC modules. Unknown (`?`) modules, format information, version information, function modules, and remainder bits are excluded from this comparison.

When the known data/ECC bit error limit is set, only candidates whose mismatch count is no greater than the limit are retained. `0 bits` requires an exact match against every comparable known data/ECC bit. With Unlimited, the mismatch count still affects candidate ranking but does not by itself reject a candidate. An image-derived known value replaced by forced payload structure is also counted as a mismatch.

When conflicts with fixed values are not allowed, a candidate that contradicts a fixed value is rejected before applying the error-limit allowance. Such a conflict never consumes one bit of the allowance. For tentative cells and fixed cells with conflicting candidates allowed, a manually entered `0 / 1` is treated like an image-derived known bit: RS may change it, and any change counts toward the mismatch limit. A manually locked bit that agrees with the candidate is included in the known-bit denominator.

If no candidate remains within the limit, the app distinguishes this from ordinary RS failure. For the best limit-exceeded attempt, it reports the actual mismatch count, known-bit count, and configured limit. With a zero limit, an inconsistent exact-bit system is reported directly as no exact known-bit solution; an error count is not invented.

The following table distinguishes cell regions. Format cells also follow the selection consistency and comparison exclusions in sections 3.1–3.2.

| Cell region and state | Fixed (conflicts not allowed) | Tentative | Data/ECC error limit |
| --- | --- | --- | --- |
| Manual data/ECC 0/1 | Reject conflicting candidates | Allow changes and count differences | Compared; matching locked bits also count as known |
| Image-derived data/ECC 0/1 | Allow changes and count differences | Same as left | Compared |
| Data/ECC `?` / `*` | Cannot be Fixed | Fill/search | Excluded |
| Manual format 0/1 | Reject conflicting candidates | Follow section 3.2 | Excluded |
| Image-derived format 0/1 / `?` | Follow section 3.2 | Same as left | Excluded |
| Manual version/function/remainder 0/1 | Reject candidates whose structure/selected format conflicts | Allow replacement from structure/selected format | Excluded |
| Image-derived version/function/remainder 0/1 / `?` | Repair/fill from structure/selected format | Same as left | Excluded |

Version information, other function modules, and remainder bits also stay outside the format error limit. Conflicting locked manual values in these regions produce a separate diagnostic with coordinates, manual values, and expected values. A positive or unlimited data/ECC limit never permits overriding locked manual values. At limit zero, all compared known data/ECC bits must agree even for tentative cells.

### 4.2 Priority `*` search

At most four `*` markers may be assigned, and only to data/ECC modules. In the cell editors, first assign the four corner positions and roles. Selecting fixed structures or remainder bits displays their type and coordinates. Orientation changes recheck eligibility and turn ineligible `*` marks back into `?`. Version changes clear all cell edits after confirmation.

1. First treat every marked cell as an ordinary erasure.
2. If that fails, try at most `2^4 = 16` assignments for marked bits only.
3. Within each assignment trial, also allow the ordinary bounded search for other `?` bits; retain only payload-valid candidates.
4. If distinct solutions remain, retain multiple candidates and do not declare an assignment unique.
5. If all assignments fail, continue to the ordinary bounded `?` search using the original observation without priority guesses.

In the current implementation, payload-constraint propagation remains enabled during the ordinary erasure attempt in step 1. A `*` at a position uniquely implied by mode/count, terminator, byte alignment, padding, or numeric/alphanumeric value ranges can therefore be resolved by a constraint before explicit 0/1 assignment enumeration, allowing the ordinary attempt to succeed. Thus `*` does not mean “always enumerate this bit”; it prioritizes enumeration only when ordinary erasure handling plus safe constraint propagation does not succeed. Constraint-derived resolutions remain distinguishable in repair provenance.

Temporary priority 0/1 assignments constrain only their trial; they are not known input bits. Known-bit counts, mismatch counts, original erased-codeword counts, and Before codeword views use the observation before those assignments. RS erasure counts and correction margins still describe the trial. Explicitly enumerated coordinates and values are retained in candidate provenance and displayed separately from ordinary partial-bit search. Other cells in the same codeword do not inherit priority-assignment provenance. Success on the ordinary route is not recorded as explicit priority enumeration.

The ordinary search inside each priority trial uses the per-block bounds in section 4.3. The maximum of 16 priority assignments is separate from the maximum of 4096 conventional enumeration trials.

### 4.3 Ordinary partial-bit search

When erasure decoding fails, or when the combined error/erasure correction margin is small, partial codewords with more known evidence are prioritized for a bounded enumeration: at most 12 unknown bits and 4096 trials. The solver fixes enough partial erasures, within those bounds, to reserve capacity for multiple unknown-position errors. This is not an unlimited brute-force search over every `?` in the image.

These are per-block limits. Only codewords with one to four unknown bits are eligible for conventional enumeration. They are selected by increasing unknown-bit count, preserving local codeword order on ties. Codewords with five to eight unknown bits remain erasures on this route; the separate binary-constraint route may still use them.

### 4.4 Partial-bit RS constraints (automatic, no additional option)

For each format interpretation and RS block, expand the GF(256) zero-syndrome equations into GF(2) equations on individual unknown bits. All known data and ECC bits are constants. Row reduction produces either an inconsistent system or an affine space with dimension `d = unknown bits − rank`. The solver computes bits fixed across every solution and a set of independent measurement bits without enumerating that space.

- With a data/ECC error limit of zero, this exact-match route supplies block candidates. With a positive or unlimited allowance, its candidates supplement conventional errors+erasures decoding; finding an exact solution does not skip that conventional route. This is not exhaustive error-location search under an arbitrary bit-error budget.
- A space with `d = 0` supplies one conditional RS solution. For `1 <= d <= 12`, all `2^d` assignments are passed to QR-level validation. Larger spaces remain symbolic, not an arbitrary sample of 4096 assignments. QR-level combination limits still apply. The initial priority-`*` pass permits only a unique exact solution, not free-variable enumeration; explicit priority trials and the existing fallback remain available.
- Payload-structure constraints can reduce the space used for candidate generation. The existing retry without those constraints is retained. Diagnostics and measurement recommendations describe the original observation before structure forcing or temporary priority guesses.
- Fixed-bit values never overwrite the input or become manual pins. A completed candidate carries RS partial-bit provenance. A conventional symbol correction margin is not inferred from binary rank.
- Incorrect known ECC bits can make the system inconsistent **or** produce a consistent but incorrect solution. Even a unique solution is conditional on the version, format and known bits being correct; payload validation, manual locks and final mismatch checks remain mandatory.

For example, twenty one-bit erasures scattered across twenty V1-L codewords can be uniquely solved when the binary rank is twenty, even though the conventional seven-symbol erasure budget and twelve-bit enumeration cannot resolve them. Conversely, erasing all seven ECC bytes plus four data bits leaves four free bits: sixteen RS assignments, not a unique payload. Erasing every ECC bit cannot by itself constrain unknown data.

## 5. Candidate validation and ranking

Candidates are rejected for violating fixed values when conflicts are not allowed, contradicting fixed structures outside codewords, exceeding configured error limits, or failing QR structure validation. RS consistency alone is not success; final decoding is also checked.

The retained-candidate comparator uses this order:

1. successful final decoding first;
2. fewer format mismatches;
3. more format matches;
4. fewer known input data/ECC bit mismatches;
5. more compared known bits;
6. larger minimum RS margin;
7. fewer partial-search bits;
8. fewer corrected target symbols;
9. fewer erased codewords at recovery.

Ties preserve discovery order; comparisons across geometries finally use the image geometry score. Structure-filled bits are diagnostic information, not a comparator condition. The internal margin placeholder for an RS partial-bit constraint route is zero; the UI reports symbol margin as not applicable.

Identical version, ECC, mask, and corrected codewords are merged into one result across geometries and polarities. Other derivations retain their diagnostics and provenance within the retention allowance, with omissions disclosed. Retrying without structure constraints is preserved. The main display uses the best derivation’s provenance; route counts are not result counts.

Equivalent block solutions also retain distinct conventional erasure/enumeration routes, including those found later. A conventional representative is chosen by larger RS margin, fewer search bits, fewer target symbols, then lower penalty; exact ties preserve discovery order. A conventional route remains representative when an equivalent binary-constraint route exists. Combination traversal independently uses the lowest penalty of all retained routes. Improving that traversal rank does not rewrite the representative's own metrics.

Final payload validation has the highest priority. Among candidates without a valid payload, format disagreement is a stronger ranking condition than mathematical RS success. Thus an RS-consistent solution that substantially contradicts format information is not considered diagnostically better than a format-consistent attempt with a small erasure-capacity shortfall. Candidates that overturn substantial known evidence, violate manual locks, or break structural rules are rejected or ranked lower.

When multiple RS blocks have alternatives, combinations are examined in an initial ranked window of 4096, doubling to 8192, 16,384, 32,768, and 65,536 while no decoded payload has been found. A candidate that has valid QR bitstream structure but cannot be decoded by ZXing does not stop this search. Reaching the bound without a decoded payload is reported as a combination-search limit.

The best-first prefix traversal uses lazy siblings rather than a visited Cartesian grid. Each format/structure pass also has a 65,536 expanded-prefix-node limit, including prefixes rejected by QR validation. This bounds work and frontier memory even if almost no complete combination is valid. Hitting either bound is a search truncation, not proof that no valid payload exists.

If no recovery candidate is produced, the engine retains a representative failure for the format-specific reference diagnosis and cell guidance. It is ranked by format disagreement, erasure-capacity bit shortfall, agreement with known bits, and erasure evidence. Separate search-wide evidence aggregates input restrictions, candidate rejections, and unsearched ranges across formats, geometries, and sampling profiles. Final explanations use this aggregate rather than treating one failure as the reason for the entire search. The original matrix remains unchanged.

While combining RS blocks, a prefix can end inside the next mode indicator. An incomplete tail alone does not reject the prefix; the complete stream still undergoes final validation of its terminator, byte alignment, and padding. Passing prefix validation does not constitute successful decoding.

QR combination-search diagnostics are shown on success as well as failure. They distinguish a window ending after success, the combination limit, and the expanded-node limit. An unexplored remainder in any pass is retained even if an unconstrained retry exhausts its combinations. Displayed candidate counts cover retained validated results, not every candidate found during search. Exhausting attempted passes does not imply exhaustive formats, geometries, or solution spaces.

The eight-bit FNC1 second-position application indicator is retained separately from the body. Only the private decoding view sent to ZXing 3.5.4 omits that field; original codewords, RS consistency, structure validation and mismatch counts use the original stream. This also applies after a Structured Append header.

Kanji and Hanzi validation maps each 13-bit value to its character code and checks the QR byte ranges and Shift_JIS/GB2312 assignments. Silently replacing an invalid sequence does not count as successful decoding. Prefix validation applies the same check once all 13 character bits are available. These character restrictions do not apply to arbitrary Byte-mode binary data.

Structured Append is permitted only once at the beginning. Validation requires 2–16 symbols and a zero-based index smaller than the total. Index, total and eight-bit parity are retained in the result. The app repairs individual symbols; it does not assemble multiple symbols or verify whole-message parity.

## 6. Explanation of results

Recovery retains at most the best-ranked 256 distinct QR candidates throughout each invocation and across geometries and polarities. Higher-ranked results can displace earlier results; lower-ranked details are released without stopping the search window. Representative input matrices and shared explanation contexts count toward the result allowance. Alternative derivation details are also bounded, and omitted details are disclosed separately from incomplete exploration. Cell explanations are generated on access.

The Android app derives separate result, current-trial, and solver allowances from available Java heap, reserving additional room for display and runtime allocations. These are conservative accounting estimates, not a measurement or hard cap of the whole Android process. Solver working-memory limits are checked before materializing block-candidate lists and expanding the combination frontier. A working-memory stop reports unchecked combinations. Failed trials retain only the best reference matrix, while compact search diagnostics and attempted-geometry metadata cover the full search.

Candidate agreement and candidate-based check guidance compare distinct candidates decoded as text from the same input matrix as the selected candidate. A full QR matrix is generated for each compared candidate, so the comparison uses at most the top 16 to bound memory and computation. If the selected decoded candidate is outside that range, it replaces the last entry. The compared count and the retained decoded-candidate count for that input are explicit; sample agreement is not treated as agreement among all candidates. Candidates that pass QR structure validation without decoding as text can be selected and exported but are excluded from this comparison.

History in QRR schema 19 stores the selected QR’s version, ECC level, mask, corrected codewords, and actual sampling polarity. Recalculation restores selection by QR identity rather than list position; if no candidate matches, selection remains empty. Actual polarity is recorded separately from the user’s automatic/normal/inverted search preference. Older histories without QR identity retain the saved-index restoration path. User-facing history count and size limits are described in the [User Guide](USER_GUIDE.en.md#7-save-resume-and-export).

When conflicts with fixed values are not allowed, data/ECC pin conflicts reject the candidate and retain conflicting coordinates, input values, and required candidate values. Otherwise equivalent generic payload failures do not overwrite actionable diagnostics. Temporary priority assignments are not manual input pins.

The result matrix retains a repair reason for each changed module. Tapping a cell displays its value, provenance, applicable structure/constraint/RS operations, and explanatory confidence. The eight modules belonging to its codeword are highlighted only after the user presses “Show same Codeword.”

Cells uniquely determined after recovery are rendered as 0 or 1. Modules that are irrelevant to decoding, such as QR remainder positions, are distinguished from genuinely unresolved `?` values.

When no concrete recovery candidate exists but a nonempty ambiguous exact RS space does, a conditional additional-check cell can still be shown. Only unknown data/ECC coordinates are eligible, never Finder/function/remainder cells. Every nonconstant coordinate halves an affine space; independent coordinates ensure successive measurements remain useful. Ties prefer higher source-image confidence, which is only a readability heuristic. The UI shows a one-bit reduction in RS freedom and explicitly states the exact-known-bit/format assumption; these are not counts of validated QR candidates or a guarantee for all error-allowing solutions. An inconsistent or zero-dimensional space supplies no such recommendation.

## 7. Termination and limitations

- Work invalidated by cancellation or a newer settings generation cannot publish a stale result to the UI.
- A platform-independent cancellation check also stops computation at geometry, format, block, binary elimination, payload-plan and assignment/prefix traversal boundaries. Android checks the request generation and thread interruption. Cancellation escapes hypothesis fallback handling and is not reported as QR failure; it is cooperative, not an immediate interruption of every individual arithmetic operation.
- Saving preprocessing settings invalidates queued and running recovery results and clears the previous selection. Recovery and history recalculation capture their settings before entering the worker queue; sampling and decoding use that same snapshot.
- Geometry, format, and bit-enumeration stages have explicit bounds.
- Model 1, Micro QR, and rMQR are unsupported.
- Strong glare, extreme blur, missing module centers, or incorrectly locked version/corners can prevent recovery even within nominal ECC capacity.
- Distinct RS-consistent solutions can exist in principle. Independently verify important recovered information.

## 8. Implementation boundary

The main primitives for finder detection, geometry, tri-state matrices, GF(256) RS, partial-bit search, and payload constraints live under `shared/src/commonMain`. ZXing-dependent version/format/block interleave and final payload decoding currently live under `androidApp`. This keeps the shared core independent of Android and JVM-only APIs.

## 9. Quick glossary

| Term | Meaning in this document |
| --- | --- |
| Module / cell | One square element of the QR symbol; an editable cell in the UI |
| Codeword | Eight bits extracted in placement order; a data or ECC unit |
| Erasure | A codeword whose position is known but value is incomplete; a codeword containing `?` may qualify |
| Error | A position supplied as known whose value must change in the corrected result |
| Format information | The ECC level and mask information stored in two copies in a QR symbol |
| Geometry hypothesis | One mapping from logical module centers to sampling positions in the image |
| Provenance | The route that supplied a value: image, user, fixed structure, constraint, RS, and so on |
| RS consistency | Satisfaction of Reed–Solomon equations; not synonymous with correct QR content |
| Validated candidate | A candidate that passed QR structure and final decoding; uniqueness is separate |
| Degrees of freedom | Unknown bits that remain independently selectable in a partial-bit RS solution space |
