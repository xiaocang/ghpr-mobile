package com.ghpr.app.push

import android.util.Log
import com.ghpr.app.GhprApplication
import com.ghpr.app.data.TokenRegistrationWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

class GhprFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token received")
        TokenRegistrationWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as GhprApplication
        val notificationsEnabled = runBlocking {
            app.container.notificationSettingsStore.readNotificationsEnabled()
        }
        val payload = app.container.handlePushDataUseCase.handle(message.data)
        if (payload != null) {
            Log.i(TAG, "Push handled: ${payload.repo}#${payload.prNumber} (${payload.action})")
            if (notificationsEnabled) {
                NotificationChannelManager.showPrNotification(
                    context = applicationContext,
                    deliveryId = payload.deliveryId,
                    repo = payload.repo,
                    prNumber = payload.prNumber,
                    action = payload.action,
                    prTitle = payload.prTitle,
                    prUrl = payload.prUrl,
                )
            }
        }
    }

    companion object {
        private const val TAG = "GhprFCM"
    }
}
