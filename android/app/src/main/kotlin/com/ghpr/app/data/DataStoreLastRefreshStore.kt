package com.ghpr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ghpr.domain.refresh.LastRefreshStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.refreshDataStore: DataStore<Preferences> by preferencesDataStore(name = "refresh")

class DataStoreLastRefreshStore(private val context: Context) : LastRefreshStore {

    private val key = longPreferencesKey("last_refresh_at_millis")

    override fun readLastRefreshAtMillis(): Long? = runBlocking {
        context.refreshDataStore.data.first()[key]
    }

    override fun writeLastRefreshAtMillis(value: Long) {
        runBlocking {
            context.refreshDataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }
}
