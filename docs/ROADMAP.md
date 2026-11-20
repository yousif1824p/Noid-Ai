# Development Roadmap

## Stage 0 — repository foundation
- [x] Establish project purpose and safety boundaries.
- [x] Record initial architecture decisions.
- [x] Add secret-safe ignore rules and APK signing policy.

## Stage 1 — Android application skeleton
- [x] Add a minimal native Android application module.
- [x] Add a Compose chat shell.
- [x] Add unit-test foundations.
- [x] Add a CI workflow for unit tests only; APK packaging is disabled during development.
- [x] Verify compatible toolchain versions through a successful CI run (Gradle 8.14.4, JDK 21 toolchain with Java 17 bytecode target, Android SDK platform 36 setup, Kotlin compilation, and unit tests).
- [x] Add horizontally scrollable, screen-reader-labelled navigation with selected-state semantics, plus Robolectric Compose UI tests for destination selection and accessibility labels.

## Stage 2 — local data and model interfaces
- [x] Add Room chat entity, DAO, and repository.
- [x] Add lifecycle-aware chat ViewModel.
- [x] Add confirmed local conversation deletion.
- [x] Add bounded, case-insensitive local conversation search with newest-first results and repository tests.
- [x] Add Room v1-to-v2 migration for the user-memory table.
- [x] Add explicit memory repository operations and unit tests.
- [x] Add user-facing memory approval, editing, and deletion screens.
- [x] Connect memory search to a bounded Room query (120-character query, 100-result limit) with cancellation and stale-result protection.
- [x] Bound assistant memory context to the 20 newest approved memories and 12,000 combined characters without modifying stored records.
- [x] Evaluate indexed FTS5 retrieval and availability on supported Android versions; see `docs/FTS5_EVALUATION.md`. The current Room 2.8.5/framework-SQLite setup does not provide a verified FTS5 path, so indexed adoption remains deferred until a bundled-driver/Room upgrade is validated.
- [x] Define the model/runtime interface and explicit availability/stream event contracts.
- [x] Add a model runtime status/setup screen that clearly reports the unconfigured state without offering a fake activation path.
- [x] Integrate the pinned LiteRT-LM Android Kotlin runtime, user-selected private model import, bounded model-file copying, background initialization, bounded chat/memory context, and streaming adapter.
- [ ] Verify native runtime compilation and execute on the target phone with a compatible model; do not claim device readiness before this test.
- [ ] Evaluate on-device inference on the target phone before selecting a model and tune CPU/GPU only from measured compatibility.

## Stage 3 — assistant capabilities
- [x] Add initial conversation orchestration, memory/context handoff, stream completion validation, and cancellation handling.
- [x] Add the initial tool authorization policy and Room audit-event foundation.
- [x] Add a shared tool execution coordinator that enforces authorization, requires a persisted pre-execution audit event, records completion/failure, and propagates cancellation; concrete tool adapters still require integration.
- [x] Route the user-authorized project source scanner through the shared coordinator, deriving READ_FILES only from the selected tree's persisted Android SAF read grant and retaining the in-scan scope revalidation.
- [x] Route APK manifest inspection and report export through the shared coordinator, deriving READ_FILES/WRITE_FILES only from Android URI grants for the exact user-selected source/destination.
- [x] Add authorization matrix tests proving sensitive/destructive actions require confirmation and capability grants cannot be substituted.
- [x] Wire all executable tool adapters through authorization and audit logging; verified seven model-callable adapters through `ToolRegistry` and all twelve user-started workflow actions through `executeUserAction`, with registry-wide authorization/audit regression tests.

  Current inventory and the remaining central-registry gap are tracked in `docs/TOOL_EXECUTION_INVENTORY.md`; do not mark this item complete until UI-triggered and model-triggered execution paths have one tested, consistent authorization/audit boundary.
