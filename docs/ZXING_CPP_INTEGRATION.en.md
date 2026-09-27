# Initial grid estimation and automatic Finder assignment

English | [Japanese](ZXING_CPP_INTEGRATION.ja.md)

This document records the current Android behavior, validation conditions, and known limits of initial QR grid estimation. It covers ZXing-C++ boundary detection, the grid-period fallback, the two-Finder fallback, and automatic Finder-role assignment. These stages provide editable starting values; none of them proves that recovery is correct.

## Processing boundary

- Android calls ZXing-C++ 3.1.1 through JNI on the existing worker thread.
- Boundary-to-module conversion and candidate validation are implemented in shared code.
- Only QR Code Model 2 versions 1–40 are supported.
- Native decoded text is not used as a recovery result.
- An iOS native detector adapter is not yet implemented.

## Estimation order

For a newly imported image, the app tries these routes in order:

1. Validate boundaries returned by ZXing-C++.
2. Estimate a rectangular module grid from image periodicity.
3. If both earlier routes fail, try geometry derived from two individually detected Finders.
4. If no route succeeds, show version 1 and default corners for manual adjustment.

The selected route and initial version are recorded in import history. Recovery treats the displayed corners and selected version as user-confirmed inputs, so they must still be reviewed.

## Normal and inverted images

Each boundary is checked with normal and inverted luminance. If both interpretations pass, that boundary is ambiguous and is not accepted. Periodicity and the two-Finder fallback also check both interpretations. If both produce estimates, their versions, corner ordering, and corresponding corners must agree within 0.25 module; contradictory confirmed polarities are rejected. Conflicting periodicity estimates stop automatic estimation rather than falling through to the two-Finder route. Periodicity alone does not confirm polarity. A native or two-Finder result records polarity only when Finder roles are verified.

## ZXing-C++ boundary validation

Each single native boundary is tested against versions 1–40 and four logical rotations. Coordinates must be finite and inside the image, and the quadrilateral must be convex. Finder-pattern and timing-pattern mismatch rates must each be at most 20%. Candidate ranking weights these scores 0.55 and 0.45; a gap below 0.03 from the runner-up is treated as ambiguous.

Multiple boundaries are validated separately. They are merged only when every pair agrees on version and logical orientation and each corresponding corner lies within 0.25 module. The tolerance uses the smaller local module width of the pair. The representative prefers a boundary that passes automatic-role validation, then minimizes total corner distance. Coordinates are never averaged, and duplicate detections do not increase confidence.

An unvalidated boundary, a different version or orientation, or a spatially distinct QR prevents merging. When native boundaries cannot be accepted or merged, or JNI loading or execution fails, the app continues to the grid-period fallback.

## Grid-period fallback

The periodicity estimator groups luminance gradients into two orthogonal edge families. It searches projected edges for a phase and module pitch aligned to an evenly spaced boundary grid. When a half-period or another harmonic has similar support, the fundamental period is preferred.

An initial version is selected from `dimension = 17 + 4 × version` only when both axis pitches agree, the extent supported by definite-black pixels is approximately square, and it matches one of the permitted dimensions from 21 through 177. Large gray damaged regions and isolated dark background pixels are excluded from the definite-black boundary evidence.

Periodicity estimates do not automatically assign Finder roles.

## Two-Finder fallback

If periodicity also fails, individually detected Finders may provide provisional geometry for version 2 and later symbols that are approximately described by a similarity transform. Finder pitch, axes, patterns, and white separators are checked. This route is not used when ZXing-C++ returned multiple native boundaries.

For a diagonal Finder pair, the horizontal and vertical timing patterns may establish the observed top-right and bottom-left roles without an alignment pattern. Each timing axis requires:

- at least eight known cells covering at least 60% of its length;
- at least three black and three white observations;
- no more than 5% mismatches;
- the same conditions at the center and at offsets of 0.2 module in four axial directions;
- white-separator validation; and
- a score gap of at least 0.12 from competing hypotheses.

