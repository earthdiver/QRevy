#!/usr/bin/env python3
"""Summarize the opt-in synthetic JVM recovery baseline without hiding resource stops."""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import statistics


def percentile(values, fraction):
    """Nearest-rank percentile; small samples are explicitly not population estimates."""
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def evidence_signature(result):
    # Empty candidate lists still carry meaningful rejection and resource-stop evidence.
    diagnostic = json.dumps(result.get('searchDiagnostic', {}), sort_keys=True, separators=(',', ':'))
    return (result['candidateSetHash'], result['evidenceHash'], result['completeness'], result['stopCause'], diagnostic)


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row['fixture']['scenario']].append(row)
    result = []
    for scenario, runs in sorted(groups.items()):
        measurements = [r['result'] for r in runs]
        by_fixture = defaultdict(list)
        for row in runs:
            by_fixture[(row['fixture']['seed'], row['fixture']['inputHash'])].append(row['result'])
        unstable = [seed for (seed, _), repeats in by_fixture.items()
                    if len({evidence_signature(r) for r in repeats}) > 1]
        eligible = [r for r in runs if r['fixture']['originalAllowedByFixedCells']]
        times = [r['decodeMs'] for r in measurements]
        cancellation = [r['cancellationReturnMs'] for r in measurements if r['cancellationReturnMs'] is not None]
        result.append({
            'scenario': scenario, 'runs': len(runs), 'distinctFixtures': len(by_fixture),
            'originalFoundRuns': sum(r['originalFound'] for r in measurements),
            'eligibleOriginalFoundRuns': sum(r['result']['originalFound'] for r in eligible),
            'eligibleRuns': len(eligible),
            'wrongSingletonRuns': sum(r['result']['wrongSingletonRelativeToOriginal'] for r in eligible),
            'completeRuns': sum(r['completeness'] == 'COMPLETE' for r in measurements),
            'completionCounts': dict(Counter(r['completeness'] for r in measurements)),
            'stopCounts': dict(Counter(r['stopCause'] or 'none' for r in measurements)),
            'diagnosticStopCounts': dict(Counter(reason for r in measurements for reason in r.get('searchDiagnostic', {}).get('incompleteReasons', []))),
            'medianDecodeMs': statistics.median(times), 'p95DecodeMs': percentile(times, .95),
            'maxHeapPoolPeakSumBytes': max(r['heapPoolPeakSumBytes'] for r in measurements),
            'gcCount': sum(r['gcCount'] for r in measurements),
            'gcMillis': sum(r['gcMillis'] for r in measurements),
            'maxCancellationReturnMs': max(cancellation, default=None),
            'candidateCounts': sorted({r['retainedCandidateCount'] for r in measurements}),
            'witnessCountsFound': sorted({r['knownValidWitnessesFound'] for r in measurements}),
            'unstableSeeds': unstable,
            'medianBoundaryDetectionMs': statistics.median(r['image']['boundaryDetectionMs'] for r in runs),
            'medianDecoderThreadAllocatedBytes': statistics.median(r['decoderThreadAllocatedBytes'] for r in measurements) if all(r['decoderThreadAllocatedBytes'] is not None for r in measurements) else None,
            'medianGridEstimateMs': statistics.median(r['image']['gridEstimateMs'] for r in runs),
            'medianSamplingMs': statistics.median(r['image']['knownGeometrySamplingMs'] for r in runs),
            'correctEstimatedVersionRuns': sum(r['image']['estimatedVersion'] == r['fixture']['version'] for r in runs),
            'maxSamplingDifferences': max(r['image']['sampledValueDifferences'] for r in runs),
        })
    return result


