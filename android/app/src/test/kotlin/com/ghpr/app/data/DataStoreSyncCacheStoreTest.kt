package com.ghpr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DataStoreSyncCacheStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `readOpenPrs ignores legacy v1 cache key`() = runTest {
        val (store, dataStore) = createStore(backgroundScope)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(legacyOpenPrsCacheKeyName)] = legacyOpenPrsJson
        }

        assertTrue(store.readOpenPrs().isEmpty())
    }

    @Test
    fun `readOpenPrs falls back to empty list for malformed v2 cache`() = runTest {
        val (store, dataStore) = createStore(backgroundScope)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(openPrsCacheKeyName)] = "{not-json"
        }

        assertTrue(store.readOpenPrs().isEmpty())
    }

    @Test
    fun `writeOpenPrs stores v2 cache and removes legacy key`() = runTest {
        val (store, dataStore) = createStore(backgroundScope)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(legacyOpenPrsCacheKeyName)] = legacyOpenPrsJson
        }
        val expected = listOf(sampleOpenPr())

        store.writeOpenPrs(expected)

        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey(legacyOpenPrsCacheKeyName)])
        assertNotNull(prefs[stringPreferencesKey(openPrsCacheKeyName)])
        assertEquals(expected, store.readOpenPrs())
    }

    private fun createStore(scope: CoroutineScope): Pair<DataStoreSyncCacheStore, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempDir.resolve("sync_cache_${System.nanoTime()}.preferences_pb").toFile()
        }
        return DataStoreSyncCacheStore(dataStore) to dataStore
    }

    private fun sampleOpenPr() = OpenPullRequest(
        number = 1,
        title = "PR #1",
        url = "https://github.com/owner/repo/pull/1",
        isDraft = false,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
        authorLogin = "user",
        authorAvatarUrl = "",
        repoOwner = "owner",
        repoName = "repo",
        ciState = "SUCCESS",
    )

    companion object {
        private const val legacyOpenPrsJson =
            """[{"number":1,"title":"PR #1","url":"https://github.com/owner/repo/pull/1","isDraft":false,"createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z","authorLogin":"user","authorAvatarUrl":"","repoOwner":"owner","repoName":"repo","ciState":"SUCCESS","approvalCount":0,"unresolvedCount":0,"category":"AUTHORED"}]"""
    }
}
