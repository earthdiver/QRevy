# QR Repair Recovery Strategy

English | [Japanese](RECOVERY_STRATEGY.ja.md)

This document describes how QR Repair currently builds, validates, and explains recovery candidates for damaged QR Code Model 2 images. It is an implementation overview, not a replacement for the QR specification.

## Start with the big picture

For a first reading, this section, the tri-state example in section 1, the pipeline and scenarios in section 2, and the limitations in section 7 provide the operating model without requiring every implementation detail. Sections 3–5 cover format information, Reed–Solomon (RS), and candidate ranking. UI fields and cell-level evidence are documented separately in [Understanding recovery result fields](RECOVERY_RESULT_FIELDS.en.md) and [Understanding decode and repair reasons](REPAIR_REASONS.en.md).

QR Repair does not guess one answer from an image and immediately declare it correct. It combines independent evidence from geometry, format information, error correction, and QR payload structure, then presents validated candidates and any remaining ambiguity to the user.

| Display or state | What has been established | What it does not establish by itself |
| --- | --- | --- |
| RS-consistent | The RS equations hold under the selected version, format, and known bits | The original QR content, correct geometry, or correct format |
| Validated candidate | QR payload structure and the specified input constraints passed in addition to RS | The real-world author's intended content or a unique solution |
| Decoded candidate | Final decoding of QR data also succeeded | That the decoded data matches the original content |
| Multiple candidates | Distinct solutions passed validation | Which candidate was the original content |
| No candidate | No validated candidate was found within the finite search performed | That no correct solution exists |
| Candidate agreement | The cell agrees across the decoded candidates selected for comparison | Mathematical uniqueness across unretained or unexplored solutions |
| Confidence | An explanatory comparison of image evidence and provenance | A statistical probability of correctness |

Recovery checks described as requiring fixed-value agreement in this document apply when **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** is OFF. ON allows correction while retaining fixed designations. Consistency with explicit ECC/mask selections remains enforced (section 3.1).

## 1. Principles

Recovery is the overall process of repairing a QR code; decoding interprets its data. QR data can contain text or binary data.

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

Even if the last line in the example is RS-consistent, it is not accepted on that basis alone. It becomes a validated candidate only after format, manual constraints, error limits, and QR payload structure also pass. Final decoding then checks whether the QR data can be interpreted.

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

Transparency is composited onto white before tone adjustments. Display, luminance sampling, color-marker classification, and immediate decoding use the same opaque pixels. New sessions store the unadjusted white-backed source; verified transparent sources from saved sessions are composited when prepared for analysis.

The working image is converted to luminance values that represent each pixel’s brightness. If it is not known whether black and white are reversed, both normal and inverted interpretations can be tried. Once a confirmed Finder pattern establishes which interpretation to use, reading follows that result. When quiet-zone correction is enabled, the image used to find these patterns receives a margin representing QR white (0).

Contrast and brightness are bounded integer settings from −100 to 100. Each RGB channel uses gain `2^(contrast/50)` about 127.5 and an offset of `2.55 * brightness`, followed by optional inversion and clamping to 0–255. Geometry is applied to the unadjusted source before tones; display pixels and sampled luminance share this transform. Color-marker classification retains the corresponding original RGB values. Decoder polarity describes the adjusted image, so importing an inverted image does not also force inverted decoding. QRR stores the original image and adjustment parameters separately.

### 2.2 Initial version and corner estimation

On new image import, the app first validates Model 2 boundaries from ZXing-C++ and converts accepted boundaries to outer module centers. It searches versions 1–40 and logical rotations; native decoded text is not used as a recovery result. Multiple boundaries can be merged only if all agree on the same QR.

Boundary validation estimates one common luminance threshold from pixels inside the detected quadrilateral, excluding the surrounding background and missing image area. It does not use per-cell or regional thresholds. An outer boundary may extend beyond the image only when all outermost module centers remain inside; otherwise that grid candidate is rejected.

If native boundaries cannot be accepted, the app tries grid periodicity, then geometry from two individually detected Finders. Normal and inverted interpretations are checked. Conflicting periodicity results stop automatic estimation rather than falling through to another estimator; multiple native boundaries also disable the two-Finder route. A two-Finder estimate covers version 2 and later, approximately under a similarity transform. A diagonal pair can establish top-right and bottom-left roles from both timing axes; otherwise a verified lower-right alignment pattern may support corners without assigning roles.

The estimates are editable starting values. If no estimate is accepted, the app displays version 1 and default corners. Recovery treats the displayed corners and selected version as confirmed inputs, so review them before repairing.

Physical corner positions and logical orientation are separate. Strong native evidence can automatically assign all three Finder roles; a verified diagonal pair can assign two. Periodicity alone does not assign roles. Automatic assignment runs only on new import and is recorded in history. Clearing all roles makes logical orientation unassigned while retaining corners and cell edits, and saved assignments are not re-estimated on reopening.

