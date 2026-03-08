package com.ghpr.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.ghpr.app.data.AppContainer

class GhprApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        container = AppContainer(this)
    }
}
