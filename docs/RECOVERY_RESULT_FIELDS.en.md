# Understanding repair results

English | [Japanese](RECOVERY_RESULT_FIELDS.ja.md)

This reference explains results, diagnostics, and the reasons shown for repaired cells. For operations and settings, see the [User Guide](USER_GUIDE.en.md). For search order and when each method applies, see [How QRevy recovers a code](RECOVERY_STRATEGY.en.md).

QR codes include error-correction (ECC) data. Passing Reed–Solomon (RS) checks alone does not prove that the recovered content is correct. Independently verify important recovered information.

## Identify the outcome

A **validated candidate** passes QR structure checks and the specified input conditions. A **decoded candidate** also passes final decoding of the QR data. QR data can contain text or binary data.

| Display | Meaning | What to check |
| --- | --- | --- |
| N recovery candidates meeting the specified conditions were found. | The listed candidates passed validation; decoding is reported separately | Content, differences between candidates, and search completion |
| The QR data format was verified / The data cannot be displayed as text. | The selected candidate passed structure checks, but final decoding failed | **Data** and cell repair reasons; the QR can still be selected and exported |
| No recovery candidate was found in the checked range. | No selectable candidate was retained | Input conditions, rejection reasons, and search completion |

When no repair candidate is found, **Data** shows the input, including its unknown values (`?`), and result-image export is unavailable. Partially readable input shown in diagnostics is not a decoded result: it stops at unknown bits or invalid values.

## Candidates and content

The result list contains up to 256 selectable candidates from all reading attempts. Each attempt uses one combination of grid position and reading conditions. Retention or memory limits can leave other candidates out of the list.

Each distinct recovered QR counts once. **Calculation methods yielding this QR** describes the methods and reading attempts that produced it.

The distinct-content count compares encoded QR data, rather than displayed text alone. For example, two candidates that encode `ABC` differently can display the same text and still count as different contents.

Copying content and candidate-agreement analysis require decoded candidates. Displaying and copying content preserve the complete recovered string, including when it is interpreted as a web address (URL) or contact.

For a message split across several QR symbols (**Structured Append**), the result shows this symbol's position, total count, and parity (a value used to check the complete message). QRevy handles individual symbols; it does not join them or verify the complete message's parity.

## Search completion and omissions

Finding a candidate and completing the search are separate outcomes.

| Display | Meaning |
| --- | --- |
| Search completed within the specified conditions and search policy. | The app finished its defined search range, including normal endings at search limits. It did not necessarily check every mathematically possible solution. |
| Some combinations remain unchecked. | Interruption, a time limit, insufficient working memory, or an internal error prevented required work. |
| Search completion could not be determined. | The available records do not establish whether the search finished. |
| Others omitted / Candidates omitted (retention limit) | Some candidates already found are absent from the list and cannot be selected. This is separate from unfinished searching. |

List decoding has a cumulative time allowance of **600 seconds (10 minutes) per repair**, excluding ordinary searches. Timeout, insufficient memory, or interruption preserves validated candidates already found. A completed search does not establish uniqueness, and completion without a candidate does not prove that no solution exists.

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

An error limit of **0 bits** requires agreement at every compared known bit. Increasing an error limit does not permit changes to protected fixed cells. Explicit version, ECC, and mask selections remain enforced.

## Diagnostic details

| Section | What it describes |
| --- | --- |
| Settings for this run | The settings used for this repair |
| Input before repair | The starting input and its conventional RS reference check |
| Processing in this run | Candidate methods and calculation records, list-decoding execution status, and single-segment assumption application status |
| Verification results | Structure checks, final decoding, input mismatches, search completion, and omissions |

The conventional RS reference check uses the input before structure-based filling or temporary assignments. Reference calculations describe their own input and conditions; one failed calculation does not establish why the whole search failed.

When no candidate is found, **Processing in this run** reports the search outcome and completion status. Without search-wide records, completion remains unknown even if the reference input's search completed.

A **codeword** is a group of eight bits. In RS calculation records, **symbol** and **byte** also refer to this unit. An **RS block** is a group of codewords processed together for error correction. Counts of unknown or corrected codewords differ from counts of repaired cells: filling one unknown bit can resolve a whole codeword.

### Methods shown in diagnostics

Method names describe how candidate values were calculated, often separately for each RS block. The values used can include structure-based fills and temporary assignments made before that calculation.

| Method | Meaning |
| --- | --- |
| Conventional RS | Fills unknown codewords and corrects wrong values within the usual RS range. Any codeword containing an unknown bit is treated as an erasure. |
| Partial-bit constraints | Solves for unknown bits while preserving the known bits at that stage. It cannot correct a wrong known bit. |
| Bit search / priority-cell bit search | Tries `0 / 1` assignments for selected unknown bits before further checks. Priority-cell search uses cells marked `*`. Trial values are assumptions. |
| Structure filling | Fills values implied by the QR data structure being tested, such as mode, length, terminator, and padding. Some fills depend on the single-segment assumption. |
| Guruswami–Sudan (GS) | Uses known codeword positions and values to find RS candidates beyond the usual correction range. |
| Koetter–Vardy (KV) | Weights possible codeword values consistent with known bits and searches for sufficient weighted agreement. These weights are calculation parameters, not probabilities or image-brightness measurements. |
| Wu | Searches error-location patterns and checks their implied corrections beyond the usual RS range. |

Method names do not rank confidence. Multiple methods producing the same candidate do not provide independent proof that it is correct.

### Calculation records