- [x] Show the exact relative path in the project-file approval dialog while keeping selection tokens and file contents hidden; add bounded-path/control-character regression tests.
- [x] Add a provider-neutral, fail-closed proposal validation contract: registered action IDs only, bounded string arguments, and rejection of model-supplied privilege/execution fields; add unit tests. This is not yet wired to inference.
- [x] Bound pending/approved tool tickets to eight, purge expired entries before capacity checks, and reject overflow without crashing the UI; add regression tests.
- [x] Connect LiteRT-LM structured tool calls to the pending-approval flow, await the user decision, and return a tool response to the same conversation; see `docs/TOOL_CALL_INTEGRATION.md`. Runtime model/device validation remains pending.
- [x] Add deterministic tests for model tool handoff registration, single-claim approval, rejection, timeout cleanup, generation cancellation, and duplicate approval IDs.
- [x] Define a separate optional vision-engine contract, bounded policy for user-selected JPEG/PNG/WebP images (12 MiB maximum), strict completed-response collector with a 32,000-character output cap, non-empty completion requirement, and ContentResolver reader enforcing size, MIME/signature consistency, and cancellation while streaming.
- [x] Add a user-controlled Compose image picker with a bounded prompt and actual-byte validation; local analysis is enabled only after a separately imported vision runtime reports Ready, while target-device inference remains unverified.
- [x] Validate JPEG/PNG/WebP container signatures and inspect decoded image bounds before pixel allocation; cap dimensions at 8,192 and decoded pixel count at 40 megapixels, with pure-policy unit tests.
- [x] Add a voice setup/status screen that clearly reports the unconfigured state.
- [x] Define a provider-neutral synthetic speech-output contract and bounded Arabic request policy with unit-test coverage; no TTS provider or audio playback is integrated yet.
- [x] Review Arabic TTS candidates and record licensing, dialect, and target-device validation gates; no candidate is approved or integrated. See `docs/VOICE_TTS_CANDIDATE_REVIEW.md`.
- [ ] Select and validate a licensed gender-ambiguous synthetic Arabic TTS voice on the target device; integrate playback only after dialect, license, resource, and listening-test gates pass.
- [x] Add a static launcher shortcut that opens voice setup directly without requesting microphone access or starting capture; cover route validation with unit tests.
- [x] Add a pure voice-session gate requiring user initiation, visible app, microphone permission, foreground-service availability, and configured local KWS runtime, with unit tests.
- [x] Add a fail-closed microphone foreground-service preflight policy with API 28/34 permission gates, visible-activity/user-gesture requirements, notification-channel readiness, and local KWS readiness tests; this is policy only, not a running service.
- [x] Add explicit user-triggered Android system speech dictation fallback (Arabic-Iraq preference, offline preference disclosed as best-effort), bounded transcript text, and return-to-draft review without auto-send.
- [ ] Implement a user-started microphone foreground service with visible notification, runtime permission flow, immediate stop, and Android 14+ compliance.
- [x] Evaluate candidate KWS runtimes, licenses, Android paths, and Arabic/Iraqi validation requirements; document why no candidate is enabled before evidence exists. See `docs/VOICE_KWS_EVALUATION.md`.
- [x] Define a measurable Arabic/Iraqi wake-word evaluation dataset, consent, held-out speaker split, false-activation/recall/latency targets, and Android runtime safety gates. See `docs/VOICE_MODEL_VALIDATION_PLAN.md`.
- [ ] Integrate and validate a real local Arabic-capable KWS model for «يا NOID».
- [x] Verify the pinned LiteRT-LM 0.18.1 Kotlin API exposes image content and a vision backend; document the separate multimodal-model requirement and validation gates in `docs/VISION_RUNTIME_DECISION.md`.
- [x] Add a separate user-selected multimodal vision-model importer/restore flow and a LiteRT-LM `VisionEngine` adapter using `Content.ImageBytes`, a dedicated CPU vision backend, bounded image reads, and strict completion validation; target-device compatibility remains unverified.
- [ ] Verify real image inference, native runtime compatibility, memory, latency, and thermal behavior on the target phone using a known image and a compatible Gemma 3n LiteRT-LM model.
- [ ] Add local KWS only after foreground-service and battery behavior are tested.
- [x] Add project-tree picker, file browser, text editor, empty-file creation, and confirmed backup-backed save.
- [x] Add bounded line-by-line diff preview before saving.
- [x] Add persistent backup history and restore-any-version support; restore creates a reversible backup of the current contents.
- [x] Add confirmed file rename with path, extension, length, control-character, and collision validation.
- [x] Apply capability checks, explicit save/restore confirmation, and audit records to project file create/save/restore operations.
- [x] Add a read-only audit history screen with search, decision labels, timestamps, and accurate coverage disclosure.
- [x] Record authorized APK manifest inspection outcomes in the local audit history without storing APK contents.
- [x] Add audit repository unit tests for persistence, bounded event details, and non-fatal database failure handling.
- [x] Extend audit logging and confirmation gates to all remaining executable adapters; `ToolRegistryTest` verifies each of the twelve user workflow actions for exact capability grants, failed start-audit fail-closed behavior, operation-failure redaction, and cancellation audit; sensitive/destructive confirmation is separately tested.

- [x] Add regression tests for vision response duplicate-completion rejection and exact output-bound acceptance, plus fail-closed rejection of invalid Android API levels in microphone-service preflight.

