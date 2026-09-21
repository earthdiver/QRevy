"""Summarize paired synthetic recovery runs without treating censored runs as negatives."""
import argparse
import collections
import csv
import gzip
import json
import statistics
from pathlib import Path


def summarize(rows):
    policies = sorted({r["policy_percent"] for r in rows},
                      key=lambda p: -1 if p == "one-bit" else float("inf") if p == "unlimited" else float(p))
    by_id = collections.defaultdict(dict)
    for row in rows:
        assert row["policy_percent"] not in by_id[row["id"]], "Duplicate fixture/policy"
        by_id[row["id"]][row["policy_percent"]] = row
    # A cancelled run yields no candidate list. Exclude its entire pair from all policies.
    complete = {i: rs for i, rs in by_id.items()
                if set(rs) == set(policies) and not any(r.get("stopped") for r in rs.values())}
    result = []
    for policy in policies:
        own = [rs[policy] for rs in complete.values()]
        truth = [r for r in own if r["has_truth"]]
        base_pairs = [(rs[policy], rs["unlimited"]) for rs in complete.values() if "unlimited" in rs]
        base_success = [(r, b) for r, b in base_pairs if b["correct_raw"]]
        times = sorted(r["elapsed_ms"] for r in own)
        result.append(dict(
            policy=policy, paired_complete=len(own), total_fixtures=len(by_id),
            excluded_incomplete=len(by_id)-len(complete), truth_cases=len(truth),
            correct_raw=sum(r["correct_raw"] for r in truth),
            correct_text=sum(r["correct_text"] for r in truth),
            other_payload_cases=sum(r["other_payloads"] > 0 for r in own),
            other_payloads=sum(r["other_payloads"] for r in own),
            other_raw_candidates=sum(r.get("other_raw_candidates", 0) for r in own),
            wrong_only_cases=sum(r["other_payloads"] > 0 and not r["correct_raw"] for r in own),
            zero_mismatch_other_cases=sum(r["other_zero_mismatch_payloads"] > 0 for r in own),
            nonzero_other_payloads=sum(r["other_payloads"] - r["other_zero_mismatch_payloads"] for r in own),
            negative_cases=sum(not r["has_truth"] for r in own),
            negative_with_payload=sum(not r["has_truth"] and r["parsed_candidates"] > 0 for r in own),
            baseline_correct=len(base_success),
            retained_baseline_correct=sum(r["correct_raw"] for r, _ in base_success),
            gained_vs_baseline=sum(r["correct_raw"] and not b["correct_raw"] for r, b in base_pairs),
            stopped_runs=sum(bool(r.get("stopped")) for r in rows if r["policy_percent"] == policy),
            unsearched_runs=sum(r.get("diagnostic", {}).get("unsearched", False) for r in own),
            unresolved_runs=sum(r.get("diagnostic", {}).get("unresolvedCalculations", False) for r in own),
            incomplete_coverage=sum(not r.get("diagnostic", {}).get("coverageComplete", False) for r in own),
            candidates_omitted=sum(r.get("diagnostic", {}).get("candidatesOmitted", False) for r in own),
            median_ms=statistics.median(times) if times else None,
            p95_ms=times[min(len(times)-1, int(len(times)*.95))] if times else None,
        ))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with (gzip.open(args.input, "rt") if args.input.suffix == ".gz" else args.input.open()) as source:
        rows = [json.loads(line) for line in source]
    summary = summarize(rows)
    groups = collections.defaultdict(list)
    for r in rows:
        for field in ("family", "version", "ecc", "seed"):
            groups[(field, str(r[field]))].append(r)
    breakdown = [dict(dimension=field, group=value, **s)
                 for (field, value), rs in sorted(groups.items()) for s in summarize(rs)]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    for name, data in (("summary.csv", summary), ("breakdown.csv", breakdown)):
        with (args.output / name).open("w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(data[0]))
            writer.writeheader()
            writer.writerows(data)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
