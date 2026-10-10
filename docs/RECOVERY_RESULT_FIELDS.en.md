# Understanding recovery result fields

English | [Japanese](RECOVERY_RESULT_FIELDS.ja.md)

The main content display and diagnostic details describe candidate generation and consistency. Confidence is not a statistical probability, and Reed–Solomon (RS) success alone does not prove correctness.

A **validated candidate** has passed QR structure validation and the specified input constraints. A **decoded candidate** has also passed final decoding of QR data. QR data can contain text or binary data.

Invalid Kanji/Hanzi character codes are rejected by structure validation even when RS is consistent; replacement text is not accepted as a successful character-mode result. These character-code checks do not apply to Byte-mode binary data.

## Identify the outcome first

List decoding (GS, KV, and Wu) can produce multiple candidates for errors beyond the correction range of conventional RS. See [Recovery Strategy, section 4.6](RECOVERY_STRATEGY.en.md#46-automatic-list-decoding-fallback) for the search order and applicable conditions.

| Outcome or warning | Meaning | Check first |
| --- | --- | --- |
| N recovery candidates meeting the specified conditions were found. | Validated candidates were retained; final decoding is reported separately | Content, distinct-content count, candidate agreement, and search completion |
| The QR data format was verified / The data cannot be displayed as text. | The selected candidate passed QR structure validation, but final decoding did not succeed | Data/codewords and repair evidence; the QR can still be selected and exported |
| No recovery candidate was found in the checked range. | No selectable candidate was retained; this can include RS-consistent values rejected by later checks | Input conditions, reference diagnosis, rejection reasons, and search completion |
| Combination/node limit | The specified search policy reached its normal boundary | Completion does not prove uniqueness |
| Candidates omitted (retention limit) | Only some of the candidates found are displayed | Omitted candidates are not included in the displayed count and cannot be selected |
| List-decoding time limit | The cumulative 600-second allowance for list-decoding fallback was reached | Validated candidates remain available; some list-decoding trials remain unchecked |
| Working-memory limit | Search stopped at the memory limit for calculation | Some combinations remain unchecked |

### Short reading example

Suppose three decoded candidates from the same reading attempt are compared. Candidate agreement of `2 / 3` means one candidate differs from the selected candidate at that cell. Inspect its source-image evidence and repair reason. Agreement of `3 / 3` still does not prove uniqueness across candidates outside the comparison or unexplored solutions.

## Results and counts

The result list shows up to 256 of the highest-ranked candidates from all reading attempts. Result switching, comparison of two candidates, and PNG export all use this list.

When many candidates are found, or memory limits apply, only some candidates may be displayed. The displayed count is the number of selectable candidates in the list, not necessarily the total number found.

“Others omitted” means some candidates that were found are absent from the list. “Some combinations remain unchecked” means required work within the specified search policy was not completed. Both notices can appear together.

Validated candidates can be selected and exported as a QR regardless of final decoding. Only decoded candidates support copying content and candidate-agreement analysis. Neither proves identity with the original content. Candidate count and search completion are separate: finding a candidate does not mean all required search work finished.

Each candidate represents a distinct recovered QR code. **Records yielding the same QR** shows other recovery methods and sampled-input attempts that produced that QR. Cell repair reasons describe the representative method.

The distinct-content count compares data-codeword sequences; equal displayed text can still count as different content. Switching candidates updates the QR display, the input before repair, and diagnostics to match that candidate.

When decoded text can also be interpreted as a URL, email, SMS, contact, or another structured type, the display shows the complete original string in addition to parsed fields. It is not silently truncated or replaced by selected fields. Copying preserves that same complete string.

When no candidate passes QR structure and the specified constraints, the app reports that no recovery candidate was found in the checked range and shows diagnostics. Unvalidated corrections do not overwrite the result matrix. The Data dialog initially shows Before with original partial codewords containing `?`. Switching to After does not expose an unvalidated corrected byte sequence. Result PNG export is unavailable for unvalidated candidates and failed attempts. A diagnostic input image can instead be exported from detailed repair settings.

## Partial input diagnostics

Partial diagnostics describe unvalidated input, not successful decoding. For a leading Numeric, Alphanumeric, Byte or Kanji segment, the view shows its mode, character/byte count and consecutive readable body prefix. Body display stops at unknown bits or invalid character values.

For Hanzi, the four-bit subset indicator after the mode is read before the character count. Only subset 1 (GB2312) permits reading the following count. A missing, unknown or unsupported indicator is reported without reading a count. Partial body display is not supported for Hanzi; this is separate from final Hanzi candidate validation and decoding.

## Input agreement and QR settings

| Field | Meaning |
|---|---|
| Error correction level | `L / M / Q / H`, with progressively more ECC codewords at the same version. Recovery depends on damage locations and codeword layout. |
| Mask | `0–7`. Modules are unmasked to extract codewords; the mask is reapplied after correction. |
| Format information: mismatches / known input bits compared at repair start | Disagreements with the candidate's BCH format value / compared known bits. Manually entered format bits determined by explicit ECC/mask selections are excluded. |
| Data/ECC region: mismatches / known input bits compared at repair start | Disagreements between known data/ECC input bits and corrected codewords mapped back through the mask / compared bits. |

Comparisons use known bits at repair start, including image sampling and manual edits. Zero compared bits leaves agreement unknown.

The format-information error limit applies even when explicit ECC and mask selections leave only one format. Among attempts without validated payloads, stronger format agreement takes precedence over RS success.

Data/ECC comparisons exclude `?`, temporary priority `*` assignments, function modules, and remainder bits. The data/ECC error limit retains candidates at or below the limit. `0 bits` uses partial-bit constraints to fill unknown bits and accepts only candidates matching every known bit. “Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)” defaults to OFF: candidates that conflict with fixed cells are rejected separately. ON allows their values to be corrected while retaining their fixed designation and input values. Tentative cells are correctable in either setting. Corrections remain subject to the format and data/ECC error limits; explicit ECC/mask/version selections and conflicts between fixed cells and explicit selections remain enforced.

