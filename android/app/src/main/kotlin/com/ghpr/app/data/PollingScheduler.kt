package com.ghpr.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PollingScheduler(private val context: Context) {

    companion object {
        const val CLIENT_POLL_WORK = "client_notification_poll"
        const val SERVER_GRANT_REFRESH = "server_grant_refresh"
    }

    fun scheduleClientPolling() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<NotificationPollingWorker>(
            15, TimeUnit.MINUTES,
        ).setConstraints(constraints).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CLIENT_POLL_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelClientPolling() {
        WorkManager.getInstance(context).cancelUniqueWork(CLIENT_POLL_WORK)
    }

    fun scheduleGrantRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<GitHubTokenSyncWorker>(
            50, TimeUnit.MINUTES,
        ).setConstraints(constraints).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SERVER_GRANT_REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelGrantRefresh() {
        WorkManager.getInstance(context).cancelUniqueWork(SERVER_GRANT_REFRESH)
    }
}
