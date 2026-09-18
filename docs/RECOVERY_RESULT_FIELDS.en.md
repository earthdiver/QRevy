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
| Combination/node limit | Search stopped with part of the candidate space unexplored | Do not treat candidate counts as uniqueness; review settings or an additional-check cell |
| Candidate retention budget | The 32 MiB accounting budget was reached | Results and agreement cover retained candidates only |

### Short reading example

Suppose Repair succeeded with three validated candidates, two distinct contents, and candidate agreement of `2 / 3` for one cell. At least one compared candidate differs at that cell, so inspect its source-image evidence and repair reason instead of silently choosing the selected payload. Agreement of `3 / 3` still does not prove uniqueness across unretained or unexplored solutions.

## Results and counts

“Candidate retention budget” means the shared request budget stopped further search. Previously validated results remain available, but counts and consensus cover only retained results. This warning also applies when repair succeeds. Cell explanations are generated when accessed; the accounting budget and its scope are described in [Recovery Strategy](RECOVERY_STRATEGY.en.md#6-explanation-of-results).

“Repair succeeded” means a result passed QR structure validation, including terminator and filler, and final ZXing decoding. It does not prove identity with the original content. The old `Payload parse: ✓`, `Text: ✓`, and `RS: ✓` labels are no longer separate UI fields.

Within one geometry, identical version, ECC level, mask, and corrected codewords identify one result. Derivations with and without structure constraints retain their individual metrics and cell provenance. “Derivations of the same QR” lists their counts and constraint/search bit counts. Regular diagnostics and cell reasons describe the best-ranked derivation, route 1.

“Distinct contents” compares data-codeword sequences; equal displayed text can still count as different content. Result switching and PNG candidate selection display at most the top 256 results. This is a display limit, not a claim of exhaustive search.

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

When explicit ECC and mask selections leave only one format, the format-error limit is bypassed. Among attempts without validated payloads, stronger format agreement takes precedence over RS success.

Data/ECC comparisons exclude `?`, temporary priority `*` assignments, function modules, and remainder bits. The data/ECC error limit retains candidates at or below the limit; zero requires exact agreement. With “Exclude recovery candidates that conflict with manual 0/1 values” ON, pin conflicts are rejected separately. Without locking, manual 0/1 values are ordinary known bits and may be corrected by RS.

## Diagnostic details

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

“Filled by structure constraints” counts bits assigned by this route, including replaced known bits when single-segment forcing is enabled. Assignments later overturned by RS remain in this processing count; it is not necessarily the number of constraints supporting final values.

“Known-structure matches” counts known bits agreeing with the terminator, byte alignment, and alternating `0xEC / 0x11` filler. It is not a repaired-bit count and replaces the old `e=` field.

### Priority 0/1 search / Partial-bit search

“Priority 0/1 search” counts explicitly enumerated `*` cells that were unknown in the input. Success using only the ordinary route does not record priority enumeration.

“Partial-bit search” sums enumerated unknown bits for conventional partial-codeword enumeration, or enumerated free variables for RS partial-bit constraints, across blocks. It is neither restored cells nor trial count. The two routes also have different cell-provenance descriptions.

### RS partial-bit constraints

Shows unknown bits, independent constraint rank, free dimension, and bits fixed across every solution. These diagnostics assume the selected format and known input data/ECC are correct. They describe the observation before structure constraints and temporary priority assignments, before QR payload validation.

Zero freedom means a unique RS solution under those assumptions. An inconsistent space is reported as “no exact solution,” without guessing an error count. Exceeding the enumeration dimension limit does not mean no solution exists.

QR combination-search diagnostics are shown on success as well as failure. They distinguish a window ending after success, the combination limit, and the expanded-node limit. An unexplored remainder in any pass is retained even if an unconstrained retry exhausts its combinations. Candidate counts cover validated results found so far. Exhausting attempted passes does not imply exhaustive formats, geometries, or solution spaces.

## Failure reasons and additional checks

Diagnostics cover explicit format/manual conflicts, pins outside codewords, and data/ECC pins conflicting with corrected candidates. Pin failures show coordinates, manual values, and values required by the rejected candidate. Required values are neither applied automatically nor guaranteed correct. Temporary priority-assignment conflicts are not reported as manual input pin conflicts.

Other reasons include known-bit mismatch limits, RS capacity failures, no exact solution, partial-bit search limits, and QR combination-search limits. “First failing RS block” uses one-based numbering.

Erasure-capacity shortfall measures the minimum bits that must be decided to reduce each block's erasure count to its ECC symbol count. When used by guidance, zero can still fail because of known-bit errors or search limits. This is different from free dimension.

Even without validated candidates, a consistent ambiguous RS space can provide a conditional check cell that reduces freedom by one bit. This is not a count of validated QR candidates.

If the format-error limit excludes every permitted format, recovery reports a format-limit failure without attempting RS. It shows mismatches, compared known bits, and the configured limit for the best permitted format. Review format cells, ECC/mask selections, or the format-error limit. Selecting both ECC and mask still bypasses this limit, while manual selection conflicts remain checked.

## Structured Append and FNC1

For Structured Append, the preview and success display include this symbol’s one-based position, total and parity. “Repair succeeded” applies to this symbol; it does not mean the whole message is assembled or parity-verified. Fragment text is displayed without masking or redaction, and copying preserves the complete recovered text. The FNC1 second-position application indicator is shown separately from the body as its raw eight-bit value in decimal.

## Cell confidence and candidate agreement

Consensus and candidate-based guidance use only distinct validated candidates. At most the top 16 matrices are compared; if the selected validated result is outside that range, it replaces the last entry.

Candidate agreement shows both the compared count and the full validated count. When the comparison is a subset, candidate split and information-gain guidance explicitly apply only to that subset. Agreement within the sample does not establish agreement among unexamined candidates or unexplored solutions.

See [Understanding repair reasons](REPAIR_REASONS.en.md) for cell reasons and intermediate history.
