package com.ghpr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ghpr.domain.refresh.RefreshSettings
import com.ghpr.domain.refresh.RefreshSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "refresh_settings")

class DataStoreRefreshSettingsStore(private val context: Context) : RefreshSettingsStore {

    private val minIntervalKey = longPreferencesKey("min_interval_millis")
    private val defaultMinInterval = 5 * 60 * 1000L

    override fun read(): RefreshSettings = runBlocking {
        val prefs = context.settingsDataStore.data.first()
        RefreshSettings(
            minIntervalMillis = prefs[minIntervalKey] ?: defaultMinInterval,
        )
    }

    override fun write(settings: RefreshSettings) {
        runBlocking {
            context.settingsDataStore.edit { prefs ->
                prefs[minIntervalKey] = settings.minIntervalMillis
            }
        }
    }
}
