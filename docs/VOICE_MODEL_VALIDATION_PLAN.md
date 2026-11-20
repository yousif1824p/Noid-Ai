# Local wake-word validation plan

Status: planned; no Arabic/Iraqi model has passed this gate.

Target phrase: «يا NOID» (Iraqi Arabic). Inference must remain on-device.

## Dataset and consent

- Collect only recordings from speakers who explicitly consent to this specific wake-word evaluation.
- Keep raw recordings local to the controlled evaluation workspace; do not upload them to a hosted trainer or third-party service.
- Separate speakers between training, calibration, and final test sets. Never split clips from one speaker across train and final test.
- Include natural variations: distance, quiet/normal speech level, fast/slow delivery, different rooms, and common household background noise.
- Negative audio must include silence, ordinary Iraqi Arabic conversation, television/music, and phrases acoustically similar to the wake phrase.
- Record the dataset manifest, consent status, model hash, training configuration, and evaluation script version. Do not commit personal recordings or voiceprints to Git.

## Minimum evaluation set

- Positive set: at least 300 phrase utterances from at least 10 consenting speakers, with at least 4 speakers held out entirely from training.
- Negative set: at least 8 hours of varied audio, with at least 2 hours from held-out speakers and realistic background sound.
- Report results per speaker and per acoustic condition, not only one aggregate score.

## Provisional acceptance thresholds

These are engineering targets, not measured results:

| Metric | Required target |
|---|---:|
| Recall on held-out speakers | >= 95% |
| False activations | <= 0.1 per hour |
| Median trigger latency after phrase end | <= 300 ms |
| 95th-percentile trigger latency | <= 500 ms |
| Crash / unrecoverable stream errors | 0 during a 30-minute session |
| Network audio transmission | 0 bytes |

A model failing any threshold remains disabled. Threshold tuning must use calibration data only; final test data must remain untouched until the configuration is frozen.

## Android runtime gate

1. Verify model provenance, license, redistribution rights, and dependency licenses.
2. Verify ONNX/TFLite operator compatibility and ABI support on the target Android device.
3. Compare preprocessing output against the reference implementation with known test vectors.
4. Benchmark CPU utilization, memory, thermal behavior, latency, and battery over a controlled 30-minute run.
5. Confirm no network permission or network code is required by the inference path.
6. Only then connect the runtime to the KWS contract and microphone foreground service.

## Microphone service safety gate

- Start only from an explicit in-app user action while the activity is visible and after RECORD_AUDIO is granted.
- On Android 14+, declare the microphone foreground-service type and its permission; handle platform start restrictions and SecurityException.
- Show an ongoing notification with a clear stop action. Stop capture immediately on user request, permission loss, service failure, or runtime shutdown.
- Do not start at boot, silently restart, or claim guaranteed operation with the screen off.
- Never store raw audio by default. Keep audio frames in bounded memory and pass them directly to the local KWS runtime.
- Do not implement or enable this service until a validated local KWS runtime is available to consume frames.

## Current decision

The existing provider-neutral KWS contract remains unconnected. User-triggered Android dictation remains the only voice-input feature. This plan does not claim a model, microphone service, or wake phrase is operational.
