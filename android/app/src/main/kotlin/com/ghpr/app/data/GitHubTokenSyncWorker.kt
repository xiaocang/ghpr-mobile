package com.ghpr.app.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghpr.app.GhprApplication

class GitHubTokenSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "GitHubTokenSync"
        private const val WORK_NAME = "github_token_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GitHubTokenSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as GhprApplication
        val ghToken = app.container.gitHubOAuthManager.getToken()
        val ghLogin = app.container.gitHubOAuthManager.getLogin()

        if (ghToken.isNullOrBlank() || ghLogin.isNullOrBlank()) {
            Log.w(TAG, "No GitHub token or login available")
            return Result.failure()
        }

        return try {
            val response = app.container.apiClient.api.storeGitHubToken(
                StoreGitHubTokenRequest(githubToken = ghToken, githubLogin = ghLogin)
            )
            if (response.isSuccessful) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync token", e)
            Result.retry()
        }
    }
}
