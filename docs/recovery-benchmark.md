# Synthetic recovery benchmark

This opt-in desktop JVM benchmark measures the current recovery policy on generated QR inputs. It uses the existing example harness and dependencies. Production recovery code and Android settings are unchanged. No external images or private payloads are used.

## Run

From the repository root, with the existing JDK 17 and Gradle dependencies installed:

```bash
./gradlew -p scripts/example-harness recoveryBenchmark -PbenchmarkArgs=--self-test --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/recovery-baseline --samples 20 --warmups 5 --seeds 3' --offline
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/recovery-baseline
```

Output paths are relative to `scripts/example-harness`. Choose a new directory for each run; existing results are never overwritten by the benchmark. The summarizer regenerates `summary.json` and `summary.md`. Build output is ignored by Git. Run timing measurements without concurrent builds or other heavy work.

Optional paired arguments are `--case NAME`, `--checks N` (default 100000), and `--guard-ms N` (default 10000). The Gradle property splits arguments at spaces, so choose output paths without spaces. `--seeds` selects integer seeds starting at zero, using `java.util.Random`; mask is seed modulo eight. The default run contains 39 different inputs and 780 measured invocations, after five warmups per input. Repetitions are timing samples, not independent recovery trials. Small synthetic samples do not establish a real-world recovery rate.

## Inputs and policy

Scenarios cover clean input, seven complete byte erasures, twenty dispersed partial-byte erasures, three/four/five erroneous parity symbols in version 1-L, mixed erasures and errors, a local hole, version 3-Q with errors in both blocks, two compatible valid QR witnesses, a conflicting FIXED bit, zero working-memory allowance, and cancellation after 1000 checks. Seeds vary payload, mask, and selected damage positions where applicable. The ambiguous case erases the differences between independently encoded messages; both known valid witnesses are checked against the surviving observations. Witnesses are not an exhaustive oracle for every possible payload.

The generated version/ECC/mask are fixed for recovery. Partial-bit limit is 12, list extension is one additional error, working memory is 32 MiB except for the intentional memory-stop case, and normal retention defaults apply. Exact-observation cases explicitly set a zero mismatch allowance. This isolates decoding and does not measure format or orientation search.

List-decoding clocks are frozen using the existing injectable clock, including the early list slice. Search is bounded by cancellation-check count, existing internal node/retention limits, and an emergency wall guard. This is a reproducible benchmark policy, not production time scheduling. Check counts are not machine instructions and may change when the implementation changes. Wall-guard hits must be reported as such and excluded from deterministic equivalence claims. The cancellation probe measures return latency after the engine observes a request, not asynchronous UI-to-worker delivery latency.

The raw records include per-block lengths, ECC, erasures, known bit errors, exact RS-space dimension, and the error region relative to conventional decoding and the punctured Johnson radius. Partial bytes count as erased symbols for that comparison. This classification describes the hard-decision problem; partial-bit and payload constraints provide additional information. A null exact-space dimension means inconsistent known-bit constraints, not proof that error correction is impossible.

## Measurements and interpretation

- `runs.jsonl`: complete retained codewords, text, repair and alternative-derivation evidence; ground truth and known witnesses; candidate and evidence hashes; search diagnostics, omissions, stop causes and cancellation latency.
- `environment.json`: source revision, dirty state, source fingerprint, JVM/OS, processor count, heap cap and run settings. The fingerprint covers common production sources, Android core and harness sources; it is not a substitute for preserving the source tree and locked build configuration.
- `summary.md` / `summary.json`: median and nearest-rank P95 time, candidate counts, original recovery, wrong singleton relative to the generated original, completion, repeat stability, GC and memory measurements. Intentional FIXED conflicts are excluded from eligible-original rates.

Original recovery compares all interleaved codewords, not text alone. Other candidates may be structurally valid alternatives; their presence alone is not a false recovery. A wrong singleton means one retained candidate differing from the generated original, not that the app declared mathematical uniqueness. Inspect candidate/derivation omission and completion flags as well. COMPLETE means the configured search policy completed, not exhaustive search of all mathematically possible solutions.

