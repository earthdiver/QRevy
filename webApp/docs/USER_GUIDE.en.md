# QRevy PWA User Guide

English | [日本語](USER_GUIDE.ja.md) | [README](../README.md)

QRevy PWA (Progressive Web App, a web app that can be installed from a browser) attempts to recover damaged QR codes from images or manually entered readings. Each small square in a QR code is a **module**, also called a cell in some controls. The app supports QR Code Model 2, versions 1–40.

A recovered candidate is not guaranteed to match the original content. Independently verify important information before using it.

## 1. Open the app

Open the app's HTTPS address in a browser on iPhone, Android, or a desktop computer. Installation is optional. To launch it from your home screen or app list, use the steps below.

| Device and browser | Installation |
| --- | --- |
| iPhone, Safari | Choose **Share → Add to Home Screen**. |
| Android, Chrome | Choose the installation option in the browser menu and follow the prompts. |
| Desktop, Chrome | Use the install icon in the address bar, or **Cast, save, and share → Install page as app** in the menu. |

Menu names and installation support vary by browser. See the instructions for [Android](https://support.google.com/chrome/answer/9658361?hl=en&co=GENIE.Platform%3DAndroid) and [desktop](https://support.google.com/chrome/answer/9658361?hl=en&co=GENIE.Platform%3DDesktop). On a desktop computer, read "tap" in this guide as "click".

Before disconnecting, open **Menu → Help** and check for **Offline ready**. Initial loading and updates require a connection.

Use **English / 日本語** at the top to change languages. In **Menu → Help**, choose the appearance setting to select Classic or Modern for the next launch. The same menu contains version information, licenses, and privacy information.

To try the workflow, select **Try a damaged sample** and move through input, module editing, and recovery results.

## 2. Choose an input

Select **Menu → Home** at the top of the screen to choose an image or workspace. You can also return from recovery results; your current work is retained. **Return to current workspace** takes you back to the previous screen. Select **Open a saved workspace** to resume saved work.

| Input | Action |
| --- | --- |
| Photo or scan | Select **Select image**, then a file. |
| Camera | Select **Take photo**. Camera availability and the selection screen depend on your device and browser. |
| Text matrix | Select a text file using **TXT matrix**, or use **Paste a text matrix → Import text matrix**. |
| Saved workspace | Use **Workspace** to open a `.qrpwa` or `.qrr` file. |

Use an image showing the entire code and its surrounding margin, with individual modules distinguishable. In the crop screen, drag a corner or edge to resize the selection, or drag inside it to move it. Select **Crop and analyze**. You can also adjust rotation, brightness, contrast, and color inversion. **Before** compares tones without undoing the adjustments that will be applied.

The initial selection surrounds the detected code with a margin, or covers the whole image if detection fails. Analysis resolution uses the larger of the initial and selected crop sizes, limiting resolution changes when you narrow the selection.

A text matrix contains only `0`, `1`, `?`, and `*`, with one character per module. The number of rows must equal the number of characters per row: 21 × 21 for version 1, 25 × 25 for version 2. Do not include spaces or separators.

To adjust a text matrix as an image, use **Adjust as an image (new workspace)**. This creates an image from the original matrix and carries over manual edits. The original workspace can still be reopened from **Menu → Open a saved workspace**.

### Quick automatic repair

**Quick automatic repair** in the input panel starts enabled. The preference is retained across launches and applies to subsequent image, camera, and TXT matrix imports. For images and camera input, the app first attempts ordinary QR reading. A successful read opens a text result with Copy and Detailed analysis actions; it is not a repair candidate. The Ordinary reading result button reopens it while that workspace remains open. If ordinary reading fails, the app attempts a short automatic repair. When automatic repair is disabled, unreadable inputs open in the repair settings so you can review the grid and conditions before repairing.

Choose **Detailed analysis** during processing to stop automatic repair and adjust the grid or modules. Image imports open in image view. If loading is still in progress, the app finishes the necessary loading first.

| Result | Next action |
| --- | --- |
| A completed trial leaves one candidate | Review its content and conditions; continue to detailed analysis if needed. |
| Repair failed | Review the reason, then inspect the grid or modules in detailed analysis. |
| Time ran out | Retry with a ten-second limit. |
| The ten-second retry also timed out | Retry without time limits. No further retry button is offered after this attempt. |

Retries restart from the same input and reuse an available grid. Even without time limits, an attempt can stop for insufficient memory or other reasons. Retained candidates remain available in the repair result even when no single result is selected. Changing detailed settings does not change the automatic execution conditions recorded with the result.

When needed, enable **Record quick automatic repair diagnostics** before importing. Open **Repair result → Quick automatic repair diagnostics** to review each attempt and use **Copy diagnostics** to copy the record.

## 3. Adjust the image and grid

### Version and corners

1. Select **Image** and check **QR version**. Version 1 has 21 modules per side; each subsequent version adds four.
2. Set **Assign Finder centers and logical roles** to **Select a role**, then align the orange points with the **centers of the outermost modules**, rather than the code's outer boundary or surrounding margin.
3. For precise adjustments, select **Adjust corners with magnifier**. Choose a corner and movement step, use the arrows, then select **Apply**. Cancel discards changes made in this dialog.
4. Switch to **Modules** and check that the grid matches the image.

Use **＋ / −** to zoom and **Pan/zoom** to move the image. On touch screens, you can also zoom and pan with two fingers. **Fit** returns to the full view.

The overlaid **Finder and timing-pattern guides** help you choose the version manually. Finder patterns are the three large square position markers. Timing patterns are the alternating black and white modules between them. Adjust the version and corners until the guide sizes, positions, and alternating modules match the image. Before orientation is assigned, provisional Finder guides appear at all four corners. The guides alone may not establish the version when parts of the image are missing.

**Changing the version clears module edits.** Check it before editing modules. Also review and adjust the result of **Detect grid** against the image.

### Finder roles and orientation

Select **Assign Finder centers and logical roles**, choose top left, top right, or bottom left, then tap the corresponding Finder's center. Roles refer to the upright QR code, which may differ from the photo's orientation.

Corners with a confirmed Finder center are green and protected from movement; orange corners can move. Green does not indicate reading confidence. If the center cannot be detected, an estimated or manually adjusted grid lets you assign only the role, leaving the corners movable. Review any confirmation prompt before applying the assignment; cancel keeps the previous state.

Choose a role and use **Clear selected Finder** to remove its measured center, or **Clear role without a center** to remove a role-only assignment. **Clear all Finder assignments**, under **Finder assignment**, removes all assignments.

Use **Right 90°** or **Treat input image as mirrored** as needed and check the orientation. Orientation is assigned by automatic estimation or a Finder role. After clearing all assignments, assign at least one Finder role again. Recovery requires a confirmed orientation.

**Classification**, directly below the preview in the repair settings and cell editor, shows the current counts of `1`, `0`, `?`, and `*`.

### Tones and sampling

Use **Additional image adjustments** to adjust brightness, contrast, and polarity. For a code with reversed black and white, choose **Thresholding → Inverted**.

**Set thresholds automatically** starts enabled. Thresholds separate black, white, and unknown readings. Use the sliders or number fields. Numbers represent image brightness from 0 to 255. With normal polarity, keep **Black maximum < White minimum**. For example, values of 96 and 160 make intermediate tones unknown. Changing a value disables automatic thresholds. If the boundaries overlap, the other value is adjusted too. With inverted polarity, the brightness value for QR black is higher than for white, and numeric values change in the opposite direction to the sliders. Polarity confirmed by Finder patterns takes precedence. Manual module edits take precedence over image readings.

When colored damage has been detected, enable **Treat detected color clusters as unknown (?)** if those areas should be unknown. Cell readings update automatically when you change the grid or sampling settings. Sliders apply when released, and number fields apply when committed. Manual module edits are preserved.

### Grid refinement and image transforms

**Grid correction** groups LM and curved-surface controls together. **Additional image adjustments** contains image rotation, perspective transforms, and **Save sampling image**.

| Action | Purpose |
| --- | --- |
| **Correct grid using LM** | Attempts position refinement using the Levenberg–Marquardt (LM) method. Enabled for new work. Check the resulting grid against the image. |
| **Correct grid for curved surfaces** | Adjusts the grid for curved paper or labels. Confirm orientation first; if several choices appear, select the one that fits the image. |
| **Automatically fine-tune sampling position** | Tests small reading-position changes when the grid is already approximately aligned. Increases processing time. |
| Image rotation / reset | Rotates the image while retaining perspective correction. Reset returns only the rotation angle to zero. |
| Rectify the four corners to a square | Transforms the selected region into a square, preserving Finder assignments and logical module edits. |
| Remove perspective correction | Removes perspective correction while retaining the rotation angle. |
| Save the sampling image | Saves the image used for sampling, rather than the recovered QR image. |

Reassigning a Finder aligns the grid to all assigned Finder centers, even with LM disabled; corners without Finder assignments remain fixed. Image rotation and perspective operations retain the LM and curved-surface correction settings and recalculate their results. While LM correction is enabled, assigning a Finder again recalculates the correction. Changing corners or orientation disables it; enable it again if needed. Editing module values or fixed designations keeps the correction active. It can be combined with curved-surface correction, and saved work restores the selected correction.

## 4. Edit modules

In **Edit cells**, select a module and assign a value.

| Value | Meaning |
| --- | --- |
| `0` | White under normal polarity. |
| `1` | Black under normal polarity. |
| `?` | Unknown. Use where damage prevents a reliable reading. |
| `*` | Unknown, with priority trials of both 0 and 1 during recovery. |

With **Select a rectangular range**, drag to select an area, then assign a value. Draw with the white, black, or unknown brush to set values. Unknown stamps mark a 3 × 3, 5 × 5, or 7 × 7 area around the tapped position as unknown. Return to individual selection with **1 cell**. Arrow controls select a neighboring module; **Undo / Redo** reverses or reapplies edits.

| Edit type | Effect |
| --- | --- |
| **From image** | Removes the manual override and uses the image reading. |
| **Tentative (correctable)** | Uses your value but permits correction during recovery. |
| **Fixed (no correction)** | By default, excludes candidates that conflict with the specified 0 or 1. Use for values you are sure of. |

`?` and `*` cannot be fixed. **From image** removes the selected modules' manual overrides. Enable highlighting of specified modules to review edits.

After confirming orientation, you can assign `*` to data and error correction code (ECC) modules. For detailed analysis, the default limit is four; **Change priority limit** opens **Priority search cell limit**, which allows 4–14. Four modules give 16 combinations; 14 give 16,384, so use priority marks selectively. If you lower the limit below the number marked, remove excess marks before recovery.

TXT import has no `*` count limit when Quick automatic repair is enabled, although time and memory limits still apply during automatic repair. With Quick automatic repair disabled, import allows up to 14 marks. Recovery in detailed analysis enforces the selected count limit, including marks imported from TXT.

Enable **Codewords** to distinguish Data in blue, ECC in orange, and unclassified words in purple. Zoom in to see eight-bit codeword boundaries, then zoom farther to see their order in the QR code. Selecting a cell shows its block, index within Data/ECC, and bit position.

Open **Data** in the cell editor to read current module data in a dedicated large view. Unmasking and partial-information switches update the display automatically. Use **Close** to return to editing; reopening after edits or Undo / Redo shows the current data.

## 5. Recover and inspect results

Select **Repair**. To interrupt, use the stop control and follow the displayed instructions. Some environments retain candidates found so far, but forced termination does not save unfinished candidates from that run. Edits and the previous result remain available.

Select a candidate in **Repair result**. **Candidates** supports complete-text search and pages of 20 candidates. At most 256 candidates are displayed; this may differ from the total found during the search.

**Candidate validation** shows the number of known bits compared and the number of mismatches separately for **format information** and **data/ECC**. The latter includes both data and error correction bits. Comparisons use the known `0`/`1` values at repair start, including image readings and manual edits; unknown bits are excluded. If a region has no known bits to compare, the app states that agreement cannot be checked.

Decoded content remains visible while details are open. **Recovery evidence**, **Data**, and **Search diagnostics** each expand from their heading. Use **Adjust image and grid**, **Edit modules**, or **Review recovery settings** to return to the relevant settings before running recovery again.

The content panel also shows the content type and available fields, a recovery summary, and any notices about character encoding or disagreements with manual edits. **Copy content** copies the complete decoded text only, without these explanations. Detailed recovery methods appear in **Recovery evidence**.

- **QR code content**: Read the complete decoded text, then use **Copy content** or **Save PNG**. PNG (Portable Network Graphics) is an image file format.
- **QR preview → View**: Switch between before, after, and side-by-side views. To compare different candidates, select the comparison candidate in **QR code content**.
- **Recovery evidence**: Tap a module to inspect why it was filled or corrected. **Colors and symbols**, directly below the result preview, explains module values and the border colors used for repair reasons. It is initially expanded and can be collapsed from its heading.
- **QR preview → Show same codeword**: Highlights modules belonging to the selected module's codeword, a group of eight bits.
- **Search diagnostics**: Review the conditions tried and reasons recovery failed. Candidate assessment scores are not probabilities of correctness. Distinguish the selected candidate’s trial completion from the aggregate search record: for example, its trial may have completed while another condition timed out.

**Data** shows the data and ECC codewords as hexadecimal (HEX) values and binary bit sequences when **Partial info** is off. Unknown bits appear as `?`. Select the display stage and use **Unmask** and **Partial info** to change the interpretation. The display updates when opened or when a setting changes. It defaults to **After constraints/RS / Unmask ON / Partial info OFF** after success, or **Before constraints/RS / Unmask ON / Partial info ON** after failure. Navigation and language changes preserve your choices for the same result; a new recovery result restores the defaults. Partial information is unvalidated diagnostic data.

In detailed analysis, list decoding searches beyond ordinary error correction and has a cumulative limit of ten minutes per repair. Ordinary search time is additional, so the whole repair may take longer.

**Unmask** changes how data is shown. Exported recovery PNG images retain the normal QR mask.

If there are no candidates, review version, orientation, corners, and incorrect fixed values. **Data** is also available before recovery. Partial information is not a validated decoding result.

Opening **Analyze additional segments after terminator** examines and displays information after the ordinary end of the data. Check the selected analysis source. For raw data, choose the position immediately after the terminator, the start of the next byte, or a specified bit position. Treat these interpretations separately from validated recovery candidates.

## 6. Choose detailed settings

Open **Detailed recovery settings**, between **Additional image adjustments** and **Repair**, to change conditions. Review the ECC level, mask, and other conditions inside this section. Within a workspace, open sections and scroll positions are retained when switching views.

Start with the defaults and change only what you need.

| Setting | Purpose and limits |
| --- | --- |
| **ECC level / Mask** | Specify these if known; otherwise leave them automatic. |
| **Allow recovery candidates that conflict with fixed 0/1 values** | Allows corrections to fixed modules, as with tentative values. Disabled initially. |
| **Format-information error limit** | Limits disagreements with the information specifying the ECC level and mask. |
| **Known data/ECC bit error limit** | Limits disagreements with observed data and error correction modules. |
| **Partial-bit search / dimension limit** | Limits the combinations of unknown bits searched. Defaults to 12; increasing to 13 or 14 increases processing work. |
| **Known-bit error search** | Additionally searches for errors in up to one or two known bits. Defaults to OFF. |
| **Extra errors beyond conventional RS** | Extends the normal Reed–Solomon (RS) error correction search. Defaults to +1 codeword (symbol). Raising the limit does not guarantee more candidates. |
| **Assume UTF-8 text in Byte mode** | Narrows byte segments without an explicit encoding to the UTF-8 character encoding. Leave disabled if other encodings or non-text data are possible. |
| **Force single-segment terminator and padding** | Use for a single data section (segment) of numeric, alphanumeric, byte, or Kanji data, where its type and length are known or can be trial-assigned. Do not use for multiple segments. |

Error-limit choices are **Default, Exact, Strict, Relaxed, Manual, and Unlimited**. Exact permits no disagreements with observations. Relaxing limits may increase time and candidate count.

If offered a retry with a single-segment assumption, the one-time retry applies only to that run. Unlike enabling the setting, it is cleared for the next ordinary recovery.

## 7. Save, resume, and export

**Menu → Exit** saves your work before attempting to close the app. If your device or browser does not allow this, follow the message to return to your device's home screen or close the tab. Select **Return to app** to continue working. If saving fails, stay in the app and use **Menu → Current workspace → Export backup** to save a file.

Use **Menu → Current workspace** for checkpoint saving, restoration, history, and workspace file exports. PNG export remains beside the decoded content.

Changes are saved automatically in this browser. Use **Menu → Open a saved workspace** to resume or delete work. Storage holds up to 20 workspaces with 40 history states each; older entries are removed when limits are exceeded. Select an earlier state in **Operation history** to return to it. Reopening an automatic result preserves the execution conditions used for that result.

**Save** records a checkpoint. After further edits, use **Restore saved checkpoint** to return to that state.

| Export | Contents and purpose |
| --- | --- |
| **Export backup** (`.qrpwa`) | Workspace, settings, history, and source image. Use for backups or transfers to another device. The import limit is 32 mebibytes (MiB, about 34 megabytes). |
| **Export Android-compatible QRR** (`.qrr`) | A file compatible with the storage format used by QRevy for Android. Does not include the source image. Reads format versions 23–42 and writes version 42. |
| **Save pre-repair TXT matrix** (`.txt`) | Edited input module values as plain text. Excludes the image, edit types, and history. |
| **Save PNG** (`.png`) | The selected candidate as a QR image. This format does not preserve a resumable workspace. |

The standard interface of QRevy for Android does not import or export QRR files.

If a QRR needs an image, use **Attach QRR source image**, select the corresponding image, and check corners and orientation. Take care not to attach a different image. Sharing and file-saving screens depend on the device and browser.

Workspaces do not synchronize between devices, browsers, or different site addresses. Transfer them by exporting and importing `.qrpwa` files. Browser data clearing or storage pressure can remove work, so export important work as a file too.

Photos and QR contents are processed on your device. Exported files and copied text are also subject to the destination's storage and sharing settings. See [Privacy](../PRIVACY.md).
