# QR Repair User Guide

English | [Japanese](USER_GUIDE.ja.md)

This guide covers the Android workflow from importing a damaged QR through adjustment, recovery, review, and saving. See [Recovery Strategy](RECOVERY_STRATEGY.en.md) for the algorithms and [Understanding recovery result fields](RECOVERY_RESULT_FIELDS.en.md) for diagnostics.

A recovered result is not proof that it matches the original content. Independently verify important URLs, credentials, payment destinations, and other consequential data.

## 1. Choose an input

| Input | Best suited for | Important note |
| --- | --- | --- |
| Image file | An existing photo or scan | EXIF orientation is applied during import |
| Camera | Capturing the symbol now | Confirm the crop after capture; the hand-off JPEG is deleted after import |
| `0 / 1 / ? / *` matrix text | Module decisions prepared elsewhere | Only the permitted characters and valid QR dimensions are accepted |

Prefer an image containing the complete QR and, when possible, its surrounding quiet zone, with module centers that are not completely blurred. Never attach a sensitive real QR image to a public issue.

## 2. Adjust image and geometry

1. Rotate in 90°, 1°, or 0.1° increments when needed.
2. Crop to the QR region.
3. Review the estimated corners and version.
4. Review the black/unknown/white thresholds so gray damage remains `?` where appropriate. **Set thresholds automatically** starts checked for a new image and updates one global pair when the grid changes. Moving either threshold slider unchecks it; unchecking it directly keeps the current values. With it unchecked, both values remain manual during corner adjustment and repair fine-tuning. Checking it again recalculates the thresholds. Reopening a session restores its saved checkbox state. Unstable sampling also produces `?`; fixed and tentative cell edits are retained.
5. Enable **Treat detected color clusters as unknown (?)** only when colored damage should be treated as unknown. It starts disabled.

Automatic estimates are starting values, not proof of the correct symbol. Align corners with the centers of the outer modules and confirm that the selected version agrees with the number of cells along one side.

For a newly imported image, initial corners and version are tried in this order: ZXing-C++, grid periodicity, then a two-Finder fallback. The last route covers version 2 and later symbols. Two diagonal Finders can be assigned as top-right and bottom-left when both timing axes provide sufficient evidence, even without an alignment pattern. Otherwise, a verified lower-right alignment pattern may still support provisional corners without role assignment. Strong perspective distortion is not supported. If every route fails, adjust the default geometry manually.

## 3. Review and assign Finder roles

On new image import, a grid derived from ZXing-C++ can automatically set Finder centers and their top-left, top-right, and bottom-left logical roles when strict checks of all three Finders, white separators, timing patterns, and position stability pass. The import history records this assignment. These checks are heuristics, not a guarantee or a statistical probability.

To remove an incorrect assignment, press **Clear** in the Finder section and tap the green Finder. Clear all assigned Finders to make logical orientation unassigned again. Corners and existing cell edits are retained. Saving and reopening the work does not reapply a cleared assignment.

When roles were not assigned automatically, or orientation needs changing, manually assign logical roles to visible Finders. The app refines center and polarity near the selected point. A hidden Finder need not be assigned as though it were observed.

With exactly one assigned role and **Automatically fine-tune sampling position** enabled, small rotations around that logical corner are also evaluated. An incorrect role can prevent the correct recovery, so compare results without an assignment when its evidence is weak.

## 4. Edit cells

| Input | Meaning | Typical use |
| --- | --- | --- |
| `0` | Logical white/zero | Mark a cell that can be read as white |
| `1` | Logical black/one | Mark a cell that can be read as black |
| `?` | Unknown | Treat a weak observation as an erasure |
| `*` | Keep value `?`, but prioritize bounded 0/1 search | Mark at most four cells to try first if ordinary erasure handling fails |

Choose **From image / Tentative (correctable) / Fixed (no correction)** from the source button. Changing an image-derived value to a different 0/1 makes it Fixed; entering the image value again keeps it Fixed. You can also fix the current image-derived 0/1 without changing it. Tentative keeps the edited value as recovery input but allows correction; changing a tentative 0/1 to another 0/1 keeps it tentative. Changing `?` or `*` to 0/1 makes it Fixed. From image removes the override and resamples with the current thresholds. Fixed and tentative edits survive threshold changes, Undo/Redo, and saving.

In the enlarged cell editor, dragging or pinching temporarily hides cell symbols and overlays, leaving the image and QR boundary visible. The overlays return when all fingers are lifted or the gesture is canceled. Cell values and edits are unchanged.

Cyan solid squares mark fixed cells; dashed squares mark tentative cells. Yellow circles mark selections. **Highlight edited cells** includes both kinds and changes only the overlay. The menu also applies to multiple selected cells. `?` is image-derived or tentative; `*` is always tentative. Neither can be Fixed.

**Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** in advanced settings defaults to OFF. OFF rejects candidates that conflict with fixed 0/1 values. ON retains their fixed designation, input values, and solid outlines while allowing correction during recovery. Switching it OFF protects the fixed values again. As with tentative values, correction remains subject to format and data/ECC error limits. Explicit ECC/mask/version selections and consistency checks between fixed cells and explicit selections remain enforced.

Assign the four corner positions and roles before marking `*`. Fixed structures such as finder/alignment patterns and format information, and remainder bits, cannot be marked. Selecting an ineligible cell displays its type and coordinates. If a selection includes any ineligible cell, no cells in that selection are marked.

Changing orientation rechecks existing `*` positions, removes only ineligible marks, and reports their coordinates. Their `?` values remain, and eligible marks are retained. Changing version clears all cell edits, including `*`, after confirmation.

## 5. Choose advanced settings

| Setting | Consider it when | Main risk or limit |
| --- | --- | --- |
| Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells) | Retain fixed designations while allowing recovery to correct their values | Defaults to OFF; ON still enforces error limits and explicit ECC/mask selections |
| Automatically fine-tune sampling position | Corners are close but module-center sampling is slightly displaced | Increases work; never changes version or logical orientation |
| Format-information error limit | ECC-level/mask candidates should closely match known format cells | Too strict a limit can remove every format before RS is attempted |
| Data/ECC error limit | Candidates that overturn too much known evidence should be restricted | `0 bits` requires exact agreement with every comparable known bit |
| Force following structure when mode and character/byte count are read correctly | One segment and its mode and character/byte count are known to be correct | Do not use with ECI, FNC1, Structured Append, or multiple segments |

See [Recovery Strategy](RECOVERY_STRATEGY.en.md) for exact interactions and bounds.

## 6. Recover and inspect results

**Repair** saves the current work and then runs recovery. **Save** stores the current work without running recovery.

Review results in this order:

1. Whether the outcome is Repair succeeded, incomplete, or failed.
2. The number of validated candidates and distinct contents.
3. ECC level, mask, format mismatches, and data/ECC mismatches.
4. Any search-limit or candidate-retention warning.
5. Decoded content and repair reasons for important cells.

Open diagnostics with **Show detailed results**, then tap a cell to inspect provenance. **Show same Codeword** highlights the other cells in its eight-bit codeword. When multiple candidates remain, compare their content and candidate-agreement scope.

On the recovery result screen, **Analyze additional segments after terminator** appears below the diagnostics as a secondary text button only when error-correction candidates were rejected because the QR data structure did not validate. It parses the data immediately after the four-bit terminator identified from the payload structure as QR segments (preserving ECI and FNC1 context).

## 7. Save, resume, and export

Import automatically saves the source image and a session before you press Save or Repair. **Saved work** retains at most 20 sessions; new imports also count toward this limit. When it is exceeded, the least recently saved work and its source image are automatically deleted. The limit also applies on startup. Touch and hold an entry to delete that work, or use **Delete all**.

Resume saved sessions from **Saved work**. **Operation history** shows the current edit history. QRR sessions and saved source images stay in app-private no-backup storage, but they are not encrypted files.

Each session has at most 40 history checkpoints. Memory or storage limits can reduce that number. Trimming history to fit these limits removes older checkpoints while preserving the currently selected checkpoint and the latest one.

Reopening work or a history checkpoint recalculates recovery candidates from the saved input and settings. When the selected QR was recorded, the same QR is selected even if candidate order changes. If that QR is not found in the new search, no candidate is selected and the app explains why.

Deleting the currently open saved work leaves the on-screen work available. Choosing **Save** or running repair afterward saves it as a new session.

Explicitly exporting a recovered image or other result moves it outside app-private storage. Review sharing and backup behavior at the destination. See [Privacy](../PRIVACY.md) and [Security](../SECURITY.md).

## 8. Appearance and navigation

Choose Classic or Modern under **Menu → Appearance (next launch)**, then save. Classic is the default. The choice takes effect on the next launch; screen rotation and language changes keep the active style.

Modern uses the **01 Repair settings**, **02 Edit cells**, and **03 Result** tabs. The result tab is enabled only when a result exists. Switching tabs preserves the unsaved draft and Undo history. Returning Home asks before discarding unsaved changes.

Unsaved settings, cell edits, and Undo/Redo history survive screen rotation in both styles. They remain unsaved until you choose **Save** or run repair.

The on-screen Home/Cancel controls, Android Back, and tapping outside the settings dialog ask before discarding unsaved changes.