See [Initial grid estimation and automatic Finder assignment](ZXING_CPP_INTEGRATION.en.md) for the mismatch thresholds, ambiguity gates, polarity handling, and validation measurements. These are heuristics, not correctness probabilities.

### 2.3 Manual Finder-role assignment

Roles can also be assigned manually, whether or not automatic assignment was available. The user chooses a top-left, top-right, or bottom-left role and taps its approximate center. The app looks for a square pattern near the selected point. If it can verify a Finder pattern, it adjusts the center and determines whether black and white are reversed. If no Finder is detected, the tapped point remains in use. Once a Finder role has established the logical grid, however, a subsequent Finder assignment is rejected when it is far from the expected position. This local refinement does not decide Finder roles automatically.

The search around the selected position tries both normal and inverted interpretations when **Auto** is selected and neither has been established. If a Finder pattern has already established which interpretation to use, the search follows that result. Otherwise, selecting **Normal** or **Inverted** limits the search to that choice. A detected center is expressed as a position in the original image.

Automatic Model 1 classification is not implemented yet. Input must therefore be limited to Model 2 symbols.

The following table counts currently assigned Finder roles during recovery, including automatic and manual assignments that have not been cleared. It does not count Finder-like shapes visible in the image.

This document calls the search around the selected grid enabled by **Automatically fine-tune sampling position** the **neighborhood search**.

| Assigned roles | Logical orientation | Base hypotheses with neighborhood search | Important qualification |
| --- | --- | --- | --- |
| 0 | Use the displayed corners and default logical orientation | At most 19 | No global Finder search or automatic role assignment during recovery |
| 1 | Treat the assigned logical corner as a pivot | At most 21 | Add clockwise and counterclockwise rotations to the ordinary 19 |
| 2 | Fix orientation from the two roles and corners | At most 19 | Do not add the two rotation hypotheses |
| 3 | Use the fully explicit role assignment | At most 19 | Incorrect roles can prevent the correct recovery |

When **Automatically fine-tune sampling position** is ON, version 2 and later may add at most one alignment-refined variant for each base hypothesis. The next section gives the complete bounds.

### 2.4 Geometry hypotheses

Recovery fixes the logically assigned corners and selected version. It first creates the selected geometry as one base hypothesis. Enabling “Automatically fine-tune sampling position” in advanced settings expands the base set to at most 19 hypotheses without changing logical orientation or version: one baseline, four axial translations, four diagonal translations, two overall-scale variants, and eight variants that move one corner inward or outward. Only the 0.6-module width is used. Diagonal components are `0.6 / √2`, and scale factors are chosen so the mean outer displacement of the four corners is 0.6 module; axial, diagonal, and scale comparisons therefore use the same displacement magnitude.

If exactly one Finder role is assigned, two more base hypotheses rotate clockwise and counterclockwise around its logical corner, for 21 base hypotheses in total. The angle is chosen so the farthest outer corner moves by 0.6 module. No rotation hypothesis is added with zero or at least two assigned Finder roles. These neighborhood transformations are not combined with one another, and logical orientation and version remain unchanged.

Only when **Automatically fine-tune sampling position** is ON, for each base hypothesis with sufficient interior alignment-pattern evidence in version 2 or later, the engine adds one alignment-refined geometry. The observed alignment pattern must differ from its predicted position by 0.1 to 1.25 modules; refinement moves only the nearest logical corner to fit that observation. A refinement is rejected if it materially worsens the independent timing pattern score. It never replaces the user-selected geometry: both the base and refined hypotheses are evaluated. If alignment refinement succeeds for every base hypothesis, the resulting maxima are one hypothesis with **Automatically fine-tune sampling position** OFF, 38 with neighborhood search, and 42 with neighborhood search plus exactly one assigned Finder role. The engine-wide bound is 72 geometry hypotheses.

Every geometry hypothesis uses fixed and tentative edits as input at the same cell coordinates. Sampled image values do not need to match the edits. With **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** OFF, candidates that contradict fixed values are rejected. ON permits correction, subject to error limits and explicit QR settings.

### 2.5 Module sampling

On image import, the whole-image two-/three-class luminance estimate supplies provisional thresholds, with 96/160 as the fallback. This analysis is reused until the analysis image changes. Automatic mode estimates one global pair for each grid, including corner adjustments and recovery sampling fine-tuning. Known finder, separator, timing and alignment cells provide black/white evidence; user edits and repaired bits are not training labels. Support is checked in a 3×3 partition, without applying regional luminance correction. Separated observation envelopes produce adjacent thresholds; overlapping envelopes leave an unknown band. A stable middle histogram group is associated with black or white only when function-pattern evidence supports it across regions; otherwise its interval is included in the unknown band. Sparse or unreliable function evidence uses the image-wide provisional pair, independently of earlier geometry trials.

