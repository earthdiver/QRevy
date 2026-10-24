# QRevy User Guide

English | [Japanese](USER_GUIDE.ja.md)

This guide explains how to import, adjust, repair, and save a QR code in the Android app. Each square in the code is called a **cell** in the app; the QR specification calls it a module.

Recovery does not guarantee that the result matches the original content. Independently verify important information before using it.

## 1. Choose an input

| Input | How to open it |
| --- | --- |
| Image file | Choose **Import image** or **Select image**, then select a photo or scan. Its recorded orientation is applied automatically. |
| Camera | Choose **Camera** or **Take photo**, allow camera access, and capture the code. Review the crop, then choose **Crop and analyze**. |
| Matrix text | Choose **Import text matrix** or **TXT matrix**. Each character represents one cell: `0`, `1`, `?`, or `*`. Use a [matrix template](../templates/text-matrices/README.md) to keep the required format and dimensions. |

Prefer an image containing the complete QR code and its surrounding white margin, with clearly visible cell centers. Transparent areas are displayed and analyzed against white.

Importing automatically saves the work and source image. See [Save, resume, and export](#7-save-resume-and-export) for storage limits.

## 2. Adjust the image and grid

In Classic, choose **Repair** to open the settings. In Modern, use **01 Repair settings**.

1. Rotate and crop the image as needed. Drag a crop corner or edge to resize it, or drag inside it to move it.
2. Use **Image adjustment** for **Contrast** and **Brightness**, or **Invert** to reverse black and white. The item menu provides numeric entry and resets. **Before** compares the adjusted and original tones without changing the crop or rotation; analysis still uses the adjustments. **Done** closes the slider.
3. Check **QR Version** and align the orange corner handles with the centers of the outermost cells. Version 1 has 21 cells per side; each subsequent version adds four.
4. Review the cell values in the preview. Adjust the white/unknown/black thresholds if damaged areas are being read as definite `0` or `1` values.

Automatic estimates may need correction. If only an outline appears, select **QR Version**, even if the displayed value is already correct, to show a grid. Adjust the corners and assign a Finder role as described below. **Rectify selection to a square** clears Finder roles and orientation; assign a role again afterward.

**Set thresholds automatically** starts enabled for image imports. It recalculates thresholds when the grid changes. Moving a slider switches to manual thresholds; turning the option off keeps the current values. Manual thresholds stay unchanged during corner adjustment and sampling fine-tuning. Turn the option on to recalculate them. Saved work retains the mode and values.

Unstable readings can also become `?`. Manual edits override image readings. Enable **Treat detected color clusters as unknown (?)** only when detected colored damage should be treated as unknown. It starts disabled and is available only when color clusters were detected.

## 3. Check Finder roles

The three large square patterns near a QR code’s corners are **Finder patterns**, labeled **Finder** in the app. Their roles describe the code’s upright orientation, which may differ from the photo’s orientation.

Image and text imports may assign roles automatically. Check the green Finder markers. **At least one Finder role must be assigned before repair.**

- To assign a role, choose **Top left**, **Top right**, or **Bottom left**, then tap the center of the corresponding visible pattern. The app refines the position when it detects a nearby pattern.
- To remove an incorrect role, choose **Clear** and tap its green marker. Clearing all roles also clears orientation, while keeping corners and cell edits. Assign a role again before repair.
- A hidden Finder does not need an assignment. An incorrect role can prevent recovery, so use only roles you can identify.

When Finder patterns establish whether black and white are reversed, that interpretation takes precedence over **Auto**, **Normal**, and **Inverted**. Otherwise, those controls determine how image values are interpreted. Manually entered cell values stay unchanged.

## 4. Edit cells

In Classic, long-press **Edit cells (long press)**. In Modern, choose **Edit cells** or **02 Cells**. Tap a cell, then choose its value; use the arrow buttons to move to a neighboring cell.

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

**Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells)** in **Details** is off by default. Turning it on allows recovery to correct fixed values while preserving their entered values and Fixed designation. Turning it off protects them again. Error limits and explicitly selected version, error-correction level, and mask still apply, including consistency checks between fixed cells and explicit selections.