Memory is reported as the sum of per-pool JVM heap peaks, including already-live objects. Peaks can occur at different times; this is neither simultaneous peak heap nor process RSS. Decoder-thread allocated bytes are also reported when supported; they measure allocation traffic rather than retained memory. Fixture generation, image probing, and JSON serialization are outside decoder timing and allocation counters. No forced GC is performed. JIT, GC, and case order affect timings; use repeated fresh JVM runs before drawing optimization conclusions.

Image work is separate: a six-pixel module raster with quiet zone is processed by the existing ZXing Java boundary adapter, grid validation, and sampling with known geometry. Sampled values are checked but never replace the matrix's original constraints. This is not Android JNI, camera decoding, a geometry robustness study, device power measurement, or end-to-end recovery. Time to first correct candidate is unavailable from the current return-only API; decoder time measures invocation completion.

## Compare and validate

```bash
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/new-baseline --baseline scripts/example-harness/build/recovery-baseline
python3 -B -m unittest discover -s scripts -p test_recovery_benchmark_summary.py
python3 -B scripts/check-changes.py --dry-run
python3 -B scripts/check-changes.py
```

Comparisons match scenario, seed, input hash and resource settings, then report candidate/evidence/completion changes. Unmatched cases remain visible. Matching hashes show retained evidence agreement, not proof of global completeness. Time- or memory-limited runs must be reviewed separately from completed runs. Benchmark self-tests check deterministic fixtures, ambiguous witnesses, raw-codeword recovery, FIXED protection, memory stops and cancellation. The repository's full validation is also required for harness/check changes.

## Detailed decoder profiling

The JDK 17 Flight Recorder option records Java execution samples (requested period 1 ms) and weighted allocation samples (requested throttle 1000/s). It needs no extra dependency or production instrumentation. Run a warmed control and two separate profiled JVMs for the mixed-damage inputs:

```bash
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-control --case mixed --samples 300 --warmups 50 --seeds 3' --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-profile --case mixed --samples 300 --warmups 50 --seeds 3 --profile true' --offline
./gradlew -p scripts/example-harness recoveryBenchmark '-PbenchmarkArgs=--output build/mixed-profile-repeat --case mixed --samples 300 --warmups 50 --seeds 3 --profile true' --offline
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-control
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-profile --baseline scripts/example-harness/build/mixed-control
python3 -B scripts/summarize-recovery-benchmark.py scripts/example-harness/build/mixed-profile-repeat --baseline scripts/example-harness/build/mixed-control
```

`profile.jfr` preserves the recording. `profile.json` contains total and per-input attribution; the summarizer also writes `profile.md`. Custom invocation events mark only measured decoder calls. Samples must match both the invocation's thread and its half-open time interval. Warmup, fixture analysis, image processing, JSON serialization, other threads and JIT/compiler work outside those windows are excluded. Excluded event counts and missing/truncated stacks remain visible. Profiling self-tests check boundary handling, recursive-frame deduplication and attribution totals.

Execution percentages are sampled Java execution shares, not exact wall-clock fractions or method call counts. A nearest-core-method row includes library work below that frame and is mutually exclusive with other nearest rows. Inclusive rows count every core method on the stack once per sample, so their percentages overlap and must not be added. Inlining can attribute arithmetic to a shared field helper; use inclusive methods and caller paths to identify its calling algorithm. Low or zero sample counts cannot prove that a method costs nothing.

Allocation weights estimate allocation pressure, not exact allocated or retained bytes. A sample's weight can cover allocations since an earlier sample outside its decoder interval; window filtering therefore does not provide exact per-invocation byte accounting. Cross-check this estimate with the benchmark's decoder-thread allocation counter. GC, safepoints, JIT and recording overhead can change sample distributions and elapsed time. Compare retained candidates, derivations and full search diagnostics against the control before interpreting the profile. Use the unprofiled control for latency, and do not present a more thoroughly warmed run as a production-code speedup.

These small mixed cases identify optimization targets, not universal hotspots across all QR versions or damage patterns. Repeated profiles should support the same broad ranking before selecting a change. Finding an expensive fallback is not authorization to remove it: preserve supported candidate sets, constraints and provenance in any later optimization.
