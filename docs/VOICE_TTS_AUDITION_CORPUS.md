# NOID Arabic synthetic-voice audition corpus

Status: text-only evaluation material. It is not an audio sample, trained voice, or evidence that any TTS candidate is suitable.

## Purpose

Use the same text with every candidate so Arabic authenticity can be compared fairly. NOID's target is an original, intentionally synthetic identity with an ambiguous perceived gender—not a cloned or imitated human voice.

Evaluate Modern Standard Arabic (MSA) and Iraqi Arabic independently. A candidate that performs well in MSA must not be assumed to handle Iraqi Arabic.

## MSA set

1. مرحباً، أنا نويد. أنا مساعد اصطناعي، وصوتي مصمم ليكون واضحاً ومميزاً.
2. أستطيع مساعدتك في تنظيم الملفات، وفحص المعلومات، وشرح الخطوات قبل تنفيذها.
3. لم أبدأ التسجيل؛ لن أستخدم الميكروفون إلا بعد طلبك ومنح الإذن.
4. اكتمل الفحص الأول، وما زالت هناك خطوات تحتاج إلى مراجعتك.
5. الإصدار ٢٫٨٫٤ متاح للاختبار، لكن لم يتم إنشاء حزمة تثبيت بعد.
6. هل تريد متابعة المهمة الآن، أم حفظ التقدم والعودة إليه لاحقاً؟
7. تُنطق الأسماء والمصطلحات التقنية بوضوح، من دون ابتلاع الحروف أو تغيير معناها.

## Iraqi Arabic set

1. هلا بيك، آني نويد. شلون أگدر أساعدك اليوم؟
2. إذا تريد، أرتّب الملفات وأشرحلك شنو راح يتغيّر قبل لا أنفّذ.
3. هسه ما دا أسجّل؛ المايك ما يشتغل إلا من تطلب وتوافق على الإذن.
4. خلّصت الفحص الأول، وباقي عدنا شغلات لازم نراجعها.
5. إذا صار خطأ، أوقف العملية وأوضحلك وين المشكلة وشلون نصلحها.
6. تريد نكمّل هسه، لو نخلي التقدّم محفوظ ونرجعله بعدين؟
7. هذا الملف حجمه كبير، فراح أفحصه ضمن الحدود المسموح بيها.

## Pronunciation stress set

Use these separately to detect weak Arabic phoneme handling:

- ح، ع، غ، ق، خ، ص، ض، ط، ظ، ث، ذ
- يا نويد، شنو، أگدر، هسه، مايكروفون، صلاحية، استعادة
- الأرقام: ١٢٣٤٥٦٧٨٩٠، ٢٠٢٦، ٣٫٥، ١٠٠٪
- English technical tokens embedded in Arabic: Android، SQLite، LiteRT-LM، API، HTTPS

Do not force a single pronunciation for dialect-sensitive letters. Record the intended MSA or Iraqi pronunciation with each rating.

## Blind listening scorecard

For every candidate and each dialect, rate 1 (poor) to 5 (excellent):

| Dimension | What to judge |
|---|---|
| Arabic authenticity | Sounds like fluent Arabic speech, not foreign-language phoneme mapping |
| Iraqi dialect fidelity | Natural Iraqi vocabulary, consonants, rhythm, and phrasing |
| Intelligibility | Words and consonants remain understandable |
| Gender ambiguity | Listeners do not consistently classify it as distinctly male or female |
| Synthetic identity | Clearly designed as an artificial voice, not an imitation of a person |
| Stability | No clipping, glitches, abrupt pitch jumps, or inconsistent delivery |

Have multiple listeners rate randomized, unlabeled samples. Keep MSA and Iraqi scores separate. Record disagreements; do not average away a consistent gender-coded perception or a serious pronunciation defect.

## Candidate handling and release gates

- Render identical text, rate, loudness, and effect settings for each candidate.
- Do not use voice cloning, voice prompts from real people, or recordings without documented rights and consent.
- First judge clean speech. Add restrained electronic texture only after intelligibility passes.
- Preserve the original audio and record model revision, full model/code/codec licenses, checksums, runtime, and generation settings.
- Do not upload audition audio, speaker data, private recordings, or model weights to this public repository.
- Do not select a production voice from model-card claims alone. Device latency, peak RAM, thermal behavior, cancellation, offline behavior, and license compatibility remain mandatory checks.

## Current status

No candidate has passed this audition. Habibi-TTS IRQ remains a first dialect-quality candidate to evaluate, not an approved NOID voice. SILMA remains a comparison baseline; Iraqi dialect quality is unproven. The Android speech engine must remain `NotConfigured` until a candidate passes the listening, licensing, and target-device gates.
