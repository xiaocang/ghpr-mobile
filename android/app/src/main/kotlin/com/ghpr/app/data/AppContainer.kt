package com.ghpr.app.data

import android.content.Context
import com.ghpr.app.BuildConfig
import com.ghpr.app.auth.FirebaseAuthManager
import com.ghpr.app.auth.GitHubOAuthManager
import com.ghpr.domain.push.HandlePushDataUseCase
import com.ghpr.domain.push.PushDeliveryTracker
import com.ghpr.domain.refresh.PushRefreshState
import com.ghpr.domain.refresh.RefreshCoordinator

class AppContainer(context: Context) {

    val pushRefreshState = PushRefreshState()

    val lastRefreshStore = DataStoreLastRefreshStore(context)

    val refreshSettingsStore = DataStoreRefreshSettingsStore(context)

    val refreshCoordinator = RefreshCoordinator(
        minIntervalMillis = 5 * 60 * 1000L,
        pushRefreshState = pushRefreshState,
        lastRefreshStore = lastRefreshStore,
        refreshSettingsStore = refreshSettingsStore,
    )

    val deliveryTracker = PushDeliveryTracker()

    val handlePushDataUseCase = HandlePushDataUseCase(
        refreshCoordinator = refreshCoordinator,
        deliveryTracker = deliveryTracker,
    )

    val authManager = FirebaseAuthManager()

    val apiClient = GhprApiClient(
        baseUrl = BuildConfig.GHPR_SERVER_URL,
        authManager = authManager,
    )

    val gitHubOAuthManager = GitHubOAuthManager(context)

    val gitHubGraphQLClient = GitHubGraphQLClient(gitHubOAuthManager)

    val notificationSettingsStore = DataStoreNotificationSettingsStore(context)

    val syncCacheStore = DataStoreSyncCacheStore(context)

    val pollingModeStore = DataStorePollingModeStore(context)

    val pollingScheduler = PollingScheduler(context)
}
