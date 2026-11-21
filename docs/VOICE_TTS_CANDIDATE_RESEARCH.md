# Modern Standard Arabic TTS candidate research — NOID voice

Last reviewed: 2026-11-21

## Decision

No production voice is approved. Keep Android speech output in its existing not-configured state.

NOID's voice target is **Modern Standard Arabic (MSA) only**. Iraqi dialect fidelity is explicitly out of scope; candidate selection and auditioning should focus on clear, natural MSA.

## Candidate comparison

| Candidate | Arabic coverage | License evidence | Role in evaluation | Current decision |
|---|---|---|---|---|
| SILMA TTS v1 (`silma-ai/silma-tts`) | MSA Arabic and English; 150M parameters | Model card states Apache-2.0 weights and MIT code | Small MSA baseline | Evaluate; not approved |
| Habibi-TTS MSA checkpoint | Modern Standard Arabic | Upstream project lists MSA among Apache-2.0 dialect checkpoints | MSA comparison candidate | Evaluate exact checkpoint and terms |
| Audar-TTS-V1-Flash (`audarai/Audar-TTS-V1-Flash`) | Arabic-first, MSA plus multiple dialect claims; about 553M parameters; GGUF variants | AudarAI Open License v1.0; codec is a separate component with its own provenance/license review | Higher-resource Arabic comparison | Research only; not approved |

Sources checked:
- Habibi-TTS project: https://github.com/SWivid/Habibi-TTS
- SILMA model card: https://huggingface.co/silma-ai/silma-tts
- SILMA official repository: https://github.com/SILMA-AI/silma-tts
- Audar Flash model card: https://huggingface.co/audarai/Audar-TTS-V1-Flash

License scope must be checked against the exact checkpoint and its upstream files at the time of download. Do not infer the license of one checkpoint from another.

## Audar Flash: additional evaluation constraints

The model card describes an approximately 553M-parameter Arabic-first model with GGUF Q4_K_M, Q5_K_M, and Q8_0 variants (roughly 0.46–0.60 GB for the listed weights). Weight-file size is not peak runtime RAM: tokenizer, cache state, codec decoder, audio buffers, and inference runtime add overhead. Do not infer phone suitability from GGUF availability or model-card CPU/edge claims.

The model card names the AudarAI Open License v1.0 and describes commercial use/modification/redistribution subject to notice and attribution conditions. The audar-codec is a separate dependency derived from NeuCodec; audit its exact revision, license, and artifacts independently before packaging. Voice-cloning capability is not a reason to use an identifiable person's voice and does not establish an intrinsically gender-ambiguous identity.

## Important implementation constraint

Some published inference examples require reference audio and its transcript. This means the resulting timbre depends on the reference voice/conditioning; Arabic language coverage does not create a gender-neutral voice automatically.

For NOID:
1. Do not clone an identifiable person's voice.
2. Prefer a purpose-created, rights-cleared synthetic reference identity if the selected model requires reference audio; document how it was generated and verify intelligibility.
3. If a synthetic reference produces unstable or unnatural MSA, reject the approach rather than substituting a male/female stock voice.
4. Evaluate MSA only using `docs/VOICE_TTS_AUDITION_CORPUS.md` and the anonymous scorecard tool.
5. Test actual runtime, peak RAM, thermal behavior, offline operation, and target-device compatibility before integration.

## Why not integrate on Android yet?

These candidates are not demonstrated ready-to-use Android LiteRT/TFLite packages. Published setups use Python/PyTorch or require an additional codec/adapter. No compatible Android runtime conversion, model package, or target-device performance result has been established. Do not download multi-gigabyte weights into the Android app or claim on-device operation without a verified conversion and benchmark.

## Exit criteria

A candidate may move to integration only after:
- exact checkpoint and code revisions are pinned;
- model, code, vocoder, and dataset licenses are reviewed;
- MSA blind listening results meet the voice scorecard;
- the synthetic identity is not consistently perceived as distinctly male or female by the listening panel;
- the chosen inference path runs within measured device/server resource limits;
- cancellation, offline behavior, and privacy boundaries are tested.

Until then, `SpeechOutputEngine` remains a contract only and `VoiceSetupScreen` must continue to state that TTS is not configured.
