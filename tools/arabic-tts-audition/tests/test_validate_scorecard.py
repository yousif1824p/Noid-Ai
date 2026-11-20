import csv
import tempfile
import unittest
from pathlib import Path

from validate_scorecard import COLUMNS, validate


class ScorecardTests(unittest.TestCase):
    def write_rows(self, rows):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "ratings.csv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=COLUMNS)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def row(self, listener="L1", dialect="msa", **overrides):
        row = {
            "candidate_id": "candidate-a",
            "dialect": dialect,
            "listener_code": listener,
            "arabic_authenticity": "4",
            "iraqi_fidelity": "3",
            "intelligibility": "5",
            "gender_ambiguity": "4",
            "synthetic_identity": "5",
            "stability": "4",
        }
        row.update(overrides)
        return row

    def test_valid_ratings_report_medians_without_approval(self):
        path = self.write_rows([self.row("L1"), self.row("L2"), self.row("L3")])
        result = validate(path)
        self.assertTrue(result["valid"])
        self.assertEqual(result["groups"][0]["listener_count"], 3)
        self.assertEqual(result["groups"][0]["median_scores"]["intelligibility"], 5)
        self.assertEqual(result["groups"][0]["decision"], "MANUAL_REVIEW_REQUIRED")

    def test_duplicate_listener_is_rejected(self):
        path = self.write_rows([self.row("L1"), self.row("L1")])
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("duplicate" in error for error in result["errors"]))

    def test_invalid_score_is_rejected(self):
        path = self.write_rows([self.row("L1", intelligibility="6")])
        self.assertFalse(validate(path)["valid"])

    def test_unknown_dialect_is_rejected(self):
        path = self.write_rows([self.row("L1", dialect="gulf")])
        self.assertFalse(validate(path)["valid"])

    def test_small_panel_is_not_misrepresented_as_sufficient(self):
        path = self.write_rows([self.row("L1")])
        result = validate(path)
        self.assertTrue(result["valid"])
        self.assertFalse(result["groups"][0]["minimum_listener_count_met"])


if __name__ == "__main__":
    unittest.main()