## Stage 4 — first-class authorized application security assessment
- [x] Define the assessment workflow, target/scope boundaries, allowed checks, and safety constraints.
- [x] Implement the first APK metadata and manifest inspection rules and PackageManager adapter, including SDK-level and cleartext-traffic indicators.
- [x] Add unit tests for the deterministic manifest analyzer (execution still pending).
- [x] Add a user-facing APK picker and inspection results screen with bounded temporary file handling.
- [x] Implement the first bounded source/configuration security scanning rules with secret-value redaction.
- [x] Extend project preview redaction to unquoted API keys, refresh tokens, private/secret keys, and Authorization bearer values; add regression coverage for JSON/YAML delimiters.
- [x] Bound project scan traversal to 500 files, 2,000 entries, depth 12, and 20 MiB total; enforce actual streamed file-size limits and preserve cancellation.
- [x] Require a user-selected SAF content tree and revalidate its persisted read grant before read-only project scanning, with pure-policy unit tests.
- [x] Add finding confidence labels and secret-value redaction.
- [x] Add regression tests proving cleartext URL/query data is not echoed in findings and oversized source files are skipped.
- [x] Add plain-text APK report export with evidence, confidence, and explicit scope limitations.
- [x] Add scope validation and per-check risk/authorization gates for read-only APK Manifest and selected project-source checks; dynamic/network adapters remain unimplemented.
- [x] Add authorization regression tests proving dynamic/network checks fail closed without target authorization and target modification remains denied.
- [x] Add a deterministic simulation-only lab harness for the registered sample target, with explicit simulation provenance, authorization/isolation gates, bounded probe inputs, and zero file/network side effects; unit tests cover rejection and simulated findings. This is not a real APK/process dynamic test.
- [ ] Add controlled dynamic checks for a deliberately vulnerable sample app/test environment.
- [x] Add bounded in-memory remediation diff previews for supported manifest attributes, rescan proposed content, and verify that unrelated or remaining findings are not incorrectly marked resolved; previews never write project files.

## Stage 5 — optional remote services
- [x] Specify a versioned, opt-in HTTP/SSE contract, including transport, authentication, bounded payloads, streaming events, cancellation, and local permission boundaries; the HTTPS/SSE client implementation and unit tests are recorded below; live-server interoperability remains pending.
- [x] Add bounded JSON serialization for validated remote chat requests, emitting only request IDs and plain-text user/assistant messages; verify exact schema, Unicode round-tripping, unsupported-role rejection, and UTF-8/body limits with unit tests. This does not perform network I/O.
- [x] Implement the opt-in remote HTTPS/SSE client with explicit configuration, Android Keystore-backed bearer-token storage, bounded request/response handling, cancellation, and strict event validation; covered by remote transport, settings, policy, and adapter unit tests. Live interoperability with a deployed compatible server remains pending.
- [x] Support LF, CRLF, and CR-only SSE line endings in the bounded frame decoder, with regression tests; live-server interoperability remains pending.
- [ ] Keep untrusted execution isolated on a properly sandboxed remote environment.

## Stage 6 — personal private release (no public distribution)
- [ ] Verify target-device compatibility and performance.
- [ ] Configure signing through secure secret storage without committing keys.
- [ ] Build, test, and verify the signed APK only after development is complete.
- [ ] Prepare a private, locally retained personal release package with checksums and upgrade/restore instructions; no public publishing or public distribution.

**Release constraint:** NOID AI is for the user's personal use only. The source repository is public by the owner's explicit choice. Treat every commit, issue, workflow log, and file as public; never commit credentials, tokens, signing keys, personal data, or private datasets. The application and any APK remain for personal use only: do not publish APKs, app-store releases, or public binary releases. No APK build or release packaging is part of intermediate development. A personal APK may be built, tested, and signed only after all planned capabilities, integration, and tests are complete and reviewed, with the user's explicit approval.

Each stage must be tested and reviewed before proceeding. Checklist items are not complete until evidence is available.

- [x] Define a provider-neutral local keyword-spotting runtime contract and validate model metadata (no detector or microphone capture is claimed).

- [x] Add strict keyword-runtime boundary validation for exact PCM frame lengths, configured wake phrase, finite confidence in [0,1], and bounded unavailable reasons, with unit tests; this remains a contract/policy layer, not an audio detector.
- [x] Add negative tests for invalid KWS metadata, non-local descriptors, and invalid sample/frame ranges; still no microphone capture or acoustic detector.
- [x] Verify Kotlin wake-word DSP features against a shared reference generated from the pinned real Heed profile in CI; the Python job creates the actual coefficient payload and 40×101 reference features, and the Android test checks RMSE and maximum error. This proves preprocessing parity for the reference signal, not a trained KWS detector or target-device readiness.
