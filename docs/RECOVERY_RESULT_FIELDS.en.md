# Understanding recovery result fields

English | [Japanese](RECOVERY_RESULT_FIELDS.ja.md)

The main content display and diagnostic details describe candidate generation and consistency. Confidence is not a statistical probability, and Reed–Solomon (RS) success alone does not prove correctness.

Invalid Kanji/Hanzi character codes are rejected by structure validation even when RS is consistent; replacement text is not accepted as a successful character-mode result. Byte-mode binary data is handled separately.

## Identify the outcome first

| Outcome or warning | Meaning | Check first |
| --- | --- | --- |
| Repair succeeded | At least one candidate passed QR payload structure and final decoding | Decoded content, distinct-content count, candidate agreement, and search-limit warnings |
| Repair incomplete | An RS-consistent candidate exists, but payload validation or final decoding did not succeed | Format information, version/geometry, partial diagnostics, and failure reason |
| Repair failed | Format conditions prevented RS attempts, or the performed search found no RS-consistent candidate | First failing block, erasure-capacity shortfall bits, and known-bit conflicts |
| Combination/node limit | The specified search policy reached its normal boundary | Completion does not prove uniqueness |
| Candidates omitted (retention limit) | Only some of the candidates found are displayed | Omitted candidates are not included in the displayed count and cannot be selected |
| Working-memory limit | Search stopped at the memory limit for calculation | Some combinations remain unchecked |

### Short reading example

Suppose Repair succeeded and three candidates from the same reading attempt are compared. Candidate agreement of `2 / 3` means one candidate differs from the selected candidate at that cell. Inspect its source-image evidence and repair reason. Agreement of `3 / 3` still does not prove uniqueness across candidates outside the comparison or unexplored solutions.

## Results and counts

The result list shows up to 256 of the highest-ranked candidates from all reading attempts. Result switching, comparison of two candidates, and PNG export all use this list.

When many candidates are found, or memory limits apply, only some candidates may be displayed. The displayed count is the number of selectable candidates in the list, not necessarily the total number found.

“Others omitted” means some candidates that were found are absent from the list. “Some combinations remain unchecked” means required work within the specified search policy was not completed. Both notices can appear together.

“Repair succeeded” means a result passed QR structure validation, including terminator and filler, and final ZXing decoding. It does not prove identity with the original content.

Results with identical version, ECC level, mask, and corrected codewords appear as one candidate, even when reading positions or polarity differ. “Derivations of the same QR” shows constraint and search-bit counts for routes that produced that QR. Regular diagnostics and cell reasons describe the best-ranked route, route 1. A route-detail omission notice means not every route is available for inspection.

“Distinct contents” compares data-codeword sequences; equal displayed text can still count as different content. Switching candidates updates the QR display, the input before repair, and diagnostics to match that candidate.

When decoded text can also be interpreted as a URL, email, SMS, contact, or another structured type, the display shows the complete original string in addition to parsed fields. It is not silently truncated or replaced by selected fields. Copying preserves that same complete string.

Without a validated payload, the app shows “Repair incomplete” or “Repair failed” and diagnostics. Unvalidated corrections do not overwrite the result matrix. The Data dialog initially shows Before with original partial codewords containing `?`. Switching to After does not expose an unvalidated corrected byte sequence. Result PNG export is unavailable for unvalidated candidates and failed attempts. A diagnostic input image can instead be exported from detailed repair settings.

When conventional RS and partial-bit constraints produce the same block values, those values remain one candidate while each route retains its targets, enumerated positions, search bits, and RS margin. Diagnostics list derivations per RS block with route 1 as representative. Main cell reasons and conventional margins describe that representative; other routes appear separately. Products of block-route counts are not added to QR-level derivation or result counts.

## Partial input diagnostics

Partial diagnostics describe unvalidated input, not successful decoding. For a leading Numeric, Alphanumeric, Byte or Kanji segment, the view shows its mode, character/byte count and consecutive readable body prefix. Body display stops at unknown bits or invalid character values.

For Hanzi, the four-bit subset indicator after the mode is read before the character count. Only subset 1 (GB2312) permits reading the following count. A missing, unknown or unsupported indicator is reported without reading a count. Partial body display is not supported for Hanzi; this is separate from final Hanzi candidate validation and decoding.

## Main content display