List-decoding fallback caps known-bit mismatches at 5% of all data/ECC bits in the QR, rounded up, or the **Data/ECC error limit** if smaller. A zero limit disables list decoding.

## Diagnostic details

Diagnostics are organized into four sections.

| Section | What to check |
| --- | --- |
| Settings for this run | Settings used when recovery was run |
| Input before repair | Input at repair start and the conventional RS reference diagnostic |
| Processing in this run | Methods and calculation conditions used to obtain the candidate, and search records |
| Verification results | QR structure, final decoding, input mismatches, search completion, and candidate omissions |

The conventional RS reference diagnostic applies conventional RS to the input before structure filling or tentative assignments. It identifies the version, ECC, and mask used. Check **Verification results** for the outcome of the whole search.

If QR structure verification passes but final decoding fails, inspect the recovered content in **Data** in the detailed results.

**Processing in this run** contains these records:

- **Methods used to obtain this candidate**: recovery methods, applied assumptions, and intermediate filling or tentative assignments.
- **Calculation record for this candidate**: block values and formulas, the list-decoding mismatch limit, and combination-search stop reasons.
- **Records yielding the same QR**: other recovery methods and sampled-input attempts yielding the same QR, and their differences.
- **Search-wide records**: list-decoding trial count (newly executed calculations), reuse count for computed results, and elapsed time.

Search-wide records include trials that did not produce the selected candidate. GS/Wu counts by error radius cover all inputs and blocks; inputs may reach different radii. See [Recovery Strategy](RECOVERY_STRATEGY.en.md) for the search range.

Read search completion as follows:

| Message | Meaning |
| --- | --- |
| Search completed within the specified conditions and search policy. | The bounded policy finished; this does not mean every mathematical solution was enumerated or establish uniqueness |
| Some combinations remain unchecked. | Interruption, the list-decoding time limit, insufficient working memory, or an internal error prevented required work |
| Search completion could not be determined. | Execution records are insufficient to determine completion |

Validated candidates remain available for inspection and export after timeout, insufficient memory, or interruption. Completing the search without a candidate does not prove that no mathematical solution exists.

### Geometry

“Manual projective” uses selected corners. “Projective” includes refinement supported by alignment-pattern image evidence. Logical orientation and version are preserved. References such as “top-left reference” describe geometry, not payload reading direction. `V<version>` is the QR version; each side has `17 + 4 × version` modules.

### Unknown codewords before and after structure filling

- At input: codewords containing at least one unknown input bit.
- At recovery: codewords still containing unknown bits after this route's priority assignments and structure constraints, before RS processing.

Multiplying the difference by eight does not give repaired bits. Additional codewords resolved by conventional partial-bit enumeration are also not included in that difference.

### Codewords targeted for filling/correction and conventional RS margin

“Codewords targeted for filling/correction” counts targeted codeword positions, potentially including both erasures and unknown-position errors. Partial-bit constraint routes count positions containing unknown bits as targets.

The conventional RS margin is the minimum block value of `ECC symbols − erasures passed to RS − 2 × unknown-position errors` for the representative recovery method. It uses the erasure count after conventional partial-bit enumeration; zero is the conventional RS capacity boundary. This measure does not apply to partial-bit constraints or list decoding.

