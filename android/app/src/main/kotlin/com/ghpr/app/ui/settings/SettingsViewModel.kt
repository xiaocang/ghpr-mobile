package com.ghpr.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghpr.app.auth.FirebaseAuthManager
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.app.data.DataStoreNotificationSettingsStore
import com.ghpr.app.data.DataStorePollingModeStore
import com.ghpr.app.data.DataStoreRefreshSettingsStore
import com.ghpr.app.data.GhprApiClient
import com.ghpr.app.data.RegisterRunnerRequest
import com.ghpr.app.data.RunnerStatusResponse
import com.ghpr.app.data.PollingMode
import com.ghpr.app.data.PollingScheduler
import com.ghpr.domain.refresh.RefreshSettings
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
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
    val showRunnerModeConfirmDialog: Boolean = false,
    val runnerPollingStatus: String = "No runner registered",
    val runnerPollingError: String? = null,
    val runnerLastPollAt: String? = null,
    val runnerLastSeenAt: String? = null,
    val runnerPairingToken: String? = null,
    val runnerRegistering: Boolean = false,
    val runnerRevoking: Boolean = false,
    val showRevokeRunnerConfirmDialog: Boolean = false,
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
) : ViewModel() {

    private val refreshInterval = MutableStateFlow(
        (refreshSettingsStore.read().minIntervalMillis / 60_000L).toInt(),
    )

    private val _showRunnerConfirmDialog = MutableStateFlow(false)
    private val runnerStatus = MutableStateFlow<RunnerStatusResponse?>(null)
    private val _runnerPairingToken = MutableStateFlow<String?>(null)
    private val _runnerRegistering = MutableStateFlow(false)
    private val _runnerRevoking = MutableStateFlow(false)
    private val _showRevokeRunnerConfirmDialog = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            gitHubOAuthManager.authState
                .filter { it is GitHubAuthState.SignedIn }
                .collect {
                    refreshRunnerPollingStatus()
                }
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        gitHubOAuthManager.authState,
        refreshInterval,
        notificationSettingsStore.notificationsEnabled,
        pollingModeStore.pollingMode,
        _showRunnerConfirmDialog,
        runnerStatus,
        _runnerPairingToken,
        _runnerRegistering,
        _runnerRevoking,
        _showRevokeRunnerConfirmDialog,
    ) { values ->
        val authState = values[0] as GitHubAuthState
        val interval = values[1] as Int
        val notifEnabled = values[2] as Boolean
        val pollMode = values[3] as PollingMode
        val showDialog = values[4] as Boolean
        val rStatus = values[5] as RunnerStatusResponse?
        val pairingToken = values[6] as String?
        val registering = values[7] as Boolean
        val revoking = values[8] as Boolean
        val showRevokeDialog = values[9] as Boolean
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
            showRunnerModeConfirmDialog = showDialog,
            runnerPollingStatus = when {
                rStatus == null -> "Unknown"
                rStatus.deviceId == null -> "No runner registered"
                rStatus.lastPollStatus.isNullOrBlank() -> "No polls yet"
                else -> rStatus.lastPollStatus
            },
            runnerPollingError = rStatus?.lastPollError,
            runnerLastPollAt = rStatus?.lastPollAt?.let { formatUtcToLocal(it) },
            runnerLastSeenAt = rStatus?.lastSeenAt?.let { formatUtcToLocal(it) },
            runnerPairingToken = pairingToken,
            runnerRegistering = registering,
            runnerRevoking = revoking,
            showRevokeRunnerConfirmDialog = showRevokeDialog,
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
            pollingScheduler.cancelClientPolling()
            pollingModeStore.setPollingMode(PollingMode.OFF)
            runnerStatus.value = null
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
        if (mode == PollingMode.RUNNER) {
            _showRunnerConfirmDialog.value = true
        } else {
            setPollingMode(mode)
        }
    }

    fun confirmRunnerMode() {
        _showRunnerConfirmDialog.value = false
        setPollingMode(PollingMode.RUNNER)
    }

    fun dismissRunnerModeDialog() {
        _showRunnerConfirmDialog.value = false
    }

    private fun setPollingMode(mode: PollingMode) {
        viewModelScope.launch {
            pollingModeStore.setPollingMode(mode)
            when (mode) {
                PollingMode.CLIENT -> {
                    pollingScheduler.scheduleClientPolling()
                }
                PollingMode.RUNNER -> {
                    pollingScheduler.cancelClientPolling()
                    refreshRunnerPollingStatus()
                }
                PollingMode.OFF -> {
                    pollingScheduler.cancelClientPolling()
                }
            }
        }
    }

    fun registerRunner() {
        val authState = gitHubOAuthManager.authState.value
        val login = (authState as? GitHubAuthState.SignedIn)?.login ?: return
        val deviceId = firebaseAuthManager.currentUserId ?: return

        viewModelScope.launch {
            _runnerRegistering.value = true
            val token = generatePairingToken()
            runCatching {
                apiClient.api.registerRunner(
                    RegisterRunnerRequest(
                        deviceId = deviceId,
                        pairingToken = token,
                        githubLogin = login,
                    )
                )
            }.onSuccess { response ->
                if (response.isSuccessful) {
                    _runnerPairingToken.value = token
                    refreshRunnerPollingStatus()
                }
            }
            _runnerRegistering.value = false
        }
    }

    fun revokeRunner() {
        _showRevokeRunnerConfirmDialog.value = true
    }

    fun confirmRevokeRunner() {
        _showRevokeRunnerConfirmDialog.value = false
        viewModelScope.launch {
            _runnerRevoking.value = true
            runCatching { apiClient.api.revokeRunner() }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        runnerStatus.value = null
                        _runnerPairingToken.value = null
                        refreshRunnerPollingStatus()
                    }
                }
            _runnerRevoking.value = false
        }
    }

    fun dismissRevokeRunnerDialog() {
        _showRevokeRunnerConfirmDialog.value = false
    }

    private fun generatePairingToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun formatUtcToLocal(utcTimestamp: String): String {
        return try {
            val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            utcFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = utcFormat.parse(utcTimestamp) ?: return utcTimestamp
            val localFormat = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
            localFormat.timeZone = TimeZone.getDefault()
            localFormat.format(date)
        } catch (_: Exception) {
            utcTimestamp
        }
    }

    private var lastRefreshStatusMs = 0L

    fun refreshRunnerPollingStatus() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshStatusMs < 1_000L) return
        lastRefreshStatusMs = now
        viewModelScope.launch {
            runCatching { apiClient.api.runnerStatus() }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        runnerStatus.value = response.body()
                    }
                }
        }
    }
}
