#!/usr/bin/env python3
"""Import only the train split from a validated private NOID KWS dataset into Heed."""
from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import tempfile
from pathlib import Path

from validate_dataset import DatasetError, _inside, validate_dataset


class ImportErrorSafe(ValueError):
    pass


def import_training_split(dataset_dir: Path, heed_project: Path, manifest: Path = Path("manifest.csv")) -> dict:
    dataset_root = dataset_dir.resolve(strict=True)
    project_root = heed_project.resolve(strict=True)
    manifest_path = manifest if manifest.is_absolute() else dataset_root / manifest

    if not (project_root / "config.json").is_file():
        raise ImportErrorSafe("Heed project config.json is missing; initialize with heed init first")
    targets = {label: project_root / label for label in ("positive", "negative")}
    for label, target in targets.items():
        if target.is_symlink() or not target.is_dir():
            raise ImportErrorSafe(f"Heed {label}/ must exist and must not be a symlink")
        if any(target.iterdir()):
            raise ImportErrorSafe(f"Heed {label}/ is not empty; refusing to overwrite existing clips")

    # Validate consent, every WAV, speaker separation, and all NOID minimum gates before copying.
    report = validate_dataset(dataset_root, manifest_path)
    if not report["valid"]:
        raise ImportErrorSafe("dataset does not meet NOID minimum gates: " + "; ".join(report["errors"]))

    if (project_root == dataset_root or project_root.is_relative_to(dataset_root)
            or dataset_root.is_relative_to(project_root)):
        raise ImportErrorSafe("dataset and Heed project must be separate directory trees")

    with manifest_path.open("r", encoding="utf-8-sig", newline="") as stream:
        rows = [row for row in csv.DictReader(stream) if row["split"].strip() == "train"]

    sources: list[tuple[str, Path]] = []
    for row in rows:
        label = row["label"].strip()
        if label not in targets:
            raise ImportErrorSafe(f"unsupported training label: {label!r}")
        sources.append((label, _inside(dataset_root, row["path"].strip())))

    copied: list[Path] = []
    try:
        with tempfile.TemporaryDirectory(prefix=".noid-heed-import-", dir=project_root) as temporary:
            stage = Path(temporary)
            for label in targets:
                (stage / label).mkdir()
            for index, (label, source) in enumerate(sources, start=1):
                # Filenames intentionally contain no speaker or participant identifiers.
                filename = f"noid_train_{label}_{index:06d}.wav"
                shutil.copyfile(source, stage / label / filename)
            for label, target in targets.items():
                for staged in sorted((stage / label).iterdir()):
                    destination = target / staged.name
                    if destination.exists() or destination.is_symlink():
                        raise ImportErrorSafe(f"destination appeared during import: {destination.name}")
                    staged.replace(destination)
                    copied.append(destination)
    except Exception:
        for path in copied:
            try:
                path.unlink()
            except OSError:
                pass
        raise

    return {
        "imported": len(copied),
        "positive": sum(label == "positive" for label, _ in sources),
        "negative": sum(label == "negative" for label, _ in sources),
        "excluded_calibration_and_final_test": True,
        "speaker_ids_in_filenames": False,
        "audio_uploaded": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("heed_project", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("manifest.csv"))
    args = parser.parse_args(argv)
    try:
        result = import_training_split(args.dataset_dir, args.heed_project, args.manifest)
    except (DatasetError, ImportErrorSafe, OSError) as exc:
        print(json.dumps({"imported": 0, "error": str(exc), "audio_uploaded": False},
                         ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
