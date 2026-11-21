# NOID Modern Standard Arabic synthetic-voice audition corpus

Status: text-only evaluation material. It is not an audio sample, trained voice, or evidence that any TTS candidate is suitable.

## Purpose

Use the same Modern Standard Arabic (MSA) text with every candidate so pronunciation and intelligibility can be compared fairly. NOID's target is an original, intentionally synthetic identity with an ambiguous perceived gender—not a cloned or imitated human voice.

MSA is the only required Arabic variety for NOID TTS. Iraqi dialect evaluation is not part of this scorecard.

## MSA set

1. مرحباً، أنا نويد. أنا مساعد اصطناعي، وصوتي مصمم ليكون واضحاً ومميزاً.
2. أستطيع مساعدتك في تنظيم الملفات، وفحص المعلومات، وشرح الخطوات قبل تنفيذها.
3. لم أبدأ التسجيل؛ لن أستخدم الميكروفون إلا بعد طلبك ومنح الإذن.
4. اكتمل الفحص الأول، وما زالت هناك خطوات تحتاج إلى مراجعتك.
5. الإصدار ٢٫٨٫٤ متاح للاختبار، لكن لم يتم إنشاء حزمة تثبيت بعد.
6. هل تريد متابعة المهمة الآن، أم حفظ التقدم والعودة إليه لاحقاً؟
7. تُنطق الأسماء والمصطلحات التقنية بوضوح، من دون ابتلاع الحروف أو تغيير معناها.

## Pronunciation stress set

Use these separately to detect weak Arabic phoneme handling:

- ح، ع، غ، ق، خ، ص، ض، ط، ظ، ث، ذ
- يا نويد، مساعد اصطناعي، ميكروفون، صلاحية، استعادة
- الأرقام: ١٢٣٤٥٦٧٨٩٠، ٢٠٢٦، ٣٫٥، ١٠٠٪
- English technical tokens embedded in MSA: Android، SQLite، LiteRT-LM، API، HTTPS

Use standard MSA pronunciation consistently. Record any pronunciation variant explicitly rather than mixing dialectal vocabulary into the corpus.

## Blind listening scorecard

For every candidate, rate 1 (poor) to 5 (excellent):

| Dimension | What to judge |
|---|---|
| Arabic authenticity | Sounds like fluent, natural Modern Standard Arabic |
| MSA pronunciation | Clear standard consonants, vowels, rhythm, and phrasing |
| Intelligibility | Words and consonants remain understandable |
| Gender ambiguity | Listeners do not consistently classify it as distinctly male or female |
| Synthetic identity | Clearly designed as an artificial voice, not an imitation of a person |
| Stability | No clipping, glitches, abrupt pitch jumps, or inconsistent delivery |

Have multiple listeners rate randomized, unlabeled samples. Record disagreements; do not average away a consistent gender-coded perception or a serious pronunciation defect.

## Candidate handling and release gates

- Render identical text, rate, loudness, and effect settings for each candidate.
- Do not use voice cloning, voice prompts from real people, or recordings without documented rights and consent.
- First judge clean speech. Add restrained electronic texture only after intelligibility passes.
- Preserve the original audio and record model revision, full model/code/codec licenses, checksums, runtime, and generation settings.
- Do not upload audition audio, speaker data, private recordings, or model weights to this public repository.
- Do not select a production voice from model-card claims alone. Device latency, peak RAM, thermal behavior, cancellation, offline behavior, and license compatibility remain mandatory checks.

## Current status

No candidate has passed this audition. SILMA TTS v1 is the small MSA baseline, Habibi-TTS MSA is a comparison candidate, and Audar Flash is a higher-resource Arabic comparison. None is approved as NOID's original neutral synthetic identity. The Android speech engine must remain `NotConfigured` until a candidate passes the listening, licensing, and target-device gates.
