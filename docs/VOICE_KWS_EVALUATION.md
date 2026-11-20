# Voice wake-word runtime evaluation

Reviewed: 2026-11-18

## Decision

No production Arabic/Iraqi wake-word model is approved or bundled. Keep `KeywordSpottingRuntime` disconnected and keep microphone capture disabled until a real model artifact passes the acceptance gates below on the target Android device.

## Candidate assessment

### Heed Wake Word — open-source prototype candidate

- Project: https://github.com/AndreiBulzan/heed-wakeword
- The project documents ONNX/TFLite export and mobile/Android deployment paths, and custom phrase training.
- Its documented ready-made examples do not establish an Arabic/Iraqi pretrained model or measured results for «يا NOID».
- Decision: the most practical open-source route to investigate for a locally trained prototype. It is not approved for integration until the exact trained artifact, operator compatibility, license/provenance, and held-out Iraqi Arabic results are available.

### Voicute ONNX Wake Word

- Project: https://github.com/voicute/onnx-wakeword
- The documented language list does not include Arabic.
- Decision: reject for «يا NOID» unless the project supplies verifiable Arabic support and a compatible model.

### Picovoice Porcupine

- Android supports custom `.ppn` keyword files and a language-specific `.pv` model. The SDK requires an AccessKey.
- The current official Model API language table lists English, French, German, Italian, Japanese, Korean, Mandarin Chinese, Portuguese, and Spanish; Arabic is not listed. Older repository release notes mention historical Arabic support, but that does not establish that the current custom-model training API can produce an Arabic Android model.
- Decision: do not integrate or add credentials. Reconsider only if Picovoice confirms in writing that it can provide a custom «يا NOID» model for Arabic/Iraqi, Android, and the intended personal-use licensing terms.

## Required work before integration

1. Obtain a real Arabic/Iraqi model artifact for the exact phrase «يا NOID» and record its source, license, SHA-256, runtime version, sample rate, frame size, and preprocessing requirements.
2. Verify the model can run locally on Android arm64 without network access during inference. No secrets or model weights are committed to Git.
3. Collect consented recordings locally. Keep speakers disjoint between training, calibration, and final test. Minimum: 300 positive utterances from at least 10 speakers (at least 4 held out) and 8 hours of varied negative audio.
4. Compare model preprocessing against reference test vectors and measure held-out recall, false activations, trigger latency, crashes, memory, CPU, thermal behavior, and battery on the actual target phone.
5. Only after the model and measurements pass, connect it to `KeywordSpottingRuntime`, then implement the user-started microphone foreground service and immediate-stop path. Do not start capture before these gates.

## Acceptance thresholds

These are engineering targets, not measured results:

| Metric | Required target |
|---|---:|
| Recall on held-out speakers | >= 95% |
| False activations | <= 0.1 per hour |
| Median trigger latency after phrase end | <= 300 ms |
| 95th-percentile trigger latency | <= 500 ms |
| Crash / unrecoverable stream errors | 0 during a 30-minute session |
| Network audio transmission | 0 bytes |

Threshold tuning uses calibration data only. Keep the final test set untouched until configuration is frozen.

## Microphone and Android safety gate

- Start only from an explicit in-app user action while the activity is visible and after RECORD_AUDIO is granted.
- On Android 14+, declare the microphone foreground-service type and permission; handle platform restrictions and SecurityException.
- Show an ongoing notification with a clear stop action. Stop capture immediately on user request, permission loss, service failure, or runtime shutdown.
- Do not start at boot, silently restart, or claim guaranteed operation with the screen off.
- Do not store raw audio by default. Keep frames in bounded memory and pass them directly to the local runtime.
- Keep the service fail-closed until a validated model is integrated and device battery/thermal tests pass.

## Current status

The repository has a provider-neutral KWS contract, metadata/frame boundary checks, a fail-closed foreground-service shell, and unit tests for policy. It does not contain an acoustic detector, a real Arabic model, a microphone capture pipeline, or target-device benchmark evidence. User-triggered Android dictation remains the only operational voice-input path.
