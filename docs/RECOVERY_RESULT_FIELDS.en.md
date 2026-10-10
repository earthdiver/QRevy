# Understanding repair results

English | [Japanese](RECOVERY_RESULT_FIELDS.ja.md)

This reference explains results, diagnostics, and the reasons shown for repaired cells. For operations and settings, see the [User Guide](USER_GUIDE.en.md). For search order and when each method applies, see [How QRevy recovers a code](RECOVERY_STRATEGY.en.md).

QR codes include error-correction code (ECC) data to help recover damaged content. Passing Reed–Solomon (RS) checks alone does not prove that the recovered content is correct. Confidence scores are explanatory measures, not probabilities. Independently verify important recovered information.

## Identify the outcome

A **validated candidate** passes QR structure checks and the specified input conditions. A **decoded candidate** also passes final decoding of the QR data. QR data can contain text or binary data.

| Display | Meaning | What to check |
| --- | --- | --- |
| N recovery candidates meeting the specified conditions were found. | The listed candidates passed validation; decoding is reported separately | Content, differences between candidates, and search completion |
| The QR data format was verified / The data cannot be displayed as text. | The selected candidate passed structure checks, but final decoding failed | **Data** and cell repair reasons; the QR can still be selected and exported |
| No recovery candidate was found in the checked range. | No selectable candidate was retained | Input conditions, rejection reasons, and search completion |

When no repair candidate is found, **Data** shows the input, including its unknown values (`?`), and result-image export is unavailable. Partially readable input shown in diagnostics is not a decoded result: it stops at unknown bits or invalid values.

## Candidates and content

The result list contains up to 256 candidates from all reading attempts. A reading attempt samples the image with one combination of grid position and reading conditions. The displayed count covers selectable candidates; retention or memory limits can leave other candidates out of the list.

Each distinct recovered QR counts once. **Records yielding the same QR** describes other methods or reading attempts that produced it. Switching candidates updates the QR, input before repair, and diagnostics together.

The distinct-content count compares encoded QR data, rather than displayed text alone. For example, two candidates that encode `ABC` differently can display the same text and still count as different contents.

Validated candidates can be selected and exported even if final decoding fails. Copying content and candidate-agreement analysis require decoded candidates. The content display and copy action preserve the complete recovered string, including when it is also interpreted as a web address (URL) or contact.

For a message split across several QR symbols (**Structured Append**), the result shows this symbol's position, total count, and parity (a value used to check the complete message). QRevy handles individual symbols; it does not join them or verify the complete message's parity.

## Search completion and omissions

Finding a candidate and completing the search are separate outcomes.

| Display | Meaning |
| --- | --- |
| Search completed within the specified conditions and search policy. | The app finished its defined search range, including normal endings at search limits. It did not necessarily check every mathematically possible solution. |
| Some combinations remain unchecked. | Interruption, a time limit, insufficient working memory, or an internal error prevented required work. |
| Search completion could not be determined. | The available records do not establish whether the search finished. |
| Others omitted / Candidates omitted (retention limit) | Some candidates already found are absent from the list and cannot be selected. This is separate from unfinished searching. |

List decoding is an additional search for candidates beyond the usual RS correction range. Its cumulative time allowance is **600 seconds (10 minutes) per repair**, excluding time spent on ordinary searches. Timeout, insufficient memory, or interruption preserves validated candidates already found. A completed search does not establish uniqueness, and completion without a candidate does not prove that no solution exists.

## Input agreement and QR settings

| Field | Meaning |
| --- | --- |
| Version | The QR's size; `V3`, for example, means version 3. |
| Error correction level (ECC level) | `L / M / Q / H`. At the same version, these provide increasing amounts of error-correction data. Recovery also depends on where damage occurs. |
| Mask | A numbered pattern (`0–7`) applied to the QR data. The selected mask affects how cell values are interpreted. |
| Format-information mismatches | Differences between the candidate and the two copies of format information (error-correction level and mask) in the input QR code. |
| Data/ECC-region mismatches | Disagreements in the encoded content and error-correction data. QR function patterns and other information outside this region are excluded. |

Mismatch counts compare the candidate with known `0 / 1` values at repair start, including manual edits. For example, `3 / 80` means that three of the 80 compared bits disagree. Unknown cells, including temporary values tried for `*`, are excluded. With zero compared bits, agreement is unknown.

