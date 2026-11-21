# Heed wake-word candidate review

Reviewed: 2026-11-21

## Decision

**Promote Heed to the primary experimental candidate for «يا NOID», but do not approve or integrate a model yet.** The upstream project is unusually suitable for a custom phrase: it is Apache-2.0, supports training a user-defined wake phrase, and exports ONNX and TFLite artifacts with a separate preprocessing contract. This makes it a feasible route to investigate without adopting a cloud-only detector.

Upstream project: https://github.com/AndreiBulzan/heed-wakeword

## Verified upstream capabilities

The upstream project currently documents:

- Custom phrase training, including CPU training; GPU is optional.
- ONNX FP32, ONNX INT8, and TFLite exports.
- A separate `wake.json` preprocessing/trigger contract.
- A small depthwise-separable CNN that consumes log-mel features.
- A streaming preprocessing chain described as causal high-pass filtering, 25 ms Hann window, 512-point FFT, 40-bin log-mel, and CMN.
- Android/iOS deployment examples through React Native, with a native Kotlin port described as possible by reproducing the reference preprocessing.
- Apache-2.0 licensing for the upstream project. Keep its LICENSE and NOTICE if code is ever copied.

The upstream README's latency, model-size, and cross-speaker claims are vendor/project claims, not NOID measurements. They must not be presented as results on the target phone.

## Arabic/Iraqi suitability: still unproven

Heed can train a custom phrase, but its documented pretrained examples are English phrases. The project does not publish an evaluated Arabic/Iraqi «يا NOID» model. Synthetic multi-speaker TTS augmentation may improve speaker diversity, but synthetic speech alone cannot establish Iraqi pronunciation fidelity, robustness to real speakers, or a sufficiently low false-activation rate.

Train and test the exact spoken phrase with consented Iraqi Arabic speakers. Preserve separate speaker-disjoint training, calibration, and final-test sets. Include natural pronunciation variants only when they preserve the intended wake phrase; do not silently broaden the trigger vocabulary.

## Licensing and privacy gates

- The upstream code license is Apache-2.0; preserve LICENSE and NOTICE for any copied code.
- Re-check the exact pinned release and every dependency before adding them to Android.
- Review each optional TTS voice/model license separately. The project's general dependency statement does not automatically grant rights to every downloaded voice or dataset.
- Keep user recordings and the evaluation corpus on the controlled local workstation. Do not upload them to Colab, a hosted trainer, or an external service.
- Do not commit recordings, voiceprints, trained weights, API keys, or private manifests to this public repository.
- Keep model artifacts in app-private storage after the user imports them; publish only non-sensitive metadata and test code.

## Proposed integration sequence

1. On a computer, pin a reviewed Heed commit/release and reproduce its training/export pipeline in an isolated workspace.
2. Train a first «يا NOID» experimental model using a small consented Iraqi dataset plus carefully licensed augmentation. Do not treat a single-speaker model as general-purpose.
3. Export ONNX FP32 and TFLite candidates. Record SHA-256, source revision, model format, tensor dimensions, sample rate, frame contract, preprocessing profile, threshold, and trigger parameters.
4. Feed new PCM chunks through `WakeWordStreamingFeaturePipeline`, which filters each sample once and maintains a rolling one-second window. Compare its feature tensor with one-shot filtering of the identical complete stream and with the pinned Heed reference vectors. Reject any unexplained numerical drift.
5. Add a real model-runtime adapter only after the exact graph input/output contract and Android runtime operators are verified. No dummy detector and no microphone capture.
6. Evaluate held-out speakers and at least eight hours of negative audio. Keep the model disabled unless every target in `VOICE_MODEL_VALIDATION_PLAN.md` passes.
7. Test arm64 inference, memory, latency, CPU, heat, and battery on the actual phone before enabling the user-started microphone service.

## Current NOID status

The repository contains the generic KWS contract, strict model-bundle integrity checks, a chunk-streaming Kotlin preprocessor with a rolling one-second window, and Heed-derived parity fixtures. It does **not** contain a trained Arabic model, an ONNX/TFLite inference adapter, or a verified microphone capture path. The wake phrase remains unavailable; user-triggered Android dictation is still the only operational voice-input path.
