package com.ghpr.app.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FirebaseAuthManager {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val signInMutex = Mutex()

    val currentUserId: String?
        get() = auth.currentUser?.uid

    val isSignedIn: Boolean
        get() = auth.currentUser != null

    suspend fun getIdToken(forceRefresh: Boolean = false): String? {
        val user = auth.currentUser ?: return null
        val result = user.getIdToken(forceRefresh).await()
        return result.token
    }

    suspend fun signInAnonymously(): String {
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: throw IllegalStateException("Anonymous sign-in returned no user")
    }

    suspend fun ensureIdToken(forceRefresh: Boolean = false): String? {
        getIdToken(forceRefresh)?.let { return it }

        return signInMutex.withLock {
            getIdToken(forceRefresh)?.let { return@withLock it }
            signInAnonymously()
            getIdToken(forceRefresh = true)
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