**Calculation methods yielding this QR** labels multiple methods A, B, and so on. **Used for cell explanations** identifies the method supplying per-cell repair provenance. Details show calculations for each RS block. Whole-QR methods, alternative block calculations, and other reading attempts have separate counts; none is a count of additional repair candidates. Retention-limit omissions are indicated.

A method marked **same QR without filling** has a recorded alternative that obtains the same QR without fills under matching input, format, settings, priority assignments, and other conditions. Filling can still resolve unknown values or change known values during calculation. Fill records show unmasked before/after values, including priority assignments in the before value. Comparisons between methods do not describe a sequence of processing steps.

**List decoding execution status** keeps recorded status, trial counts, and elapsed time visible. **Show calculation details** reveals method/radius breakdowns, reused results, and skip reasons; these details start closed regardless of execution status. Counts and elapsed time concern list decoding. Trial and completion counts describe block calculations rather than repaired QR candidates.

When no candidate is found and the single-segment assumption was ON, **Single-segment assumption application status** separately reports whether that assumption could be applied.

The scope in these headings matters: **whole search** includes trials that did not produce the selected candidate. **Selected candidate's input** and **reference diagnostic input** cover only the named input because search-wide records are unavailable. A skipped calculation and an interrupted calculation have different meanings; read their reasons together with search completion.

### Calculation terms

| Display | Meaning |
| --- | --- |
| Correction radius | The maximum number of non-erased codewords that may differ from the values used in that trial. It is not the number of repaired bits or differences from the original QR. |
| Remaining erasures | Codewords still treated as unknown after any filling or temporary assignments. They are counted separately from errors within the correction radius. |
| Extension limit: +1 / +2 / +3 | The requested GS/Wu extension beyond the conventional RS radius for each block, given its remaining erasures. It counts codewords. Applicability, time, and memory limits can prevent reaching it; KV uses its own score condition. |
| Conventional RS correction margin | ECC symbols − erasures − 2 × erroneous codewords. A value calculated after filling or temporary assignments does not describe the starting input's margin. Without a correction result, the margin is unknown. Partial-bit constraints and list decoding have no conventional RS margin. |
| Agreement score / Required score | KV's weighted agreement total and the minimum needed by that calculation. These are neither probabilities nor the candidate-agreement counts shown for cells. |
| Multiplicity / Base multiplicity | The weight used to build GS/KV calculation conditions. KV shares its base weight among compatible codeword values. Larger values can increase work; they do not indicate greater confidence. |
| Y degree | A degree limit in the equations used by GS/KV. It describes the calculation, not the number or correctness of candidates. |
| Johnson bound | A mathematical condition limiting the GS/Wu correction radius used by the app. A trial that does not meet this condition is skipped; that does not prove that no repair exists. |

The **degrees of freedom** value counts unknown bits that can be chosen independently while satisfying RS conditions, assuming the selected format and known input bits are correct. Zero means one RS solution under those assumptions, before checking QR content; it does not count recovered candidates.

Suggestions for additional cells to check can appear even without a validated candidate. An estimate of zero additional bits does not guarantee recovery: known values may be wrong, or the search may reach a limit. Rejection reasons identify conditions to review, such as a fixed edit or a format-information mismatch limit.

## Cell repair reasons

A cell's details distinguish its input value, final value, main reason, and supporting evidence. Reasons explain how the value was obtained.

| Repair-reason border | Category | Meaning |
| --- | --- | --- |
| Green | QR structure | The selected version and format determine the value, such as a square finder pattern or format-information cell. This depends on those selections being correct. |
| Orange | RS | **RS erasure recovery** fills an input `?`; **RS error correction** changes an input `0` or `1`. |
| Purple | Payload constraint | Rules for the QR's content, length, end marker, or padding (data filling unused space after the content) supplied the value. It may depend on an assumed data structure. |
| Magenta | Bit-level candidate search | The search tried `0 / 1` values for unknown bits within a limit. |
| Blue | User edit | The user specified the value or its source. **Input** shows whether it was fixed, tentative, or fixed with correction allowed. |

**Supporting evidence** supports the final value. For example, if a structure rule fills `0` and RS later changes it to `1`, the earlier `0` appears only as intermediate history.

**Single-segment assumption** reports whether that setting was enabled and applied to this candidate. A segment is QR data encoded in one mode. A generic **Repair** reason means that no more specific category is available. Relabeling a cell's source without changing its value does not highlight it as changed.

Cell coordinates `(x,y)` start at zero. Codeword numbers, RS block numbers, positions within a block, and bit positions start at one; bit 1 is the leftmost bit. **After removing the QR mask** shows before/after codeword values, with `?` for unknown bits and brackets around the selected bit.

Format-information cells show their role, bit position, and comparisons between the two copies. The data-region **Unmask** display does not change these cells.

## Cell confidence and candidate agreement

**Input-cell relative confidence** describes the input before repair, which can include manual edits. **Candidate assessment** also considers evidence such as candidate agreement. Both are explanatory scores, not probabilities of correctness.

**Candidate agreement** counts candidates whose value at a cell matches the selected candidate. For example, `12 / 16` means that 12 of the 16 compared candidates agree at that cell.

The comparison uses up to 16 of the highest-ranked **decoded candidates from the same reading attempt**, always including the selected decoded candidate. It appears when at least two candidates can be compared. This is a smaller set than the result list of up to 256 candidates.

The accompanying **validated candidates in total** count covers retained decoded candidates from that reading attempt, excluding other attempts. Candidate-based suggestions apply only to the candidates compared. Even `16 / 16` does not establish agreement with excluded or undiscovered candidates, or prove that the cell is correct.
