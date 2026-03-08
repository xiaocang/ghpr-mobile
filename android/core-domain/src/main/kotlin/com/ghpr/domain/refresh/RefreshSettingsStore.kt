package com.ghpr.domain.refresh

/**
 * Abstraction for refresh-related user settings persistence.
 *
 * Android app layer can back this with DataStore.
 */
interface RefreshSettingsStore {
    fun read(): RefreshSettings
    fun write(settings: RefreshSettings)
}

data class RefreshSettings(
    val minIntervalMillis: Long,
)

class InMemoryRefreshSettingsStore(
    initialSettings: RefreshSettings,
) : RefreshSettingsStore {
    private var current: RefreshSettings = initialSettings

    override fun read(): RefreshSettings = current

    override fun write(settings: RefreshSettings) {
        current = settings
    }
}
