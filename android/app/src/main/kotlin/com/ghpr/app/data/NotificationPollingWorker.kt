package com.ghpr.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ghpr.app.GhprApplication
import com.ghpr.app.push.NotificationChannelManager
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private val Context.pollingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notification_polling",
)

class NotificationPollingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "NotifPollWorker"
        private val lastCheckKey = stringPreferencesKey("last_check_at")
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as GhprApplication
        val ghToken = app.container.gitHubOAuthManager.getToken() ?: return Result.success()
        val notificationsEnabled = app.container.notificationSettingsStore.readNotificationsEnabled()
        if (!notificationsEnabled) return Result.success()

        val lastCheck = applicationContext.pollingDataStore.data.first()[lastCheckKey]
        val now = java.time.Instant.now().toString()

        return try {
            val notifications = fetchNotifications(ghToken, lastCheck)

            // Get subscribed repos
            val subsResponse = app.container.apiClient.api.listSubscriptions()
            val subscribedRepos = if (subsResponse.isSuccessful) {
                subsResponse.body()?.subscriptions?.map { it.lowercase() }?.toSet() ?: emptySet()
            } else {
                emptySet()
            }

            for (notif in notifications) {
                val repo = notif.repoFullName.lowercase()
                if (repo !in subscribedRepos) continue

                NotificationChannelManager.showPrNotification(
                    context = applicationContext,
                    deliveryId = notif.id,
                    repo = notif.repoFullName,
                    prNumber = notif.prNumber,
                    action = notif.action,
                    prTitle = notif.title,
                    prUrl = notif.prUrl,
                )
            }

            applicationContext.pollingDataStore.edit { prefs ->
                prefs[lastCheckKey] = now
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Polling failed", e)
            Result.retry()
        }
    }

    private data class PrNotification(
        val id: String,
        val repoFullName: String,
        val prNumber: Int,
        val action: String,
        val title: String,
        val prUrl: String,
    )

    private fun fetchNotifications(token: String, since: String?): List<PrNotification> {
        val client = OkHttpClient()
        val urlBuilder = StringBuilder("https://api.github.com/notifications?participating=true")
        if (since != null) {
            urlBuilder.append("&since=$since")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val jsonArray = JSONArray(body)
        val results = mutableListOf<PrNotification>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val subject = item.getJSONObject("subject")
            if (subject.getString("type") != "PullRequest") continue

            val repo = item.getJSONObject("repository").getString("full_name")
            val subjectUrl = subject.optString("url", "")
            val prNumber = Regex("""/(\d+)$""").find(subjectUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue

            results.add(
                PrNotification(
                    id = item.getString("id"),
                    repoFullName = repo,
                    prNumber = prNumber,
                    action = NotificationEventMapper.normalizeReason(item.optString("reason")),
                    title = subject.getString("title"),
                    prUrl = "https://github.com/${repo}/pull/${prNumber}",
                )
            )
        }
        return results
    }
}
