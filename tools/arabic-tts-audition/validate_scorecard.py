#!/usr/bin/env python3
"""Validate anonymous NOID Arabic TTS audition ratings; never auto-approve a voice."""
from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

COLUMNS = (
    "candidate_id", "dialect", "listener_code", "arabic_authenticity",
    "iraqi_fidelity", "intelligibility", "gender_ambiguity",
    "synthetic_identity", "stability",
)
DIALECTS = {"msa", "iraqi"}
SCORES = COLUMNS[3:]
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_RATINGS = 10_000
MAX_CANDIDATE_ID_LENGTH = 80
MAX_LISTENER_CODE_LENGTH = 32


def _safe_identifier(value: str, maximum: int) -> bool:
    return bool(value) and len(value) <= maximum and not any(
        unicodedata.category(char) in {"Cc", "Cf"} for char in value
    )


def validate(path: Path) -> dict:
    errors: list[str] = []
    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    seen: set[tuple[str, str, str]] = set()

    try:
        if path.stat().st_size > MAX_FILE_BYTES:
            return {"valid": False, "errors": [f"CSV exceeds {MAX_FILE_BYTES} bytes"], "groups": []}
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            headers = reader.fieldnames
            if headers is None:
                return {"valid": False, "errors": ["CSV has no header"], "groups": []}
            if len(headers) != len(set(headers)):
                duplicates = sorted({name for name in headers if headers.count(name) > 1})
                return {"valid": False, "errors": ["Duplicate columns: " + ", ".join(duplicates)], "groups": []}
            missing = [name for name in COLUMNS if name not in headers]
            extra = [name for name in headers if name not in COLUMNS]
            if missing:
                errors.append("Missing columns: " + ", ".join(missing))
            if extra:
                errors.append("Unexpected columns: " + ", ".join(extra))
            if missing or extra:
                return {"valid": False, "errors": errors, "groups": []}

            for line, row in enumerate(reader, start=2):
                if line - 1 > MAX_RATINGS:
                    errors.append(f"CSV exceeds {MAX_RATINGS} rating rows")
                    break
                if None in row:
                    errors.append(f"Line {line}: too many CSV fields")
                    continue
                if any(value is None for value in row.values()):
                    errors.append(f"Line {line}: too few CSV fields")
                    continue
                candidate = (row.get("candidate_id") or "").strip()
                dialect = (row.get("dialect") or "").strip().lower()
                listener = (row.get("listener_code") or "").strip()
                if not _safe_identifier(candidate, MAX_CANDIDATE_ID_LENGTH):
                    errors.append(f"Line {line}: candidate_id is empty, too long, or contains control/format characters")
                    continue
                if not _safe_identifier(listener, MAX_LISTENER_CODE_LENGTH):
                    errors.append(f"Line {line}: listener_code is empty, too long, or contains control/format characters")
                    continue
                if dialect not in DIALECTS:
                    errors.append(f"Line {line}: dialect must be msa or iraqi")
                    continue
                key = (candidate, dialect, listener)
                if key in seen:
                    errors.append(f"Line {line}: duplicate listener rating for {candidate}/{dialect}/{listener}")
                    continue
                seen.add(key)
                values = {}
                for field in SCORES:
                    raw = (row.get(field) or "").strip()
                    try:
                        score = int(raw)
                    except ValueError:
                        errors.append(f"Line {line}: {field} must be an integer from 1 to 5")
                        continue
                    if str(score) != raw or not 1 <= score <= 5:
                        errors.append(f"Line {line}: {field} must be an integer from 1 to 5")
                        continue
                    values[field] = score
                if len(values) == len(SCORES):
                    grouped[(candidate, dialect)].append(values)
    except (OSError, csv.Error) as exc:
        return {"valid": False, "errors": [f"Cannot read valid CSV: {exc}"], "groups": []}

    summaries = []
    for (candidate, dialect), ratings in sorted(grouped.items()):
        summaries.append({
            "candidate_id": candidate,
            "dialect": dialect,
            "listener_count": len(ratings),
            "median_scores": {
                field: statistics.median(item[field] for item in ratings)
                for field in SCORES
            },
            "minimum_listener_count_met": len(ratings) >= 3,
            "decision": "MANUAL_REVIEW_REQUIRED",
        })
    if not summaries:
        errors.append("No complete valid ratings found")
    return {
        "valid": not errors,
        "errors": errors,
        "groups": summaries,
        "notice": "Ratings are descriptive only; this tool never selects or approves a voice.",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--output", type=Path, help="Optional JSON summary destination")
    args = parser.parse_args(argv)
    result = validate(args.csv_file)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
