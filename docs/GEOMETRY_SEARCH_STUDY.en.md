# Synthetic comparison of geometry-neighborhood search

English | [Japanese](GEOMETRY_SEARCH_STUDY.ja.md)

## Purpose

This study compares the search widths and transformation types used by **Automatically fine-tune sampling position**. Synthetic QR images retain distinguishable pixels only near module centers, and each case measures whether any geometry candidate can recover the original text. The results are deterministic regression comparisons between search candidates, not guarantees from the QR specification or statistics for real photographs.

## Related implementation and reproduction

- Geometry-candidate generation: [`FixedGeometryRecovery.kt`](../androidApp/src/main/java/io/github/earthdiver/qrrepair/core/FixedGeometryRecovery.kt)
- Displacement and single-width regression: [`GeometryNeighborhoodDisplacementRegressionTest.kt`](../androidApp/src/test/java/io/github/earthdiver/qrrepair/core/GeometryNeighborhoodDisplacementRegressionTest.kt)
- Per-transformation and rotation regression: [`GeometryNeighborhoodExpandedRegressionTest.kt`](../androidApp/src/test/java/io/github/earthdiver/qrrepair/core/GeometryNeighborhoodExpandedRegressionTest.kt)
- Candidate-count and pivot-rotation unit checks: [`AutoRecoveryOrderingTest.kt`](../androidApp/src/test/java/io/github/earthdiver/qrrepair/core/AutoRecoveryOrderingTest.kt)

```bash
./gradlew :androidApp:testDebugUnitTest \
  --tests io.github.earthdiver.qrrepair.core.GeometryNeighborhoodDisplacementRegressionTest \
  --tests io.github.earthdiver.qrrepair.core.GeometryNeighborhoodExpandedRegressionTest \
  --tests io.github.earthdiver.qrrepair.core.AutoRecoveryOrderingTest
```

The tables record comparison values used when the design decision was made with synthetic fixtures. Current tests detect regressions caused by code changes but do not freeze every historical number. An update to the measured results should also identify the tested commit, fixtures, success criterion, and any changed search limits.

## Ordinary-geometry conditions

- QR symbols: version 1 / ECC H and version 7 / ECC Q
- Injected widths: 0.3, 0.4, 0.5, 0.6, 0.7, and 0.8 module
- Injected transformations: four axial translations, four diagonal translations, two scale changes, and eight single-corner movements
- Total: `2 × 6 × 18 = 216` cases
- Axial, diagonal, and scale transformations use the same representative displacement magnitude
- Success criterion: at least one candidate decodes to the original text

## One search width

| Search width | Successes | Success rate |
| ---: | ---: | ---: |
| 0.3 | 174/216 | 80.6% |
| 0.4 | 192/216 | 88.9% |
| 0.5 | 208/216 | 96.3% |
| 0.6 | 212/216 | 98.1% |
| 0.7 | 204/216 | 94.4% |
| 0.8 | 198/216 | 91.7% |

Under these conditions, 0.6 was the best single width.

## Transformation types

Across the union of all search widths, the following table counts cases additionally recovered by each transformation type after the baseline candidate failed.

| Transformation | Additional successful cases | Candidates per width |
| --- | ---: | ---: |
| Axial translation | 50 | 4 |
| Diagonal translation | 56 | 4 |
| Scale | 18 | 2 |
| Single-corner movement | 26 | 8 |

Diagonal translation was most effective both in total and per candidate. Scale recovered fewer additional cases, but because it uses only two candidates, it was more efficient per candidate than single-corner movement. The sets overlap, so their counts cannot be added together.

## Two search widths

Several pairs, including 0.3 + 0.6, 0.4 + 0.6, and 0.5 + 0.6, reached 216/216. The study therefore has no unique optimum. The 0.3 + 0.6 pair is the representative regression condition because 0.3 is the most distant complementary point from the default 0.6; this does not imply that the pair is optimal for real images.

## Rotation around one assigned Finder role

The test fixes the logical corner corresponding to an assigned Finder pattern and evaluates clockwise and counterclockwise candidates whose farthest outer corner moves by the search width. This gives 72 cases: 2 QR symbols × 6 widths × 3 Finder roles × 2 directions.

In the initial comparison, the existing 19 baseline candidates recovered 70/72 cases at width 0.6, 63/72 at 0.7, and 62/72 at 0.8. Adding two dedicated rotation candidates reached 72/72 at every width. At the default width of 0.6, this was an improvement of two cases, or 2.8 percentage points. Based on that result, the app adds two rotation baselines only when 0.6-width neighborhood search is enabled and exactly one Finder role is assigned. The baseline count then grows from 19 to 21. The current app can subsequently add at most one alignment-pattern-refined candidate for each baseline supported by image evidence, producing at most 42 geometry attempts.

After the partial-bit RS route was added, the current synthetic regression recovers 72/72 width-0.6 cases with the ordinary 19 candidates alone. Current tests ensure that the dedicated rotations do not worsen that result and still improve on the ordinary candidates for at least one tested width. The initial measurements remain as the rationale for adding the candidates, not as current absolute performance figures.

## Candidate exclusion for manual input values

This comparison toggles **Exclude recovery candidates that conflict with manual 0/1 values**.

It covers 72 cases: 2 QR symbols × 12 data positions × 0, 2, or 4 erased codewords. Correct and incorrect manual values are tested separately.

| Manual value | Setting ON | Setting OFF |
| --- | ---: | ---: |
| Correct | 72/72 | 72/72 |
| Incorrect | 0/72 | 72/72 |

With the setting ON, every explicit 0/1 must be preserved. A correct pin caused no loss in these cases, but even one incorrect bit excluded every correct RS recovery. With the setting OFF, manual 0/1 values are treated as ordinary known bits derived from the image, so RS could correct the tested errors. The UI must therefore describe ON as rejecting every conflicting candidate, not merely as preferring the user's input.
