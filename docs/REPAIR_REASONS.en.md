# Understanding Decode and Repair Reasons

English | [日本語](REPAIR_REASONS.ja.md)

Tapping a cell in “Show result” displays the primary reason for its final value and any supporting evidence. These reasons explain provenance; they are not probabilities of correctness. RS success alone is not proof that a result is correct.

An attempt that fails payload validation does not modify the original matrix, so unvalidated guesses receive no repair reasons. The reasons below apply to payload-validated results or explicit user edits.

## Why an overall decode succeeds or fails

The app distinguishes these checks (some run more than once during search):

1. sample a QR matrix from geometry and version;
2. confirm or infer ECC level and mask from the two format-information copies;
3. extract codewords and deinterleave them into RS blocks;
4. apply payload-structure hypotheses and generate block solutions through errors+erasures, bounded enumeration, and partial-bit RS constraints;
5. prune block combinations using payload-prefix validation, then validate complete mode, count, data, terminator, alignment, and padding;
6. reject corrected candidates that violate data/ECC manual pins or known-bit mismatch limits;
7. decode the validated data stream with ZXing.

Format and non-codeword manual constraints are checked before RS. RS consistency and complete structural validation alone are insufficient: successful repair also requires final ZXing decoding.

Only a candidate that reaches final payload validation is a successful decode. RS success without a parseable payload is shown as “Repair incomplete: an RS-consistent candidate is available”. If RS fails, only the best failed attempt is retained, favoring fewer format disagreements and a smaller erasure-capacity bit shortfall.

For RS failures, `erasure-capacity shortfall bits` measures erasures only. It can be zero when known-bit errors exceed the combined correction budget, bounded search finds no consistent assignment, or a syndrome remains after correction. Check the failure reason and diagnostic details as well. See [result fields](RECOVERY_RESULT_FIELDS.en.md) for the current display.

When BCH evidence confirms the format, RS or payload failure does not permit switching to another ECC level or mask. When the format is not confirmed, provisional and remaining formats may be tried, but format disagreement is a stronger ranking condition than RS success among attempts without a valid payload.

The BCH-confirmed and provisional single-format fast paths apply when both ECC and mask are automatic. With either selection explicit, recovery tries the permitted set in rank order: at most eight formats for ECC only, four for mask only, and one for both. A partial explicit selection does not narrow the set to a BCH-trusted format alone. Format-error limits and manual pins further restrict the attempted set.

