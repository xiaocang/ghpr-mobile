package com.ghpr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ghpr.app.data.AppContainer
import com.ghpr.app.ui.history.NotificationHistoryScreen
import com.ghpr.app.ui.history.NotificationHistoryViewModel
import com.ghpr.app.ui.subscriptions.SubscriptionsScreen
import com.ghpr.app.ui.subscriptions.SubscriptionsViewModel

private sealed class Screen(val route: String, val label: String) {
    data object Subscriptions : Screen("subscriptions", "Subscriptions")
    data object History : Screen("history", "History")
}

private val screens = listOf(Screen.Subscriptions, Screen.History)

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = when (screen) {
                                    Screen.Subscriptions -> Icons.AutoMirrored.Filled.List
                                    Screen.History -> Icons.Default.Notifications
                                },
                                contentDescription = screen.label,
                            )
                        },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
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
            startDestination = Screen.Subscriptions.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Subscriptions.route) {
                SubscriptionsScreen(
                    viewModel = SubscriptionsViewModel(container.apiClient),
                )
            }
            composable(Screen.History.route) {
                NotificationHistoryScreen(
                    viewModel = NotificationHistoryViewModel(container.apiClient),
                )
            }
        }
    }
}
