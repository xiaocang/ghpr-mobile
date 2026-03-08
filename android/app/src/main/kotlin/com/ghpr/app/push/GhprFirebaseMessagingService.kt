package com.ghpr.app.push

import android.util.Log
import com.ghpr.app.GhprApplication
import com.ghpr.app.data.TokenRegistrationWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GhprFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token received")
        TokenRegistrationWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as GhprApplication
        val payload = app.container.handlePushDataUseCase.handle(message.data)
        if (payload != null) {
            Log.i(TAG, "Push handled: ${payload.repo}#${payload.prNumber} (${payload.action})")
        }
    }

    companion object {
        private const val TAG = "GhprFCM"
    }
}
