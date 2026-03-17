package com.ghpr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notification_settings",
)

class DataStoreNotificationSettingsStore(private val context: Context) {

    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")

    val notificationsEnabled: Flow<Boolean> = context.notificationSettingsDataStore.data
        .map { prefs -> prefs[notificationsEnabledKey] != false }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.notificationSettingsDataStore.edit { prefs ->
            prefs[notificationsEnabledKey] = enabled
        }
    }

    suspend fun readNotificationsEnabled(): Boolean {
        return notificationsEnabled.first()
    }
}
