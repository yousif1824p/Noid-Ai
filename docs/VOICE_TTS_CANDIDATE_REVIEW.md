# NOID Modern Standard Arabic TTS Candidate Review

Status: desk research only (21 November 2026). No candidate is approved, downloaded, integrated, or device-tested.

## Requirements that cannot be traded away

- Modern Standard Arabic (MSA) only; Iraqi dialect support is not a requirement.
- Intentionally synthetic and gender-ambiguous presentation. Do not clone a real speaker or select a strongly gender-coded speaker profile.
- Clear intelligibility before electronic effects.
- Documented license for model weights, inference code, and any companion codec.
- A realistic Android deployment path, bounded memory, acceptable latency, cancellation, and offline operation if local.
- Do not treat a model card's claims as independent validation.

## Candidates worth evaluating

| Candidate | Evidence from its model card | Main concern for NOID |
|---|---|---|
| SILMA TTS v1 | 150M parameters, Arabic/English, Apache-2.0 weights and MIT code; model card reports MSA support. | Published setup uses Python/PyTorch and reported speed is not an Android measurement. Voice-cloning capability is not needed and must not be used. |
| Habibi-TTS MSA checkpoint | The upstream project lists an MSA checkpoint among its Apache-2.0 variants. | Confirm exact checkpoint terms and inference requirements. F5-TTS reference conditioning can determine timbre; it does not automatically provide a neutral synthetic identity. |
| Audar-TTS-V1-Flash | Arabic-first model, MSA and multiple dialect claims, GGUF variants, approximately 553M parameters. | AudarAI Open License v1.0 and codec terms need separate review; mobile runtime, MSA quality, and gender ambiguity remain unverified. |

## Preliminary direction

Evaluate MSA-capable candidates only. SILMA TTS v1 is the small-model baseline; Habibi-TTS MSA is a comparison candidate; Audar Flash is a higher-resource experiment. No production engine is selected.

The selected voice must be evaluated as a designed synthetic identity, not by cloning a person's recording. A neutral/ambiguous perceived gender cannot be guaranteed by a model card: use a controlled listening test with multiple listeners and reject a candidate consistently perceived as distinctly male or female. If needed, apply restrained electronic post-processing only after intelligibility testing.

## Standardized audition material

Use only the Modern Standard Arabic corpus in `docs/VOICE_TTS_AUDITION_CORPUS.md` and the anonymous MSA scorecard tool. Iraqi-dialect prompts and dialect-specific scoring have been removed from the TTS evaluation scope.

## Required next evidence

1. Pin exact model revision and obtain the full model, code, and codec licenses.
2. Verify model format, inference dependencies, Android/ARM64 support, and whether conversion/quantization preserves quality.
3. Generate the same MSA test corpus for each candidate, without using a real person's voice prompt.
4. Conduct blinded intelligibility and perceived-gender ratings; keep the electronic effect level adjustable.
5. Measure cold-start, real-time factor, peak RAM, CPU, thermal behavior, battery, and cancellation on the actual Android device.
6. Only then select a provider and implement an adapter. Until then the speech engine remains NotConfigured.

## Sources

- SILMA TTS v1: https://huggingface.co/silma-ai/silma-tts
- Habibi-TTS: https://github.com/SWivid/Habibi-TTS
- Audar-TTS-V1-Flash: https://huggingface.co/audarai/Audar-TTS-V1-Flash
