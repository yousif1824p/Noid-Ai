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

    def row(self, listener="L1", **overrides):
        row = {
            "candidate_id": "candidate-a", "listener_code": listener,
            "arabic_authenticity": "4", "msa_pronunciation": "4",
            "intelligibility": "5", "gender_ambiguity": "4",
            "synthetic_identity": "5", "stability": "4",
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
        result = validate(self.write_rows([self.row("L1"), self.row("L1")]))
        self.assertFalse(result["valid"])
        self.assertTrue(any("duplicate" in error for error in result["errors"]))

    def test_invalid_score_is_rejected(self):
        self.assertFalse(validate(self.write_rows([self.row("L1", intelligibility="6")]))["valid"])

    def test_old_dialect_column_is_rejected(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "legacy-dialect.csv"
        headers = (*COLUMNS, "dialect")
        row = self.row("L1")
        row["dialect"] = "iraqi"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=headers)
            writer.writeheader()
            writer.writerow(row)
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("Unexpected columns: dialect" in error for error in result["errors"]))

    def test_small_panel_is_not_misrepresented_as_sufficient(self):
        result = validate(self.write_rows([self.row("L1")]))
        self.assertTrue(result["valid"])
        self.assertFalse(result["groups"][0]["minimum_listener_count_met"])

    def test_duplicate_csv_headers_are_rejected(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "duplicate.csv"
        path.write_text(",".join(COLUMNS) + ",listener_code\n", encoding="utf-8")
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertIn("Duplicate columns: listener_code", result["errors"][0])

    def test_extra_csv_fields_are_rejected(self):
        path = self.write_rows([self.row("L1")])
        content = path.read_text(encoding="utf-8").rstrip("\n")
        path.write_text(content + ",unexpected-extra-field\n", encoding="utf-8")
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("too many CSV fields" in error for error in result["errors"]))

    def test_missing_csv_fields_are_rejected(self):
        path = self.write_rows([self.row("L1")])
        lines = path.read_text(encoding="utf-8").splitlines()
        path.write_text("\n".join(lines[:-1] + [",".join(lines[-1].split(",")[:-1])]) + "\n", encoding="utf-8")
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("too few CSV fields" in error for error in result["errors"]))

    def test_candidate_identifier_over_limit_is_rejected(self):
        result = validate(self.write_rows([self.row("L1", candidate_id="x" * 81)]))
        self.assertFalse(result["valid"])
        self.assertTrue(any("candidate_id" in error for error in result["errors"]))

    def test_listener_identifier_control_character_is_rejected(self):
        result = validate(self.write_rows([self.row("L1" + chr(0x202e))]))
        self.assertFalse(result["valid"])
        self.assertTrue(any("listener_code" in error for error in result["errors"]))

    def test_listener_identifier_over_limit_is_rejected(self):
        result = validate(self.write_rows([self.row("L" * 33)]))
        self.assertFalse(result["valid"])
        self.assertTrue(any("listener_code" in error for error in result["errors"]))

    def test_rating_row_limit_is_enforced(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "too-many-ratings.csv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=COLUMNS)
            writer.writeheader()
            row = self.row("L1")
            for _ in range(10_001):
                writer.writerow(row)
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("exceeds 10000 rating rows" in error for error in result["errors"]))

    def test_oversized_csv_is_rejected_before_parsing(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "oversized.csv"
        path.write_text("x" * (2 * 1024 * 1024 + 1), encoding="utf-8")
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("exceeds" in error for error in result["errors"]))

    def test_invalid_utf8_is_reported_without_traceback(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "invalid-utf8.csv"
        path.write_bytes(b"candidate_id,listener_code,\xff")
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("Cannot read valid CSV" in error for error in result["errors"]))

    def test_unterminated_quoted_field_is_rejected(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "malformed.csv"
        path.write_text(
            ",".join(COLUMNS) + "\n" + '"candidate-a,L1,4,4,5,4,5,4\n',
            encoding="utf-8",
        )
        result = validate(path)
        self.assertFalse(result["valid"])
        self.assertTrue(any("Cannot read valid CSV" in error for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
