package com.ghpr.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.auth.FirebaseAuthManager
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.app.data.DataStoreNotificationSettingsStore
import com.ghpr.app.data.DataStoreRefreshSettingsStore
import com.ghpr.domain.refresh.RefreshSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val gitHubAuthState: GitHubAuthState = GitHubAuthState.SignedOut,
    val refreshIntervalMinutes: Int = 5,
    val notificationsEnabled: Boolean = true,
    val firebaseUid: String? = null,
    val firebaseAuthStatus: String = "Signed in",
    val serverBaseUrl: String = "",
    val appVersion: String = "",
)

class SettingsViewModel(
    private val gitHubOAuthManager: GitHubOAuthManager,
    private val refreshSettingsStore: DataStoreRefreshSettingsStore,
    private val notificationSettingsStore: DataStoreNotificationSettingsStore,
    private val firebaseAuthManager: FirebaseAuthManager,
    private val serverBaseUrl: String,
    appVersion: String,
) : ViewModel() {

    private val refreshInterval = MutableStateFlow(
        (refreshSettingsStore.read().minIntervalMillis / 60_000L).toInt(),
    )

    val state: StateFlow<SettingsUiState> = combine(
        gitHubOAuthManager.authState,
        refreshInterval,
        notificationSettingsStore.notificationsEnabled,
    ) { authState, interval, notifEnabled ->
        SettingsUiState(
            gitHubAuthState = authState,
            refreshIntervalMinutes = interval,
            notificationsEnabled = notifEnabled,
            firebaseUid = firebaseAuthManager.currentUserId,
            firebaseAuthStatus = if (firebaseAuthManager.currentUserId == null) {
                "Not signed in (anonymous Firebase login failed)"
            } else {
                "Signed in"
            },
            serverBaseUrl = serverBaseUrl,
            appVersion = appVersion,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState(
            appVersion = appVersion,
            serverBaseUrl = serverBaseUrl,
            firebaseAuthStatus = if (firebaseAuthManager.currentUserId == null) {
                "Not signed in (anonymous Firebase login failed)"
            } else {
                "Signed in"
            },
            firebaseUid = firebaseAuthManager.currentUserId,
        )
    )

    fun startGitHubLogin() {
        viewModelScope.launch {
            gitHubOAuthManager.startDeviceFlow()
        }
    }

    fun signOutGitHub() {
        gitHubOAuthManager.signOut()
    }

    fun setRefreshInterval(minutes: Int) {
        refreshInterval.value = minutes
        refreshSettingsStore.write(RefreshSettings(minIntervalMillis = minutes * 60_000L))
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationSettingsStore.setNotificationsEnabled(enabled)
        }
    }
}
