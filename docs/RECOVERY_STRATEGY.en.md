# How QRevy recovers a code

English | [Japanese](RECOVERY_STRATEGY.ja.md)

QRevy combines image readings, QR structure, and error correction to find candidates for a damaged Model 2 QR code. This document explains how those sources of information work together and where uncertainty remains.

For operations and settings, see the [User Guide](USER_GUIDE.en.md). For diagnostic labels and cell evidence, see [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md).

## 1. What the search works with

| Term | Meaning |
| --- | --- |
| Cell (module) | One square in a QR code, read as `0`, `1`, or unknown (`?`). |
| Codeword | A group of eight bits. QR content and error-correction data are arranged in codewords. |
| RS block | A group of codewords corrected together using Reed–Solomon (RS) codes. |
| Error / erasure | An error is a supplied value that needs correction. An erasure has a known location but an incomplete value; a codeword containing `?` is treated as an erasure by conventional RS. |

The **starting input** is the image reading or imported matrix with manual edits applied, before inferred structure or temporary values are added. A readable `0` or `1` can still be wrong. `?` keeps that uncertainty explicit. A `*` mark also remains `?`; it asks the search to prioritize trying both values for that cell.

Error-correction code (**ECC**) data provides redundancy used to repair damaged content. It can narrow the possible answers, but cannot establish the author's intended content on its own. Passing RS checks is therefore only one requirement for a recovery candidate.

## 2. Choose how to read the image

A reading attempt uses one grid position and one set of reading conditions to obtain cell values.

1. **Establish the grid.** Automatic estimates provide editable corners and a version, which determines the grid size. At least one finder pattern (**Finder** in the app) must have a logical role assigned. Recovery can proceed with one visible Finder; all three are not required.
2. **Sample the cells.** Brightness, thresholds, black/white interpretation, and optional exclusion of detected color clusters produce `0 / 1 / ?`. Unstable readings remain unknown. Manual edits override the sampled values.
3. **Optionally refine the sampling position.** Small changes to position, size, or corners can help when the grid nearly matches the image. With one assigned Finder, small rotations around that corner can also be tried. The selected version and logical orientation stay unchanged.
4. **Read format information.** Two copies in the QR code identify the ECC level and mask. The mask is the numbered pattern applied to data cells. These choices determine how the data is read and divided into RS blocks.

Explicit ECC or mask selections restrict the formats considered. Otherwise, a format confirmed by its own error-correction check fixes that format for the reading attempt, provided it satisfies the input constraints. A later RS or content failure does not reopen other ECC/mask combinations. Weaker format evidence only affects the order: other compatible formats remain available if the preferred one fails.

The app checks every format against the format-information error limit. A plausible grid or format is not proof that it belongs to the original QR code.

## 3. Search order for each reading attempt

The app first tries to recover content with minimal assumptions, then combines further methods as needed.

| Stage | What happens |
| --- | --- |
| 1. Conventional RS on the starting input | Correct errors and erasures without filling structure or trying temporary bit values. |
| 2. Initial list decoding | Look beyond the conventional RS correction range without temporary bit assignments. Work exceeding this short initial time allowance is deferred to stage 5. |
| 3. RS partial-bit constraints | Use known bits within incomplete codewords to solve for unknown bits. Apply compatible QR data rules to narrow the remaining possibilities. |
| 4. Partial-bit search with conventional RS | Try bounded combinations of unknown bits, then use conventional RS on the resulting codewords. |
| 5. Additional list decoding | Revisit deferred work and, where useful, combine list decoding with temporary bit assignments. |

If stage 1 produces a decoded candidate that satisfies the selected conditions, further stages are skipped for that reading attempt. Merely passing RS checks, or retaining a candidate whose content cannot be decoded, does not trigger this shortcut. Candidates found at later stages are kept while subsequent stages continue.

With the data/ECC error limit set to **0 bits**, stages 2, 4, and 5 are skipped. Stage 3 still searches for solutions that preserve every compared known data/ECC bit. Limits and applicability checks can also prevent a method from running; the presence of a method in this table does not mean every repair uses it.

## 4. How the methods help

### Conventional RS and partial-bit constraints

Conventional RS treats an incomplete codeword as an erasure. This is useful when enough correction capacity remains, and it can also correct wrong values at unknown locations. Errors consume more correction capacity than erasures. That capacity applies separately to each block, so the distribution of damage matters.

RS partial-bit constraints retain the known bits inside incomplete codewords. For example, eight codewords with one unknown bit each become eight erasures for conventional RS. A bit-level method can still use the other 56 known bits to constrain those eight unknown bits.

This method assumes the known values are correct. A wrong known bit may make the conditions inconsistent, or may leave a consistent answer that is still wrong. The app therefore checks QR data structure and input constraints as well. The number of unknown bits that can be chosen independently while satisfying RS conditions is called the **degrees of freedom**. Only a bounded number of their combinations can be examined.

### Trying unknown bits, including `*`

Partial-bit search assigns `0` and `1` to selected unknown bits and retries correction. It favors codewords with fewer missing bits. The partial-bit search limit also bounds the degrees of freedom examined by RS partial-bit constraints; it is a per-block limit, not a limit on the total number of QR candidates.

In stages that use `*` marks, the marked cells are first left unknown. If that does not produce decoded content, their value combinations are tried. Other unknown bits can also be searched within the ordinary bounds.

