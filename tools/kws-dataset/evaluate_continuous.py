#!/usr/bin/env python3
"""Evaluate timestamped KWS detections against continuous recording metadata.

No audio is opened or uploaded. Detection timestamps must come from a separately
run local detector; this tool evaluates its exported CSV events only.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

RECORDING_COLUMNS = {"recording_id", "speaker_id", "split", "label", "condition", "duration_seconds"}
DETECTION_COLUMNS = {"recording_id", "timestamp_seconds", "confidence"}
TRUTH_COLUMNS = {"recording_id", "start_seconds", "end_seconds"}
SPLITS = {"train", "calibration", "final_test"}
LABELS = {"positive", "negative"}
MAX_CSV_FILE_BYTES = 8 * 1024 * 1024
MAX_RECORDINGS = 10_000
MAX_DETECTION_EVENTS = 100_000
MAX_TRUTH_EVENTS = 100_000
MAX_IDENTIFIER_LENGTH = 128
MAX_METADATA_LENGTH = 128


class EvaluationError(ValueError):
    pass


def _read_csv(path: Path, required: set[str], max_rows: int) -> list[dict[str, str]]:
    if path.stat().st_size > MAX_CSV_FILE_BYTES:
        raise EvaluationError(f"{path.name}: CSV exceeds {MAX_CSV_FILE_BYTES} bytes")
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        headers = reader.fieldnames or []
        if len(headers) != len(set(headers)):
            raise EvaluationError(f"{path.name}: duplicate CSV columns")
        if set(headers) != required:
            missing = sorted(required - set(headers))
            extra = sorted(set(headers) - required)
            details = []
            if missing:
                details.append("missing: " + ", ".join(missing))
            if extra:
                details.append("unexpected: " + ", ".join(extra))
            raise EvaluationError(f"{path.name}: invalid columns ({'; '.join(details)})")
        rows = []
        for row_number, row in enumerate(reader, start=2):
            if row_number - 1 > max_rows:
                raise EvaluationError(f"{path.name}: exceeds {max_rows} data rows")
            if None in row or any(value is None for value in row.values()):
                raise EvaluationError(f"{path.name}: malformed field count on row {row_number}")
            rows.append(row)
        return rows


def _safe_token(value: str, field: str, maximum: int = MAX_IDENTIFIER_LENGTH) -> str:
    token = value.strip()
    if not token or len(token) > maximum or any(
        ord(char) < 32 or 127 <= ord(char) <= 159 or
        unicodedata.category(char) == "Cf"
        for char in token
    ):
        raise EvaluationError(f"invalid {field}: empty, oversized, or contains control/format characters")
    return token


def _number(value: str, field: str, *, minimum: float | None = None) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise EvaluationError(f"invalid {field}: {value!r}") from exc
    if not math.isfinite(result) or (minimum is not None and result < minimum):
        raise EvaluationError(f"invalid {field}: {value!r}")
    return result


def evaluate(recordings_csv: Path, detections_csv: Path, truth_csv: Path,
             threshold: float, tolerance: float, split: str = "final_test") -> dict:
    if split not in SPLITS:
        raise EvaluationError("split must be train, calibration, or final_test")
    if not 0.0 <= threshold <= 1.0 or not math.isfinite(threshold):
        raise EvaluationError("threshold must be finite and between 0 and 1")
    if tolerance < 0 or not math.isfinite(tolerance):
        raise EvaluationError("matching tolerance must be finite and non-negative")

    recordings: dict[str, dict] = {}
    for row in _read_csv(recordings_csv, RECORDING_COLUMNS, MAX_RECORDINGS):
        rid = _safe_token(row["recording_id"], "recording_id")
        speaker = _safe_token(row["speaker_id"], "speaker_id", MAX_METADATA_LENGTH)
        row_split, label = row["split"].strip(), row["label"].strip()
        condition = row["condition"].strip() or "unspecified"
        if len(condition) > MAX_METADATA_LENGTH or any(
            ord(char) < 32 or 127 <= ord(char) <= 159 or
            unicodedata.category(char) == "Cf"
            for char in condition
        ):
            raise EvaluationError(f"invalid condition for {rid!r}")
        duration = _number(row["duration_seconds"], "duration_seconds", minimum=0.000001)
        if not rid or not speaker or not condition or row_split not in SPLITS or label not in LABELS:
            raise EvaluationError(f"invalid recording metadata for {rid!r}")
        if rid in recordings:
            raise EvaluationError(f"duplicate recording_id: {rid}")
        recordings[rid] = {"speaker_id": speaker, "split": row_split, "label": label,
                           "condition": condition, "duration": duration}
    if not recordings:
        raise EvaluationError("recordings CSV is empty")
    if any(rec["split"] != split for rec in recordings.values()):
        raise EvaluationError("recordings must contain only the requested split")

    detections: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for row in _read_csv(detections_csv, DETECTION_COLUMNS, MAX_DETECTION_EVENTS):
        rid = _safe_token(row["recording_id"], "recording_id")
        if rid not in recordings:
            raise EvaluationError(f"detection references unknown recording: {rid!r}")
        timestamp = _number(row["timestamp_seconds"], "timestamp_seconds", minimum=0)
        confidence = _number(row["confidence"], "confidence")
        if timestamp > recordings[rid]["duration"]:
            raise EvaluationError(f"detection timestamp exceeds recording duration: {rid}")
        if not 0 <= confidence <= 1:
            raise EvaluationError("detection confidence must be between 0 and 1")
        if confidence >= threshold:
            detections[rid].append((timestamp, confidence))

    truths: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for row in _read_csv(truth_csv, TRUTH_COLUMNS, MAX_TRUTH_EVENTS):
        rid = _safe_token(row["recording_id"], "recording_id")
        if rid not in recordings:
            raise EvaluationError(f"ground truth references unknown recording: {rid!r}")
        if recordings[rid]["label"] != "positive":
            raise EvaluationError(f"ground-truth phrase event belongs to non-positive recording: {rid}")
        start = _number(row["start_seconds"], "start_seconds", minimum=0)
        end = _number(row["end_seconds"], "end_seconds", minimum=0)
        if end <= start or end > recordings[rid]["duration"]:
            raise EvaluationError(f"invalid ground-truth interval for {rid}")
        truths[rid].append((start, end))

    positive_ids = [rid for rid, r in recordings.items() if r["label"] == "positive"]
    negative_ids = [rid for rid, r in recordings.items() if r["label"] == "negative"]
    if not negative_ids:
        raise EvaluationError("at least one negative recording is required")
    if not positive_ids or not any(truths[rid] for rid in positive_ids):
        raise EvaluationError("positive recordings with ground-truth phrase events are required")

    totals = {"positive_events": 0, "true_positives": 0, "missed_events": 0,
              "negative_duration_seconds": 0.0, "false_activations_negative": 0,
              "unmatched_detections_positive": 0}
    groups: dict[tuple[str, str], dict] = defaultdict(
        lambda: {"recordings": 0, "duration_seconds": 0.0, "detections": 0,
                 "events": 0, "true_positives": 0, "missed_events": 0})
    for rid, rec in recordings.items():
        key = (rec["speaker_id"], rec["condition"])
        group = groups[key]
        group["recordings"] += 1
        group["duration_seconds"] += rec["duration"]
        found = sorted(detections[rid])
        expected = sorted(truths[rid])
        matched: set[int] = set()
        for start, end in expected:
            totals["positive_events"] += 1
            group["events"] += 1
            candidates = [(abs(t - end), i) for i, (t, _) in enumerate(found)
                          if i not in matched and start - tolerance <= t <= end + tolerance]
            if candidates:
                _, index = min(candidates)
                matched.add(index)
                totals["true_positives"] += 1
                group["true_positives"] += 1
            else:
                totals["missed_events"] += 1
                group["missed_events"] += 1
        unmatched = len(found) - len(matched)
        group["detections"] += len(found)
        if rec["label"] == "negative":
            totals["negative_duration_seconds"] += rec["duration"]
            totals["false_activations_negative"] += unmatched
        else:
            totals["unmatched_detections_positive"] += unmatched

    negative_hours = totals["negative_duration_seconds"] / 3600.0
    recall = totals["true_positives"] / totals["positive_events"] if totals["positive_events"] else 0.0
    breakdown = []
    for (speaker, condition), values in sorted(groups.items()):
        breakdown.append({
            "speaker_id": speaker, "condition": condition,
            "recordings": values["recordings"],
            "duration_hours": round(values["duration_seconds"] / 3600, 4),
            "positive_events": values["events"],
            "true_positives": values["true_positives"],
            "missed_events": values["missed_events"],
            "recall": round(values["true_positives"] / values["events"], 6) if values["events"] else None,
            "detections": values["detections"],
        })
    return {
        "split": split, "threshold": threshold, "matching_tolerance_seconds": tolerance,
        "recordings": len(recordings),
        "positive_events": totals["positive_events"],
        "true_positives": totals["true_positives"],
        "missed_events": totals["missed_events"],
        "recall": round(recall, 6),
        "negative_audio_hours": round(negative_hours, 6),
        "false_activations_on_negative_audio": totals["false_activations_negative"],
        "false_activations_per_negative_audio_hour": round(
            totals["false_activations_negative"] / negative_hours, 6),
        "unmatched_detections_on_positive_audio": totals["unmatched_detections_positive"],
        "breakdown_by_speaker_and_condition": breakdown,
        "audio_opened_or_uploaded": False,
        "model_or_detector_run": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recordings_csv", type=Path)
    parser.add_argument("detections_csv", type=Path)
    parser.add_argument("ground_truth_csv", type=Path)
    parser.add_argument("--threshold", type=float, required=True,
                        help="frozen detector confidence threshold (0..1)")
    parser.add_argument("--tolerance-seconds", type=float, default=0.5)
    parser.add_argument("--split", choices=sorted(SPLITS), default="final_test")
    args = parser.parse_args(argv)
    try:
        result = evaluate(args.recordings_csv, args.detections_csv, args.ground_truth_csv,
                          args.threshold, args.tolerance_seconds, args.split)
    except (EvaluationError, OSError) as exc:
        print(json.dumps({"valid": False, "error": str(exc),
                          "audio_opened_or_uploaded": False}, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
