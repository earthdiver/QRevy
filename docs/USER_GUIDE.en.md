# QR Repair User Guide

English | [Japanese](USER_GUIDE.ja.md)

This guide covers the Android workflow from importing a damaged QR through adjustment, recovery, review, and saving. See [Recovery Strategy](RECOVERY_STRATEGY.en.md) for the algorithms and [Understanding recovery result fields](RECOVERY_RESULT_FIELDS.en.md) for diagnostics.

A recovered result is not proof that it matches the original content. Independently verify important URLs, credentials, payment destinations, and other consequential data.

## 1. Choose an input

| Input | Best suited for | Important note |
| --- | --- | --- |
| Image file | An existing photo or scan | EXIF orientation is applied during import |
| Camera | Capturing the symbol now | Confirm the crop after capture; the temporary capture and lossless hand-off image are deleted after use |
| `0 / 1 / ? / *` matrix text | Module decisions prepared elsewhere | Only the permitted characters and valid QR dimensions are accepted |

Prefer an image containing the complete QR and, when possible, its surrounding quiet zone, with module centers that are not completely blurred. Never attach a sensitive real QR image to a public issue.

## 2. Adjust image and geometry

1. Rotate in 90°, 1°, or 0.1° increments when needed.
2. Crop to the QR region.
3. Review the estimated corners and version.
4. Review the black/unknown/white thresholds so gray damage remains `?` where appropriate.
5. Enable **Treat detected color clusters as unknown (?)** only when colored damage should be treated as unknown. It starts disabled.

**Image adjustment** in the crop screen switches the bottom tool row to one slider for **Contrast** or **Brightness**, keeping the image area unchanged at standard width and text size. Use the item menu for numeric entry, an individual reset, or **Reset all image adjustments**. **Invert** is available on the normal tool row. **Done** closes the slider; it does not start analysis. On narrow screens or with large text, an open slider uses two rows. Closing it returns that space to the image; the normal tool row stays directly above the analysis buttons.

**Before** toggles the same image between adjusted and unadjusted tones without changing the crop or rotation. Analysis always uses the adjustment settings; while comparing, the action says **Analyze adjusted image**.

**Set thresholds automatically** is enabled by default for a new image. While enabled, the app recalculates one global threshold pair when the grid changes. Moving either slider switches to manual thresholds; turning the option off directly keeps the current pair. Manual thresholds remain unchanged during corner adjustment and repair fine-tuning. Turn the option on again to recalculate them. A reopened session restores its saved mode and values. Unstable sampling can also produce `?`; fixed and tentative edits are not overwritten.

The threshold sliders in pre-repair settings show which brightness values after image adjustment are read as `0` or `1`.

When the app can determine whether black and white are reversed from the square patterns at the QR code’s corners (Finder patterns), it uses that result for the preview’s cell values and the threshold descriptions. If **Auto** is selected and this has not yet been determined, assigning a Finder position makes the app check the surrounding area using both normal and inverted interpretations.

Automatic estimates are starting values, not proof of the correct symbol. Align corners with the centers of the outer modules and confirm that the selected version agrees with the number of cells along one side.

For a newly imported image, initial corners and version are tried in this order: ZXing-C++, grid periodicity, then a two-Finder fallback. The last route covers version 2 and later symbols. Two diagonal Finders can be assigned as top-right and bottom-left when both timing axes provide sufficient evidence, even without an alignment pattern. Otherwise, a verified lower-right alignment pattern may still support provisional corners without role assignment. Strong perspective distortion is not supported. If every route fails, adjust the default geometry manually.

## 3. Review and assign Finder roles

On new image import, a grid derived from ZXing-C++ can automatically set Finder centers and their top-left, top-right, and bottom-left logical roles when strict checks of all three Finders, white separators, timing patterns, and position stability pass. The import history records this assignment. These checks are heuristics, not a guarantee or a statistical probability.

To remove an incorrect assignment, press **Clear** in the Finder section and tap the green Finder. Clear all assigned Finders to make logical orientation unassigned again. Corners and existing cell edits are retained. Saving and reopening the work does not reapply a cleared assignment.

When roles were not assigned automatically, or orientation needs changing, manually assign logical roles to visible Finders. The app looks for the square pattern near the selected point to refine its center and determine whether black and white are reversed. A hidden Finder need not be assigned as though it were observed.

With exactly one assigned role and **Automatically fine-tune sampling position** enabled, small rotations around that logical corner are also evaluated. An incorrect role can prevent the correct recovery, so compare results without an assignment when its evidence is weak.