### Priority search with `*`

Error-correction code (**ECC**) data helps repair damaged QR content. `*` is available only in data/ECC cells, after corners and orientation are assigned. Finder patterns, format information, and other structural cells cannot be marked. If a selection includes an ineligible cell, no cells in that selection are changed.

**Priority search cell limit** in **Details** allows 4–14 cells and defaults to 4. Each additional marked cell doubles the value combinations to try, so repair can take much longer. Lowering the limit keeps existing marks; remove marks or raise the limit before repairing if the count is too high. This also applies to marks imported from text.

Changing orientation removes only marks that are no longer in data/ECC cells; their `?` values remain. Changing the version clears all cell edits after confirmation.

## 5. Choose advanced settings

Open **Details**. Start with the defaults and change settings when the preview or results show a reason to do so. QR error correction uses **Reed–Solomon (RS)** codes. A codeword contains eight bits; a block is a group of codewords corrected together.

If you know the error-correction level or mask, choose it under **ECC: Auto** or **Mask: Auto** in the repair settings. Otherwise leave **Auto** selected.

| Setting | When to use it and what to expect |
| --- | --- |
| **Automatically fine-tune sampling position** | Use when the corners are close but readings may miss cell centers. It adds work while preserving the selected version and orientation. |
| **Partial-bit search / dimension limit** | Choose 12, 13, or 14 bits per RS block; the default is 12. Each extra bit doubles the maximum number of combinations. |
| **List-decoding extension (symbols)** | Open this control to choose **Extra errors beyond conventional RS**: +1, +2, or +3 codewords per block; the default is +1. A higher limit can take much longer and may not find more candidates. |
| **Treat input image as mirrored** | Enable for a mirrored QR image. The top-right and bottom-left Finder roles exchange; the displayed image is unchanged and edits stay on the same image cells. It defaults to off and is saved with the work. |

Searches also have time and memory limits. Raising a setting does not guarantee that all combinations will be checked.

### Error limits

**Format-information error limit** restricts disagreements with known cells that identify the error-correction level and mask. **Known data/ECC bit error limit** restricts disagreements with known data/ECC cells. Unknown cells do not count. A smaller limit is stricter, but can exclude the correct result when the input contains misread cells.

Both controls offer **Default**, **Exact match (0 bits)**, **Strict**, **Relaxed**, **Manual**, and **Unlimited**. The effective bit count is shown. Editing the number or using ± selects Manual; choose **Apply** to confirm.

Presets recalculate when the version changes. Manual keeps the entered number; if it becomes invalid, enter a valid number. Exact match requires agreement with all compared known cells. See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md#input-agreement-and-qr-settings) for cells excluded from comparison. Unlimited removes that control’s limit; fixed-cell protection and explicit QR settings still apply.

List decoding explores alternatives beyond conventional RS correction. It also limits known data/ECC bit disagreements to 5% of all data/ECC bits, rounded up, or the selected data/ECC limit if smaller. A zero data/ECC limit disables list decoding.

### Single-segment assumption

A segment is a continuous part of QR data using one encoding mode. The option beginning **When the mode and character/byte count are read or assigned by \* trials…** assumes exactly one Numeric, Alphanumeric, Byte, or Kanji segment. Diagnostics call it **Single-segment assumption**.

Enable it only when that structure is known. It treats data as ending at the declared count and may replace conflicting observations after that point. The mode and count must be readable or assigned through `*` trials. Do not use it for multiple segments, messages split across QR codes (**Structured Append**), or data requiring additional control headers. Leave it off when uncertain.

## 6. Repair and inspect results

