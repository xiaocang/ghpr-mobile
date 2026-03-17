package com.ghpr.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ghpr.app.BuildConfig
import com.ghpr.app.data.AppContainer
import com.ghpr.app.ui.history.NotificationHistoryScreen
import com.ghpr.app.ui.history.NotificationHistoryViewModel
import com.ghpr.app.ui.openprs.OpenPrsScreen
import com.ghpr.app.ui.openprs.OpenPrsViewModel
import com.ghpr.app.ui.settings.SettingsScreen
import com.ghpr.app.ui.settings.SettingsViewModel
import com.ghpr.app.ui.subscriptions.SubscriptionsScreen
import com.ghpr.app.ui.subscriptions.SubscriptionsViewModel
import com.ghpr.app.ui.theme.LocalNeoBrutalColors

private sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object OpenPrs : Screen("open_prs", "PRs", Icons.AutoMirrored.Filled.CallMerge)
    data object History : Screen("history", "History", Icons.Filled.Notifications)
    data object Subscriptions : Screen("subscriptions", "Subs", Icons.Filled.Subscriptions)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

private val screens = listOf(Screen.OpenPrs, Screen.History, Screen.Subscriptions, Screen.Settings)

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NeoBottomBar {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NeoBottomBarItem(
                        icon = screen.icon,
                        label = screen.label,
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.OpenPrs.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Subscriptions.route) {
                val subscriptionsViewModel = remember(container) {
                    SubscriptionsViewModel(container.apiClient, container.syncCacheStore)
                }
                SubscriptionsScreen(
                    viewModel = subscriptionsViewModel,
                )
            }
            composable(Screen.History.route) {
                val historyViewModel = remember(container) {
                    NotificationHistoryViewModel(container.apiClient, container.syncCacheStore)
                }
                NotificationHistoryScreen(
                    viewModel = historyViewModel,
                )
            }
            composable(Screen.OpenPrs.route) {
                val openPrsViewModel = remember(container) {
                    OpenPrsViewModel(
                        gitHubOAuthManager = container.gitHubOAuthManager,
                        gitHubGraphQLClient = container.gitHubGraphQLClient,
                        cacheStore = container.syncCacheStore,
                        apiClient = container.apiClient,
                    )
                }
                OpenPrsScreen(
                    viewModel = openPrsViewModel,
                )
            }
            composable(Screen.Settings.route) {
                val settingsViewModel = remember(container) {
                    SettingsViewModel(
                        gitHubOAuthManager = container.gitHubOAuthManager,
                        refreshSettingsStore = container.refreshSettingsStore,
                        notificationSettingsStore = container.notificationSettingsStore,
                        firebaseAuthManager = container.authManager,
                        serverBaseUrl = BuildConfig.GHPR_SERVER_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        pollingModeStore = container.pollingModeStore,
                        pollingScheduler = container.pollingScheduler,
                        apiClient = container.apiClient,
                    )
                }
                SettingsScreen(
                    viewModel = settingsViewModel,
                )
            }
        }
    }
}

@Composable
private fun NeoBottomBar(content: @Composable RowScope.() -> Unit) {
    val neo = LocalNeoBrutalColors.current
    val borderColor = neo.border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokePx = 2.5.dp.toPx()
                drawLine(
                    color = borderColor,
                    start = Offset(0f, strokePx / 2),
                    end = Offset(size.width, strokePx / 2),
                    strokeWidth = strokePx,
                )
            }
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.NeoBottomBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val neo = LocalNeoBrutalColors.current
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (selected) Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, shape)
                        .border(2.dp, neo.border, shape)
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
