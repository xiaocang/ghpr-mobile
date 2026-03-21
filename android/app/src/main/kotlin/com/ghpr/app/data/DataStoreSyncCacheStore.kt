package com.ghpr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.syncCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_cache")

internal const val legacyOpenPrsCacheKeyName = "open_prs_json"
internal const val openPrsCacheKeyName = "open_prs_v2_json"

class DataStoreSyncCacheStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson(),
) {
    constructor(context: Context) : this(context.syncCacheDataStore)

    private val subscriptionsKey = stringPreferencesKey("subscriptions_json")
    private val historyKey = stringPreferencesKey("history_json")
    private val legacyOpenPrsKey = stringPreferencesKey(legacyOpenPrsCacheKeyName)
    private val openPrsKey = stringPreferencesKey(openPrsCacheKeyName)

    suspend fun readSubscriptions(): List<String> = readList(subscriptionsKey)

    suspend fun writeSubscriptions(items: List<String>) {
        dataStore.edit { prefs ->
            prefs[subscriptionsKey] = gson.toJson(items)
        }
    }

    suspend fun readHistory(): List<ChangedPr> = readList(historyKey)

    suspend fun writeHistory(items: List<ChangedPr>) {
        dataStore.edit { prefs ->
            prefs[historyKey] = gson.toJson(items)
        }
    }

    suspend fun readOpenPrs(): List<OpenPullRequest> = readList(openPrsKey)

    suspend fun writeOpenPrs(items: List<OpenPullRequest>) {
        dataStore.edit { prefs ->
            prefs[openPrsKey] = gson.toJson(items)
            prefs.remove(legacyOpenPrsKey)
        }
    }

    private suspend inline fun <reified T> readList(key: Preferences.Key<String>): List<T> {
        val raw = runCatching { dataStore.data.first()[key].orEmpty() }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<T>>() {}.type
            gson.fromJson<List<T>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }
}
