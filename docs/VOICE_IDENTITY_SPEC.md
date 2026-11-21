# NOID Synthetic Voice Identity Specification

Status: approved design direction; not an implemented or validated voice.

## Identity

NOID's spoken voice must sound intentionally synthetic and distinctly artificial. It must not imitate, clone, or be presented as a human speaker. Its perceived gender should be neutral or ambiguous; do not describe it as a male or female voice.

## Sound direction

- Arabic-first speech in clear, natural Modern Standard Arabic (MSA) only.
- No Iraqi dialect requirement; use standard Arabic vocabulary, grammar, and pronunciation consistently.
- Clearly futuristic electronic character, with a restrained synthetic texture.
- Neutral perceived gender; avoid strongly gender-coded pitch, resonance, formant, and delivery choices.
- Stable, calm, precise, and confident delivery. Avoid theatrical emotion, exaggerated friendliness, or robotic glitches that reduce intelligibility.
- Preserve intelligibility of Arabic consonants, diacritics where supplied, names, numbers, and technical terms.
- Use a distinctive but subtle electronic signature for short prompts and state transitions; do not add effects that mask speech.

## Voice generation requirements

- Prefer a purpose-designed synthetic voice or a legally licensed voice model with documented MSA support.
- Do not clone a real person's voice or use recordings without explicit rights and consent.
- Keep speech synthesis replaceable behind an application-level TTS interface; do not bind the app to one vendor or model.
- Support adjustable speech rate, loudness, and effect intensity. Keep a clean, minimally processed fallback.
- Do not claim MSA quality until evaluated with representative Modern Standard Arabic text and listeners.

## Acceptance checks before release

1. Reviewers cannot confidently classify the voice as distinctly male or female; record subjective ratings rather than claiming perfect neutrality.
2. Arabic speech remains understandable with the synthetic texture enabled and disabled.
3. Test Modern Standard Arabic only, including names, numbers, questions, long sentences, and technical terms.
4. Confirm the voice/model license permits the intended use and redistribution; record model version, source, license, and checksums.
5. Verify offline/online behavior, latency, memory, CPU, battery use, and cancellation on the target Android device.
6. Provide a setting to disable spoken output and a clear visual alternative.

## Current implementation boundary

This document records the selected design only. It does not mean a TTS engine, voice model, audio assets, playback pipeline, or device validation exists. Do not mark voice output complete until those components are integrated and tested.