## 4. Edit cells

| Input | Meaning | Typical use |
| --- | --- | --- |
| `0` | Logical white/zero | Mark a cell that can be read as white |
| `1` | Logical black/one | Mark a cell that can be read as black |
| `?` | Unknown | Treat a weak observation as an erasure |
| `*` | Keep value `?`, but prioritize bounded 0/1 search | Mark cells to try first if ordinary erasure handling fails; default limit 4, configurable to 14 |

Choose a source for the selected cells:

| Source | Behavior during recovery |
| --- | --- |
| **From image** | Remove the edit and resample with the current thresholds |
| **Tentative (correctable)** | Keep the edited value as input, but allow recovery to correct it |
| **Fixed (no correction)** | Protect the edited 0/1 from conflicting candidates by default |

Changing an image-derived value to a different 0/1 makes it Fixed; entering the image value again does not remove that designation. You can also fix the current image-derived 0/1 without changing it. Editing a tentative 0/1 keeps it tentative, while changing `?` or `*` to 0/1 makes it Fixed. Fixed and tentative edits survive threshold changes, Undo/Redo, and saving.

In the enlarged cell editor, dragging or pinching temporarily hides cell symbols and overlays, leaving the image and QR boundary visible. The overlays return when all fingers are lifted or the gesture is canceled.

Cyan solid squares mark fixed cells; dashed squares mark tentative cells. Yellow circles mark selections. **Highlight edited cells** includes both kinds and changes only the overlay. The menu also applies to multiple selected cells. `?` is image-derived or tentative; `*` is always tentative. Neither can be Fixed.

**Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** in advanced settings defaults to OFF. Turning it ON is an explicit exception to the normal Fixed behavior: the cells retain their Fixed provenance, input values, and solid outlines, but recovery may correct them as if they were tentative. Turning it OFF protects those values again. Either way, format and data/ECC error limits, explicit ECC/mask/version selections, and consistency checks between fixed cells and explicit selections remain enforced.

In advanced settings, **Priority search cell limit** accepts 4–14 cells (default 4). The actual selected count determines the assignments: 4 cells give 16, and 14 give 16,384. Each additional cell doubles the assignments; **Automatically fine-tune sampling position** adds further work. Candidate retention limits remain independent of this setting. Lowering the limit preserves existing marks; raise it or remove marks before repairing if the count is over the limit.

The **Repair progress** dialog shows the current grid/reading-condition phase and elapsed time. During `*` assignment trials, it also shows completed and total assignments for the current grid. **Interrupt repair** stops the whole repair, including further grids and reading conditions, and retains candidates already found.

Assign the four corner positions and roles before marking `*`. Fixed structures such as finder/alignment patterns and format information, and remainder bits, cannot be marked. Selecting an ineligible cell displays its type and coordinates. If a selection includes any ineligible cell, no cells in that selection are marked.

Changing orientation rechecks existing `*` positions, removes only ineligible marks, and reports their coordinates. Their `?` values remain, and eligible marks are retained. Changing version clears all cell edits, including `*`, after confirmation.

## 5. Choose advanced settings

| Setting | Consider it when | Main risk or limit |
| --- | --- | --- |
| Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells) | Retain fixed designations while allowing recovery to correct their values | Defaults to OFF; ON still enforces error limits and explicit ECC/mask selections |
| Automatically fine-tune sampling position | Corners are close but module-center sampling is slightly displaced | Includes alignment-pattern refinement and increases work; preserves version and logical orientation |
| Format-information error limit | ECC-level/mask candidates should closely match known format cells | Too strict a limit can remove every format before RS is attempted |
| Data/ECC error limit | Candidates that overturn too much known evidence should be restricted | `0 bits` requires exact agreement with every comparable known bit |
| When the mode and character/byte count are read or assigned by \* trials, assume a single segment and force the following structure. | One segment whose header is known or can be hypothesized using * trials | Do not use with ECI, FNC1, Structured Append, or multiple segments |

Below, the last setting in the table is called **Single-segment assumption**, matching the diagnostic label. When ON, “3. Processing in this run” reports its application. When `*` trials hypothesize multiple mode/count headers, the report summarizes whether any applicable trial was found.

Both error-limit controls offer **Default**, **Exact match (0 bits)**, **Strict**, **Relaxed**, **Manual**, and **Unlimited**, with the effective bit count shown. Editing the number or using ± selects Manual.

