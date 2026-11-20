#!/usr/bin/env python3
"""Create and verify a SHA-256 manifest for a prepared private release folder."""
import argparse, hashlib, json, os, re, stat, sys, tempfile
from pathlib import Path, PurePosixPath
MANIFEST_NAME = "NOID-MANIFEST.json"
SCHEMA_VERSION = 1
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
class ManifestError(ValueError): pass
def safe_path(value):
    if not isinstance(value, str) or not value or "\\\\" in value: raise ManifestError("invalid relative path")
    p = PurePosixPath(value)
    if p.is_absolute() or any(x in ("", ".", "..") for x in p.parts) or p.as_posix() != value: raise ManifestError("unsafe or non-canonical relative path")
    return value
def location(root, manifest):
    root = root.resolve(strict=True)
    if not root.is_dir(): raise ManifestError("release root must be a directory")
    original = Path(manifest) if Path(manifest).is_absolute() else root / manifest
    if original.is_symlink(): raise ManifestError("manifest must not be a symlink")
    target = original.resolve(strict=False)
    try: rel = target.relative_to(root).as_posix()
    except ValueError as exc: raise ManifestError("manifest must be inside release root") from exc
    return target, safe_path(rel)
def scan(root, manifest_rel):
    result = []
    for current, dirs, files in os.walk(root, topdown=True, followlinks=False):
        folder = Path(current)
        for name in dirs:
            d = folder / name
            if d.is_symlink() or not d.is_dir(): raise ManifestError("unsafe directory entry: " + str(d.relative_to(root)))
        for name in files:
            f = folder / name; rel = f.relative_to(root).as_posix()
            if rel == manifest_rel: continue
            if f.is_symlink(): raise ManifestError("symlink not allowed: " + rel)
            try:
                if not stat.S_ISREG(f.stat(follow_symlinks=False).st_mode): raise ManifestError("non-regular file: " + rel)
                h = hashlib.sha256(); size = 0
                with f.open("rb") as stream:
                    while True:
                        chunk = stream.read(1024 * 1024)
                        if not chunk: break
                        h.update(chunk); size += len(chunk)
            except OSError as exc: raise ManifestError("cannot read " + rel + ": " + str(exc)) from exc
            result.append({"path": rel, "size_bytes": size, "sha256": h.hexdigest()})
    return sorted(result, key=lambda x: x["path"])
def create_manifest(root, manifest=MANIFEST_NAME):
    root = root.resolve(strict=True); target, rel = location(root, manifest)
    doc = {"schema_version": SCHEMA_VERSION, "algorithm": "sha256", "files": scan(root, rel)}
    data = (json.dumps(doc, ensure_ascii=False, indent=2) + "\n").encode()
    target.parent.mkdir(parents=True, exist_ok=True); temp_name = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=target.parent, prefix=".noid-manifest-", delete=False) as out:
            temp_name = out.name; out.write(data); out.flush(); os.fsync(out.fileno())
        os.replace(temp_name, target)
    except OSError:
        if temp_name:
            try: os.unlink(temp_name)
            except OSError: pass
        raise
    return target
def verify_manifest(root, manifest=MANIFEST_NAME):
    root = root.resolve(strict=True); target, rel = location(root, manifest)
    if not target.is_file(): raise ManifestError("manifest file does not exist")
    try: doc = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc: raise ManifestError("invalid JSON manifest: " + str(exc)) from exc
    if not isinstance(doc, dict) or set(doc) != {"schema_version", "algorithm", "files"}: raise ManifestError("invalid manifest fields")
    v = doc["schema_version"]
    if isinstance(v, bool) or v != SCHEMA_VERSION or doc["algorithm"] != "sha256": raise ManifestError("unsupported manifest version or algorithm")
    if not isinstance(doc["files"], list): raise ManifestError("files must be an array")
    expected = {}
    for row in doc["files"]:
        if not isinstance(row, dict) or set(row) != {"path", "size_bytes", "sha256"}: raise ManifestError("invalid file entry")
        path = safe_path(row["path"]); size = row["size_bytes"]; digest = row["sha256"]
        if path == rel or path in expected: raise ManifestError("duplicate or self-referencing path: " + path)
        if isinstance(size, bool) or not isinstance(size, int) or size < 0: raise ManifestError("invalid size: " + path)
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest): raise ManifestError("invalid digest: " + path)
        expected[path] = (size, digest)
    actual_rows = scan(root, rel)
    actual = {x["path"]: (x["size_bytes"], x["sha256"]) for x in actual_rows}
    missing = sorted(expected.keys() - actual.keys()); extra = sorted(actual.keys() - expected.keys())
    if missing or extra: raise ManifestError(f"file set mismatch; missing={missing}, unexpected={extra}")
    changed = sorted(k for k in expected if expected[k] != actual[k])
    if changed: raise ManifestError("checksum or size mismatch: " + str(changed))
    return len(actual)
def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); subs = parser.add_subparsers(dest="command", required=True)
    for name in ("create", "verify"):
        p = subs.add_parser(name); p.add_argument("root", type=Path); p.add_argument("--manifest", default=MANIFEST_NAME)
    args = parser.parse_args(argv)
    try:
        if args.command == "create": print("Manifest created:", create_manifest(args.root, args.manifest))
        else: print("Verified", verify_manifest(args.root, args.manifest), "files.")
        return 0
    except (ManifestError, OSError) as exc: print("manifest error:", exc, file=sys.stderr); return 1
if __name__ == "__main__": raise SystemExit(main())