Choose **Save & repair** in Classic or **Repair** in Modern to save the current work and run recovery. **Save** stores the work without running recovery.

A **validated candidate** passes QR structure and input checks; content decoding can still fail.

The progress dialog shows elapsed time and the number of candidates found. **Interrupt repair** stops the repair and retains validated candidates already found. List decoding has a cumulative limit of **10 minutes per repair**, excluding ordinary search time, so the whole repair can take longer. Validated candidates remain available after a timeout or insufficient memory.

1. Review the recovered content. If several candidates remain, open **Candidate contents** to compare their full text and tap one to select it.
2. Open **Show diagnostics** to check input disagreements, search completion, and omitted-candidate notices. One candidate or a completed search does not prove the result is correct.
3. Open **Show detailed results** and tap cells to inspect their values and reasons. **Show same codeword** highlights the cells in the same codeword. **Data** shows bits and hexadecimal (**HEX**) values.

See [Understanding repair results](RECOVERY_RESULT_FIELDS.en.md) for result statuses, field meanings, cell colors, and comparison examples. [How QRevy recovers a code](RECOVERY_STRATEGY.en.md) explains the search order and when each method applies.

**Unmask** changes the display only; it does not change the saved QR image. For an unsuccessful attempt with no verified mask, the displayed image may stay unchanged. Turn it off to return to the normal view.

When available below diagnostics, **Analyze additional segments after terminator** examines data after the QR end marker in an unsuccessful structural check. Select a proposed starting position to compare interpretations. **Show/hide raw tail bits and HEX** displays the underlying bits; its display can start after the terminator, at the next byte, or at a chosen bit offset. This inspection does not make the attempt or the additional content a validated recovery result.

## 7. Save, resume, and export

The app saves automatically on import and before repair. It keeps at most **20 saved work entries**. When this limit is exceeded, the least recently stored entries and their source images are deleted, including during startup cleanup.

Tap an entry in **Saved work** or **Recent work** to reopen it. Long-press to delete one, or use **Delete all** to remove the list. Deleting the entry currently on screen leaves that open work available; saving afterward creates a new entry.

**Operation history** keeps up to **40 states**; memory and file-size limits can reduce that number. The selected and latest states are kept when older states are trimmed. Reopening a state saved after repair recomputes its candidates. If the previous candidate is no longer found, the app leaves the result unselected and explains why.

### Saved-work compatibility

QRR is the app’s saved-work format. The app writes format version **26** and reads versions **23–26**; other versions are rejected. Older files restore settings they did not store as follows:

| File version | Restored defaults |
| --- | --- |
| 25 | List-decoding extension: +1 |
| 24 | List-decoding extension: +1; mirrored input: off |
| 23 | List-decoding extension: +1; mirrored input: off; partial-bit search / dimension limit: 12 |

### Export and privacy

**Copy content** sends the complete recovered text to the device clipboard. **Save PNG** exports the selected validated QR candidate as a Portable Network Graphics (PNG) image; an unsuccessful attempt cannot be exported as a recovered QR.

Saved work is private to the app and excluded from Android backup and device transfer. The app does not encrypt it. Exported images are stored outside app-private storage; review the destination’s sharing and backup settings. See [Privacy](../PRIVACY.md) and [Security](../SECURITY.md).

## 8. Appearance and navigation

- Choose Japanese or English under **Menu → Language**. Before a choice is saved, the app uses Japanese for a Japanese system language and English otherwise.
- Choose Classic or Modern under **Menu → Appearance (next launch)**, then save. Classic is the default. The choice takes effect on the next launch; rotating the screen or changing language does not apply it.
- Modern uses **01 Repair settings**, **02 Cells**, and **03 Result**. The result tab is available after a result exists and keeps that result until the next repair.

Switching tabs and rotating the screen preserve unsaved settings, cell edits, and Undo/Redo history. Choose **Save** or run repair to save them. Leaving the settings asks before discarding unsaved changes.