Format comparison also excludes **fixed 0/1 cells whose values are determined by explicit ECC/mask selections alone**. Tentative edits remain in the comparison. This exclusion still applies when the setting allowing correction of fixed values is enabled.

An error limit of **0 bits** requires agreement at every compared known bit. Fixed-value protection is a separate condition: increasing an error limit does not permit changes to protected fixed cells. Explicit version, ECC, and mask selections remain enforced. See the User Guide for changing these settings.

## Diagnostic details

| Section | What it describes |
| --- | --- |
| Settings for this run | The settings used for this repair |
| Input before repair | The starting input and its conventional RS reference check |
| Processing in this run | Methods, assumptions, and calculation records for the candidate, plus search-wide records |
| Verification results | Structure checks, final decoding, input mismatches, search completion, and omissions |

The conventional RS reference check uses the input before structure-based filling or temporary assignments. Its success or failure describes that check; **Verification results** reports the overall outcome. Search-wide records also include attempts that did not produce the selected candidate.

A **codeword** is a group of eight bits. In RS calculation records, **symbol** and **byte** also refer to this unit. An **RS block** is a group of codewords processed together for error correction. Counts of unknown or corrected codewords differ from counts of repaired cells: filling one unknown bit can resolve a whole codeword.

### Methods shown in diagnostics

Method names describe how candidate values were calculated, often separately for each RS block. The values used can include structure-based fills and temporary assignments made before that calculation.

| Method | What it uses | What it does |
| --- | --- | --- |
| Conventional RS | Codeword values and positions containing unknown bits | Fills unknown codewords and corrects wrong values within the usual RS range. A codeword containing any unknown bit is treated as an erasure. |
| Partial-bit constraints | Known bits and RS conditions | Solves for unknown bits while preserving the known bits at that stage. This method does not correct a wrong known bit. |
| Bit search / priority-cell bit search | A limited set of unknown bits | Tries `0 / 1` assignments before further checks. Partial-bit search completes selected codewords; priority-cell search uses cells marked `*`. Trial values are assumptions. |
| Structure filling | QR data rules, such as mode, length, terminator, and padding | Fills values implied by the structure being tested. Some fills depend on the single-segment assumption. |
| Guruswami–Sudan (GS) | Positions and values of codewords not treated as erasures | Uses equations relating positions and values to find RS candidates beyond the usual correction range. |
| Koetter–Vardy (KV) | Possible codeword values consistent with the known bits | Shares calculation weight among those possible values and looks for candidates with sufficient weighted agreement. This can use partly known codewords without trying every combination of their unknown bits. |
| Wu | Possible positions of erroneous codewords | Finds error-location patterns and checks the corrections they imply, beyond the usual RS range. Wu is a researcher's surname, not an abbreviation. |

For the same input and correction range, the app selects GS or Wu based on estimated work and memory. The method name is not a confidence ranking. Multiple methods producing the same candidate do not provide independent proof that it is correct.

For example, KV treats `10100011` and `10101011` as equally weighted possibilities for `1010?011`. This does not mean that each has a 50% probability of being correct. The weights come from known and unknown bits, not directly from image brightness. At the same base weight, more unknown bits reduce each value's share; highly ambiguous codewords may contribute no weight. Known input errors may still be corrected, but protected fixed `0 / 1` values remain separate requirements for accepting a candidate.

### Calculation terms

| Display | Meaning |
| --- | --- |
| Correction radius | The maximum number of non-erased codewords that may differ from the values used in that trial. It is not the number of repaired bits or differences from the original QR. |
| Remaining erasures | Codewords still treated as unknown after any filling or temporary assignments. They are counted separately from errors within the correction radius. |
| Extension limit: +1 / +2 / +3 | The requested GS/Wu extension beyond the conventional RS radius for each block, given its remaining erasures. It counts codewords. Applicability, time, and memory limits can prevent reaching it; KV uses its own score condition. |
| Conventional RS correction margin | The ECC allowance left in that calculation after counting each erasure once and each erroneous codeword twice. A value calculated after filling or temporary assignments does not describe the starting input's margin. |
| Agreement score / Required score | KV's weighted agreement total and the minimum needed by that calculation. These are neither probabilities nor the candidate-agreement counts shown for cells. |
| Multiplicity / Base multiplicity | The weight used to build GS/KV calculation conditions. KV shares its base weight among compatible codeword values. Larger values can increase work; they do not indicate greater confidence. |
| Y degree | A degree limit in the equations used by GS/KV. It describes the calculation, not the number or correctness of candidates. |
| Johnson bound | A mathematical condition limiting the GS/Wu correction radius used by the app. A trial that does not meet this condition is skipped; that does not prove that no repair exists. |

