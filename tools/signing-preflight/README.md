# Release signing preflight

This standard-library-only helper checks that all four release-signing environment variables are present and that the keystore path identifies a non-empty regular file outside the repository. It rejects a keystore symlink and never prints secret values.

Run from the checkout:

```bash
python tools/signing-preflight/preflight.py
```

The four values must be supplied by a local operating-system secret manager or another protected secret store. Do not put them in Git, `.env` files, shell history, command-line arguments, or logs.

This is a preflight only. It does not create, inspect, unlock, sign with, copy, or upload a keystore; it does not build an APK; and it does not prove the keystore is encrypted or backed up. A successful result is not release approval. Actual signing remains blocked until the project is complete and the user explicitly approves a private release.

Run tests:

```bash
python -m unittest discover -s tests -v
```