**Calculation record for this candidate** shows the radius and remaining erasures for GS/Wu, the agreement score and required score for KV, and interpolation multiplicity and Y degree where applicable. The radius is the maximum number of targeted erroneous codewords; the score uses interpolation weights.

### Structure filling and observed suffix matches

This document uses **Single-segment assumption**, the diagnostic label, for the advanced setting “When the mode and character/byte count are read or assigned by \* trials, assume a single segment and force the following structure.”

“Filling/changes using QR structure” or “Filling/changes under the specified assumption” counts bits assigned by this route, including replaced known bits when **Single-segment assumption** is ON. Heuristic assignments later overturned by RS remain in this processing count.

“Observed bits matching the assumed suffix structure” counts known bits agreeing with the terminator, byte alignment, and alternating `0xEC / 0x11` padding. It is not a repaired-bit count.

### Priority 0/1 search / Partial-bit search

“Search with tentative values for prioritized unknown cells” counts bits searched by assigning tentative values to `*` cells that were unknown in the input.

The partial-bit search count is the sum of the following values across RS blocks.

- Conventional partial-bit search counts the unknown bits selected for enumeration.
- RS partial-bit constraints count the degrees of freedom remaining after mode and character/byte count, terminator, and padding conditions are applied.

The diagnostic dimension describes the input, so it may differ from the partial-bit search count.

### RS partial-bit constraints

Partial-bit constraints find unknown-cell values that satisfy QR error-correction conditions. Degrees of freedom count unknown bits that can be chosen independently among valid combinations.

Shows unknown bits, independent constraint rank, degrees of freedom, and bits fixed across every solution. These diagnostics assume the selected format and known input data/ECC are correct. They describe the observation before structure constraints and temporary priority assignments, before QR payload validation.

With zero degrees of freedom, the RS solution is unique under those assumptions. An inconsistent space is reported as “No combination satisfies the RS constraints while preserving all entered values,” without guessing an error count. Exceeding the enumeration dimension limit does not mean no solution exists.

QR combination-search diagnostics distinguish a window ending after success, the combination limit, and the expanded-node limit. These are normal endings of the specified policy, even if other mathematical combinations exist. They do not produce an unfinished-search warning. A working-memory allowance that prevents reaching the specified policy boundary is reported separately as unfinished work. Displayed candidate counts cover retained validated results, not every candidate found during search.

## Failure reasons and additional checks

Whole-search reasons distinguish fixed format cells incompatible with explicit ECC/mask selections, fixed cells outside data/ECC incompatible with the QR structure, and calculated candidates rejected by fixed cells or error limits. Temporary priority-assignment conflicts are not reported as manual input pin conflicts.

RS capacity and correction results appear in the format-specific reference diagnosis. Partial-bit and QR combination-search limits appear as search-limit notices. Block numbers are one-based.

Erasure-capacity shortfall measures the minimum bits that must be decided to reduce each block's erasure count to its ECC symbol count. When used by guidance, zero can still fail because of known-bit errors or search limits. This is different from degrees of freedom.

Even without validated candidates, a consistent ambiguous RS space can provide a conditional check cell that reduces degrees of freedom by one bit. This is not a count of validated QR candidates.

If the format-information error limit excludes every permitted format, recovery reports a format-limit failure without attempting RS. It shows mismatches, compared known bits, and the configured limit for the best permitted format. Review format cells, ECC/mask selections, or the format-information error limit. The limit applies when both ECC and mask are selected, and manual selection conflicts remain checked.

## Structured Append and FNC1

For Structured Append, the preview and result display include this symbol’s one-based position, total and parity. Validation applies to this symbol; it does not mean the whole message is assembled or parity-verified. Fragment text is displayed without masking or redaction, and copying preserves the complete recovered text. The FNC1 second-position application indicator is shown separately from the body as its raw eight-bit value in decimal.

## Cell confidence and candidate agreement

“Candidate agreement” shows how many compared candidates have the same black or white value as the selected QR at a given cell. For example, `12 / 16` means 12 of the 16 compared candidates agree with the selected candidate at that cell. Agreement is displayed when at least two candidates can be compared.

The comparison uses at most 16 decoded candidates from the same reading attempt as the selected candidate. It uses the highest-ranked candidates and always includes the selected candidate if it is a decoded candidate. This comparison scope is separate from the result list of up to 256 candidates.

The accompanying “validated candidates in total” count covers decoded candidates still listed from that reading attempt; it excludes candidates from other attempts. Candidate splits and additional-check guidance also apply only to the candidates actually compared. Even `16 / 16` neither guarantees agreement with candidates outside the comparison nor expresses the probability that the cell is correct.

See [Understanding repair reasons](REPAIR_REASONS.en.md) for cell reasons and intermediate history.
