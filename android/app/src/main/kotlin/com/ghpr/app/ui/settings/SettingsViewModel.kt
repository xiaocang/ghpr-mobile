package com.ghpr.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.auth.FirebaseAuthManager
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.app.data.DataStoreNotificationSettingsStore
import com.ghpr.app.data.DataStorePollingModeStore
import com.ghpr.app.data.DataStoreRefreshSettingsStore
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.GitHubTokenSyncWorker
import com.ghpr.app.data.PollingMode
import com.ghpr.app.data.PollingScheduler
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
    val pollingMode: PollingMode = PollingMode.CLIENT,
    val showServerModeConfirmDialog: Boolean = false,
)

class SettingsViewModel(
    private val gitHubOAuthManager: GitHubOAuthManager,
    private val refreshSettingsStore: DataStoreRefreshSettingsStore,
    private val notificationSettingsStore: DataStoreNotificationSettingsStore,
    private val firebaseAuthManager: FirebaseAuthManager,
    private val serverBaseUrl: String,
    appVersion: String,
    private val pollingModeStore: DataStorePollingModeStore,
    private val pollingScheduler: PollingScheduler,
    private val apiClient: GhprApiClient,
    private val applicationContext: Context,
) : ViewModel() {

    private val refreshInterval = MutableStateFlow(
        (refreshSettingsStore.read().minIntervalMillis / 60_000L).toInt(),
    )

    private val _showServerConfirmDialog = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        gitHubOAuthManager.authState,
        refreshInterval,
        notificationSettingsStore.notificationsEnabled,
        pollingModeStore.pollingMode,
        _showServerConfirmDialog,
    ) { values ->
        val authState = values[0] as GitHubAuthState
        val interval = values[1] as Int
        val notifEnabled = values[2] as Boolean
        val pollMode = values[3] as PollingMode
        val showDialog = values[4] as Boolean
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
            pollingMode = pollMode,
            showServerModeConfirmDialog = showDialog,
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
        viewModelScope.launch {
            try { apiClient.api.deleteGitHubToken() } catch (_: Exception) {}
            pollingScheduler.cancelClientPolling()
            pollingScheduler.cancelGrantRefresh()
            pollingModeStore.setPollingMode(PollingMode.OFF)
        }
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

    fun requestPollingMode(mode: PollingMode) {
        if (mode == PollingMode.SERVER) {
            _showServerConfirmDialog.value = true
        } else {
            setPollingMode(mode)
        }
    }

    fun confirmServerMode() {
        _showServerConfirmDialog.value = false
        setPollingMode(PollingMode.SERVER)
    }

    fun dismissServerModeDialog() {
        _showServerConfirmDialog.value = false
    }

    private fun setPollingMode(mode: PollingMode) {
        viewModelScope.launch {
            pollingModeStore.setPollingMode(mode)
            when (mode) {
                PollingMode.CLIENT -> {
                    try { apiClient.api.deleteGitHubToken() } catch (_: Exception) {}
                    pollingScheduler.cancelGrantRefresh()
                    pollingScheduler.scheduleClientPolling()
                }
                PollingMode.SERVER -> {
                    GitHubTokenSyncWorker.enqueue(applicationContext)
                    pollingScheduler.cancelClientPolling()
                    pollingScheduler.scheduleGrantRefresh()
                }
                PollingMode.OFF -> {
                    try { apiClient.api.deleteGitHubToken() } catch (_: Exception) {}
                    pollingScheduler.cancelClientPolling()
                    pollingScheduler.cancelGrantRefresh()
                }
            }
        }
    }
}
