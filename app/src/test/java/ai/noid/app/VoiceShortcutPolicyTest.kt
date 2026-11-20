package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceShortcutPolicyTest {
    @Test
    fun acceptsOnlyVoiceRoute() {
        assertTrue(VoiceShortcutPolicy.shouldOpenVoiceScreen("voice"))
    }

    @Test
    fun rejectsMissingOrUnknownRoute() {
        assertFalse(VoiceShortcutPolicy.shouldOpenVoiceScreen(null))
        assertFalse(VoiceShortcutPolicy.shouldOpenVoiceScreen(""))
        assertFalse(VoiceShortcutPolicy.shouldOpenVoiceScreen("chat"))
    }
}