For example, two `*` cells allow four combinations: `00`, `01`, `10`, and `11`. These are trial values, not new observations. They do not become known input bits or increase the number of bits used in an input-agreement comparison. The result records which values were tried.

### QR structure as a constraint

QR data records an encoding mode, a character or byte count, the content, and rules for ending the data and filling unused space. Those rules can help infer missing bits and reject impossible candidates. For example, ten bits used for three decimal digits must represent a number below 1,000.

Automatic filling can consider a single-segment interpretation and fill values shared by the compatible interpretations it selects. A **segment** is a continuous part of the data using one encoding mode. This inference is conditional; it does not prove that a damaged code has only one segment. When **Single-segment assumption** is off, the search also retains a path without that inferred filling.

Enabling that option makes the single-segment interpretation and its required ending mandatory. The app may replace conflicting readings after the declared count, and does not silently drop the condition if it becomes inapplicable. See the [User Guide](USER_GUIDE.en.md#single-segment-assumption) for eligible data and settings.

### List decoding: GS, KV, and Wu

List decoding can return several RS-consistent answers beyond the range where conventional RS finds a single answer. QRevy chooses methods according to the available information, estimated work, and memory. They are not a fixed sequence of three increasingly stronger decoders.

| Method | Role in this app |
| --- | --- |
| **[Guruswami–Sudan (GS)](https://ieeexplore.ieee.org/document/782097/)** | Extends the search using complete codeword values and known erasure positions. It is one of two alternatives for a given error range. |
| **[Wu](https://arxiv.org/abs/cs/0703105)** | Covers the same error range as GS. The app selects one of the two methods according to estimated work and available memory. |
| **[Koetter–Vardy (KV)](https://www.site.uottawa.ca/~zhcheng/RS_soft_decision.pdf)** | Uses known bits within partial codewords before trying their unknown-bit combinations. Compatible values receive equal weights; overly ambiguous codewords contribute no weight. |

GS and Wu can work directly on the input, or after unknown bits have been assigned trial values. Codewords that still contain unknown bits are treated as erasures. The list-decoding extension controls their additional error range; it does not change KV's score requirement.

KV is considered when partial codewords provide useful information and its computation fits the available limits. In this app, its weights come from bit compatibility, not image brightness, image-confidence scores, or probabilities of correctness. If it does not produce new decoded content, bounded assignment searches can continue.

All three methods must satisfy their mathematical checks and the same QR and input validation used for other candidates. For the meaning of radius, agreement scores, and related diagnostic fields, see [Methods shown in diagnostics](RECOVERY_RESULT_FIELDS.en.md#methods-shown-in-diagnostics).

## 5. Conditions that every method must respect

| Condition | Effect on the search |
| --- | --- |
| Selected version and logical orientation | Sampling refinements do not switch to another version or orientation. |
| Explicit ECC level or mask | Candidates must use the selected values. |
| Fixed `0 / 1` cells | Conflicting candidates are rejected unless the user enables the explicit exception allowing correction of fixed values. |
| Tentative edits and image readings | They may be corrected within the applicable error limits. Their starting values remain available for comparison. |
| Error limits | Candidates exceeding the allowed disagreements with compared known input bits are rejected. Unknown and temporary trial values are not counted as known evidence. |
| Single-segment assumption, when enabled | Accepted candidates must retain the specified mode/count interpretation and its required ending. |

The fixed-cell exception preserves the original entered values and their Fixed designation. It does **not** remove explicit ECC/mask selections or the separate consistency check between those selections and fixed format cells. Raising an error limit also does not remove fixed-cell protection.

For example, a candidate that changes a protected fixed `0` to `1` is rejected even if only one bit differs and the error limit allows more. If that cell is tentative, the change may be allowed, but the candidate must still pass every other check.

Input-agreement counts use the starting input, including manual edits. Values inferred from structure or assigned during a search do not become independent confirmation of the result. Details of excluded format cells are in [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md#input-agreement-and-qr-settings).

## 6. Validate, retain, and explain candidates

A candidate must satisfy the RS checks, QR data structure, selected settings, protected cells, and applicable error limits. Passing these checks does not guarantee the original content.

The app ranks candidates using successful decoding, agreement with the input, and correction evidence. This is a way to review plausible results first, not a probability of correctness. Identical recovered QR codes are merged into one candidate even when different reading attempts or methods find them; their alternative explanations can be retained separately.

For result statuses and cell evidence, see [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md).

## 7. What completion and stopping mean

Search is finite. Limits apply to bit combinations, candidate combinations, working memory, and retained results. A completed search means the app finished its defined search policy, not that it examined every mathematically possible QR code.

List decoding has a shared cumulative allowance of **10 minutes per repair**, excluding ordinary search time. It also limits disagreements with known data/ECC bits to 5% of all data/ECC bits, rounded up, or the selected data/ECC error limit if smaller. Increasing a search setting cannot bypass these limits.

Interrupting repair, reaching the time allowance, or running out of working memory retains validated candidates already found and reports unfinished work. Candidate retention limits are separate: some candidates may have been found but omitted from the selectable list. See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md#search-completion-and-omissions) for the distinction.

Severe blur, glare, missing cell centers, or incorrect grid settings can prevent recovery. No candidate means that the performed search found none; one candidate does not prove uniqueness. Verify important recovered information independently.
