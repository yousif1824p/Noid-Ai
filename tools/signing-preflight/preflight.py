#!/usr/bin/env python3
"""Fail-closed preflight for NOID's locally supplied release-signing environment."""
from __future__ import annotations

import argparse
import os
import stat
import sys
from pathlib import Path
from typing import Mapping

REQUIRED = (
    "NOID_RELEASE_STORE_FILE",
    "NOID_RELEASE_STORE_PASSWORD",
    "NOID_RELEASE_KEY_ALIAS",
    "NOID_RELEASE_KEY_PASSWORD",
)


def validate_signing_environment(
    env: Mapping[str, str], repository_root: Path
) -> list[str]:
    """Return safe diagnostic messages; never include secret values."""
    missing = [name for name in REQUIRED if not env.get(name, "").strip()]
    if missing:
        return ["missing required variables: " + ", ".join(missing)]

    raw_path = Path(env["NOID_RELEASE_STORE_FILE"]).expanduser()
    try:
        repo_path = repository_root.resolve(strict=True)
    except (OSError, RuntimeError):
        return ["repository root cannot be resolved"]

    try:
        if raw_path.is_symlink():
            return ["keystore must not be a symbolic link"]
        store_path = raw_path.resolve(strict=True)
    except FileNotFoundError:
        return ["keystore file is not accessible"]
    except (OSError, RuntimeError):
        return ["keystore path cannot be resolved"]

    try:
        store_path.relative_to(repo_path)
    except ValueError:
        pass
    else:
        return ["keystore must be stored outside the repository"]

    try:
        metadata = store_path.stat()
    except OSError:
        return ["keystore file is not accessible"]

    if not stat.S_ISREG(metadata.st_mode):
        return ["keystore path must identify a regular file"]
    if metadata.st_size == 0:
        return ["keystore file is empty"]
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (defaults to this checkout)",
    )
    args = parser.parse_args(argv)
    errors = validate_signing_environment(os.environ, args.repo_root)
    if errors:
        for error in errors:
            print("signing preflight: " + error, file=sys.stderr)
        return 1
    print("signing preflight passed; secret values were not displayed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