def compare(current, baseline):
    """Compare evidence and completion per matched input, independently of timing noise."""
    def index(rows):
        groups = defaultdict(set)
        for row in rows:
            f, r = row['fixture'], row['result']
            key = (f['scenario'], f['seed'], f['inputHash'], f['workingLimitBytes'], f['mismatchLimit'], r['checkLimit'])
            groups[key].add(evidence_signature(r))
        return groups
    now, before = index(current), index(baseline)
    return {'matchedInputs': len(now.keys() & before.keys()),
            'changedInputs': [list(k) for k in sorted(now.keys() & before.keys()) if now[k] != before[k]],
            'newInputs': [list(k) for k in sorted(now.keys() - before.keys())],
            'missingInputs': [list(k) for k in sorted(before.keys() - now.keys())]}


def read_runs(directory):
    return [json.loads(line) for line in (directory / 'runs.jsonl').read_text().splitlines() if line.strip()]


def profile_markdown(profile):
    total = profile['total']
    cpu, allocation = total['execution'], total['allocation']
    lines = ['# Decoder sample profile', '', profile['scope'] + '.', '',
             f"Measured invocations: {total['invocations']}; execution samples: {cpu['samples']}; allocation samples: {allocation['samples']}.",
             f"Truncated stacks: execution {cpu['truncatedStacks']}, allocation {allocation['truncatedStacks']}; missing stacks: execution {cpu['missingStacks']}, allocation {allocation['missingStacks']}.", '',
             profile['executionMeaning'] + '.', profile['allocationMeaning'] + '.',
             'Profiled elapsed times include recording overhead; use a separate unprofiled run for latency comparisons.', '',
             '| Input | Invocations | Execution samples | Allocation samples | Allocation weight MiB |',
             '| --- | ---: | ---: | ---: | ---: |']
    for name, group in profile['byFixture'].items():
        lines.append(f"| {name} | {group['invocations']} | {group['execution']['samples']} | "
                     f"{group['allocation']['samples']} | {group['allocation']['weight']/1024**2:.2f} |")
    lines += ['', '## Selected algorithm scopes', '',
              'Inclusive sample shares; helpers are already included in their caller. These rows are not an exhaustive partition.', '',
              '| Operation | Execution sample share | Allocation weight share |', '| --- | ---: | ---: |']
    for label, method in [
        ('Koetter-Vardy interpolation', 'KoetterVardyDecoder.interpolate'),
        ('Wu decoding', 'WuDecoder.decode'),
        ('Payload constraints', 'PayloadConstraintSolver.apply'),
        ('RS bit-space analysis', 'RsBitConstraintSolver.analyze'),
        ('RS syndrome calculation', 'ReedSolomonErasures.calcSyndromes'),
    ]:
        def share(counts):
            matches = [row for row in counts['inclusiveCoreMethods'] if row['name'] == method or
                       (method.endswith('.calcSyndromes') and row['name'].startswith(method + '$'))]
            if len(matches) != 1:
                return 'not sampled' if not matches else 'see raw methods'
            return f"{matches[0]['percent']:.2f}%"
        lines.append(f"| {label} | {share(cpu)} | {share(allocation)} |")
    for title, counts, table, explanation in [
        ('Execution: nearest core method', cpu, 'nearestCoreMethod', profile['nearestMeaning']),
        ('Execution: inclusive method', cpu, 'inclusiveCoreMethods', profile['inclusiveMeaning']),
        ('Allocation: nearest core method', allocation, 'nearestCoreMethod', profile['nearestMeaning']),
        ('Allocation: inclusive method', allocation, 'inclusiveCoreMethods', profile['inclusiveMeaning']),
        ('Allocation classes', allocation, 'allocationClasses', 'JFR weighted allocation samples'),
        ('Execution caller paths (leaf first)', cpu, 'coreCallerPaths', 'First eight core frames; library frames omitted'),
        ('Allocation caller paths (leaf first)', allocation, 'coreCallerPaths', 'First eight core frames; library frames omitted'),
    ]:
        lines += ['', '## ' + title, '', explanation + '.', '',
                  '| Method / class / path | Weight | Share |', '| --- | ---: | ---: |']
        for row in counts[table][:20]:
            label = row['name'].replace('|', r'\|')
            lines.append(f"| `{label}` | {row['weight']} | {row['percent']:.2f}% |")
        if counts['samples'] == 0:
            lines += ['', 'No samples captured; no hotspot inference is supported.']
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    rows = read_runs(args.directory)
    if not rows:
        parser.error('No measured runs found')
    summaries = summarize(rows)
    environment = json.loads((args.directory / 'environment.json').read_text())
    comparison = compare(rows, read_runs(args.baseline)) if args.baseline else None
    (args.directory / 'summary.json').write_text(json.dumps(
        {'environment': environment, 'scenarios': summaries, 'comparison': comparison}, indent=2) + '\n')
    lines = [
        '# Synthetic recovery baseline', '',
        f"Source revision: `{environment['sourceRevision']}`; dirty: `{environment['sourceDirty']}`.",
        f"JVM: {environment['javaVersion']} ({environment['vm']}); {environment['os']} {environment['arch']}.",
        f"Warmups per fixture: {environment['warmups']}; measured repetitions: {environment['samples']}.", '',
        'Desktop JVM measurements, not Android device latency. List time slices are frozen; the logical check budget is fixed.',
        'Repeated runs of one seed are timing samples, not independent recovery trials. COMPLETE refers only to the configured search policy.',
        'Heap figure is the maximum sum of per-pool JVM heap peaks, including pre-existing objects; it is not simultaneous peak heap, allocation volume, or RSS.', '',
        '| Scenario | Original found / eligible runs | Complete | Wrong singleton* | Median ms | P95 ms | Heap pool peaks MiB | Candidates | Median allocated MiB |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |',
    ]
    for s in summaries:
        lines.append(f"| {s['scenario']} | {s['eligibleOriginalFoundRuns']}/{s['eligibleRuns']} | "
                     f"{s['completeRuns']}/{s['runs']} | {s['wrongSingletonRuns']} | {s['medianDecodeMs']:.2f} | "
                     f"{s['p95DecodeMs']:.2f} | {s['maxHeapPoolPeakSumBytes']/1024**2:.1f} | {s['candidateCounts']} | "
                     + (f"{s['medianDecoderThreadAllocatedBytes']/1024**2:.2f}" if s['medianDecoderThreadAllocatedBytes'] is not None else 'unavailable') + ' |')
    lines += ['', '*Relative to the generated original, excluding intentional FIXED conflicts. A different valid payload is not necessarily malformed.', '',
              '| Scenario | Boundary detection median ms | Grid estimate median ms | Known-grid sampling median ms | Correct version | Max sampled value differences | Harness / solver stop causes | Repeat instability seeds |',
              '| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |']
    for s in summaries:
        lines.append(f"| {s['scenario']} | {s['medianBoundaryDetectionMs']:.2f} | {s['medianGridEstimateMs']:.2f} | {s['medianSamplingMs']:.2f} | "
                     f"{s['correctEstimatedVersionRuns']}/{s['runs']} | {s['maxSamplingDifferences']} | {s['stopCounts']} / {s['diagnosticStopCounts']} | {s['unstableSeeds']} |")
    lines += ['', 'Image probes use unrotated synthetic rasters and a ZXing Java boundary adapter (not Android JNI). Recovery receives the original matrix with its provenance and fixed format; this is not an end-to-end camera benchmark.',
              'Allocated bytes count the decoder thread only, when supported by the JVM; they are not retained memory.',
              'Time to first correct candidate is unavailable without a production observer. Reported decoder time is invocation completion.', '']
    for s in summaries:
        if s['maxCancellationReturnMs'] is not None:
            lines.append(f"- {s['scenario']}: maximum observed cancellation return latency {s['maxCancellationReturnMs']:.3f} ms.")
    if comparison is not None:
        lines += ['', 'Evidence comparison (changes require review; they are not automatically regressions):',
                  '```json', json.dumps(comparison, indent=2), '```']
    (args.directory / 'summary.md').write_text('\n'.join(lines) + '\n')
    profile_path = args.directory / 'profile.json'
    if profile_path.exists():
        (args.directory / 'profile.md').write_text(profile_markdown(json.loads(profile_path.read_text())))
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