| Field | Meaning |
|---|---|
| Error correction level | `L / M / Q / H`, with progressively more ECC codewords at the same version. Recovery depends on damage locations and codeword layout. |
| Mask | `0–7`. Modules are unmasked to extract codewords; the mask is reapplied after correction. |
| Format information errors | Disagreements with the candidate's BCH format value / compared known bits. Manually entered format bits determined by explicit ECC/mask selections are excluded. |
| Data and ECC errors | Disagreements between known data/ECC input bits and corrected codewords mapped back through the mask / compared bits. |

The format-error limit applies even when explicit ECC and mask selections leave only one format. Among attempts without validated payloads, stronger format agreement takes precedence over RS success.

Data/ECC comparisons exclude `?`, temporary priority `*` assignments, function modules, and remainder bits. The data/ECC error limit retains candidates at or below the limit; zero requires exact agreement. “Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)” defaults to OFF: candidates that conflict with fixed cells are rejected separately. ON allows their values to be corrected while retaining their fixed designation and input values. Tentative cells are correctable in either setting. Corrections remain subject to the format and data/ECC error limits; explicit ECC/mask/version selections and conflicts between fixed cells and explicit selections remain enforced.

## Diagnostic details

Diagnostics separate execution settings, the initial input, processing, and verification. A zero **Data/ECC error limit** selects partial-bit constraints for candidate calculation. Only candidates matching every known initial data/ECC bit can be accepted; unknown bits may be filled. Allowing conflicts with fixed cells does not override this limit. Intermediate structure filling may change known suffix bits, so this guarantee describes accepted results, not every intermediate calculation.

The initial-input section identifies the version, ECC, mask, and geometry used for its reference diagnosis. Conventional RS is evaluated separately before structure filling or bit search, including when the data/ECC limit is zero. Block results distinguish a correction result, correction skipped because codewords containing unknown bits outnumber ECC symbols, and correction attempted without a result. A missing diagnostic is reported as unrecorded, not unexecuted. RS results alone do not establish a correct QR payload.

Search completion distinguishes three states within the specified conditions and search policy.

| Message | Meaning |
| --- | --- |
| Search completed within the specified conditions and search policy. | The bounded policy finished; this does not mean every mathematical solution was enumerated or establish uniqueness |
| Some combinations remain unchecked. | Interruption, insufficient working memory, or an internal error prevented required work |
| Search completion could not be determined. | Execution records are insufficient to determine completion |

Reasons for unfinished work are collected across all attempts and shown with the completion status. Internal-error messages distinguish some work performed, no work performed, and unavailable execution information. For search-policy limits versus candidate retention limits, see “Results and counts” above and [Recovery Strategy](RECOVERY_STRATEGY.en.md).

Final result messages aggregate evidence across formats, geometries, and sampling profiles. A representative failure cannot establish a search-wide reason. Fixed-cell or format conflicts are shown as whole-search reasons only when supported across the permitted inputs. “No RS candidate meets the zero-bit mismatch limit” requires inconsistent initial-input constraints for every permitted format, not just a failed structure-filling or priority assignment. Unknown or incomplete evidence suppresses that conclusion. Completing the bounded policy without a candidate does not prove that no mathematical solution exists.

### Geometry

“Manual projective” uses selected corners. “Projective” includes refinement supported by alignment-pattern image evidence. Logical orientation and version are preserved. References such as “top-left reference” describe geometry, not payload reading direction. `V<version>` is the QR version; each side has `17 + 4 × version` modules.

### Erased codewords: at recovery / at input

- At input: codewords containing at least one unknown input bit.
- At recovery: codewords still containing unknown bits after this route's priority assignments and structure constraints, before RS processing.

Multiplying the difference by eight does not give repaired bits. Additional codewords resolved by conventional partial-bit enumeration are also not included in that difference.

### RS corrected symbols / RS margin

Equivalent conventional erasure/enumeration routes are retained as block derivations. Route 1 prefers larger conventional margin, fewer search bits, fewer targets, then lower penalty. The traversal penalty may come from a different retained route; displayed margins and cell explanations always belong to route 1.

RS corrected symbols counts targeted codeword positions, potentially including both erasures and unknown-position errors. Partial-bit constraint routes count positions containing unknown bits as targets.

