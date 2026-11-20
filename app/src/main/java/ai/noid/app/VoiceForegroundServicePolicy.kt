package ai.noid.app

/**
 * Fail-closed preflight for a future microphone foreground service.
 * This policy does not start a service, request permissions, or capture audio.
 */
internal object VoiceForegroundServicePolicy {
    enum class BlockReason {
        INVALID_PLATFORM_VERSION,
        USER_GESTURE_REQUIRED,
        ACTIVITY_NOT_VISIBLE,
        MICROPHONE_PERMISSION_MISSING,
        FOREGROUND_SERVICE_PERMISSION_MISSING,
        MICROPHONE_FGS_PERMISSION_MISSING,
        NOTIFICATION_CHANNEL_UNAVAILABLE,
        LOCAL_KEYWORD_MODEL_UNAVAILABLE
    }

    data class Request(
        val userInitiated: Boolean,
        val activityVisible: Boolean,
        val recordAudioGranted: Boolean,
        val foregroundServicePermissionGranted: Boolean,
        val microphoneForegroundServicePermissionGranted: Boolean,
        val notificationChannelReady: Boolean,
        val localKeywordModelReady: Boolean,
        val androidApiLevel: Int
    )

    fun blockReason(request: Request): BlockReason? = when {
        request.androidApiLevel < 1 -> BlockReason.INVALID_PLATFORM_VERSION
        !request.userInitiated -> BlockReason.USER_GESTURE_REQUIRED
        !request.activityVisible -> BlockReason.ACTIVITY_NOT_VISIBLE
        !request.recordAudioGranted -> BlockReason.MICROPHONE_PERMISSION_MISSING
        request.androidApiLevel >= 28 && !request.foregroundServicePermissionGranted ->
            BlockReason.FOREGROUND_SERVICE_PERMISSION_MISSING
        request.androidApiLevel >= 34 && !request.microphoneForegroundServicePermissionGranted ->
            BlockReason.MICROPHONE_FGS_PERMISSION_MISSING
        !request.notificationChannelReady -> BlockReason.NOTIFICATION_CHANNEL_UNAVAILABLE
        !request.localKeywordModelReady -> BlockReason.LOCAL_KEYWORD_MODEL_UNAVAILABLE
        else -> null
    }

    fun mayStart(request: Request): Boolean = blockReason(request) == null
}
