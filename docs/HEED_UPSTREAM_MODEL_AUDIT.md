# Heed upstream demo model audit

Date: 2026-11-18  
Repository: https://github.com/AndreiBulzan/heed-wakeword  
Status: source inspection only; no model is imported or enabled.

## Result

The checked-in browser demo `wake.json` identifies its wake phrase as `hey doc` and uses threshold `0.667`. This is not «يا NOID» and is not an Arabic/Iraqi model. It must not be used as NOID's wake-word model.

## Integration hazards found

1. Metadata says `window_frames: 100`, but the reference preprocessor emits 101 frames, giving ONNX input shape `[1, 40, 101]`. Export/runtime metadata must resolve this discrepancy before a Kotlin port can be accepted.
2. Metadata lists `peak_normalize`, while the streaming reference implementation explicitly omits it because per-bin cepstral mean normalization removes a constant global scale. The exact model/export path must be validated against reference vectors.
3. The generated `filter-coeffs.js` supplies the actual SOS filter coefficients, Hann window, and sparse mel filterbank. `wake.json` alone is not a complete preprocessing specification.
4. The 16 kHz, 512 FFT, 400-sample window, 160-sample hop, 40-mel, 101-frame layout belongs to this export profile and must not be assumed for every model.

## Required bundle contract before implementation

A NOID model bundle must bind the model hash to a versioned preprocessing profile and provide:

- Exact wake phrase «يا NOID» and language/locale provenance.
- Sample rate, channels, frame/window sizes, FFT/window/hop settings.
- Mel filterbank definition, coefficient data/version/digest, log floor, CMN behavior, and tensor layout.
- Explicit resolution of the frame-count convention and all input/output tensor names and shapes.
- Trigger threshold, consecutive-frame rule, refractory period, and energy-gate parameters.
- Model SHA-256, exporter/version, license/provenance, and reference test vectors.

The importer must reject unknown preprocessing operations, inconsistent dimensions, missing coefficients, non-finite values, unsupported versions, and a phrase that does not exactly match the configured wake phrase. No defaults may silently fill missing model-specific parameters.

## Upstream source files inspected

- `examples/inference_browser/wake.json`
- `examples/inference_browser/preprocessing.js`
- `examples/inference_browser/filter-coeffs.js`
- `examples/inference_browser/verify-preprocessing.mjs`

## Decision

Heed remains a possible training/export framework only. Its checked-in English demo is rejected for NOID production use. No microphone capture, model activation, or roadmap completion is authorized by this audit.
