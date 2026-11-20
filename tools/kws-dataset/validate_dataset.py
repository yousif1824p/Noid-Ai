#!/usr/bin/env python3
"""Validate a private, speaker-disjoint wake-word dataset from a CSV manifest.

The tool reads WAV headers only. It never uploads, copies, transcodes, or stores audio.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import wave
from collections import defaultdict
from pathlib import Path

SPLITS = {"train", "calibration", "final_test"}
LABELS = {"positive", "negative"}
MIN_POSITIVE = 300
MIN_POSITIVE_SPEAKERS = 10
MIN_HELD_OUT_POSITIVE_SPEAKERS = 4
MIN_NEGATIVE_SECONDS = 8 * 60 * 60
MIN_HELD_OUT_NEGATIVE_SECONDS = 2 * 60 * 60
REQUIRED_COLUMNS = {"speaker_id", "split", "label", "consent", "path"}


class DatasetError(ValueError):
    pass


def _inside(root: Path, relative: str) -> Path:
    rel = Path(relative)
    if rel.is_absolute() or not relative or any(part in {"", ".", ".."} for part in rel.parts):
        raise DatasetError(f"unsafe relative audio path: {relative!r}")
    candidate = root / rel
    # Reject symlinks in every component, even if they resolve inside the root.
    current = root
    for part in rel.parts:
        current = current / part
        if current.is_symlink():
            raise DatasetError(f"symlink audio path is not allowed: {relative!r}")
    resolved_root = root.resolve(strict=True)
    resolved = candidate.resolve(strict=True)
    if not resolved.is_relative_to(resolved_root) or not resolved.is_file():
        raise DatasetError(f"audio path escapes dataset root or is not a file: {relative!r}")
    return resolved


def _wav_duration(path: Path) -> float:
    try:
        with wave.open(str(path), "rb") as wav:
            frames = wav.getnframes()
            rate = wav.getframerate()
            width = wav.getsampwidth()
            channels = wav.getnchannels()
            if frames <= 0 or rate <= 0 or width not in {1, 2, 3, 4} or channels <= 0:
                raise DatasetError(f"invalid WAV geometry: {path.name}")
            if wav.getcomptype() != "NONE":
                raise DatasetError(f"compressed WAV is not supported: {path.name}")
            return frames / rate
    except (wave.Error, EOFError) as exc:
        raise DatasetError(f"invalid or unreadable WAV file: {path.name}") from exc


def validate_dataset(root: Path, manifest: Path) -> dict:
    root = root.resolve(strict=True)
    manifest_path = manifest if manifest.is_absolute() else root / manifest
    manifest_path = manifest_path.resolve(strict=True)
    if not manifest_path.is_relative_to(root) or not manifest_path.is_file():
        raise DatasetError("manifest must be a file inside the dataset root")

    with manifest_path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not REQUIRED_COLUMNS.issubset(set(reader.fieldnames)):
            missing = sorted(REQUIRED_COLUMNS - set(reader.fieldnames or []))
            raise DatasetError("manifest missing required columns: " + ", ".join(missing))
        rows = list(reader)

    if not rows:
        raise DatasetError("manifest contains no audio records")

    speaker_splits: dict[str, set[str]] = defaultdict(set)
    positive_speakers: set[str] = set()
    final_positive_speakers: set[str] = set()
    seen_paths: set[str] = set()
    positive_count = 0
    negative_seconds = 0.0
    final_negative_seconds = 0.0
    negative_speakers: set[str] = set()
    final_negative_speakers: set[str] = set()

    for line, row in enumerate(rows, start=2):
        speaker = (row.get("speaker_id") or "").strip()
        split = (row.get("split") or "").strip()
        label = (row.get("label") or "").strip()
        consent = (row.get("consent") or "").strip().lower()
        relative = (row.get("path") or "").strip()
        if not speaker:
            raise DatasetError(f"line {line}: speaker_id is required")
        if split not in SPLITS:
            raise DatasetError(f"line {line}: split must be train, calibration, or final_test")
        if label not in LABELS:
            raise DatasetError(f"line {line}: label must be positive or negative")
        if consent not in {"yes", "true", "1"}:
            raise DatasetError(f"line {line}: explicit participant consent is required")
        if relative in seen_paths:
            raise DatasetError(f"line {line}: duplicate audio path: {relative}")
        seen_paths.add(relative)
        speaker_splits[speaker].add(split)
        audio_path = _inside(root, relative)
        duration = _wav_duration(audio_path)

        if label == "positive":
            positive_count += 1
            positive_speakers.add(speaker)
            if split == "final_test":
                final_positive_speakers.add(speaker)
        else:
            negative_seconds += duration
            negative_speakers.add(speaker)
            if split == "final_test":
                final_negative_seconds += duration
                final_negative_speakers.add(speaker)

    leaked = sorted(speaker for speaker, splits in speaker_splits.items() if len(splits) > 1)
    if leaked:
        raise DatasetError("speaker leakage across splits: " + ", ".join(leaked))

    problems = []
    if positive_count < MIN_POSITIVE:
        problems.append(f"positive utterances: {positive_count}/{MIN_POSITIVE}")
    if len(positive_speakers) < MIN_POSITIVE_SPEAKERS:
        problems.append(f"positive speakers: {len(positive_speakers)}/{MIN_POSITIVE_SPEAKERS}")
    if len(final_positive_speakers) < MIN_HELD_OUT_POSITIVE_SPEAKERS:
        problems.append(f"held-out positive speakers: {len(final_positive_speakers)}/{MIN_HELD_OUT_POSITIVE_SPEAKERS}")
    if negative_seconds < MIN_NEGATIVE_SECONDS:
        problems.append(f"negative audio: {negative_seconds / 3600:.2f}/8.00 hours")
    if final_negative_seconds < MIN_HELD_OUT_NEGATIVE_SECONDS:
        problems.append(f"held-out negative audio: {final_negative_seconds / 3600:.2f}/2.00 hours")
    if len(final_negative_speakers) < 2:
        problems.append(f"held-out negative speakers: {len(final_negative_speakers)}/2")

    report = {
        "valid": not problems,
        "records": len(rows),
        "positive_utterances": positive_count,
        "positive_speakers": len(positive_speakers),
        "held_out_positive_speakers": len(final_positive_speakers),
        "negative_hours": round(negative_seconds / 3600, 4),
        "held_out_negative_hours": round(final_negative_seconds / 3600, 4),
        "held_out_negative_speakers": len(final_negative_speakers),
        "speaker_split_leakage": 0,
        "errors": problems,
        "audio_uploaded": False,
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_dir", type=Path, help="private local dataset directory")
    parser.add_argument("--manifest", default="manifest.csv", help="CSV path relative to dataset directory")
    args = parser.parse_args(argv)
    try:
        report = validate_dataset(args.dataset_dir, Path(args.manifest))
    except (DatasetError, OSError) as exc:
        print(json.dumps({"valid": False, "errors": [str(exc)], "audio_uploaded": False}, indent=2))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())
