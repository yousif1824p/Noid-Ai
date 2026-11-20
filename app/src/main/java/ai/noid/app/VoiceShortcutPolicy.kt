package ai.noid.app

/**
 * Accepts only the explicit static-shortcut route. It never starts microphone capture.
 */
internal object VoiceShortcutPolicy {
    const val EXTRA_OPEN_SCREEN = "ai.noid.app.extra.OPEN_SCREEN"
    const val VOICE_SCREEN = "voice"

    fun shouldOpenVoiceScreen(screen: String?): Boolean = screen == VOICE_SCREEN
}
