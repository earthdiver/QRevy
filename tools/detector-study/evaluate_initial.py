"""Evaluate recorded native boundaries replayed through InitialGridEstimator."""
import argparse
import collections
import json
from pathlib import Path
from evaluate import geometry_error
import cv2
import numpy as np


def evaluate_initial(corpus, candidates, output):
    truth = {t['id']: t for t in json.loads((corpus / 'manifest.json').read_text())}
    records = [json.loads(line) for line in candidates.read_text().splitlines()]
    assert len(records) == len(truth) and {r['id'] for r in records} == set(truth)
    counts = collections.Counter()
    categories = collections.Counter()
    for r in records:
        t = truth[r['id']]
        assert len(r['candidates']) <= 1
        finders = r.get('auto_assigned_finders', {})
        if finders:
            counts['auto_assigned_cases'] += 1
            if not t['positive']:
                counts['auto_assigned_negative_cases'] += 1
            else:
                n = t['n']
                q = np.float32(t['quad'])
                h = cv2.getPerspectiveTransform(np.float32([[0, 0], [n, 0], [n, n], [0, n]]), q)
                expected = cv2.perspectiveTransform(np.float32([[3.5, 3.5], [n-3.5, 3.5], [3.5, n-3.5]]).reshape(-1, 1, 2), h).reshape(3, 2)
                roles = ['TOP_LEFT', 'TOP_RIGHT', 'BOTTOM_LEFT']
                pitch = np.mean([np.linalg.norm(q[(i+1) % 4] - q[i]) for i in range(4)]) / n
                good = set(finders) == set(roles) and all(np.linalg.norm(np.array(finders[role]) - point) / pitch <= .5 for role, point in zip(roles, expected))
                counts['correct_assigned_roles' if good else 'incorrect_assigned_roles'] += 1
        for c in r['candidates']:
            counts[c['stage']] += 1
            if not t['positive']:
                counts['negative_proposals'] += 1
                continue
            good = c['n'] == t['n'] and geometry_error(c['quad'], t) <= .5
            counts['correct_grid' if good else 'incorrect_grid'] += 1
            if good:
                categories[t['category']] += 1
    for key in ['auto_assigned_cases', 'correct_assigned_roles', 'incorrect_assigned_roles', 'auto_assigned_negative_cases']:
        counts.setdefault(key, 0)
    report = dict(positive_cases=sum(t['positive'] for t in truth.values()),
                  negative_cases=sum(not t['positive'] for t in truth.values()),
                  counts=dict(counts), correct_by_condition=dict(categories),
                  scope='Recorded Python ZXing-C++ 3.1.1 boundaries; production common selection; no Android JNI execution or payload recovery')
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('corpus', type=Path)
    p.add_argument('candidates', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    evaluate_initial(args.corpus, args.candidates, args.output)