Unknown cells do not count as matches. If timing evidence cannot establish roles, a verified lower-right alignment pattern may still provide provisional corners without assigning roles. Version 1 has too few timing cells for this route. A hidden Finder is never treated as observed, and strong perspective distortion remains unsupported.

## Automatic Finder-role assignment

A grid accepted from ZXing-C++ may assign the top-left, top-right, and bottom-left Finder centers and logical roles only when all of these conditions pass:

- at most 2 mismatches among 49 cells for each Finder;
- at most 1 mismatch among 15 white-separator cells for each Finder;
- at most 5% timing-pattern mismatch;
- a score gap of at least 0.08 from alternatives, including rejected alternatives;
- at least two pixels per module along every edge; and
- at most 2 Finder mismatches at samples shifted by 0.25 module in each axial direction.

These are conservative structural heuristics, not probabilities. Automatic assignment runs only during new-image import. It is recorded in history and is not repeated when saved work is reopened. Clearing every assigned Finder also clears logical orientation while retaining corners and cell edits.

The two-Finder route uses its separate timing conditions above. A grid-period estimate, a weaker native grid, or unmergeable native boundaries do not assign roles automatically.

## Stored validation evidence

The stored synthetic study contains 240 positive and 24 negative cases. A grid counts as correct when its dimension matches and every corner is within 0.5 module.

- 224 of 240 positive cases produced a correct grid.
- 164 correct grids came from ZXing-C++ and 60 from periodicity estimation.
- All 36 cases missing one, two, or three Finders were retained by periodicity estimation.
- Three negative cases produced pre-existing periodicity candidates; a candidate is not a successful decode.
- Periodicity estimation alone produced 140 correct grids among the 240 positive cases.
- The automatic three-Finder gate assigned 128 cases; all 128 matched the expected roles and positions under the study criterion, and no negative case was assigned.

These measurements are regression evidence for the stored synthetic corpus, not an independent performance guarantee. The surrounding-sample gate was selected after observing errors from a center-only gate. The stored-coordinate study does not measure Android JNI runtime behavior, speed, real-photo error rates, or recovered-text accuracy.

- [Initial-grid summary](detector-study/2026-09-18/initial-grid-summary.json)
- [Initial-grid coordinates](detector-study/2026-09-18/initial-grid.jsonl)
- [Finder-assignment summary](detector-study/2026-09-18/finder-assignment-summary.json)
- [Finder-assignment coordinates](detector-study/2026-09-18/finder-assignment.jsonl)

Shared tests use independently rendered synthetic grids for perspective changes, versions 1, 3, and 7, rotations, corner ordering, missing Finders, invalid boundaries, multiple candidates, and uniform images. Android JVM tests verify that JNI loading failure does not abort image import. Regression fixtures derived from user-attached images are not stored.

## Reproduction

After preparing `build/detector-study/work` as described in the [detector-study instructions](../tools/detector-study/README.md), run:

```sh
./gradlew -p tools/detector-study installDist
tools/detector-study/build/install/qr-detector-study/bin/qr-detector-study initial-grid \
  build/detector-study/work/corpus build/detector-study/work/results/zxing_cpp.jsonl \
  build/detector-study/work/initial-grid.jsonl
build/detector-study/venv/bin/python tools/detector-study/evaluate_initial.py \
  build/detector-study/work/corpus build/detector-study/work/initial-grid.jsonl \
  build/detector-study/work/initial-grid-summary.json
```

Measured results should identify the tested commit, fixtures, success criterion, and relevant limits rather than being presented as timeless product performance.

## Dependency and distribution notes

The Android app pins `io.github.zxing-cpp:android:3.1.1` from Maven Central. The AAR contains native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` and supplies JNI consumer rules. License notices cover ZXing-C++ under Apache-2.0, bundled `libzueci` under BSD-3-Clause, the UTF-8 conversion code, and the LLVM libc++ exception.

No Internet permission, external transmission, or signing configuration is introduced by this integration. Dependency hashes, ABI inspection, and transitive-dependency review are recorded in [native-dependency-review.json](detector-study/2026-09-18/native-dependency-review.json). Current build and publication checks, rather than historical test totals, are the release gate.
