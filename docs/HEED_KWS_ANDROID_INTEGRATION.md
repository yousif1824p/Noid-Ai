# Heed KWS Android integration

The Android implementation validates the model bundle, preprocessing profile, and DSP payload before a runtime may use them. A successful import is not evidence of wake-word accuracy or device compatibility.

## DSP payload v1

The payload uses fixed 16 kHz mono geometry and five dense float32 sections in canonical order: high-pass SOS, 50 Hz notch SOS, 60 Hz notch SOS, Hann window, and mel filterbank. The descriptor parser verifies the exact shapes, contiguous offsets, section digests, whole-payload digest, byte order, and finite values.

## Android filter-to-feature pipeline

`WakeWordFeaturePipeline` composes the three SOS cascades in reference order (100 Hz high-pass, 50 Hz notch, 60 Hz notch) before STFT/log-mel/CMN. Filter state persists across windows and can be reset for an independent stream.

`WakeWordFeaturePipeline.fromValidatedArtifact` now binds the exact payload accepted by the descriptor parser to the runtime pipeline. It rechecks the whole-payload SHA-256, canonical section order, exact tensor shapes, contiguous section offsets, finite float values, Hann range, and non-negative mel weights. It decodes all five sections and retains the decoded Hann and mel arrays inside a configured processor, preventing callers from accidentally pairing a pipeline with unrelated arrays.

Unit tests cover artifact-to-pipeline decoding, payload mismatch rejection, missing/reordered sections, feature output, state retention, and reset. The tests use deterministic synthetic coefficient and feature fixtures; they do not prove production coefficient provenance or acoustic accuracy.

## Remaining validation boundary

The current fixture is synthetic. It is not a real Heed-generated coefficient payload and does not prove numerical parity with the pinned Heed implementation or a real exported model. No Arabic/Iraqi wake-word model inference, microphone capture, false-accept/false-reject evaluation, or target-device latency, battery, and thermal measurement is claimed. Those remain required before enabling local wake-word operation.