Before using the provisional thresholds from the whole source image for an inverted QR code, the app converts them to preserve the same brightness ranges after inversion. The new dark threshold is `255 − original light threshold`, and the new light threshold is `255 − original dark threshold`. For example, thresholds of 40/80 become 175/215. Thresholds estimated from the grid or set manually already refer to brightness after accounting for inversion, so they are used without this conversion.

The **Set thresholds automatically** checkbox starts checked for a new image. Changing either threshold slider unchecks it and makes the whole pair manual. Unchecking it directly preserves the current pair. That pair is kept through corner movement and repair fine-tuning; checking the box again recalculates the thresholds and resumes automatic mode. The automatic/manual choice, effective thresholds and provisional fallback are saved with the session.

Colored unknown regions are initially estimated as approximate clusters in Lab color space. Cluster acceptance uses color frequency, color-density, and separation from black and white; it does not use spatial grouping, module size, finders, or version. “Treat detected color clusters as unknown (?),” directly below the Version button in basic settings, starts disabled and applies detected clusters only when enabled. Achromatic gray regions are handled by the luminance thresholds instead.

For each module, a central 3×3 sample at offsets −0.16/0/+0.16 module is measured at nine center positions shifted by −0.1/0/+0.1 module per axis. The resulting observation range is classified with the same global pair in preview and repair. Luminance is rounded to an integer; values in the threshold gap, or observations whose classification changes under these small shifts, become `?`. The stability check also applies to manual thresholds. Adjacent thresholds do not introduce a gap solely from a fractional mean. A configured color cluster also produces `?` when its pixels occupy at least half of the central sampling area. Fixed and tentative `0 / 1 / ?` edits override resampling and retain their provenance. Tentative values remain correctable by recovery; when fixed-value correction is disabled, candidates contradicting fixed values are rejected.

Image coordinates for module centers must satisfy `0 ≤ x < width` and `0 ≤ y < height`. Samples requiring pixels beyond the available interpolation support (`0…width−1`, `0…height−1`) are invalid, without white padding or edge replication. Each of the nine shifted positions averages only valid samples and requires at least five of its nine samples. A center outside the image or insufficient coverage at any shifted position produces `?`.

The automatic estimator requires at least four black and four white observations per supporting region, black-before-white agreement of at least 0.8, and a median separation greater than twice the observation allowance. At least eight observations of each class must be supported overall. The allowance is the larger of 0.75 luminance units and the maximum center-shift deviation; envelopes are not trimmed. Middle-group detection requires at least six observations and 2% of all observed cells per group, valleys no higher than half the lower neighboring peak at smoothing scales 1/2/4, adjacent peaks more than two luminance units apart, and middle-peak movement no greater than six. Associating that group with a class requires eight supporting function observations in at least two regions and none of the opposite class. These fixed heuristics provide editable initial values; overlapping lighting and damage can still require manual adjustment.

### 2.6 Reading three common scenarios

These synthetic scenarios explain how to use the mechanisms. They are not test vectors derived from a particular image and do not promise recovery.

#### Example A: only one Finder can be trusted

1. The user confirms the version and corners, then assigns the correct logical role to the one trusted Finder.
2. Enabling **Automatically fine-tune sampling position** adds two pivot rotations to the ordinary 19 base hypotheses.
3. Every hypothesis retains the same version and logical orientation. With **Automatically fine-tune sampling position** ON, an alignment-refined variant is evaluated separately when supported by image evidence.
4. If multiple validated candidates remain, the app retains them and reports their agreement instead of choosing one silently.

Seeing one Finder-like shape does not trigger this route automatically. Role assignment is a user decision, and the rotation hypotheses do not change the selected version or logical orientation.

#### Example B: gray damage covers several cells

1. Black/unknown/white thresholds preserve the gray region as `?` instead of forcing an unreliable known value.
2. Codewords containing `?` can enter ordinary erasure decoding, bounded partial-bit search, and RS partial-bit constraints.
3. Even a unique RS solution is conditional on the format and known bits. QR payload structure and final decoding still run.
4. If the solution space remains ambiguous, the app may recommend a cell whose visual confirmation reduces RS degrees of freedom by one bit.

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

A BCH-trusted candidate becomes the confirmed format for that geometry when it satisfies the manual-value consistency conditions in section 3.2. Failure of RS recovery or final payload validation does not cause a fallback to the other 31 formats. A provisional candidate is not confirmed: it stops the search only after a decoded candidate is found; otherwise the engine falls back to the remaining candidate set, at most 31 formats after excluding the attempted one.

