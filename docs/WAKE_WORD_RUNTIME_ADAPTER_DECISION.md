# Wake-word runtime adapter decision

Status: architecture decision; no acoustic model is bundled or enabled.

## Current repository boundaries

- `KeywordSpottingRuntime.processAudioFrame` accepts one PCM16 `ShortArray` whose length must equal `KeywordModelDescriptor.frameLengthSamples`.
- `WakeWordFeaturePipeline` accepts one second of 16 kHz mono floating-point samples and produces a mel-major log-mel/CMN feature tensor with 40 bins and 101 frames.
- `WakeWordModelBundleImporter` verifies bundle files, manifest metadata, preprocessing profile, and DSP artifact integrity. Its successful result is only `READY_FOR_RUNTIME_VALIDATION`; it does not inspect or execute an ONNX/TFLite graph.
- `VoiceForegroundService` intentionally stops an ACTION_START request. It does not capture microphone audio.

These are separate validated components, not yet a connected inference path.

## Decision

Keep `KeywordSpottingRuntime` as the single public PCM-frame boundary. Do not add a second public runtime interface that accepts feature tensors.

When a real model is supplied, implement one internal adapter behind that contract. The adapter must be selected from the actual model graph and its documented preprocessing:

1. If the model graph consumes PCM, the adapter may feed bounded PCM frames directly and must not run the log-mel pipeline unnecessarily.
2. If the model graph consumes log-mel features, the adapter must buffer PCM frames into the exact window/hop geometry required by the model, run the validated feature pipeline, and convert the resulting tensor to the exact input name, type, shape, layout, and normalization required by the graph.
3. If the graph geometry or preprocessing cannot be proven from the exact model artifact and its provenance, initialization must fail closed. Manifest strings alone are not proof of graph compatibility.

The adapter must also validate the exact model SHA-256 before initialization, verify graph input/output metadata using the selected runtime, define whether output is a logit or probability, and reject non-finite or out-of-range scores before producing a detection event.

## Required model handoff data

The future model bundle must provide:

- Exact model artifact and SHA-256; keep weights out of Git unless redistribution is explicitly permitted.
- Source, immutable version/revision, license, and redistribution/use conditions.
- Runtime format (ONNX or TFLite), runtime version, supported Android ABI and required operators.
- Exact input/output tensor names, element types, dimensions, and output meaning.
- Sample rate, channel count, PCM frame/window/hop sizes, feature layout, normalization, and any state/reset requirements.
- Trigger threshold and any smoothing/energy-gate settings, with their calibration provenance.

## Integration and safety gates

- Unit-test the PCM buffering, window boundaries, overlap/hop behavior, stream reset, cancellation, and buffer zeroization.
- Compare DSP outputs with reference vectors from the exact model-generation pipeline; synthetic fixtures are not production parity evidence.
- Test graph metadata and inference using the exact model hash on the target Android ABI.
- Evaluate held-out Iraqi Arabic speakers and negative audio against `docs/VOICE_KWS_EVALUATION.md` thresholds.
- Measure latency, memory, CPU, thermal behavior, battery, and network bytes on the target phone.
- Only after those gates pass may the user-started microphone foreground service be connected. Permission grants alone must never start capture; the user must explicitly start while the app is visible, and a visible stop action must stop capture immediately.

## Explicit non-claims

This decision does not provide a model, acoustic detector, inference engine, microphone capture, or device-readiness result. It does not authorize microphone activation. User-triggered Android dictation remains the available voice-input path until the complete validation gates pass.
