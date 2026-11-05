import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('benchmark_summary', Path(__file__).with_name('summarize-recovery-benchmark.py'))
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


def row(seed=0, allowed=True, found=True, complete='COMPLETE', stop=None, evidence='route-a'):
    return {'fixture': {'scenario': 'example', 'seed': seed, 'inputHash': str(seed), 'workingLimitBytes': 32,
                        'mismatchLimit': None, 'originalAllowedByFixedCells': allowed, 'version': 1},
            'image': {'boundaryDetectionMs': 1., 'gridEstimateMs': 2., 'knownGeometrySamplingMs': 1., 'estimatedVersion': 1,
                      'sampledValueDifferences': 0},
            'result': {'decoderThreadAllocatedBytes': 500, 'decodeMs': 10., 'heapPoolPeakSumBytes': 1234, 'gcCount': 0, 'gcMillis': 0,
                       'cancellationReturnMs': 2. if stop else None, 'retainedCandidateCount': 1,
                       'knownValidWitnessesFound': int(found), 'originalFound': found,
                       'wrongSingletonRelativeToOriginal': not found, 'completeness': complete,
                       'stopCause': stop, 'candidateSetHash': 'correct' if found else 'other',
                       'evidenceHash': evidence, 'checkLimit': 1000}}


class RecoveryBenchmarkSummaryTest(unittest.TestCase):
    def test_stops_are_not_success_and_fixed_conflicts_are_not_false_recovery_failures(self):
        rows = [row(), row(1, found=False, complete='UNSEARCHED', stop='check-limit'), row(2, allowed=False, found=False)]
        result = summary.summarize(rows)[0]
        self.assertEqual((1, 2), (result['eligibleOriginalFoundRuns'], result['eligibleRuns']))
        self.assertEqual(1, result['wrongSingletonRuns'])
        self.assertEqual(2, result['completeRuns'])
        self.assertEqual({'none': 2, 'check-limit': 1}, result['stopCounts'])
        self.assertEqual(2., result['maxCancellationReturnMs'])

    def test_repeated_seeds_are_not_independent_and_changed_provenance_is_detected(self):
        result = summary.summarize([row(), row(evidence='route-b')])[0]
        self.assertEqual(1, result['distinctFixtures'])
        self.assertEqual([0], result['unstableSeeds'])
        compared = summary.compare([row(evidence='route-b')], [row()])
        self.assertEqual(1, compared['matchedInputs'])
        self.assertEqual(1, len(compared['changedInputs']))

    def test_unmatched_inputs_are_reported_and_latency_is_not_an_evidence_change(self):
        now = row()
        now['result']['decodeMs'] = 999.
        self.assertEqual([], summary.compare([now], [row()])['changedInputs'])
        compared = summary.compare([row(1)], [row(2)])
        self.assertEqual(0, compared['matchedInputs'])
        self.assertEqual(1, len(compared['missingInputs']))
        self.assertEqual(1, len(compared['newInputs']))

    def test_empty_results_still_compare_diagnostic_stop_reasons(self):
        before = row(found=False, complete='UNSEARCHED')
        now = row(found=False, complete='UNSEARCHED')
        before['result']['searchDiagnostic'] = {'incompleteReasons': ['WORKING_MEMORY_LIMIT']}
        now['result']['searchDiagnostic'] = {'incompleteReasons': ['INTERNAL_ERROR']}
        self.assertEqual(1, len(summary.compare([now], [before])['changedInputs']))
        self.assertEqual({'WORKING_MEMORY_LIMIT': 1}, summary.summarize([before])[0]['diagnosticStopCounts'])

    def test_profile_reports_empty_samples_and_preserves_inclusive_warning(self):
        counts = {'samples': 0, 'weight': 0, 'truncatedStacks': 0, 'missingStacks': 0,
                  'nearestCoreMethod': [], 'inclusiveCoreMethods': [], 'allocationClasses': [], 'coreCallerPaths': []}
        profile = {'scope': 'measured windows', 'executionMeaning': 'sample share',
                   'allocationMeaning': 'sample weights', 'nearestMeaning': 'exclusive',
                   'inclusiveMeaning': 'rows overlap and must not be summed', 'byFixture': {},
                   'total': {'invocations': 3, 'execution': counts, 'allocation': counts}}
        report = summary.profile_markdown(profile)
        self.assertIn('No samples captured', report)
        self.assertIn('rows overlap and must not be summed', report)
        self.assertIn('recording overhead', report)
        counts['inclusiveCoreMethods'] = [
            {'name': 'KoetterVardyDecoder.interpolate', 'weight': 50, 'percent': 50.},
            {'name': 'KoetterVardyDecoder.interpolate$updateDegree', 'weight': 15, 'percent': 15.}]
        report = summary.profile_markdown(profile)
        self.assertIn('| Koetter-Vardy interpolation | 50.00% | 50.00% |', report)

    def test_p95_uses_nearest_rank(self):
        self.assertEqual(19, summary.percentile(list(range(1, 21)), .95))
        self.assertEqual(4, summary.percentile([1, 2, 3, 4], .95))


if __name__ == '__main__':
    unittest.main()