The BCH-confirmed and provisional single-format fast paths apply when both ECC and mask are automatic. With either selection explicit, recovery tries the permitted set in rank order: at most eight formats for ECC only, four for mask only, and one for both. A partial explicit selection does not narrow the set to a BCH-trusted format alone. Format-information error limits and manual pins further restrict the attempted set.

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

A format-information error limit of zero requires exact agreement at compared known positions. When **both** ECC and mask are explicit, the selected format must also satisfy this limit. Fixed-value conflicts are still rejected by the consistency check. Format cells never count toward the data/ECC error limit.

When ECC and mask are automatic and conflicts with fixed values are not allowed, a BCH-correctable candidate is checked against fixed 0/1 values in format, version, function, and remainder modules before confirmation. A conflict prevents confirmation and leaves other compatible formats eligible. Tentative values do not create this veto, but format-information error limits still apply.

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

This document uses **Single-segment assumption**, the diagnostic label, for the advanced setting “When the mode and character/byte count are read or assigned by \* trials, assume a single segment and force the following structure.”

Ordinary structure propagation enumerates single-segment Numeric, Alphanumeric, Byte, and Kanji hypotheses. Among plans compatible with known bits, it selects those with the most known terminator, byte-alignment, and padding matches; fewer than six matches disables filling. It fills only values shared by all selected plans. This is inference within selected single-segment hypotheses, not proof of uniqueness across all QR streams, including multiple segments. Reaching the plan-enumeration limit also disables filling. With **Single-segment assumption** OFF, mutations by these heuristic constraints retain an unconstrained retry, even after a successful constrained result. With this setting ON, recovery never retries without that condition; final candidates must preserve the observed or priority-assigned mode/count header and its terminator, alignment, and padding.

**Single-segment assumption** applies to QR content consisting of exactly one Numeric, Alphanumeric, Byte, or Kanji segment. It forces the area immediately after that segment to the terminator, byte-alignment, and padding values, replacing conflicting known image-derived 0/1 values as well. Fixed 0/1 edits may be corrected only when **Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** is ON; tentative edits may be corrected.

Trust applies to the mode and character/byte count. An observed numeric or alphanumeric body outside its valid range does not disable suffix forcing; RS correction and final validation still handle the body itself.

This option is selectable when an eligible format has a leading Numeric, Alphanumeric, Byte, or Kanji mode/count header that is known or can be completed by explicit `*` assignments. Ordinary `?` bits cannot complete this eligibility check. Each priority assignment is evaluated separately before suffix forcing; assigned header values remain hypotheses in the repair evidence. Automatic BCH confirmation restricts this check to the confirmed format. General diagnostics may recognize ECI, FNC1, structured-append prefixes, or Hanzi, but those do not qualify for suffix forcing. If an enabled condition becomes inapplicable, it remains ON and reports the reason; no candidate is accepted by silently relaxing it. Absence of another segment in an unknown region cannot be established automatically; the user must still confirm the single-segment assumption.

Conventional RS decoding corrects errors and erasures in each block over GF(256), with the capacity relation `2 × errors + erasures <= ECC symbols`. RS partial-bit constraints also use known bits inside partial codewords, as described below. Mathematical RS success alone does not make a candidate correct.

List decoding can produce multiple candidates for errors beyond the correction range of conventional RS decoding. The app automatically selects Guruswami–Sudan (GS), Koetter–Vardy (KV), or Wu decoding according to the input and estimated computation. Section 4.6 describes the search order and applicable conditions.

### 4.1 Data/ECC error limit and manual input values

Enter an error limit directly and adjust it one bit at a time with −/＋. Format limits range from 0 to 30 bits. Data/ECC limits range from 0 to eight times the total codewords of the selected version, independent of ECC level. Blank, non-integer, and out-of-range values cannot be applied. If a version change makes the existing limit too large, the app requests a new value without changing it automatically. Saving settings or starting repair requires a valid limit or Unlimited.

For every RS-corrected candidate, the final codewords are mapped back to QR modules with the selected mask reapplied and compared bit by bit against input `0 / 1` values known in data/ECC modules. Unknown (`?`) modules, format information, version information, function modules, and remainder bits are excluded from this comparison.

When the data/ECC error limit is set, only candidates whose mismatch count is no greater than the limit are retained. `0 bits` requires an exact match against every comparable known data/ECC bit. With Unlimited, the ordinary routes use mismatch counts for ranking without a numeric rejection cap. List-decoding fallback also applies a 5% cap (section 4.6). An image-derived known value replaced by forced payload structure is also counted as a mismatch.

When conflicts with fixed values are not allowed, a candidate that contradicts a fixed value is rejected before applying the error-limit allowance. Such a conflict never consumes one bit of the allowance. For tentative cells and fixed cells with conflicting candidates allowed, a manually entered `0 / 1` is treated like an image-derived known bit: RS may change it, and any change counts toward the mismatch limit. A manually locked bit that agrees with the candidate is included in the known-bit denominator.

