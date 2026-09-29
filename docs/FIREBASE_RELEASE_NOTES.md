QR Repair — tester guide

Purpose
Recover damaged QR Code Model 2 symbols with manual assistance. Recovery does not guarantee correctness; independently verify important results.

Basic operation
1. Import an image or capture a QR code with the camera.
2. Rotate/crop as needed and review the estimated QR region.
3. Adjust geometry and black/unknown/white thresholds when needed.
4. Correct cells using 0 / 1 / ? / * and run recovery.
5. Inspect decoded content and repair explanations before using the result.

Suggested checks
Try image import, camera import, cell edits, Undo/Redo, recovery, and session restoration using non-sensitive synthetic QR codes with known content. Compare the complete recovered text with the original.

Limitations
Android 8.0 (API 26) or newer is required. Supported symbols are QR Code Model 2, versions 1–40. Model 1, Micro QR and rMQR are unsupported. Processing is offline; recovered URLs do not open automatically.

Saved work
Imports automatically save work; at most 20 sessions are retained, and older work and its source image are removed when the limit is exceeded. Current sessions use QRR schema 25. Schemas 23 and 24 can also be opened with mirrored input disabled; schema 23 uses the default 12-bit partial-bit search limit. Detailed settings can treat an input image as mirrored, exchanging grid axes and Finder roles while preserving image positions and manual edits. Resume work from Saved work (Recent work in Classic).

Feedback
Report the app version, device and Android version, reproduction steps, expected result, and actual result through the contact route supplied with your test invitation. Avoid including sensitive images or recovered content.
