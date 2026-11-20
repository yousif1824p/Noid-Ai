# Local vision runtime decision

## Decision

Use the pinned LiteRT-LM Android Kotlin API (0.18.1) as the first local multimodal runtime candidate. Implement vision as a separate runtime instance and a separate user-selected model file; do not assume the currently imported chat model supports images.

## API verification

The upstream `v0.18.1` source was inspected directly:

- `EngineConfig` exposes `visionBackend`; a null value means the vision executor is not initialized.
- `Content.ImageBytes(ByteArray)` and `Content.ImageFile(absolutePath)` are part of the Kotlin API.
- `Contents.of(...)` accepts mixed content, and `Conversation.sendMessageAsync(contents)` exposes streamed responses.
- The upstream Kotlin guide states that multimodal input works only with models that actually support it and gives Gemma 3n as an example.

This verifies that the pinned API surface can carry image input. It does **not** verify that an arbitrary `.litertlm` file contains a compatible vision encoder, that every quantized model variant works, or that the target phone can run it.

## Candidate model and loading policy

- Initial candidate family: a compatible Gemma 3n LiteRT-LM model. Treat this as a candidate, not a confirmed device-ready model.
- The user must select the vision model file through Android's Storage Access Framework. Keep it in app-private storage; never bundle model weights in the APK or download them silently.
- Keep the vision model selection independent from the text chat model. Persist only the successfully initialized model reference/name and recover gracefully if the file is missing or invalid.
- Configure the vision executor explicitly. Start with a conservative CPU backend for correctness; consider GPU/NPU only after measured compatibility on the target device.
- Reuse the existing bounded image reader and policies: user-granted `content://` URI only, JPEG/PNG/WebP, 12 MiB maximum, signature/MIME match, dimension/pixel caps, cancellation checks, and bounded prompt/response.
- Feed the validated bytes to `Content.ImageBytes`; do not pass arbitrary filesystem paths or weaken Android URI-grant boundaries.
- Treat image text and visual instructions as untrusted input. Vision output is advisory and must not execute tools or device actions automatically.
- Record model import, analysis start, and analysis outcome through the existing audit repository without logging image bytes, image paths, or sensitive prompt contents.

## Implementation status and remaining validation

Implemented in the Android client:

1. A separate user-selected multimodal model import/restore flow and a real `VisionEngine` adapter are integrated. The model is copied into app-private storage with bounded streaming and free-space checks; the last successfully initialized model reference is persisted.
2. The runtime configures a dedicated CPU vision backend and sends validated image bytes using `Content.ImageBytes`. Initialization is reported separately from confirmed multimodal capability; only a successful completed image inference can establish that the selected model works.
3. Image reads are limited to the user's granted `content://` URI and the existing JPEG/PNG/WebP, byte-size, signature, MIME, dimension, and prompt limits. The adapter does not register tools and treats image contents as untrusted.
4. Stream completion is validated by `VisionResponseCollector`; model-file naming policy and other vision input/response boundaries have unit tests. Model import and image-analysis attempts are connected to the local audit repository without recording image bytes or prompt contents.
5. Failed candidate initialization now attempts to close partially initialized native runtime resources before propagating the failure.

Still required before calling vision production-ready:

- Add focused tests for replacement/restore behavior, import cancellation and failure cleanup, and audit outcomes. The filename-collision guard and partially initialized runtime cleanup are implemented; remaining lifecycle and audit-path coverage is still pending.
- Verify native library loading and perform real image inference on the target Android phone with a known test image and a compatible Gemma 3n LiteRT-LM model.
- Measure peak memory, inference latency, thermal behavior, and battery impact on that device.
- Confirm the exact supported model variant and validate actual streamed text output on-device. API support alone does not establish model compatibility.

No target-device readiness or successful image inference is claimed yet.

## Upstream references

- LiteRT-LM Kotlin API guide: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/docs/api/kotlin/getting_started.md
- Kotlin `EngineConfig` and backend definitions: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt
- Kotlin `Content.ImageBytes` and `Content.ImageFile`: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.1/kotlin/java/com/google/ai/edge/litertlm/Message.kt
