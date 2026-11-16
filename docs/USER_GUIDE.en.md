# QRevy User Guide

English | [Japanese](USER_GUIDE.ja.md)

This guide explains how to import, adjust, repair, and save a QR code in the Android app. Each square in the code is called a **cell** in the app; the QR specification calls it a module.

After importing, review the automatic repair result and adjust the image or cells if needed. See [Advanced settings](#6-choose-advanced-settings) when changing recovery options.

Recovery does not guarantee that the result matches the original content. Independently verify important information before using it.

## 1. Choose an input

| Input | How to open it |
| --- | --- |
| Image file | Choose **Import image** or **Select image**, then select a photo or scan. Its recorded orientation is applied automatically. |
| Camera | Choose **Camera** or **Take photo**, allow camera access, and capture the code. Review the crop, then choose **Crop and analyze**. |
| Matrix text | Choose **Import text matrix** or **TXT matrix**. Each character represents one cell: `0`, `1`, `?`, or `*`. Use a [matrix template](../templates/text-matrices/README.md) to keep the required format and dimensions. |

Prefer an image containing the complete QR code and its surrounding white margin, with clearly visible cell centers. Transparent areas are displayed and analyzed against white.

Importing automatically saves the work and source image. See [Save, resume, and export](#5-save-resume-and-export) for storage limits.

### Quick automatic repair

Use **Menu → Quick automatic repair** to turn it ON or OFF. It defaults to ON, stays saved when the app closes, and applies to subsequent imports.

If ordinary reading fails for an image, camera capture, or TXT matrix, the app tries a short automatic repair. During processing, choose **Detailed analysis** to stop automatic repair and open the settings to review or adjust the grid, cells, and decoding conditions. If the input is still loading, the screen changes once the necessary loading finishes.

If ordinary reading succeeds, choose **Repair** on the result screen to open detailed analysis. When Quick automatic repair is OFF, failed ordinary reading opens detailed analysis directly.

For TXT matrices, automatic repair tries `0` and `1` for cells marked `*`. With Quick automatic repair ON, the number of `*` cells is not limited during import or automatic repair. See [Edit cells](#3-edit-cells) for the symbols and their manual use.

### Automatic results and retries

When a completed trial leaves one validated candidate, the app shows its content and the assumptions used. If repair fails, it shows the reason.

| Result | Next action |
| --- | --- |
| One candidate remains | Choose **Analyze in detail** to continue reviewing or adjusting, or **Done** to keep the result. |
| Repair failed | Choose **Detailed analysis** to review or adjust using the grid and results already obtained. |
| Time ran out | Choose **Retry with more time (10 seconds)**. |
| The ten-second retry also timed out | Choose **Retry without time limits**. No further retry button is offered after this attempt. |

Retries start again from the same input and reuse an available grid. A retry without time limits can still stop for reasons such as insufficient memory. Choose **Detailed analysis** to stop either retry.

The result screen shows the conditions used for automatic repair. Changing detailed-analysis settings does not change the conditions attached to that result. See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md) and [How quick automatic repair works](RECOVERY_STRATEGY.en.md#10-quick-automatic-repair).

## 2. Adjust the image and grid

In Classic, choose **Repair** to open the settings. In Modern, use **01 Repair settings**.

1. Rotate and crop the image as needed. Drag a crop corner or edge to resize it, or drag inside it to move it.
2. Use **Image adjustment** for **Contrast** and **Brightness**, or **Invert** to reverse black and white. The item menu provides numeric entry and resets. **Before** compares the adjusted and original tones without changing the crop or rotation; analysis still uses the adjustments. **Done** closes the slider.
3. Check **QR Version** and align the orange corner handles with the centers of the outermost cells. Version 1 has 21 cells per side; each subsequent version adds four. Changing Version clears cell edits after confirmation.
4. Review the cell values in the preview. Adjust the white/unknown/black thresholds if damaged areas are being read as definite `0` or `1` values.

Automatic estimates may need correction. If only an outline appears, select **QR Version**, even if the displayed value is already correct, to show a grid. Until a Finder role is assigned, provisional alignment guides appear at all four corners and along all four sides, with dashed outlines. Adjust the corners and assign a Finder role as described below. **Rectify selection to a square** clears Finder roles and orientation; assign a role again afterward.

**Set thresholds automatically** starts enabled for image imports. It recalculates thresholds when the grid changes. To enter an exact value, long-press the label above either slider and tap **Apply**. Applying a value or moving a slider switches to manual thresholds. Turning the option off keeps the current values. Manual thresholds stay unchanged during corner adjustment and sampling fine-tuning. Turn the option on to recalculate them. Saved work retains the mode and values.

Unstable readings can also become `?`. Manual edits override image readings. Enable **Treat detected color clusters as unknown (?)** only when detected colored damage should be treated as unknown. It starts disabled and is available only when color clusters were detected.

### Check Finder roles

The three large square patterns near a QR code’s corners are **Finder patterns**, labeled **Finder** in the app. Their roles describe the code’s upright orientation, which may differ from the photo’s orientation.

Image and text imports may assign roles automatically. Check the green Finder markers. **At least one Finder role must be assigned before repair.**

- In basic settings, enable **Treat input image as mirrored** for a mirror image. Once orientation is assigned, the image shows the role of each corner.
- To assign a role, choose **Top left**, **Top right**, or **Bottom left**, then tap the center of the corresponding visible pattern. The app refines the position when it detects a nearby pattern.
- To remove an incorrect role, choose **Clear** and tap its green marker. Clearing all roles also clears orientation, while keeping corners and cell edits. Assign a role again before repair.
- A hidden Finder does not need an assignment. An incorrect role can prevent recovery, so use only roles you can identify.

When Finder patterns establish whether black and white are reversed, that interpretation takes precedence over **Auto**, **Normal**, and **Inverted**. Otherwise, those controls determine how image values are interpreted. Manually entered cell values stay unchanged.

### Grid correction

**Correct grid using LM** uses the Levenberg–Marquardt (LM) method to fit the grid to patterns in the image. It is ON for new work. Once corners, version, and orientation are assigned, inspect the corrected grid and cell values. If correction cannot be applied, the original grid is used. Turning the option OFF restores the original grid without clearing cell edits.

While LM correction is ON, assigning or changing a Finder recalculates the correction. Changing corner positions or orientation turns LM correction OFF; turn it on again if needed. Editing cell values or their fixed designation keeps the correction active. Saved work restores the correction choice and the original corners.

**Correct grid for curved surfaces** is for curved paper or labels and defaults to OFF. Turn it on and inspect the preview. If several candidates are available, choose one under **Distortion: candidate**. Repair uses the displayed grid, and the correction recalculates when you finish adjusting corners. If correction is unavailable, the screen reports this and uses a straight grid. It can be combined with LM correction. See [distortion correction and its acceptance conditions](RECOVERY_STRATEGY.en.md#distortion-correction) for details.

## 3. Edit cells

In Classic, long-press **Edit cells (long press)**. In Modern, choose **Edit cells** or **02 Cells**. Tap a cell, then choose its value; use the arrow buttons to move to a neighboring cell. Choose **Open large editor** for a larger editing area.

| Value | Meaning |
| --- | --- |
| `0` | Logical zero: white in the normal black/white interpretation |
| `1` | Logical one: black in the normal black/white interpretation |
| `?` | Unknown; use when a cell cannot be read reliably |
| `*` | Unknown, with priority given to trying both `0` and `1` during repair |

| Tool | Operation |
| --- | --- |
| **▱** | Drag to select a rectangle, then choose a value or source for the selected cells. |
| **0 brush**, **1 brush**, **? brush** | Drag across cells to apply the corresponding value. |
| **? 5×5** | Tap to mark a five-by-five area as unknown. |
| **1 cell** | Return to selecting one cell at a time. |
| **Undo / Redo** | Undo or redo an edit. |

Choose how the selected values should be treated:

| Source | Effect |
| --- | --- |
| **From image** | Remove the edit and read the image using the current settings. |
| **Tentative (correctable)** | Use the entered value, but allow recovery to correct it. |
| **Fixed (no correction)** | Reject recovery candidates that conflict with the entered `0` or `1`, by default. |

Changing an image-derived value to a different `0` or `1`, or changing `?` or `*` to `0` or `1`, makes it Fixed. For an uncertain value, enter it and then choose **Tentative (correctable)**. Editing an existing tentative `0` or `1` keeps it tentative. To remove an edit, choose **From image**; entering the image value again does not remove it. Unknown values cannot be Fixed.

Cyan solid squares mark fixed cells; dashed squares mark tentative cells; yellow circles mark selections, with a frame around rectangle and stamp selections. The full selection remains after editing, including unchanged cells. **Highlight edited cells** changes only the display. Edits are preserved when thresholds change and when work is saved and reopened.

**Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** in **Details** is off by default. Turning it on allows recovery to correct fixed values while preserving their entered values and Fixed designation. Turning it off protects them again. Error limits and explicitly selected version, error-correction level, and mask apply, including consistency checks between fixed cells and explicit selections.

### Priority search with `*`

Error-correction code (**ECC**) data helps repair damaged QR content. `*` is available only in data/ECC cells, after corners and orientation are assigned. Finder patterns, format information, and other structural cells cannot be marked. If a selection includes an ineligible cell, no cells in that selection are changed.

**Priority search cell limit** in **Details** allows 4–14 cells and defaults to 4. Each additional marked cell doubles the value combinations to try, so repair can take much longer. Lowering the limit keeps existing marks.

| Operation | Priority-cell count limit |
| --- | --- |
| TXT import and automatic repair with Quick automatic repair ON | No cell count limit; time and memory limits still apply |
| TXT import with Quick automatic repair OFF | 14 cells |
| Starting repair in detailed analysis | The selected limit, including marks imported from TXT |

If the count exceeds the detailed-analysis limit, remove marks or raise the limit before repairing.

Changing orientation removes only marks that are no longer in data/ECC cells; their `?` values remain. Changing the version clears all cell edits after confirmation.

## 4. Repair and inspect results

Choose **Save & repair** in Classic or **Repair** in Modern to save the current work and run recovery. **Save** stores the work without running recovery.

A **validated candidate** passes QR structure and input checks; content decoding can still fail.

The progress dialog shows elapsed time and the number of candidates found. **Interrupt repair** stops the repair and retains validated candidates already found. In detailed analysis, list decoding (searching beyond the usual error-correction range) has a cumulative limit of **10 minutes per repair**, excluding ordinary search time, so the whole repair can take longer. Validated candidates remain available after a timeout or insufficient memory.

1. Review the recovered content. If several candidates remain, open **Candidate contents** to compare their full text and tap one to select it.
2. Check the selected trial's conditions and termination, along with omitted-candidate notices, in the result summary. Open **Show diagnostics** to check input disagreements and records including other trial conditions and grids. Normal endings at search boundaries are distinguished from timeouts and user interruption, with the unchecked work explained. One candidate or a completed search does not prove the result is correct.
3. Open **Show detailed results** and tap cells to inspect their values and reasons. **Show same codeword** highlights the cells in the same codeword (a group of eight bits). **Data** shows bits and hexadecimal (**HEX**) values.

See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md) for result statuses, field meanings, cell colors, and comparison examples. [QRevy recovery strategy](RECOVERY_STRATEGY.en.md) explains the search order and when each method applies.

**Unmask** changes the display only; it does not change the saved QR image. For an unsuccessful attempt with no verified mask, the displayed image may stay unchanged. Turn it off to return to the normal view.

When available below diagnostics, **Analyze additional segments after terminator** examines data after the QR end marker in an unsuccessful structural check. Select a proposed starting position to compare interpretations. **Show/hide raw tail bits and HEX** displays the underlying bits; its display can start after the terminator, at the next byte, or at a chosen bit offset. This inspection does not make the attempt or the additional content a validated recovery result.

## 5. Save, resume, and export

The app saves automatically on import and before repair. It keeps at most **20 saved work entries**. When this limit is exceeded, the least recently stored entries and their source images are deleted, including during startup cleanup.

Tap an entry in **Saved work** or **Recent work** to reopen it. Long-press to delete one, or use **Delete all** to remove the list. Deleting the entry currently on screen leaves that open work available; saving afterward creates a new entry.

**Operation history** keeps up to **40 states**; memory and file-size limits can reduce that number. The selected and latest states are kept when older states are trimmed. Reopening a state saved after repair recomputes its candidates. If the previous candidate is no longer found, the app leaves the result unselected and explains why.

Quick automatic repair saves its execution assumptions and list-decoding extension separately from the advanced settings, even when no candidate is found. Reopening its history uses those execution conditions. If the history lacks those conditions, the app retains the saved input without recalculating candidates and asks you to review the settings before repairing.

### Export and privacy

**Save pre-repair TXT matrix** at the bottom of **Details** saves the current grid as a `0/1/?/*` text file, including unsaved cell edits and priority marks, before structural completion or recovery. The file can be imported with **TXT matrix**. It contains cell values and priority marks only; it does not retain fixed/tentative distinctions, edit history, or recovery settings.

**Copy content** sends the complete recovered text to the device clipboard. **Save PNG** exports the selected validated QR candidate as a Portable Network Graphics (PNG) image; an unsuccessful attempt cannot be exported as a recovered QR.

Saved work is private to the app and excluded from Android backup and device transfer. The app does not encrypt it. Exported images and text matrices are stored outside app-private storage; review the destination’s sharing and backup settings. See [Privacy](../PRIVACY.md) and [Security](../SECURITY.md).

## 6. Choose advanced settings

Open **Details**. Start with the defaults and change settings when the preview or results show a reason to do so. QR error correction uses **Reed–Solomon (RS)** codes. An RS block is a group of codewords corrected together.

If you know the error-correction level or mask, choose it under **ECC: Auto** or **Mask: Auto** in the repair settings. Otherwise leave **Auto** selected.

| Setting | Default | When to use it and what to expect |
| --- | --- | --- |
| **Automatically fine-tune sampling position** | OFF | Use when the corners are close but readings may miss cell centers. Checking nearby positions adds work while preserving the version and orientation. |
| **Partial-bit search / dimension limit** | 12 bits | To try a wider range of unknown-cell values, choose 12, 13, or 14 bits per RS block. Each extra bit doubles the maximum number of combinations. |
| **Known-bit error search** | OFF | Use when some readable cells may be wrong as well as others being unknown. After ordinary repair fails, it searches for up to 1 or 2 incorrect known bits across the whole QR. |
| **List-decoding extension (symbols)** | +1 codeword | List decoding searches beyond the usual error-correction range. Open this control to choose **Extra errors beyond conventional RS**: OFF, or +1, +2, or +3 codewords per block. A higher limit can take much longer and may not find more candidates. |

Searches also have time and memory limits. Raising a setting does not guarantee that all combinations will be checked. See [Search completion and omissions](RECOVERY_RESULT_FIELDS.en.md#search-completion-and-omissions) for limits and the [QRevy recovery strategy](RECOVERY_STRATEGY.en.md) for calculations and applicability.

### Error limits

**Format-information error limit** restricts disagreements with known cells that identify the error-correction level and mask. **Known data/ECC bit error limit** restricts disagreements with known data/ECC cells. Unknown cells do not count. For example, a 3-bit limit rejects candidates that differ at four or more compared known cells. A smaller limit is stricter, but can exclude the correct result when the input contains misread cells.

Both controls offer **Default**, **Exact match (0 bits)**, **Strict**, **Relaxed**, **Manual**, and **Unlimited**. The effective bit count is shown. Editing the number or using ± selects Manual; choose **Apply** to confirm.

The default data/ECC limit is 5% of all data/ECC bits, rounded up. Strict uses 0.875% and Relaxed uses 10%, rounded up. The format-information default is 6 bits. See [Saved-work compatibility](#8-saved-work-compatibility) for older work.

Presets recalculate when the version changes. Manual keeps the entered number; if it becomes invalid, enter a valid number. Exact match requires agreement with all compared known cells. See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md#input-agreement-and-qr-settings) for cells excluded from comparison. Unlimited removes that control’s limit; fixed-cell protection and explicit QR settings still apply.

List decoding explores alternatives beyond conventional RS correction. It also limits known data/ECC bit disagreements to 5% of all data/ECC bits, rounded up, or the selected data/ECC limit if smaller. A zero data/ECC limit disables list decoding.

### Assume UTF-8 text in Byte mode

UTF-8 is a Unicode text encoding. Enable this option when Byte segments without an explicit encoding are known to contain UTF-8 text. It excludes candidates that cannot form UTF-8 and reads the remaining candidates as UTF-8. An encoding specified in the QR code takes precedence. Leave this OFF if other encodings or binary data are possible. It defaults to OFF and works independently of the single-segment option.

### Force single-segment terminator and padding

A segment is a continuous part of QR data using one encoding mode. **Force single-segment terminator and padding** uses a single Numeric, Alphanumeric, Byte, or Kanji segment to determine the suffix.

Enable it only when that structure is known. It treats data as ending at the declared count and may replace conflicting observations after that point. The mode and count must be readable or assigned through `*` trials. Do not use it for multiple segments, messages split across QR codes (**Structured Append**), or data requiring additional control headers. Leave it off when uncertain.

When this option is off, suffix errors can still be corrected within the selected data/ECC limit. Final mismatches are counted against the input at repair start, including any suffix bits corrected first. See [Using QR data structure](RECOVERY_STRATEGY.en.md#6-using-qr-data-structure) for filling conditions.

If no candidate decodes and a separate feasibility check succeeds with forcing, the app offers **Retry this time only**. This does not change the advanced setting. The runtime condition is saved with the history for reproducibility. The offer is not repeated for the same input and settings, or shown after you interrupt a search.

## 7. Appearance and diagnostic information

- Choose Japanese or English under **Menu → Language**. Before a choice is saved, the app uses Japanese for a Japanese system language and English otherwise.
- Choose Classic or Modern under **Menu → Appearance (next launch)**, then save. Classic is the default. The choice takes effect on the next launch; rotating the screen or changing language does not apply it.
- Modern uses **01 Repair settings**, **02 Cells**, and **03 Result**. The result tab is available after a result exists and keeps that result until the next repair.

Switching tabs and rotating the screen preserve unsaved settings, cell edits, and Undo/Redo history. Choose **Save** or run repair to save them. Leaving the settings asks before discarding unsaved changes.

### Copy diagnostic information

Debug builds, intended for development and testing, provide **Menu → Developer menu → Quick automatic repair diagnostics**. Choose **Copy diagnostics** to copy the processing record for a bug report. Copy it before rotating the screen, reopening work, or closing the app.

## 8. Saved-work compatibility

QRR is the app's saved-work format. The app writes format version **41** and reads versions **23–41**; other versions are rejected. The table describes how older work is restored and which information each format can retain.

| File versions | Item | Behavior when opened |
| --- | --- | --- |
| 23 | Partial-bit search / dimension limit | 12 bits |
| 23–24 | Treat input image as mirrored | OFF |
| 23–25 | List-decoding extension | +1 codeword |
| 23–26 | One-time single-segment forcing | No runtime override; the saved advanced setting still applies |
| 23–27 | Known-bit error search | OFF |
| 23–29 | Distortion correction | OFF |
| 23–30 | Selected preview grid | Not stored |
| 23–31 | UTF-8 assumption for Byte segments | OFF |
| 23–32 | Image sampling margins for temporary erasures | Not stored |
| 23–33 | LM correction | OFF |
| 34 | LM correction | ON only when a selected LM grid was saved |
| 35–41 | LM correction | Restore the on/off choice saved separately from the grid |
| 23–35 | Quick automatic repair assumptions | No runtime override separate from advanced settings |
| 36–41 | Quick automatic repair assumptions | Restore separately from editable advanced settings |
| 37–41 | Automatic trial assuming observed data/ECC bits are correct | Can restore this runtime assumption |
| 38–41 | Automatic trial combining UTF-8 and single-segment assumptions | Can restore this runtime assumption |
| 36–38 | Automatic list-decoding extension | +1 codeword |
| 39–41 | Automatic list-decoding extension | Restore the value saved separately from advanced settings |
| 40–41 | TXT unknown-cell and priority marks | Preserve imported marks, including more than 14 `*` cells, and the mark positions oriented for automatic repair |
| 23–40 | Default data/ECC error limit | Restore each history entry's previous effective bit count as Manual |

Selecting Default again applies the current 5% data/ECC limit. Other modes and separately saved automatic-repair execution conditions are unchanged. Handling imported TXT unknown-cell marks does not bypass fixed 0/1 edits.
