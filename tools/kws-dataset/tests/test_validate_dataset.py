import csv
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from validate_dataset import DatasetError, validate_dataset


class DatasetValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.rows = []

    def tearDown(self):
        self.temp.cleanup()

    def wav(self, name, seconds):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        # One-Hz, mono PCM keeps long-duration negative fixtures tiny.
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(1)
            out.writeframes(b"\0\0" * seconds)
        return name

    def add(self, speaker, split, label, name, seconds=1, consent="yes"):
        self.rows.append({
            "speaker_id": speaker, "split": split, "label": label,
            "consent": consent, "path": self.wav(name, seconds),
        })

    def manifest(self):
        path = self.root / "manifest.csv"
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["speaker_id", "split", "label", "consent", "path"])
            writer.writeheader()
            writer.writerows(self.rows)
        return path

    def valid_fixture(self):
        # Speaker groups are disjoint: 4 train, 2 calibration, 4 final-test.
        for i in range(300):
            split, speaker = ("final_test", f"held{i % 4}") if i < 120 else (
                ("calibration", f"cal{i % 2}") if i < 180 else ("train", f"train{i % 4}")
            )
            self.add(speaker, split, "positive", f"positive/{i}.wav")
        # 6 hours of train/calibration negatives and 2 hours held out.
        self.add("neg-train", "train", "negative", "negative/train.wav", 21600)
        self.add("neg-held-a", "final_test", "negative", "negative/held-a.wav", 3600)
        self.add("neg-held-b", "final_test", "negative", "negative/held-b.wav", 3600)

    def test_accepts_dataset_meeting_all_thresholds(self):
        self.valid_fixture()
        self.manifest()
        report = validate_dataset(self.root, Path("manifest.csv"))
        self.assertTrue(report["valid"], report["errors"])
        self.assertEqual(report["positive_utterances"], 300)
        self.assertEqual(report["held_out_positive_speakers"], 4)
        self.assertEqual(report["negative_hours"], 8.0)
        self.assertEqual(report["held_out_negative_hours"], 2.0)
        self.assertFalse(report["audio_uploaded"])

    def test_rejects_speaker_leakage(self):
        self.add("same-person", "train", "positive", "a.wav")
        self.add("same-person", "final_test", "negative", "b.wav")
        with self.assertRaisesRegex(DatasetError, "speaker leakage"):
            validate_dataset(self.root, self.manifest())

    def test_rejects_missing_consent(self):
        self.add("speaker", "train", "positive", "a.wav", consent="no")
        with self.assertRaisesRegex(DatasetError, "consent"):
            validate_dataset(self.root, self.manifest())

    def test_rejects_path_traversal(self):
        self.add("speaker", "train", "positive", "inside.wav")
        self.rows[0]["path"] = "../outside.wav"
        with self.assertRaisesRegex(DatasetError, "unsafe relative"):
            validate_dataset(self.root, self.manifest())

    def test_reports_insufficient_dataset_without_exception(self):
        self.add("speaker", "train", "positive", "a.wav")
        self.manifest()
        report = validate_dataset(self.root, Path("manifest.csv"))
        self.assertFalse(report["valid"])
        self.assertGreaterEqual(len(report["errors"]), 4)

    def test_rejects_duplicate_or_reordered_headers(self):
        self.wav("a.wav", 1)
        path = self.root / "manifest.csv"
        path.write_text(
            "speaker_id,split,label,consent,path,path\n"
            "speaker,train,positive,yes,a.wav,a.wav\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(DatasetError, "columns must exactly match"):
            validate_dataset(self.root, path)

    def test_rejects_extra_or_missing_csv_fields(self):
        self.add("speaker", "train", "positive", "a.wav")
        path = self.manifest()
        path.write_text(
            "speaker_id,split,label,consent,path\n"
            "speaker,train,positive,yes,a.wav,unexpected\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(DatasetError, "malformed CSV row width"):
            validate_dataset(self.root, path)

    def test_rejects_control_characters_in_speaker_identifier(self):
        self.add("speaker\u202e", "train", "positive", "a.wav")
        with self.assertRaisesRegex(DatasetError, "control or format"):
            validate_dataset(self.root, self.manifest())

    def test_enforces_manifest_size_and_record_count_limits(self):
        path = self.root / "manifest.csv"
        path.write_text("speaker_id,split,label,consent,path\n", encoding="utf-8")
        with patch("validate_dataset.MAX_MANIFEST_BYTES", 8):
            with self.assertRaisesRegex(DatasetError, "exceeds"):
                validate_dataset(self.root, path)

        self.add("speaker-a", "train", "positive", "a.wav")
        self.add("speaker-b", "train", "positive", "b.wav")
        path = self.manifest()
        with patch("validate_dataset.MAX_RECORDS", 1):
            with self.assertRaisesRegex(DatasetError, "exceeds 1 records"):
                validate_dataset(self.root, path)


if __name__ == "__main__":
    unittest.main()
