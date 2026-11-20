import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from evaluate_continuous import EvaluationError, evaluate


class ContinuousEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.recordings = self.root / "recordings.csv"
        self.detections = self.root / "detections.csv"
        self.truth = self.root / "truth.csv"

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, columns, rows):
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=columns)
            writer.writeheader()
            writer.writerows(rows)

    def fixture(self):
        self.write(self.recordings,
            ["recording_id", "speaker_id", "split", "label", "condition", "duration_seconds"], [
                {"recording_id": "p1", "speaker_id": "spk-a", "split": "final_test", "label": "positive", "condition": "quiet", "duration_seconds": "20"},
                {"recording_id": "n1", "speaker_id": "spk-b", "split": "final_test", "label": "negative", "condition": "tv", "duration_seconds": "3600"},
                {"recording_id": "n2", "speaker_id": "spk-c", "split": "final_test", "label": "negative", "condition": "quiet", "duration_seconds": "3600"},
            ])
        self.write(self.detections,
            ["recording_id", "timestamp_seconds", "confidence"], [
                {"recording_id": "p1", "timestamp_seconds": "5.2", "confidence": "0.95"},
                {"recording_id": "p1", "timestamp_seconds": "15", "confidence": "0.91"},
                {"recording_id": "n1", "timestamp_seconds": "100", "confidence": "0.99"},
                {"recording_id": "n2", "timestamp_seconds": "200", "confidence": "0.2"},
            ])
        self.write(self.truth,
            ["recording_id", "start_seconds", "end_seconds"], [
                {"recording_id": "p1", "start_seconds": "3", "end_seconds": "5"},
                {"recording_id": "p1", "start_seconds": "12", "end_seconds": "14"},
            ])

    def test_reports_recall_and_false_activations_per_negative_hour(self):
        self.fixture()
        result = evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)
        self.assertEqual(result["true_positives"], 1)
        self.assertEqual(result["missed_events"], 1)
        self.assertEqual(result["recall"], 0.5)
        self.assertEqual(result["negative_audio_hours"], 2.0)
        self.assertEqual(result["false_activations_on_negative_audio"], 1)
        self.assertEqual(result["false_activations_per_negative_audio_hour"], 0.5)
        self.assertEqual(result["unmatched_detections_on_positive_audio"], 1)
        self.assertEqual(len(result["breakdown_by_speaker_and_condition"]), 3)
        self.assertFalse(result["audio_opened_or_uploaded"])

    def test_rejects_recording_row_limit(self):
        self.fixture()
        with patch("evaluate_continuous.MAX_RECORDINGS", 2):
            with self.assertRaisesRegex(EvaluationError, "exceeds 2 data rows"):
                evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_detection_event_row_limit(self):
        self.fixture()
        with patch("evaluate_continuous.MAX_DETECTION_EVENTS", 3):
            with self.assertRaisesRegex(EvaluationError, "exceeds 3 data rows"):
                evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_csv_file_size_limit(self):
        self.fixture()
        with patch("evaluate_continuous.MAX_CSV_FILE_BYTES", 16):
            with self.assertRaisesRegex(EvaluationError, "exceeds 16 bytes"):
                evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_truth_event_row_limit(self):
        self.fixture()
        with patch("evaluate_continuous.MAX_TRUTH_EVENTS", 1):
            with self.assertRaisesRegex(EvaluationError, "exceeds 1 data rows"):
                evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_matching_maximizes_one_to_one_hits_when_windows_overlap(self):
        self.fixture()
        self.write(self.detections, ["recording_id", "timestamp_seconds", "confidence"], [
            {"recording_id": "p1", "timestamp_seconds": "4.9", "confidence": "0.99"},
            {"recording_id": "p1", "timestamp_seconds": "2.0", "confidence": "0.98"},
        ])
        self.write(self.truth, ["recording_id", "start_seconds", "end_seconds"], [
            {"recording_id": "p1", "start_seconds": "3", "end_seconds": "4"},
            {"recording_id": "p1", "start_seconds": "5.1", "end_seconds": "6"},
        ])
        result = evaluate(self.recordings, self.detections, self.truth, 0.8, 1.2)
        self.assertEqual(result["true_positives"], 2)
        self.assertEqual(result["missed_events"], 0)
        self.assertEqual(result["unmatched_detections_on_positive_audio"], 0)

    def test_rejects_format_character_in_speaker_identifier(self):
        self.fixture()
        self.write(self.recordings,
            ["recording_id", "speaker_id", "split", "label", "condition", "duration_seconds"], [
                {"recording_id": "p1", "speaker_id": "spk" + chr(0x200B) + "-a", "split": "final_test",
                 "label": "positive", "condition": "quiet", "duration_seconds": "20"},
                {"recording_id": "n1", "speaker_id": "spk-b", "split": "final_test",
                 "label": "negative", "condition": "tv", "duration_seconds": "3600"},
            ])
        with self.assertRaisesRegex(EvaluationError, "invalid speaker_id"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_oversized_recording_identifier(self):
        self.fixture()
        self.write(self.recordings,
            ["recording_id", "speaker_id", "split", "label", "condition", "duration_seconds"], [
                {"recording_id": "r" * 129, "speaker_id": "spk-a", "split": "final_test",
                 "label": "positive", "condition": "quiet", "duration_seconds": "20"},
                {"recording_id": "n1", "speaker_id": "spk-b", "split": "final_test",
                 "label": "negative", "condition": "tv", "duration_seconds": "3600"},
            ])
        with self.assertRaisesRegex(EvaluationError, "invalid recording_id"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_duplicate_csv_columns(self):
        self.fixture()
        self.recordings.write_text(
            "recording_id,recording_id,speaker_id,split,label,condition,duration_seconds\n",
            encoding="utf-8"
        )
        with self.assertRaisesRegex(EvaluationError, "duplicate CSV columns"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_detection_outside_recording(self):
        self.fixture()
        self.write(self.detections, ["recording_id", "timestamp_seconds", "confidence"], [
            {"recording_id": "n1", "timestamp_seconds": "4000", "confidence": "0.9"}])
        with self.assertRaisesRegex(EvaluationError, "exceeds"):
            evaluate(self.recordings, self.detections, self.truth, 0.5, 0.5)

    def test_rejects_unknown_recording_reference(self):
        self.fixture()
        self.write(self.detections, ["recording_id", "timestamp_seconds", "confidence"], [
            {"recording_id": "unknown", "timestamp_seconds": "1", "confidence": "0.9"}])
        with self.assertRaisesRegex(EvaluationError, "unknown recording"):
            evaluate(self.recordings, self.detections, self.truth, 0.5, 0.5)

    def test_rejects_mixed_splits_to_prevent_test_contamination(self):
        self.fixture()
        with self.recordings.open("a", encoding="utf-8") as stream:
            stream.write("train-extra,spk-z,train,negative,room,60\n")
        with self.assertRaisesRegex(EvaluationError, "only the requested"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_missing_negative_audio_denominator(self):
        self.write(self.recordings,
            ["recording_id", "speaker_id", "split", "label", "condition", "duration_seconds"], [
                {"recording_id": "p1", "speaker_id": "a", "split": "final_test", "label": "positive", "condition": "quiet", "duration_seconds": "10"}])
        self.write(self.detections, ["recording_id", "timestamp_seconds", "confidence"], [])
        self.write(self.truth, ["recording_id", "start_seconds", "end_seconds"], [
            {"recording_id": "p1", "start_seconds": "1", "end_seconds": "2"}])
        with self.assertRaisesRegex(EvaluationError, "negative recording"):
            evaluate(self.recordings, self.detections, self.truth, 0.5, 0.5)


    def test_rejects_invalid_utf8_csv(self):
        self.fixture()
        self.recordings.write_bytes(b"recording_id,speaker_id,split,label,condition,duration_seconds\n\xff")
        with self.assertRaisesRegex(EvaluationError, "malformed CSV encoding or quoting"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_overlapping_ground_truth_intervals(self):
        self.fixture()
        self.write(self.truth, ["recording_id", "start_seconds", "end_seconds"], [
            {"recording_id": "p1", "start_seconds": "3", "end_seconds": "5"},
            {"recording_id": "p1", "start_seconds": "4", "end_seconds": "6"},
        ])
        with self.assertRaisesRegex(EvaluationError, "overlapping ground-truth phrase intervals"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)

    def test_rejects_unterminated_quoted_csv_field(self):
        self.fixture()
        self.recordings.write_text(
            "recording_id,speaker_id,split,label,condition,duration_seconds\n"
            + '"rec-1,spk-a,final_test,positive,quiet,20\n',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EvaluationError, "malformed CSV encoding or quoting"):
            evaluate(self.recordings, self.detections, self.truth, 0.8, 0.5)


if __name__ == "__main__":
    unittest.main()
