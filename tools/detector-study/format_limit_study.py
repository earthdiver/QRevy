"""Enumerate QR format-filter behavior; not an end-to-end recovery benchmark."""
import argparse
import collections
import itertools
import json
from pathlib import Path

FULL = (1 << 15) - 1
LIMITS = (0, 3, 6, 9, None)


def format_codeword(value):
    remainder = value << 10
    while remainder.bit_length() >= 11:
        remainder ^= 0x537 << (remainder.bit_length() - 11)
    return ((value << 10) | remainder) ^ 0x5412


CODES = tuple(format_codeword(v) for v in range(32))
TRUTH = CODES[0]


def error_masks(count):
    return (sum(1 << b for b in positions)
            for positions in itertools.combinations(range(15), count))


def evaluate(name, observations):
    accum = {limit: dict(truth_retained=0, candidate_total=0,
                        candidate_min=32, candidate_max=0, other_format_cases=0)
             for limit in LIMITS}
    count = 0
    for first, second, known_first, known_second in observations:
        count += 1
        distances = [((first ^ code) & known_first).bit_count()
                     + ((second ^ code) & known_second).bit_count() for code in CODES]
        for limit, row in accum.items():
            accepted = [limit is None or d <= limit for d in distances]
            size = sum(accepted)
            row['truth_retained'] += accepted[0]
            row['candidate_total'] += size
            row['candidate_min'] = min(row['candidate_min'], size)
            row['candidate_max'] = max(row['candidate_max'], size)
            row['other_format_cases'] += any(accepted[1:])
    return dict(scenario=name, observations=count, policies=[
        dict(limit=limit, **row, candidate_mean=row['candidate_total'] / count)
        for limit, row in accum.items()])


def study():
    # The QR format code is a masked linear code. Check translation invariance,
    # so exhaustive error locations around one truth cover all 32 truth values.
    differences = {code ^ TRUTH for code in CODES}
    assert all({code ^ truth for code in CODES} == differences for truth in CODES)
    spectrum = collections.Counter(d.bit_count() for d in differences)
    assert spectrum == {0: 1, 7: 15, 8: 15, 15: 1}
    scenarios = []
    for errors in (0, 4, 8, 15):
        scenarios.append(evaluate('clean_copy_plus_' + str(errors) + '_errors',
            ((TRUTH, TRUTH ^ mask, FULL, FULL) for mask in error_masks(errors))))
    three = list(error_masks(3))
    scenarios.append(evaluate('three_errors_in_each_copy',
        ((TRUTH ^ a, TRUTH ^ b, FULL, FULL) for a in three for b in three)))
    scenarios.append(evaluate('all_unknown', [(0, 0, 0, 0)]))
    alternating = int('101010101010101', 2)
    scenarios.append(evaluate('complementary_unknown',
        [(TRUTH, TRUTH, alternating, FULL ^ alternating)]))
    scenarios.append(evaluate('one_copy_unknown', [(TRUTH, 0, FULL, 0)]))
    return dict(description='Pre-recovery format mismatch filter only; no trusted/provisional routing or payload validation.',
                format_codewords=32, single_copy_distance_spectrum=dict(sorted(spectrum.items())),
                minimum_distance_single_copy=7, minimum_distance_two_complete_copies=14,
                truth_translation_invariance_checked=True, scenarios=scenarios)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    encoded = json.dumps(study(), indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    else:
        print(encoded, end='')


if __name__ == '__main__':
    main()
