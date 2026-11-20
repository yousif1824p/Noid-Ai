# NOID wake-word DSP artifact v1

Status: **structural validation is implemented and integrated into the bundle importer**. The upstream reference is now pinned for analysis. Production coefficient generation, the Kotlin DSP decoder, reference parity, and target-device inference remain incomplete. Test data is synthetic and unusable for wake-word detection.

## Pinned reference provenance

- Repository: `https://github.com/AndreiBulzan/heed-wakeword`
- Immutable source revision: `643a93c1e6702640c515475500f2e2653bd7d621`
- Upstream package version at that revision: `0.1.4`
- License: Apache-2.0 (upstream `LICENSE` file).
- Primary scalar/DSP sources: `heed/__init__.py`, `heed/audio.py`, `tools/gen_filter_coeffs.py`, `examples/inference_browser/filter-coeffs.js`, `examples/inference_browser/preprocessing.js`, and `examples/inference_browser/verify-preprocessing.mjs`.

The upstream project declares lower bounds rather than an exact dependency lock (`torch>=2.0`, `numpy>=1.20`, `scipy>=1.10`, among others). Therefore the source revision is pinned, but a reproducible coefficient-generation environment is **not yet pinned**. Before generating a production artifact, capture exact Python and dependency versions plus hashes of generated outputs. Do not imply that a source SHA alone makes SciPy/Torch numerical output reproducible.

## Reference pipeline facts at the pinned revision

- Scalar geometry: 16,000 Hz, 1-second window, FFT 512, Hann window length 400, hop 160, 40 mel bins, 257 one-sided FFT bins, and 101 centered frames.
- Filtering is causal and stateful: 8th-order Butterworth high-pass at 100 Hz, followed by 50 Hz and 60 Hz notch filters (Q=30). SOS state is initialized using `scipy.signal.sosfilt_zi(sos) * first_input_sample_of_that_stage`; state is retained across chunks and reset between independent streams.
- The upstream generator emits each SOS row as five coefficients `b0,b1,b2,a1,a2` (normalized `a0=1`) and separately emits two initial-state values `zi0,zi1` per SOS row.
- Filter row counts at the pinned default geometry are four high-pass SOS rows and one row for each notch stage. These counts must be asserted by the generator and parity tests, not trusted from untrusted descriptor metadata.
- The Hann window is the exact periodic `torch.hann_window(400)` output.
- The mel bank is built as a dense 40 × 257 matrix in Python; the JS generator emits only non-zero `[fft_bin, weight]` pairs. The current binary format has no sparse-index encoding. A production exporter must either encode a dense 40 × 257 float matrix (including explicit zeros) or version a rigorously bounded sparse representation; it must not silently discard bin indices.
- Feature extraction uses power spectrum `real² + imag²`, matrix projection, `log(max(mel, 1e-9))`, then per-mel-bin mean subtraction over all 101 frames.
- STFT uses centered framing and reflect padding. The deployment JS implementation uses a 512-point radix-2 FFT and places the 400-sample periodic Hann window at offset 56.
- The upstream JS reference explicitly omits inference-time peak normalization on the stated basis that log-mel CMN removes constant gain. Training preparation does include peak normalization. This is a train/inference equivalence claim that must be verified against the exact exported model and reference vectors before adoption; metadata alone is not proof.

These are facts read from the pinned source, not a claim that a Kotlin implementation or a production artifact has already passed parity.

## Purpose and bundle files

The scalar `wake.json` profile does not contain the complete numeric DSP state. The app-private bundle therefore requires:

- `manifest.json`: bounded UTF-8 model manifest.
- The exact model file declared by the manifest.
- `<profile-id>.json`: scalar preprocessing profile.
- `<profile-id>.dsp.json`: version-1 DSP descriptor.
- `<profile-id>.dsp.bin`: little-endian float32 coefficient payload.

All files must be direct canonical children of the same private directory. The importer rejects traversal and symlink escapes.

## Descriptor schema

The descriptor is UTF-8 JSON, at most 16 KiB, with an exact key set:

- `schema_version`: integer, exactly 1.
- `profile_id`: exact match to manifest and profile.
- `payload_file`: exact filename `<profile-id>.dsp.bin`.
- `payload_bytes`: positive integer equal to the payload length; payload maximum 1 MiB.
- `payload_sha256`: 64 hexadecimal characters over the entire payload.
- `byte_order`: exactly `little_endian`.
- `scalar_format`: exactly `ieee754_float32`.
- `sample_rate_hz`, `fft_size`, `window_samples`, `hop_samples`, `mel_bins`: exact integer matches to the parsed profile and manifest.
- `sections`: exactly five ordered entries, one each of `highpass_sos`, `notch_50hz_sos`, `notch_60hz_sos`, `hann_window`, and `mel_filterbank`.

Each section has exactly `name`, `offset_bytes`, `element_count`, `shape`, and `sha256`. Section names must be unique and in canonical order. Shape dimensions are positive, their overflow-safe product equals elementCount, and each section's digest covers its exact byte range.

## Binary layout and validation

- Every element is IEEE-754 binary32 in little-endian order.
- Each section occupies exactly `element_count * 4` bytes.
- Sections begin at offset zero, are contiguous, do not overlap, and end exactly at payload length. Padding and trailing bytes are rejected.
- The whole payload digest and each section digest are mandatory.
- Every decoded float must be finite.
- Unknown keys, unknown or duplicate stages, missing stages, unsupported version/format, malformed numeric fields, bad geometry, invalid sizes, digest mismatches, and invalid offsets/shapes are rejected.
- Descriptor and payload are bounded before parsing; importer checks direct-child canonical paths.

The parser now enforces the exact v1 stage shapes and 42,888-byte payload geometry defined in `docs/NOID_WAKE_WORD_DSP_PAYLOAD_V1.md`. It still validates container structure and finite values only: it does not establish coefficient provenance, numeric correctness, SOS row semantics, or parity with the upstream implementation. Those require a deterministic generator and golden-vector tests.

## Generation and parity gates still required

1. Create a locked generation environment for the pinned source revision: exact Python, Torch, NumPy, SciPy and relevant transitive versions; record lockfile hash and generator source hash.
2. Implement a deterministic exporter with explicit stage shapes and packing. For SOS data, specify coefficient and initial-state ordering and test round-trip decoding. For mel data, specify dense or sparse representation including all indices/weights.
3. Regenerate twice in clean environments and require identical descriptor and payload hashes. If dependency/platform variation prevents bit-identical results, document and test a defined numeric tolerance instead of claiming byte reproducibility.
4. Generate reference vectors using the pinned Python code for silence, impulse, 50/60/100 Hz boundary tones, seeded noise, and consented representative speech.
5. Compare every intermediate stage and final log-mel tensor against the Kotlin implementation with documented tolerances.
6. Prove whole-window and chunked-streaming equivalence, including state retention, chunk boundaries, and reset.
7. Resolve peak-normalization training/inference parity for the exact model.
8. Validate the real model graph's tensor names, types, dimensions, and output semantics against the exact model hash.

## Explicit non-claims

No production coefficients or decoder are present. No DSP parity has been proven. The importer does not load the model, run an acoustic detector, capture audio, grant microphone permission, or declare wake-word detection ready. The importer test fixture contains five tiny synthetic sections solely to exercise structural validation; it is not a usable coefficient set.