The starting-input reference reports an unknown margin when RS produces no correction result. Partial-bit constraints and list decoding do not have a conventional RS margin.

Trial and completion counts describe block calculations, not recovered QR candidates. Records for the whole search can include calculations unrelated to the selected candidate. A skipped calculation and an interrupted calculation have different meanings; read the stated reason together with search completion.

The **degrees of freedom** value counts unknown bits that can be chosen independently while satisfying RS conditions. This diagnostic assumes that the selected format and known input bits are correct. Zero means one RS solution under those assumptions, before checking QR content. It does not count recovered candidates. A suggested cell to check can reduce this ambiguity even when no validated candidate has been found.

When a candidate is rejected, review the stated reason. A conflict with a fixed cell calls for reviewing that edit; a format-limit failure calls for reviewing format-information cells, ECC/mask selections, or the format-information error limit. An estimate of zero additional bits to check does not guarantee recovery: known values may be wrong, or the search may reach a limit.

## Cell repair reasons

In **Show detailed results**, a cell's details distinguish its input value, final value, main reason, and supporting evidence. Reasons explain how a value was obtained; several reasons do not amount to independent votes for correctness.

| Repair-reason border | Category | Meaning |
| --- | --- | --- |
| Green | QR structure | The selected version and format determine the value, such as a square finder pattern or format-information cell. This depends on those selections being correct. |
| Orange | RS | **RS erasure recovery** fills an input `?`; **RS error correction** changes an input `0` or `1`. |
| Purple | Payload constraint | Rules for the QR's content, length, end marker, or padding (data filling unused space after the content) supplied the value. It may depend on an assumed data structure. |
| Magenta | Bit-level candidate search | The search tried `0 / 1` values for unknown bits within a limit. |
| Blue | User edit | The user specified the value or its source. **Input** shows whether it was fixed, tentative, or fixed with correction allowed. |

**Supporting evidence** supports the final value. A value later changed by RS appears as intermediate history. For example, if a structure rule fills `0` and RS later changes it to `1`, that earlier `0` is recorded as an intermediate value. It does not support the final `1`.

**Single-segment assumption** reports whether the corresponding advanced setting was enabled and applied to this candidate. A segment is one part of QR data encoded in a particular mode. Review this assumption when structure-based filling is involved. A generic **Repair** reason means that no more specific category is available. Cells are not highlighted as changed solely because their source was relabeled while their value stayed the same.

Cell coordinates `(x,y)` start at zero. Codeword numbers, RS block numbers, positions within a block, and bit positions start at one; bit 1 is the leftmost bit. **After removing the QR mask** shows before/after codeword values, with `?` for unknown bits and brackets around the selected bit. **Show same codeword** highlights its eight cells.

Format-information cells show their role and bit position. Calculation details compare the two copies of format information; the related-cell button highlights the matching error-correction-level, mask-number, or correction-bit cells. The data-region **Unmask** display does not change format-information cells.

## Cell confidence and candidate agreement

**Input-cell relative confidence** describes the input before repair, which can include manual edits. **Candidate assessment** also considers evidence such as candidate agreement. Both are explanatory scores, not probabilities of correctness.

**Candidate agreement** counts candidates whose value at a cell matches the selected candidate. For example, `12 / 16` means that 12 of the 16 compared candidates agree at that cell.

The comparison uses up to 16 of the highest-ranked **decoded candidates from the same reading attempt**, always including the selected decoded candidate. It appears when at least two candidates can be compared. This is a smaller set than the result list of up to 256 candidates.

The accompanying **validated candidates in total** count covers retained decoded candidates from that reading attempt, excluding other attempts. Candidate-based suggestions apply only to the candidates compared. Even `16 / 16` does not establish agreement with excluded or undiscovered candidates, or prove that the cell is correct.
