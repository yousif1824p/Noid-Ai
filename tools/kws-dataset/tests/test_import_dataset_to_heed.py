import csv
import tempfile
import unittest
import wave
from pathlib import Path

from import_dataset_to_heed import ImportErrorSafe, import_training_split


class HeedDatasetImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.dataset = self.root / "dataset"
        self.dataset.mkdir()
        self.project = self.root / "heed-project"
        self.project.mkdir()
        (self.project / "config.json").write_text("{}", encoding="utf-8")
        (self.project / "positive").mkdir()
        (self.project / "negative").mkdir()
        self.rows = []

    def tearDown(self):
        self.temp.cleanup()

    def wav(self, name, seconds=1):
        path = self.dataset / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(1)
            out.writeframes(b"\\0\\0" * seconds)
        return name

    def add(self, speaker, split, label, name, seconds=1):
        self.rows.append({
            "speaker_id": speaker, "split": split, "label": label,
            "consent": "yes", "path": self.wav(name, seconds),
        })

    def valid_dataset(self):
        for i in range(300):
            if i < 120:
                split, speaker = "final_test", f"held{i % 4}"
            elif i < 180:
                split, speaker = "calibration", f"cal{i % 2}"
            else:
                split, speaker = "train", f"train{i % 4}"
            self.add(speaker, split, "positive", f"positive/{i}.wav")
        self.add("neg-train", "train", "negative", "negative/train.wav", 21600)
        self.add("neg-held-a", "final_test", "negative", "negative/held-a.wav", 3600)
        self.add("neg-held-b", "final_test", "negative", "negative/held-b.wav", 3600)
        manifest = self.dataset / "manifest.csv"
        with manifest.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["speaker_id", "split", "label", "consent", "path"])
            writer.writeheader()
            writer.writerows(self.rows)
        return manifest

    def test_imports_only_train_and_uses_anonymous_names(self):
        self.valid_dataset()
        result = import_training_split(self.dataset, self.project)
        self.assertEqual(result["positive"], 120)
        self.assertEqual(result["negative"], 1)
        files = list((self.project / "positive").iterdir()) + list((self.project / "negative").iterdir())
        self.assertEqual(len(files), 121)
        self.assertTrue(all("train" in p.name and "speaker" not in p.name for p in files))
        self.assertTrue(result["excluded_calibration_and_final_test"])
        self.assertFalse(result["audio_uploaded"])

    def test_refuses_nonempty_heed_directories_without_overwriting(self):
        self.valid_dataset()
        existing = self.project / "positive" / "keep.wav"
        existing.write_bytes(b"keep")
        with self.assertRaisesRegex(ImportErrorSafe, "not empty"):
            import_training_split(self.dataset, self.project)
        self.assertEqual(existing.read_bytes(), b"keep")
        self.assertEqual(list((self.project / "negative").iterdir()), [])

    def test_refuses_dataset_below_minimum_before_copy(self):
        self.add("one-speaker", "train", "positive", "positive/one.wav")
        manifest = self.dataset / "manifest.csv"
        with manifest.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["speaker_id", "split", "label", "consent", "path"])
            writer.writeheader()
            writer.writerows(self.rows)
        with self.assertRaisesRegex(ImportErrorSafe, "minimum gates"):
            import_training_split(self.dataset, self.project)
        self.assertEqual(list((self.project / "positive").iterdir()), [])
        self.assertEqual(list((self.project / "negative").iterdir()), [])

    def test_refuses_missing_heed_project_structure(self):
        self.valid_dataset()
        (self.project / "config.json").unlink()
        with self.assertRaisesRegex(ImportErrorSafe, "config.json"):
            import_training_split(self.dataset, self.project)


if __name__ == "__main__":
    unittest.main()
