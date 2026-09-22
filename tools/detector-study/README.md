# Synthetic QR detector study

An opt-in Linux/JVM evaluation tool. It is an independent Gradle project: none of its
third-party dependencies are added to the Android app or shared runtime. It compiles
production Kotlin recovery, sampling, periodicity and threshold-estimation sources
unchanged. The two small JVM adapters implement only presentation language selection
and grayscale `Bitmap.getPixels` for the production threshold estimator.

## Reproduce

Requires Linux x86-64, JDK 17, C compiler, Git, GNU time and `uv`. Initial setup downloads
public packages and source code. No user images are accepted by the corpus generator.
Run from the repository root:

```sh
uv venv --python 3.12 build/detector-study/venv
uv pip install --python build/detector-study/venv/bin/python -r tools/detector-study/requirements.txt
build/detector-study/venv/bin/python tools/detector-study/run.py --work build/detector-study/work
build/detector-study/venv/bin/python -m unittest discover -s tools/detector-study -p 'test_*.py'
```

Raw detections, recovery outputs, per-process peak RSS, synthetic PNGs and their manifest
remain in the requested work directory. `report/` contains CSV/JSON summaries and
per-case metrics. `fixed-report/` repeats recovery with fixed black/white thresholds
96/160; detector outputs are reused. Checked-in measurements and interpretation are linked from
`docs/DETECTOR_COMPARISON.ja.md`.

## Protocol

- 240 positive images: versions 1/3/7, four synthetic ASCII payloads/orientations per
  version, 20 conditions; plus 24 blank/noise/checkerboard/text negatives. These are
  correlated variants of 12 QR contents, not 240 independent real photographs.
- All inputs are identical lossless grayscale PNGs, normally 512x512. The no-quiet-zone
  condition is cropped to the symbol. Only Model 2 and normal polarity are tested.
- Each detector is warmed on a clean image ten times, then run three times per image.
  The per-image median excludes image-file I/O. Calls run serially in the requested order.
  OpenCV uses one thread. JVM and Python/API overhead are included. BoofCV and ZXing-C++
  public calls also attempt decoding; the other calls only detect/extract. This is an
  integration-cost comparison, not an equal-instruction-count algorithm benchmark.
- The baseline detector is the app's actual `GridPeriodicityEstimator`. The existing
  `StandardQrDecoder` is additionally run to distinguish new benefit from codes the app
  already reads conventionally. This is not a simulation of the interactive UI or manual edits.
- Localization is RMS displacement of a 9x9 lattice across the quadrilateral, minimized
  over its four cyclic rotations, divided by the ground-truth average module pitch.
  At most 0.5 modules is counted as correctly localized. A usable grid also requires the
  correct dimension in the top-four proposals of the common downstream ranker.
- Detectors never receive ground-truth payloads, dimensions or corners. Missing dimensions
  are estimated by searching versions 1–40 with four logical rotations. A common, fixed
  ranker weights Finder mismatch 0.55 and timing mismatch 0.45. It retains four proposals.
  Ground truth is read only for evaluation and the explicitly labelled oracle control.
- Each proposal is sampled by the production `MatrixSampler`, using the production
  import-time `InitialThresholdEstimator` (fallback 96/160). No manual constraints,
  corner-neighborhood search, polarity search or per-detector parameter tuning is added.
- The production `RecoveryEngine` receives its default options and a deterministic
  limit of 10,000 cancellation checks per proposal. A budget exit is recorded separately.
  Up to four proposals are attempted; the first with a parsed candidate is selected,
  using the production candidate comparator within that proposal. This is a study policy,
  not an assertion that a parsed payload is correct or should be accepted by the app.
- Success requires the selected complete text to equal the generated text exactly.
  A different parsed string counts as wrong; RS success or plausible text is not success.
- The oracle uses the true geometry, with the *same* ranker/sampler/recovery budget.
  It diagnoses downstream limitations rather than representing an attainable detector.