When “Exclude recovery candidates that conflict with manual 0/1 values” is ON, non-codeword manual values are checked before adopting a BCH-correctable candidate as the confirmed format. See [Recovery Strategy, section 3.2](RECOVERY_STRATEGY.en.md#32-format-cells-during-recovery).

## Displayed fields

- `Cell (x,y): <before> → <after>`: module values before and after recovery; `?` means unresolved.
- `Reason`: the primary source of the final value.
- `Supporting evidence`: other processing that supports the same value. It is not necessarily weaker; it is separate from the source that wrote the value.
- `Codeword #<n> / bit <b>`: placement-order codeword and bit position containing the module.
- `RS block <n> / local <n>`: RS block and local position after deinterleaving.
- `Codeword: <before> → <after>`: original partial codeword, with unknown bits shown as `?`, and the final codeword.
- `Input-cell relative confidence (not a probability)`: the value stored on the input cell before repair. It can include manual edits and is not necessarily image-derived. It is retained separately from correction-assigned confidence. The separately displayed relative confidence combines evidence such as candidate agreement. Neither is a correctness probability.

Cell coordinates, codeword indices, bit positions, and RS block/local indices in cell details are zero-based; bit 0 is the MSB. The failure diagnostic's “First failing RS block” is one-based.

## User-defined values

### `User-defined`

The user explicitly fixed the module to `0` or `1`. This is a hard constraint, and candidates contradicting it are rejected. A user-assigned `?` requests an erasure; RS or another validated step may resolve it after successful decoding. With “Exclude recovery candidates that conflict with manual 0/1 values” OFF, manual 0/1 values are ordinary known bits and may be corrected.

## Reed–Solomon reasons

### `RS erasure recovery`

The module was `?` before repair and was recovered by RS. Cell reasons describe individual bits, while conventional errors+erasures capacity counts codewords. RS partial-bit constraint routes instead solve binary equations with known bits fixed and add a conditional-constraint note to this recovery reason.

### `RS error correction`

A module known as `0` or `1` before repair was changed by RS. This reason also applies when another bit in the same codeword was `?`. The label alone does not say whether the codeword was supplied to RS as an erasure or corrected as an unknown-position error.

For example, resolving an unknown bit and changing a known bit in one codeword supplied as an erasure can produce both cell reasons, but consumes only one conventional erasure symbol. The rule that one unknown-position error costs two erasures applies to the actual erasure positions and corrected codewords. Bounded enumeration can change the erasure set; use route diagnostics, not counts of cell-reason labels, to assess capacity.

### Additional RS messages

- `RS processing treated this codeword as a repair target.`: the codeword position was included in RS correction.
- RS consistency means that the mathematical code constraints passed. Format evidence, known-bit agreement, and payload validation are still required.

## Fixed QR-structure reasons

These reasons describe values determined by QR structure. Finder, separator, timing and alignment patterns, version information and the fixed dark module follow the selected version and QR specification. Format information additionally requires the selected ECC level and mask. Remainder bits are neither function modules nor part of the RS-protected codewords: the version determines their positions and the mask determines their displayed values.

- `Finder pattern`: the three corner finder patterns.
- `Separator`: white separators around finder patterns.
- `Timing pattern`: alternating modules in row and column 6.
- `Alignment pattern`: version-dependent alignment patterns. From version 7 onward, portions overlapping timing lines (row/column 6) also carry the alignment-pattern reason.
- `Format information`: BCH format bits generated from the confirmed ECC level and mask; applied only to a payload-validated result.
- `Version information`: BCH version information for version 7 and above.
- `Fixed dark module`: the module required to remain dark by the specification.
- `QR remainder bit (from mask rule)`: bits after the final ECC codeword. Their unmasked value is zero, so the displayed value is determined by the selected mask.

A structure module whose observed value was already correct is not highlighted merely because its provenance was rewritten as structural.

## Payload-structure constraint reasons

Ordinary structure propagation enumerates single-segment Numeric, Alphanumeric, Byte, and Kanji hypotheses. Among plans compatible with known bits, it selects those with the most known terminator, byte-alignment, and padding matches; fewer than six matches disables filling. It fills only values shared by all selected plans. This is inference within selected single-segment hypotheses, not proof of uniqueness across all QR streams, including multiple segments. Reaching the plan-enumeration limit also disables filling. Every mutation by these constraints retains an unconstrained retry, even after a successful constrained result.

- `Payload mode constraint`: determined from the numeric, alphanumeric, byte, Kanji, or other supported mode indicator.
- `Payload count constraint`: determined from the mode- and version-specific character or byte count field.
- `Payload value-range constraint`: determined from valid numeric or alphanumeric group ranges.
- `Terminator constraint`: determined from the payload terminator.
- `Byte-alignment constraint`: zero fill up to the next byte boundary after the terminator.
- `Pad codeword constraint`: alternating `0xEC / 0x11` padding after the payload.
- `Payload structure constraint`: another safe payload-structure implication, or a structural implication without a more specific classification.

Bits remain `?` where selected plans disagree. Ranking the plans is hypothesis selection and does not guarantee correct final values.
When the single-segment forcing option is enabled, however, the trusted header selects one structure and known image-derived 0/1 values that conflict with its terminator, byte alignment, or padding are changed with a constraint reason.

## Bounded bit search

### `Bit-level candidate search`

Unknown bits in selected partial codewords were assigned 0/1 within explicit bounds to find RS-consistent candidates. This is not an unbounded search, and payload validation remains necessary after RS consistency.

If the detail says that unknown bits were enumerated within a limit, that codeword was actually included in the enumeration.

### RS partial-bit constraints and priority search

Conventional erasure decoding and later enumeration may produce the same block values with different explanations. Both are retained. The representative conventional route prefers larger RS margin, fewer search bits, fewer targets, then lower penalty; traversal separately uses the minimum penalty across routes. Cell reasons use the representative rather than a mixture of different routes' metrics.

Cells using RS partial-bit constraints carry a note that exact known-data/ECC agreement is assumed and uniqueness depends on that assumption. Free-variable enumeration differs from conventional partial-codeword enumeration and does not necessarily receive a magenta primary reason.

Explicit priority assignments have coordinate-specific provenance only for the cells actually assigned, with a note that they were unknown in the input. Other cells in the same codeword do not inherit that priority provenance.

### Structure constraints overturned by RS

The unmasked value assigned by a structure constraint is compared with the final codeword bit. If different, the constraint is recorded as intermediate history corrected by RS, not a primary reason or supporting evidence. Cells whose final values remain unchanged are still not highlighted; route-level codeword information retains the history.

Identical QRs from multiple routes count as one result while preserving each derivation's provenance. Cell details describe representative route 1. Consensus compares validated results only; see [result fields](RECOVERY_RESULT_FIELDS.en.md) for the 16-candidate comparison limit and its scope.

When conventional RS and partial-bit constraints produce the same block values, those values remain one candidate while each route retains its targets, enumerated positions, search bits, and RS margin. Diagnostics list derivations per RS block with route 1 as representative. Main cell reasons and conventional margins describe that representative; other routes appear separately. Products of block-route counts are not added to QR-level derivation or result counts.

## `Repair`

Fallback label for a value or provenance change that does not match a more specific reason above. Frequent appearances can indicate missing provenance classification or an unexpected processing path.

## Border colors and filters

Border colors provide a quick view of primary provenance:

- green: fixed QR structure
- orange: RS
- purple: payload constraint
- magenta: bit-level candidate search
- blue: user edit

Filters narrow the view to categories such as All changed, RS, QR structure, payload constraint, and User. Do not judge correctness from color or confidence alone; also inspect the source image, codeword details, and decoded payload.
