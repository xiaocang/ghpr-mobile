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

class DataStoreSyncCacheStore(private val context: Context) {
    private val gson = Gson()

    private val subscriptionsKey = stringPreferencesKey("subscriptions_json")
    private val historyKey = stringPreferencesKey("history_json")
    private val openPrsKey = stringPreferencesKey("open_prs_json")

    suspend fun readSubscriptions(): List<String> {
        val raw = context.syncCacheDataStore.data.first()[subscriptionsKey].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun writeSubscriptions(items: List<String>) {
        context.syncCacheDataStore.edit { prefs ->
            prefs[subscriptionsKey] = gson.toJson(items)
        }
    }

    suspend fun readHistory(): List<ChangedPr> {
        val raw = context.syncCacheDataStore.data.first()[historyKey].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ChangedPr>>() {}.type
            gson.fromJson<List<ChangedPr>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun writeHistory(items: List<ChangedPr>) {
        context.syncCacheDataStore.edit { prefs ->
            prefs[historyKey] = gson.toJson(items)
        }
    }

    suspend fun readOpenPrs(): List<OpenPullRequest> {
        val raw = context.syncCacheDataStore.data.first()[openPrsKey].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<OpenPullRequest>>() {}.type
            gson.fromJson<List<OpenPullRequest>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun writeOpenPrs(items: List<OpenPullRequest>) {
        context.syncCacheDataStore.edit { prefs ->
            prefs[openPrsKey] = gson.toJson(items)
        }
    }
}
