package com.ghpr.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ghpr.app.data.TokenRegistrationWorker
import com.ghpr.app.ui.nav.AppNavGraph
import com.ghpr.app.ui.theme.GhprTheme
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as GhprApplication
        ensureSignedIn(app)
        registerFcmToken()

        setContent {
            GhprTheme {
                AppNavGraph(container = app.container)
            }
        }
    }

    private fun ensureSignedIn(app: GhprApplication) {
        lifecycleScope.launch {
            try {
                val token = app.container.authManager.ensureIdToken()
                if (token.isNullOrBlank()) {
                    Log.e(TAG, "Firebase token unavailable after anonymous sign-in")
                } else {
                    Log.i(TAG, "Firebase auth ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Anonymous sign-in failed", e)
            }
        }
    }

    private fun registerFcmToken() {
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                TokenRegistrationWorker.enqueue(this@MainActivity, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get FCM token", e)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
