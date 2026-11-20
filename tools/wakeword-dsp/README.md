# Wake-word DSP v1 generator

This tool creates `<profile-id>.dsp.json` and `<profile-id>.dsp.bin` for the Android bundle importer.

## Environment

Use Python 3.12.x. Install the exact direct dependencies from this file:

```sh
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -r tools/wakeword-dsp/requirements-generation.txt
```

The generator verifies the installed Heed package version and immutable VCS commit through package `direct_url.json` metadata, plus exact NumPy 1.26.4, SciPy 1.14.1 and Torch 2.5.1 versions. NumPy 1.26.4 is pinned to keep the PyTorch 2.5.1 NumPy bridge on the NumPy 1.x ABI.

## Generate

```sh
python tools/wakeword-dsp/generate_dsp_v1.py --profile-id heed-ar-iq-v1 --output-dir ./out
```

Existing output files are never overwritten. The generator prints SHA-256 values for both outputs.

## Tests

The `tests/` suite builds real coefficients from the pinned Heed implementation and checks tensor shapes, finite float32 values, section offsets and hashes, payload size, profile-id validation, and repeated-run determinism. CI runs these tests independently from Android unit tests.

## Reproducibility boundary

This pins Python's supported major/minor family, direct numerical dependencies and upstream source revision. It does not yet provide a platform-specific transitive wheel hash lock or cross-platform byte-for-byte proof. Do not claim cross-platform bit identity until repeated clean-environment tests pass on the intended build host.

The output is DSP coefficients only, not a trained Arabic wake-word model. It does not prove Kotlin parity, inspect a model graph, capture audio or authorize microphone use.
