# NOID wake-word model bundle contract

Status: bounded manifest parsing, model-byte verification, profile digest and scalar semantic validation, and DSP descriptor/payload validation are integrated into the bundle importer. This is a fail-closed inspection boundary, not a production wake-word runtime and does not enable microphone capture.

## Bundle layout

A candidate bundle is staged in the app-private wake-word models directory:

- `manifest.json`: bounded UTF-8 JSON manifest (maximum 32 KiB).
- The exact ONNX or TFLite model filename declared by `modelFileName`.
- `<preprocessingProfileId>.json`: Heed-style preprocessing profile (1–64 KiB).
- `<preprocessingProfileId>.dsp.json`: version-1 DSP artifact descriptor (1–16 KiB).
- `<preprocessingProfileId>.dsp.bin`: little-endian IEEE754 float32 coefficient payload (1 byte–1 MiB).

All five files must resolve directly inside the same canonical private directory. Traversal and symlink escapes are rejected. Descriptor and payload names are derived from the validated profile ID; the descriptor must repeat the exact payload filename.

## Manifest and model checks

The manifest binds schema version, safe model filename, byte length and SHA-256, wake phrase «يا NOID», locale `ar-IQ`, audio geometry, tensor dimensions, ordered preprocessing operations, profile identity and digest, model tensor names, trigger settings, license identifier and exporter version.

The parser enforces the exact key set, UTF-8 and 32 KiB bounds, strict types, integer-valued numeric fields, and policy ranges. The model verifier checks the exact filename, private-directory location, declared byte count, 512 MiB cap, and SHA-256 incrementally with a 64 KiB buffer.

## Preprocessing profile checks

The importer verifies the profile file's bounded size and SHA-256, decodes UTF-8 strictly, then calls `WakeWordPreprocessingProfileParser`.

The semantic parser checks exact root and nested key sets, phrase and scalar geometry against the manifest, operation order, mel bounds, high-pass metadata (100 Hz, order 8, 50/60 Hz notches), trigger and energy-gate metadata, and bounded model/export metadata.

## DSP artifact checks

The importer now requires both DSP files and invokes `WakeWordDspArtifactParser` after profile validation. The parser enforces:

- Exact descriptor schema and version, profile ID, payload filename, byte length, little-endian float32 format, and geometry matching the parsed profile.
- Whole-payload and per-section SHA-256 digests.
- Exactly five unique stage labels in canonical order, positive dimensions, overflow-safe element counts, contiguous offsets without gaps/overlaps/trailing bytes, and finite float values.
- Descriptor and payload size caps, strict UTF-8, and private-directory direct-child path checks.

These checks validate the integrity and exact v1 tensor geometry of coefficient data only. They do not prove that coefficient values are numerically correct, complete for the production DSP pipeline, or equivalent to the upstream reference. Parser and importer tests use synthetic finite values with exact tensor shapes to exercise structure only; they are not usable coefficients.

## Pinned upstream reference

The current analysis baseline is `AndreiBulzan/heed-wakeword` commit `643a93c1e6702640c515475500f2e2653bd7d621` (package version `0.1.4`, Apache-2.0). Its source describes 16 kHz audio, FFT 512, window 400, hop 160, 40 mel bins, 101 centered frames, causal 8th-order 100 Hz Butterworth high-pass plus 50/60 Hz Q=30 notches, periodic Hann, power-mel projection, log floor 1e-9 and per-bin CMN. See `docs/NOID_WAKE_WORD_DSP_ARTIFACT_V1.md` for source files, exact behavior and unresolved export-format choices.

The upstream dependency declarations are lower bounds, not a lockfile. The source revision is pinned but the numerical generation environment is not. No production artifact may be generated until exact dependencies and artifact packing are pinned.

## Reference parity still required

Create deterministic reference vectors from the pinned upstream Python implementation and compare the Kotlin implementation at intermediate stages and final log-mel output with documented tolerances. Include silence, impulse, tones around the 50/60/100 Hz filter boundaries, seeded noise, and representative speech. Validate whole-window and chunked streaming equivalence, including retained filter state and reset. Resolve the training/inference peak-normalization difference against the exact exported model.

## Tensor graph and runtime gate

The current importer does not inspect the ONNX/TFLite graph. A separate gate must validate the real graph's input/output names, element types, dimensions, output semantics (logit versus probability), and runtime compatibility. It must use the exact model hash from the manifest and must not infer graph shape from metadata alone.

A bundle that passes current inspection is only **eligible for later runtime validation**. It is not declared runtime-ready. No model is loaded, no audio is captured, and no microphone permission is granted by this code.

## Safety boundary

No Arabic/Iraqi pretrained model has been integrated or validated. No acoustic detector, ONNX/TFLite runtime invocation, microphone capture, false-trigger measurement, battery test, or target-phone performance result is claimed. Microphone activation remains fail-closed until a real local KWS model, preprocessing parity, runtime compatibility, and foreground-service permission gates have all been verified.
