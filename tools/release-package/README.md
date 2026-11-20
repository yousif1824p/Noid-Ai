# Private release package integrity

Create and verify a deterministic SHA-256 inventory for a prepared private release folder. This utility does not build, sign, upload, publish, or distribute an APK.

Create:
```bash
python manifest.py create /path/to/private-release
```

Verify after copying/restoring:
```bash
python manifest.py verify /path/to/private-release
```

The manifest records each regular file's relative path, byte size, and SHA-256 digest. Verification fails for missing, added, changed, symlinked, or non-regular files and for malformed, duplicate, or traversal paths. The manifest excludes itself.

Keep the release folder and signing-key backup encrypted and local. A checksum detects accidental changes; it does not prove authorship or replace a digital signature.

Run tests:
```bash
python -m unittest discover -s tests -v
```