The conventional RS margin is the minimum block value of `ECC symbols − erasures passed to RS − 2 × unknown-position errors`. It uses the erasure count after conventional partial-bit enumeration; zero is the conventional RS capacity boundary. If any block uses RS partial-bit constraints, the UI shows “not applicable (partial-bit constraints used).” Binary rank and freedom are not symbol correction margins.

### Filled by structure constraints / Known-structure matches

This document uses **Single-segment assumption**, the diagnostic label, for the advanced setting “When the mode and character/byte count are read or assigned by \* trials, assume a single segment and force the following structure.”

“Filled by structure constraints” counts bits assigned by this route, including replaced known bits when **Single-segment assumption** is ON. Heuristic assignments later overturned by RS remain in this processing count.

“Known-structure matches” counts known bits agreeing with the terminator, byte alignment, and alternating `0xEC / 0x11` filler. It is not a repaired-bit count.

### Priority 0/1 search / Partial-bit search

“Priority 0/1 search” counts explicitly enumerated `*` cells that were unknown in the input. Success using only the ordinary route does not record priority enumeration.

“Partial-bit search” sums enumerated unknown bits for conventional partial-codeword enumeration, or enumerated free variables for RS partial-bit constraints, across blocks. It is neither restored cells nor trial count. The two routes also have different cell-provenance descriptions.

### RS partial-bit constraints

Shows unknown bits, independent constraint rank, free dimension, and bits fixed across every solution. These diagnostics assume the selected format and known input data/ECC are correct. They describe the observation before structure constraints and temporary priority assignments, before QR payload validation.

Zero freedom means a unique RS solution under those assumptions. An inconsistent space is reported as “no exact solution,” without guessing an error count. Exceeding the enumeration dimension limit does not mean no solution exists.

QR combination-search diagnostics distinguish a window ending after success, the combination limit, and the expanded-node limit. These are normal endings of the specified policy, even if other mathematical combinations exist. They do not produce an unfinished-search warning. A working-memory allowance that prevents reaching the specified policy boundary is reported separately as unfinished work. Displayed candidate counts cover retained validated results, not every candidate found during search.

## Failure reasons and additional checks

Whole-search reasons distinguish fixed format cells incompatible with explicit ECC/mask selections, fixed cells outside data/ECC incompatible with the QR structure, and calculated candidates rejected by fixed cells or mismatch limits. Temporary priority-assignment conflicts are not reported as manual input pin conflicts.

RS capacity and correction results appear in the format-specific reference diagnosis. Partial-bit and QR combination-search limits appear as search-limit notices. Block numbers are one-based.

Erasure-capacity shortfall measures the minimum bits that must be decided to reduce each block's erasure count to its ECC symbol count. When used by guidance, zero can still fail because of known-bit errors or search limits. This is different from free dimension.

Even without validated candidates, a consistent ambiguous RS space can provide a conditional check cell that reduces freedom by one bit. This is not a count of validated QR candidates.

If the format-error limit excludes every permitted format, recovery reports a format-limit failure without attempting RS. It shows mismatches, compared known bits, and the configured limit for the best permitted format. Review format cells, ECC/mask selections, or the format-error limit. The limit applies when both ECC and mask are selected, and manual selection conflicts remain checked.

## Structured Append and FNC1

For Structured Append, the preview and success display include this symbol’s one-based position, total and parity. “Repair succeeded” applies to this symbol; it does not mean the whole message is assembled or parity-verified. Fragment text is displayed without masking or redaction, and copying preserves the complete recovered text. The FNC1 second-position application indicator is shown separately from the body as its raw eight-bit value in decimal.

## Cell confidence and candidate agreement

“Candidate agreement” shows how many compared candidates have the same black or white value as the selected QR at a given cell. For example, `12 / 16` means 12 of the 16 compared candidates agree with the selected candidate at that cell. Agreement is displayed when at least two candidates can be compared.

The comparison uses at most 16 candidates decoded as text from the same reading attempt as the selected candidate. It uses the highest-ranked candidates and always includes the selected candidate if it was decoded as text. This comparison scope is separate from the result list of up to 256 candidates.

The accompanying “validated candidates in total” count covers decoded candidates still listed from that reading attempt; it excludes candidates from other attempts. Candidate splits and additional-check guidance also apply only to the candidates actually compared. Even `16 / 16` neither guarantees agreement with candidates outside the comparison nor expresses the probability that the cell is correct.

See [Understanding repair reasons](REPAIR_REASONS.en.md) for cell reasons and intermediate history.
