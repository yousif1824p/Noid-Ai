package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteConnectorSettingsPolicyTest {
    @Test
    fun disabledConnectorCanKeepValidatedEndpointWithoutEnablingNetworkUse() {
        val settings = RemoteConnectorSettingsPolicy.validate(
            RemoteConnectorSettings(false, "https://assistant.example/v1/chat")
        )
        assertFalse(settings.enabled)
        assertEquals("https://assistant.example/v1/chat", settings.endpoint)
    }

    @Test
    fun enabledConnectorRequiresHttpsEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectorSettingsPolicy.validate(
                RemoteConnectorSettings(true, "http://assistant.example/chat")
            )
        }
    }

    @Test
    fun enabledConnectorRequiresEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectorSettingsPolicy.validate(RemoteConnectorSettings(true, ""))
        }
    }

    @Test
    fun trimsEndpointBeforeSaving() {
        val settings = RemoteConnectorSettingsPolicy.validate(
            RemoteConnectorSettings(false, "  https://assistant.example/chat  ")
        )
        assertEquals("https://assistant.example/chat", settings.endpoint)
    }

    @Test
    fun disabledAndEmptySettingsRemainLocalOnly() {
        val settings = RemoteConnectorSettingsPolicy.validate(RemoteConnectorSettings())
        assertFalse(settings.enabled)
        assertTrue(settings.endpoint.isEmpty())
    }
}
