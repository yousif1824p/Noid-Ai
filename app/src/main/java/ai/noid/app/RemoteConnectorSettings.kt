package ai.noid.app

import android.content.Context

data class RemoteConnectorSettings(
    val enabled: Boolean = false,
    val endpoint: String = ""
)

/** Persists only non-secret connector preferences. Credentials live in Android Keystore. */
class RemoteConnectorSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): RemoteConnectorSettings {
        val settings = RemoteConnectorSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty()
        )
        return RemoteConnectorSettingsPolicy.validate(settings)
    }

    fun save(settings: RemoteConnectorSettings) {
        val validated = RemoteConnectorSettingsPolicy.validate(settings)
        check(
            preferences.edit()
                .putBoolean(KEY_ENABLED, validated.enabled)
                .putString(KEY_ENDPOINT, validated.endpoint)
                .commit()
        ) { "Unable to save remote connector settings." }
    }

    companion object {
        private const val PREFERENCES_NAME = "noid_remote_connector"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
    }
}

object RemoteConnectorSettingsPolicy {
    fun validate(settings: RemoteConnectorSettings): RemoteConnectorSettings {
        val endpoint = settings.endpoint.trim()
        require(endpoint.length <= RemoteConnectionPolicy.MAX_ENDPOINT_CHARS) {
            "Remote endpoint exceeds the size limit."
        }
        if (endpoint.isNotEmpty()) {
            RemoteConnectionPolicy.validate(
                enabled = true,
                endpoint = endpoint
            )
        } else {
            require(!settings.enabled) { "Enter an HTTPS endpoint before enabling the connector." }
        }
        return settings.copy(endpoint = endpoint)
    }
}
