# Arabic TTS candidate research — NOID voice

Last reviewed: 2026-11-20

## Decision

No production voice is approved. Keep Android speech output in its existing not-configured state.

The strongest directly relevant open candidate found for Iraqi Arabic is the **Habibi-TTS Iraqi-only checkpoint**. A separate **SILMA TTS v1** checkpoint is a useful MSA baseline, not an Iraqi dialect solution.

Neither model by itself supplies NOID's requested original, gender-ambiguous synthetic identity. A TTS model's Arabic fluency and a voice's perceived gender are separate questions. Do not label either model as gender-neutral without blind listening results.

## Candidate comparison

| Candidate | Arabic coverage | License evidence | Role in evaluation | Current decision |
|---|---|---|---|---|
| Habibi-TTS-IRQ (`ameer4wisam/Habibi-TTS-IRQ`) | Iraqi Arabic checkpoint only | Hugging Face model page states Apache-2.0 | Primary Iraqi dialect candidate | Evaluate first; not approved |
| Habibi-TTS MSA checkpoint | Modern Standard Arabic | Upstream project lists MSA among Apache-2.0 dialect checkpoints | MSA comparison candidate | Evaluate separately |
| SILMA TTS v1 (`silma-ai/silma-tts`) | MSA Arabic and English; 150M parameters | Model card states Apache-2.0 weights and MIT code | MSA pronunciation baseline | Evaluate; not Iraqi-native evidence |

Sources checked:
- Habibi Iraqi-only model card: https://huggingface.co/ameer4wisam/Habibi-TTS-IRQ
- Upstream Habibi project and paper: https://github.com/SWivid/Habibi-TTS and https://arxiv.org/abs/2601.13802
- SILMA model card: https://huggingface.co/silma-ai/silma-tts
- SILMA official repository: https://github.com/SILMA-AI/silma-tts

License scope must be checked against the exact checkpoint and its upstream files at the time of download. Do not infer the license of one Habibi dialect checkpoint from another; the unified/SAU/UAE variants have different terms from IRQ/MSA.

## Important implementation constraint

The published SILMA inference example takes a reference audio file and its transcript. Habibi is based on F5-TTS and must be evaluated using the upstream inference instructions for the exact checkpoint. This means the resulting timbre depends on the reference voice/conditioning; Arabic language coverage does not create a gender-neutral voice automatically.

For NOID:
1. Do not clone an identifiable person's voice.
2. Prefer a purpose-created, rights-cleared synthetic reference identity if the model requires reference audio; document how it was generated and verify that the output remains intelligible.
3. If a synthetic reference produces unstable or unnatural Arabic, reject the approach rather than quietly substituting a male/female stock voice.
4. Keep the requested synthetic electronic texture subtle and apply it only after Arabic pronunciation passes.
5. Evaluate Iraqi and MSA independently using `docs/VOICE_TTS_AUDITION_CORPUS.md` and the anonymous scorecard tool.
6. Test actual runtime, peak RAM, thermal behavior, offline operation, and target-device compatibility before integration.

## Why not integrate on Android yet?

These candidates are F5-TTS-family Python model checkpoints, not demonstrated Android LiteRT/TFLite packages. The published SILMA setup uses Python/PyTorch dependencies. No compatible Android runtime conversion, model package, or target-device performance result has been established. Do not download multi-gigabyte weights into the Android app or claim on-device operation without a verified conversion and benchmark.

## Exit criteria

A candidate may move to integration only after:
- exact checkpoint and code revisions are pinned;
- model, code, vocoder, and dataset licenses are reviewed;
- Arabic and Iraqi blind listening results meet the voice scorecard;
- the synthetic identity is not consistently perceived as distinctly male or female by the listening panel;
- the chosen inference path runs within measured device/server resource limits;
- cancellation, offline behavior, and privacy boundaries are tested.

Until then, `SpeechOutputEngine` remains a contract only and `VoiceSetupScreen` must continue to state that TTS is not configured.

## ONNX export investigation (third-party; not approved)

A third-party export exists at `OpenVoiceOS/phoonnx-f5tts`, under its `habibi-tts-irq/` directory. Its model card describes three ONNX graphs (`model.onnx`, `F5_Preprocess.onnx`, and `F5_Decode.onnx`), a vocabulary/configuration pair, 24 kHz output, and use with the phoonnx F5TTS adapter. The documented synthesis path is **in-context voice cloning** and requires both a reference audio clip and its transcript.

References:
- Export README: https://huggingface.co/OpenVoiceOS/phoonnx-f5tts/tree/main/habibi-tts-irq
- Pinned export README: https://huggingface.co/OpenVoiceOS/phoonnx-f5tts/blob/34a2b8ee5fd667b80df3333bbe78ce0de59f1ebb/habibi-tts-irq/README.md
- Source IRQ checkpoint: https://huggingface.co/ameer4wisam/Habibi-TTS-IRQ

### Findings and blockers

- ONNX is a model interchange format, not proof of Android/ARM64 deployment. The export README specifies a phoonnx adapter; it does not demonstrate a ready-to-use Android integration or measured phone performance.
- The model is reference-conditioned cloning. It does not provide a fixed, intrinsically gender-neutral NOID voice. A reference clip would determine much of the voice identity, so using an arbitrary speaker recording would violate the project's no-voice-cloning requirement.
- The source IRQ checkpoint is listed as Apache-2.0. However, this is a third-party export repository whose top-level licensing and individual artifact provenance must be checked file-by-file before copying or redistributing any ONNX weights. Do not assume that the export repository's license and the source checkpoint license are interchangeable.
- The exported graph set, adapter, tokenizer/vocabulary, preprocessing, vocoder, and any reference-audio handling must be audited together. A single `model.onnx` file is not a complete TTS runtime.

**Decision:** record this as a technical lead only. Do not download it into the Android app, bundle it, or mark TTS integrated. It may be reconsidered only if a legally cleared, purpose-created synthetic reference identity can be produced and the complete pipeline passes Arabic audition, privacy review, and real ARM64 performance tests. If that route cannot satisfy the identity requirement, reject it and seek a non-cloning multi-speaker or directly controllable voice-identity model instead.