For new imports, the format-information limit starts at **6 bits** across both copies (Strict); Relaxed allows **9 bits**, independent of version. The data/ECC limit starts at **0.875%** of the version's total data/ECC bits for **V1–4**, and **10%** for **V5–40**, rounded up to whole bits. For example, V4 starts at 7 bits and V5 at 108 bits. These presets are operational compromises, not guarantees of correct recovery.

Default follows that version rule. An explicitly selected Strict or Relaxed preset keeps its selected policy and recalculates its bit count when version changes; Manual and Unlimited remain explicit. Stored manual numbers are retained while using a preset. The format limit also applies when both ECC and mask are explicitly selected. If the limits remove a correct candidate, select a higher manual limit or Unlimited and retry.

See [Recovery Strategy](RECOVERY_STRATEGY.en.md) for exact interactions and bounds.

Search completion covers the specified conditions and bounded search policy. It is separate from candidate or explanation retention omissions and does not guarantee correctness or uniqueness. See [Recovery Result Fields](RECOVERY_RESULT_FIELDS.en.md) for the messages and reasons for unfinished work.

## 6. Recover and inspect results

**Repair** saves the current work and then runs recovery. **Save** stores the current work without running recovery.

Review results in this order:

1. Whether the outcome is Repair succeeded, incomplete, or failed.
2. The number of validated candidates and distinct contents.
3. ECC level, mask, format mismatches, and data/ECC mismatches.
4. Any search-limit or candidate-retention warning.
5. Decoded content and repair reasons for important cells.

Open diagnostics with **Show detailed results**, then tap a cell to inspect provenance. **Show same Codeword** highlights the other cells in its eight-bit codeword. Format-information cells show their role and bit position; calculation details compare both copies before and after repair and after removing the fixed format mask. The button changes to **Show ECC level cells**, **Show mask number cells**, or **Show format correction bits**, highlighting related cells in both copies. When multiple candidates remain, compare their content and candidate-agreement scope.

On the recovery result screen, **Analyze additional segments after terminator** appears below the diagnostics only when error-correction candidates were rejected because the QR data structure did not validate. It parses the data after the four-bit terminator identified from the payload structure as QR segments. Consecutive `0000` sequences after the terminator are skipped in four-bit steps, preserving ECI and FNC1 context. This is diagnostic evidence only: it does not make a rejected candidate valid, and any displayed content still requires independent verification.

In the enlarged repair result, enabling **Unmask** displays both the before and after cells with the selected candidate’s QR mask removed. Function patterns and unknown cells stay unchanged. This only changes the display: saved cell values, manual edits, and fixed constraints are preserved. Clear the checkbox to restore the masked display. The checkbox is disabled when no recovery candidate is selected; scroll the controls horizontally on narrow screens.

## 7. Save, resume, and export

Import automatically saves the source image and a session before you press Save or Repair. **Saved work** retains at most 20 sessions; new imports also count toward this limit. When it is exceeded, the least recently saved work and its source image are automatically deleted. The limit also applies on startup. Touch and hold an entry to delete that work, or use **Delete all**.

Resume saved sessions from **Saved work**. **Operation history** shows the current edit history. QRR sessions and saved source images stay in app-private no-backup storage, but they are not encrypted files. Saved work uses QRR schema 23; sessions in other schema versions cannot be opened.

Each session has at most 40 history checkpoints. Memory or storage limits can reduce that number. Trimming history to fit these limits removes older checkpoints while preserving the currently selected checkpoint and the latest one.

Reopening work or a history checkpoint recalculates recovery candidates from the saved input and settings. When the selected QR was recorded, the same QR is selected even if candidate order changes. If that QR is not found in the new search, no candidate is selected and the app explains why.

Deleting the currently open saved work leaves the on-screen work available. Choosing **Save** or running repair afterward saves it as a new session.

Explicitly exporting a recovered image or other result moves it outside app-private storage. Review sharing and backup behavior at the destination. See [Privacy](../PRIVACY.md) and [Security](../SECURITY.md).

## 8. Appearance and navigation

Choose Classic or Modern under **Menu → Appearance (next launch)**, then save. Classic is the default. The choice takes effect on the next launch; screen rotation and language changes keep the active style.

Modern uses the **01 Repair settings**, **02 Edit cells**, and **03 Result** tabs. The result tab is enabled only when a result exists. Switching tabs preserves the unsaved draft and Undo history. Returning Home asks before discarding unsaved changes.

Unsaved settings, cell edits, and Undo/Redo history survive screen rotation in both styles. They remain unsaved until you choose **Save** or run repair.

The on-screen Home/Cancel controls, Android Back, and tapping outside the settings dialog ask before discarding unsaved changes.
