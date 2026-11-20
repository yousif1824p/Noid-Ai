package ai.noid.app

/** A permission grant alone must never start microphone capture. */
object VoiceSessionPolicy {
    enum class BlockReason {
        USER_GESTURE_REQUIRED, APP_NOT_VISIBLE, MICROPHONE_PERMISSION_MISSING,
        FOREGROUND_SERVICE_UNAVAILABLE, KEYWORD_MODEL_NOT_CONFIGURED
    }

    data class Request(
        val userInitiated: Boolean,
        val appVisible: Boolean,
        val microphonePermissionGranted: Boolean,
        val foregroundServiceAvailable: Boolean,
        val keywordModelConfigured: Boolean
    )

    fun blockReason(request: Request): BlockReason? = when {
        !request.userInitiated -> BlockReason.USER_GESTURE_REQUIRED
        !request.appVisible -> BlockReason.APP_NOT_VISIBLE
        !request.microphonePermissionGranted -> BlockReason.MICROPHONE_PERMISSION_MISSING
        !request.foregroundServiceAvailable -> BlockReason.FOREGROUND_SERVICE_UNAVAILABLE
        !request.keywordModelConfigured -> BlockReason.KEYWORD_MODEL_NOT_CONFIGURED
        else -> null
    }

    fun mayStart(request: Request): Boolean = blockReason(request) == null
}
