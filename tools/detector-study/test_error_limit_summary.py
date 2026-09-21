import unittest
from error_limit_summary import summarize


def row(case, policy, *, correct=False, other=0, stopped=None):
    return dict(id=case, policy_percent=policy, has_truth=True, correct_raw=correct,
                correct_text=correct, other_payloads=other, other_zero_mismatch_payloads=0,
                parsed_candidates=int(correct)+other, elapsed_ms=1, stopped=stopped,
                diagnostic={"coverageComplete": True})


class ErrorLimitSummaryTest(unittest.TestCase):
    def test_cancellation_excludes_pair_without_claiming_no_wrong_candidates(self):
        summary = summarize([row("a", "1", stopped="wall_budget"),
                             row("a", "unlimited", other=3),
                             row("b", "1", correct=True), row("b", "unlimited", correct=True)])
        self.assertEqual([1, 1], [r["paired_complete"] for r in summary])
        self.assertEqual([1, 1], [r["excluded_incomplete"] for r in summary])
        self.assertEqual([1, 0], [r["stopped_runs"] for r in summary])

    def test_lost_and_new_correct_candidates_are_separate(self):
        summary = summarize([row("lost", "1"), row("lost", "unlimited", correct=True),
                             row("new", "1", correct=True), row("new", "unlimited")])[0]
        self.assertEqual(1, summary["baseline_correct"])
        self.assertEqual(0, summary["retained_baseline_correct"])
        self.assertEqual(1, summary["gained_vs_baseline"])

    def test_wrong_only_and_ambiguous_correct_are_distinct(self):
        s = summarize([row("ambiguous", "1", correct=True, other=2), row("wrong", "1", other=1)])[0]
        self.assertEqual(2, s["other_payload_cases"])
        self.assertEqual(3, s["other_payloads"])
        self.assertEqual(1, s["wrong_only_cases"])


if __name__ == "__main__":
    unittest.main()
