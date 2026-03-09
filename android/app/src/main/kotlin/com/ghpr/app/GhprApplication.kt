package com.ghpr.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.ghpr.app.data.AppContainer
import com.ghpr.app.data.PollingMode
import com.ghpr.app.push.NotificationChannelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GhprApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        container = AppContainer(this)
        NotificationChannelManager.createChannel(this)

        CoroutineScope(Dispatchers.IO).launch {
            val mode = container.pollingModeStore.pollingMode.first()
            when (mode) {
                PollingMode.CLIENT -> container.pollingScheduler.scheduleClientPolling()
                PollingMode.SERVER -> container.pollingScheduler.scheduleGrantRefresh()
                PollingMode.OFF -> { /* nothing */ }
            }
        }
    }
}