If no candidate remains within the limit, the app distinguishes this from conventional RS failure. For the best limit-exceeded attempt, it reports the actual mismatch count, known-bit count, and configured limit. With a zero limit, an inconsistent exact-bit system is reported directly as no exact known-bit solution; an error count is not invented.

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

Version information, other function modules, and remainder bits also stay outside the format-information error limit. Conflicting locked manual values in these regions produce a separate diagnostic with coordinates, manual values, and expected values. A positive or unlimited data/ECC limit never permits overriding locked manual values. At limit zero, all compared known data/ECC bits must agree even for tentative cells.

### 4.2 Priority `*` search

`*` markers may be assigned only to data/ECC modules. The default limit is four; advanced settings allows 4–14. The selected count determines the displayed assignment count (`2^count`, up to 16,384). Lowering the limit preserves existing marks; repair is blocked until the count fits the limit. In the cell editors, first assign the four corner positions and roles. Selecting fixed structures or remainder bits displays their type and coordinates. Orientation changes recheck eligibility and turn ineligible `*` marks back into `?`. Version changes clear all cell edits after confirmation.

When cells are marked with `*`, RS partial-bit constraints, conventional RS with partial-bit search, and additional list decoding each use the following sequence. See section 4.6 for the overall search order.

1. First treat every marked cell as an ordinary erasure.
2. If that fails, try `2^count` assignments (up to `2^14 = 16384`) for marked bits only.
3. Within each assignment trial, also allow the ordinary bounded search for other `?` bits; retain only payload-valid candidates.
4. If distinct solutions remain, retain multiple candidates and do not declare an assignment unique.
5. If all assignments fail, continue to the ordinary bounded `?` search using the original observation without priority guesses.

In step 1, QR structure such as the mode, character count, or terminator may determine a cell's value. If decoding succeeds with that filling alone, no 0/1 assignments are tried. Repair reasons distinguish values inferred from structure from tentative 0/1 assignments.

Temporary priority 0/1 assignments constrain only their trial; they are not known input bits. Known-bit counts, mismatch counts, original erased-codeword counts, and Before codeword views use the observation before those assignments. RS erasure counts and correction margins still describe the trial. Explicitly enumerated coordinates and values are retained in candidate provenance and displayed separately from ordinary partial-bit search. Other cells in the same codeword do not inherit priority-assignment provenance. Success on the ordinary route is not recorded as explicit priority enumeration.

The ordinary search inside each priority trial uses the per-block bounds in section 4.3. The maximum of 16,384 priority assignments is separate from the selected maximum of 4,096, 8,192, or 16,384 conventional enumeration trials.

