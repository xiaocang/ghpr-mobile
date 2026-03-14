package com.ghpr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PollingMode { CLIENT, RUNNER, OFF }

private val Context.pollingModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "polling_mode",
)

class DataStorePollingModeStore(private val context: Context) {

    private val pollingModeKey = stringPreferencesKey("polling_mode")

    val pollingMode: Flow<PollingMode> = context.pollingModeDataStore.data
        .map { prefs ->
            when (prefs[pollingModeKey]) {
                "RUNNER", "SERVER" -> PollingMode.RUNNER
                "OFF" -> PollingMode.OFF
                else -> PollingMode.CLIENT
            }
        }

    suspend fun setPollingMode(mode: PollingMode) {
        context.pollingModeDataStore.edit { prefs ->
            prefs[pollingModeKey] = mode.name
        }
    }
}
