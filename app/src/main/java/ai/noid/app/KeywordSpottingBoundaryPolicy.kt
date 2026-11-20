package ai.noid.app

/**
 * Validates frames and events crossing the local keyword-spotting boundary.
 * This policy does not capture audio or decide whether a session may start.
 */
internal object KeywordSpottingBoundaryPolicy {
    const val MAX_UNAVAILABLE_REASON_LENGTH = 300

    fun validateAudioFrame(frame: ShortArray, descriptor: KeywordModelDescriptor): String? {
        if (!descriptor.isValid()) return "بيانات نموذج اكتشاف الكلمة غير صالحة."
        if (frame.size != descriptor.frameLengthSamples) {
            return "طول إطار الصوت لا يطابق إعدادات النموذج."
        }
        return null
    }

    fun validateEvent(
        event: KeywordSpottingEvent,
        descriptor: KeywordModelDescriptor
    ): String? {
        if (!descriptor.isValid()) return "بيانات نموذج اكتشاف الكلمة غير صالحة."
        return when (event) {
            is KeywordSpottingEvent.Detected -> when {
                event.phrase.isBlank() -> "عبارة التنبيه المكتشفة فارغة."
                event.phrase.trim() != descriptor.wakePhrase.trim() ->
                    "عبارة التنبيه المكتشفة لا تطابق العبارة المضبوطة."
                !event.confidence.isFinite() || event.confidence !in 0f..1f ->
                    "درجة الثقة خارج النطاق المسموح."
                else -> null
            }
            is KeywordSpottingEvent.Unavailable -> when {
                event.reason.isBlank() -> "سبب عدم توفر النموذج فارغ."
                event.reason.length > MAX_UNAVAILABLE_REASON_LENGTH ->
                    "سبب عدم توفر النموذج أطول من الحد المسموح."
                else -> null
            }
        }
    }
}