- Union/additional counts describe complementary coverage using known ground truth.
  We also evaluate a naive cascade: conventional decode, periodicity recovery, then
  each added detector, stopping at the first parsed text without consulting truth.
  Its observed correctness is reported separately; it is not proof of safe selection.
  A merged candidate ranker would need a separate test before integration.

## Versions and licenses

Java ZXing 3.5.4 and BoofCV 1.1.4 (Apache-2.0); Python dependencies are pinned in
`requirements.txt`; quirc is pinned to commit `927d680904dc95fdff4cd9d022eb374b438ff8f2`
(ISC). Gradle transitive versions are in `gradle.lockfile`. Third-party implementations
and generated images are not vendored in this directory. See `DEPENDENCIES.md` for the
study-only dependency/license review. APK/iOS distribution size and on-device runtime
are not measured by this harness.

## Opt-in external-image inspection

For a separately supplied image, build the JVM tool and invoke `inspect_image.py --image`
with its path at runtime. It reads the full-resolution image, runs desktop ZXing-C++
Model 2 detection and the production shared initial-grid estimator, and prints geometry
metadata only. This is not an Android JNI or payload-recovery test. Unlike the synthetic
corpus runner, it never writes image data into the repository: grayscale conversion is
held in a temporary directory under `/tmp` and deleted before returning. Do not save
user images or their derivatives as regression fixtures; use independently generated
synthetic structures instead.


## Synthetic data/ECC error-limit study

`ErrorLimitStudyKt` compares positive bit-mismatch limits by calling the unchanged
production `RecoveryEngine` separately for each fixture and policy. It accepts no
images. `error_limit_summary.py` reports exact raw-codeword recovery, other decoded
payloads, censoring, and runtime, with breakdowns by family, version, ECC, and seed.
The percentage is of all data/ECC bits, not of observed bits or RS error-correction
capacity. `one-bit` is an explicitly labelled constant-budget control.

```sh
./gradlew -p tools/detector-study installDist --offline
java -Xmx2g -cp 'tools/detector-study/build/install/qr-detector-study/lib/*' \
  study.ErrorLimitStudyKt --output build/error-limit-study/example.jsonl \
  --versions 1,2,3,5,7,10,15,20,30,40 --seeds 307,419 \
  --sparse-max-version 40 --limits unlimited,one-bit,0.05,0.075,0.1,0.5,0.875,1
python3 tools/detector-study/error_limit_summary.py \
  build/error-limit-study/example.jsonl build/error-limit-study/example-report
python3 -m unittest discover -s tools/detector-study -p 'test_error_limit_summary.py'
```

Add `--randomized true` to use continuous randomized damage severities instead of
fixed severities. Default `--sparse-max-version 5` restricts the expensive sparse,
unknown-format and noise profiles; final confirmation used 40. Defaults are 200,000
cooperative cancellation checks and a 3,000 ms wall budget per recovery invocation.
`--ids FILE` replays selected newline-delimited fixture IDs. The summary tool also
reads compressed `runs.jsonl.gz` files directly. It excludes a censored fixture
from every policy in a paired comparison; a cancelled invocation is never counted
as a successful rejection of wrong payloads. Search diagnostics and format-attempt
counts are available only for completed invocations; cancelled rows retain their
stop reason, elapsed time, and cancellation-check count.

See the maintained [preset rationale](../../docs/RECOVERY_STRATEGY.en.md#45-error-limit-preset-rationale) and its [Japanese counterpart](../../docs/RECOVERY_STRATEGY.ja.md#45-誤り上限プリセットの採用理由). Detailed reports and measured datasets are local research artifacts and are not required to run the tools. The tool does not set the application's initial error limit.

To compare the Relaxed preset, include Version 4 and use `--limits 3,5,10,unlimited` with the same fixtures and seeds for each policy. The independent format-information enumeration is available with:

```sh
python3 tools/detector-study/format_limit_study.py
```
