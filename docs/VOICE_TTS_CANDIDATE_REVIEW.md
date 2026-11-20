# NOID Arabic TTS Candidate Review

Status: desk research only (20 November 2026). No candidate is approved, downloaded, integrated, or device-tested.

## Requirements that cannot be traded away

- Arabic-first speech; evaluate Modern Standard Arabic and Iraqi Arabic separately.
- Intentionally synthetic and gender-ambiguous presentation. Do not clone a real speaker or select a strongly gender-coded speaker profile.
- Clear intelligibility before electronic effects.
- Documented license for model weights, inference code, and any companion codec.
- A realistic Android deployment path, bounded memory, acceptable latency, cancellation, and offline operation if local.
- Do not treat a model card's claims as independent validation.

## Candidates worth evaluating

| Candidate | Evidence from its model card | Main concern for NOID |
|---|---|---|
| Habibi-TTS (IRQ checkpoint) | The project lists an Iraqi Arabic (IRQ) model; it states that the ALG, EGY, IRQ, MAR, and MSA models are Apache-2.0, while unified/SAU/UAE have a different non-commercial license. | The existence of an IRQ checkpoint does not establish a neutral synthetic voice, mobile runtime, latency, or quality on our target device. Inspect the exact checkpoint and license files before any use. |
| SILMA TTS v1 | 150M parameters, Arabic/English, Apache-2.0, and the card reports MSA support and a GPU RTF figure. | The stated Arabic support emphasizes MSA; Iraqi dialect is not established. The reported RTF is on an RTX 4090, not an Android phone. Voice-cloning capability is not needed and must not be used for NOID. |
| Audar-TTS-V1-Flash | The card describes a 553M-parameter Arabic-first/English model, GGUF variants, and an AudarAI Open License v1.0; it claims Gulf/Emirati and other dialect support. | Iraqi dialect support and truly gender-neutral output are not established by the reviewed material. The model's own license must be reviewed in full, including redistribution and codec terms. |

## Preliminary direction

Keep all three as research candidates; do not choose a production engine yet. Habibi-TTS IRQ is the first dialect-quality candidate to audition, while SILMA is the small-model baseline. Audar Flash is a higher-resource experimental candidate, not the default for the target phone.

The selected voice must be evaluated as a *designed synthetic identity*, not by cloning a person's recording. A neutral/ambiguous perceived gender cannot be guaranteed by a model card: create a controlled listening test with multiple listeners and reject any candidate consistently perceived as distinctly male or female. If needed, apply restrained electronic post-processing only after intelligibility testing.

## Required next evidence

1. Pin exact model revision and obtain the full model, code, and codec licenses.
2. Verify model format, inference dependencies, Android/ARM64 support, and whether conversion/quantization preserves quality.
3. Generate the same MSA and Iraqi Arabic test corpus for each candidate, without using a real person's voice prompt.
4. Conduct blinded intelligibility and perceived-gender ratings; keep the electronic effect level adjustable.
5. Measure cold-start, real-time factor, peak RAM, CPU, thermal behavior, battery, and cancellation on the actual target device.
6. Only then select a provider and implement an adapter. Until then the speech engine remains NotConfigured.

## Sources

- Habibi-TTS model card: https://huggingface.co/SWivid/Habibi-TTS
- SILMA TTS v1 model card: https://huggingface.co/silma-ai/silma-tts
- Audar-TTS-V1-Flash model card: https://huggingface.co/audarai/Audar-TTS-V1-Flash
- Audar-TTS-V1-Turbo model card (voice-gallery description and family context): https://huggingface.co/audarai/Audar-TTS-V1-Turbo