See the [user guide](USER_GUIDE.en.md#4-edit-cells) for progress display and interruption controls.

### 4.3 Ordinary partial-bit search

When erasure decoding fails, or when the combined error/erasure correction margin is small, partial codewords with more known evidence are prioritized for a bounded enumeration: a shared per-block limit of 12, 13, or 14 bits (default 12), with at most 4,096, 8,192, or 16,384 trials respectively. The same setting bounds the remaining dimension enumerated using RS partial-bit constraints. The solver fixes enough partial erasures, within those bounds, to reserve capacity for multiple unknown-position errors.

These are per-block limits. Only codewords with one to four unknown bits are eligible for conventional enumeration. They are selected by increasing unknown-bit count, preserving local codeword order on ties. Codewords with five to eight unknown bits remain erasures on this route; RS partial-bit constraints may still use them.

### 4.4 RS partial-bit constraints

RS partial-bit constraints find values for unknown bits that satisfy the RS equations while preserving known bits.

For each format interpretation and RS block, expand the GF(256) zero-syndrome equations into GF(2) equations on individual unknown bits. All known data and ECC bits are constants. Row reduction produces either an inconsistent system or an affine space with dimension `d = unknown bits − rank`. The solver computes bits fixed across every solution and a set of independent measurement bits without enumerating that space.

- With a data/ECC error limit of zero, this method supplies candidates. With a positive or unlimited allowance, conventional RS and list decoding continue even when this method finds candidates.
- See section 4.2 for how this combines with priority `*` search.
- Diagnostics and measurement recommendations describe the input before structure filling or temporary `*` assignments.
- Fixed-bit values never overwrite the input or become manual pins. A completed candidate carries RS partial-bit provenance.
- Incorrect known ECC bits can make the system inconsistent **or** produce a consistent but incorrect solution. Payload validation, manual locks and final mismatch checks remain mandatory.

Candidates are generated and validated in the following order. Multiple segments and binary byte data are also supported.

1. **Add structural conditions to the RS equations.** Examine modes and character/byte counts consistent with the known bits. For example, a hypothesis of “byte mode with 17 bytes” adds conditions that set the mode and character-count indicators to those values. Also add the rules for bits marking the end of the data and filling unused space, and discard hypotheses whose conditions conflict.
2. **Check whether enumeration is allowed.** Determine the remaining degrees of freedom in each RS block after applying these conditions. Enumerate when every block is within the selected partial-bit search limit (12, 13, or 14).
3. **Exclude invalid combinations, then generate and validate candidates.** For numeric and alphanumeric data, exclude combinations outside the allowed value ranges before generating candidates. For example, the ten bits encoding three numeric digits must represent a value below 1,000. Generate the remaining candidates one at a time and validate them as QR codes.

Splitting hypotheses or value ranges is limited to 4,096 branch steps per combination traversal. Search work, including condition checks and candidate generation, is limited to 65,536 nodes per format/structure trial. QR-combination limits also apply.

Conventional RS and list decoding also validate QR structure and reject invalid candidates. With **Single-segment assumption** OFF, misread header bits are also eligible for correction.

For example, twenty one-bit erasures scattered across twenty V1-L codewords can be uniquely solved when the binary rank is twenty, even though the conventional seven-symbol erasure budget and twelve-bit enumeration cannot resolve them.

### 4.5 Error-limit preset rationale

Both controls offer Default, Exact match (0 bits), Strict, Relaxed, Manual, and Unlimited. The [user guide](USER_GUIDE.en.md#5-choose-advanced-settings) describes selection and version changes. The presets are operational compromises, not correctness guarantees.

- **Format information:** Strict and Default use 6 bits across both copies; Relaxed uses 9 bits, independent of version. The repeated 15-bit format code has minimum distance 14 when all 30 bits are known, so a radius of 6 admits at most one format. Enumeration of all 207,025 placements of three errors in each copy retained only the true format at 6 bits. Nine bits also retains a clean copy paired with an eight-error copy, but can admit alternatives. Unknown cells do not count; fully unknown format information cannot be narrowed by these caps.
- **Data/ECC:** Strict uses 0.875% of all data/ECC bits, rounded up, following the conservative-policy evaluation. Relaxed uses 10%, rounded up. Default selects Strict for V1–4 and Relaxed for V5–40, reflecting the intended use of manual visual inspection for smaller symbols; the version boundary is a workflow choice, not a measured accuracy threshold.
- **Relaxed-policy comparison:** Across 8,012 synthetic conditions and 32,048 recovery calls, 5% retained the exact original QR in 2,627 conditions and admitted another decoded payload in 32; 10% retained 3,231 and admitted another payload in 37. Unlimited retained 3,874 and admitted another payload in 45. Thus 10% was chosen as a balance between correct recovery and suppressing alternatives. Correct and alternative payloads can coexist in one condition.

The conditions share generated source symbols and are not independent real photographs. Results depend on damage distribution, seed, scoring weights, and finite search/retention budgets. There was no fresh holdout after selecting 10%, and limits cannot reject alternatives that require zero changes to known bits. Reusable evaluation and enumeration commands are in the [study tools](../tools/detector-study/README.md#synthetic-dataecc-error-limit-study); detailed run archives are local research artifacts, not required product documentation.

QRR schema 25 persists each limit mode separately from its remembered manual value. Exact match stays at zero across versions. Explicit ECC/mask selections and fixed-cell protection retain their separate rules.

### 4.6 Automatic list-decoding fallback

For each sampled input, recovery proceeds in the following order. A “tentative assignment” means trying 0 or 1 for an unknown bit.

1. Apply conventional RS to the original input, without structure filling or tentative assignments.
2. Try list decoding without tentative assignments. Defer work that does not finish within one second to step 5.
3. Find candidates using RS partial-bit constraints (section 4.4).
4. Try conventional RS with tentative assignments from partial-bit search or priority `*` search.
5. Try additional list decoding, including tentative assignments and retrying unfinished work from step 2.

If step 1 produces a decoded candidate meeting the selected conditions, the search for that input ends. Candidates found in step 2 or later are retained while subsequent searches continue.

Steps 2, 4, and 5 are skipped when the **Data/ECC error limit** is zero.

GS and Wu treat codewords with remaining unknown bits as erasures and target one more erroneous codeword than conventional RS can correct. Where the strict Johnson bound permits this radius, the app selects a method using estimated work and working memory. GS interpolates over non-erased positions and evaluates candidate polynomials at all original positions. Wu uses error-locator polynomials; at this radius, constant and linear coefficient searches suffice. The same radius is not searched again with the other method.

Before enumerating unknown-bit assignments, KV uses the known bits within partial codewords. For codewords with one to four unknown bits, it distributes interpolation weights (multiplicities) equally among compatible values. More ambiguous codewords are omitted from interpolation. These weights are neither image confidence scores nor probabilities of correctness. KV is tried only when its candidate score conditions and estimated computation make it useful. If no new decoded candidate results, partial-bit search continues.

Partial-bit search follows section 4.3, assigning 0/1 only to originally unknown bits, then applying GS or Wu to each assignment. An input with too many erased codewords may become decodable through KV's use of partial information or assignments that reduce the erasure count. Every candidate must satisfy RS consistency and its method's radius or agreement-score condition. Decoding of a radius is skipped when, for example, a conventional RS result proves that no other candidate can lie within it.

Known-bit mismatches are capped at **5% of all data/ECC bits in the QR, rounded up**, or the **Data/ECC error limit** if smaller. Comparisons use the original input before structure inference or temporary assignments, including known bits inside partially erased codewords. Filling unknown bits does not count. Mismatches are summed across block combinations, and candidates exceeding the cap are rejected before QR validation.

Each trial's candidates are merged with conventional RS candidates, and QR structure and payload checks run as candidates are added. Alternatives are sought even for blocks that conventional RS decoded successfully. Rediscovering an existing decoded candidate does not end the additional search. Input conditions, including fixed cells and priority assignments, are enforced, and multiple valid candidates are retained. Each format/structure pass allows at most 65,536 combinations and 65,536 expanded search nodes.

List decoding in steps 2 and 5 shares **at most five cumulative minutes per repair operation** across all three methods, excluding ordinary search time. Computation can be interrupted; timeout, insufficient memory, or interruption retains validated candidates and reports why the search is unfinished. See [Reading recovery result fields](RECOVERY_RESULT_FIELDS.en.md#diagnostic-details) for trial counts and reasons for skipped work.

Algorithm references: [Guruswami–Sudan, errors and erasures](https://www.site.uottawa.ca/~zhcheng/list_dec.pdf), [Nielsen–Zeh, interpolation modules and root finding](https://arxiv.org/abs/1404.3022), [Koetter–Ma–Vardy, interpolation](https://arxiv.org/abs/1005.5734), [Wu, list decoding through error-locator polynomials](https://arxiv.org/abs/cs/0703105).

## 5. Candidate validation and ranking

Candidates are rejected for violating fixed values when conflicts are not allowed, contradicting fixed structures outside codewords, exceeding configured error limits, or failing QR structure validation. RS consistency alone is insufficient for retaining a candidate. Final decoding then checks whether the QR data in a validated candidate can be interpreted.

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

Identical version, ECC, mask, and corrected codewords are merged into one result across geometries and polarities. Other derivations retain their diagnostics and provenance within the retention allowance, with omissions disclosed. The main display uses the best derivation’s provenance; route counts are not result counts.

Equivalent block solutions also retain distinct conventional erasure/enumeration routes, including those found later. A conventional representative is chosen by larger RS margin, fewer search bits, fewer target symbols, then lower penalty; exact ties preserve discovery order. The conventional route remains representative when RS partial-bit constraints produce the same solution. Combination traversal independently uses the lowest penalty of all retained routes. Improving that traversal rank does not rewrite the representative's own metrics.

Final payload validation has the highest priority. Among candidates without a valid payload, format disagreement is a stronger ranking condition than mathematical RS success. Thus an RS-consistent solution that substantially contradicts format information is not considered diagnostically better than a format-consistent attempt with a small erasure-capacity shortfall. Candidates that overturn substantial known evidence, violate manual locks, or break structural rules are rejected or ranked lower.

When multiple RS blocks have alternatives, combinations are examined in an initial ranked window of 4096, doubling to 8192, 16,384, 32,768, and 65,536 while no decoded candidate has been found. A candidate that has valid QR bitstream structure but cannot be decoded by ZXing does not stop this search. Reaching the bound without a decoded candidate is reported as a combination-search limit.

The best-first prefix traversal uses lazy siblings rather than a visited Cartesian grid. Each format/structure pass also has a 65,536 expanded-prefix-node limit, including prefixes rejected by QR validation. This bounds work and frontier memory even if almost no complete combination is valid. Reaching either fixed bound completes the specified policy; it does not prove that no valid payload exists outside that policy.

If no recovery candidate is produced, the engine retains a representative failure for the format-specific reference diagnosis and cell guidance. It is ranked by format disagreement, erasure-capacity bit shortfall, agreement with known bits, and erasure evidence. Separate search-wide evidence aggregates input restrictions, candidate rejections, and unsearched ranges across formats, geometries, and sampling profiles. Final explanations use this aggregate rather than treating one failure as the reason for the entire search. The original matrix remains unchanged.

While combining RS blocks, a prefix can end inside the next mode indicator. An incomplete tail alone does not reject the prefix; the complete stream still undergoes final validation of its terminator, byte alignment, and padding. Passing prefix validation does not constitute successful decoding.

QR combination-search diagnostics distinguish a window ending after success, the combination limit, and the expanded-node limit.

Search completion means the specified conditions and bounded search policy have been processed. Skipping further search after conventional RS success, completing the selected partial-bit search (the selected 12, 13, or 14 bits / up to 4,096, 8,192, or 16,384 trials), symbolic analysis above the enumeration limit, and reaching combination/node/geometry limits are policy-defined endings. User interruption, the cumulative list-decoding time limit, insufficient working memory, or an internal error that prevents required work leaves the search unfinished; insufficient execution records make completion unknown. Candidate and explanation retention omissions are reported separately. See [Understanding recovery result fields](RECOVERY_RESULT_FIELDS.en.md) for the messages and their interpretation.

The eight-bit FNC1 second-position application indicator is retained separately from the body. Only the private decoding view sent to ZXing 3.5.4 omits that field; original codewords, RS consistency, structure validation and mismatch counts use the original stream. This also applies after a Structured Append header.

Kanji and Hanzi validation maps each 13-bit value to its character code and checks the QR byte ranges and Shift_JIS/GB2312 assignments. Silently replacing an invalid sequence does not count as successful decoding. Prefix validation applies the same check once all 13 character bits are available. These character restrictions do not apply to arbitrary Byte-mode binary data.

Structured Append is permitted only once at the beginning. Validation requires 2–16 symbols and a zero-based index smaller than the total. Index, total and eight-bit parity are retained in the result. The app repairs individual symbols; it does not assemble multiple symbols or verify whole-message parity.

## 6. Explanation of results

Recovery retains at most the best-ranked 256 distinct QR candidates throughout each invocation and across geometries and polarities. Higher-ranked results can displace earlier results; lower-ranked details are released without stopping the search window. Representative input matrices and shared explanation contexts count toward the result allowance. Alternative derivation details are also bounded, and omitted details are disclosed separately from incomplete exploration. Cell explanations are generated on access.

The Android app derives separate result, current-trial, and solver allowances from available Java heap, reserving additional room for display and runtime allocations. These are conservative accounting estimates, not a measurement or hard cap of the whole Android process. Working memory needed to retain RS solutions, narrow hypotheses, and generate candidates is estimated against the working-memory limit. If insufficient working memory stops the search, unchecked combinations are reported. Failed trials retain only the best reference matrix, while compact search diagnostics and attempted-geometry metadata cover the full search.

Candidate agreement and candidate-based check guidance compare distinct decoded candidates from the same input matrix as the selected candidate. A full QR matrix is generated for each compared candidate, so the comparison uses at most the top 16 to bound memory and computation. If the selected decoded candidate is outside that range, it replaces the last entry. The compared count and the retained decoded-candidate count for that input are explicit; sample agreement is not treated as agreement among all candidates. Candidates that pass QR structure validation without successful final decoding can be selected and exported but are excluded from this comparison.

History in QRR schema 25 stores the selected QR’s version, ECC level, mask, corrected codewords, and actual sampling polarity. Recalculation restores selection by QR identity rather than list position; if no candidate matches, selection remains empty. Actual polarity is recorded separately from the user’s automatic/normal/inverted search preference. User-facing history count and size limits are described in the [User Guide](USER_GUIDE.en.md#7-save-resume-and-export).

When conflicts with fixed values are not allowed, data/ECC pin conflicts reject the candidate and retain conflicting coordinates, input values, and required candidate values. Otherwise equivalent generic payload failures do not overwrite actionable diagnostics. Temporary priority assignments are not manual input pins.

The result matrix retains a repair reason for each changed module. Tapping a cell displays its value, provenance, applicable structure/constraint/RS operations, and explanatory confidence. The eight modules belonging to its codeword are highlighted only after the user presses “Show same codeword.”

Cells uniquely determined after recovery are rendered as 0 or 1. Modules that are irrelevant to decoding, such as QR remainder positions, are distinguished from genuinely unresolved `?` values.

When no concrete recovery candidate exists but a nonempty ambiguous exact RS space does, a conditional additional-check cell can still be shown. Only unknown data/ECC coordinates are eligible, never Finder/function/remainder cells. Every nonconstant coordinate halves an affine space; independent coordinates ensure successive measurements remain useful. Ties prefer higher source-image confidence, which is only a readability heuristic. The UI shows a one-bit reduction in RS degrees of freedom and explicitly states the exact-known-bit/format assumption; these are not counts of validated QR candidates or a guarantee for all error-allowing solutions. An inconsistent or zero-dimensional space supplies no such recommendation.

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
| Validated candidate | A candidate that passed QR structure and the specified input constraints; final decoding and uniqueness are separate |
| Decoded candidate | A validated candidate for which final decoding of QR data succeeded |
| Degrees of freedom | Unknown bits that remain independently selectable in an RS partial-bit constraint solution space |
