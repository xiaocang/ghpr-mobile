package com.ghpr.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ghpr.app.GhprApplication

class TokenRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        val app = applicationContext as GhprApplication
        val api = app.container.apiClient.api

        return try {
            val response = api.registerDevice(RegisterDeviceRequest(token = token))
            if (response.isSuccessful) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_TOKEN = "fcm_token"
        private const val WORK_NAME = "token_registration"

        fun enqueue(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<TokenRegistrationWorker>()
                .setInputData(workDataOf(KEY_TOKEN to token))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
