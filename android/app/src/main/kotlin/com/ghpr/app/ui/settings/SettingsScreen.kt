package com.ghpr.app.ui.settings

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ghpr.app.auth.GitHubAuthState
import com.ghpr.app.data.PollingMode
import com.ghpr.app.ui.components.AvatarCircle
import com.ghpr.app.ui.theme.MonoStyle
import com.ghpr.app.ui.theme.NeoButton
import com.ghpr.app.ui.theme.NeoCard
import com.ghpr.app.ui.theme.neoTopBarBorder
import com.ghpr.app.push.hasNotificationPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        viewModel.setNotificationsEnabled(granted)
    }

    LaunchedEffect(state.notificationsEnabled) {
        permissionGranted = hasNotificationPermission(context)
        if (!permissionGranted && state.notificationsEnabled) {
            viewModel.setNotificationsEnabled(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                modifier = Modifier.neoTopBarBorder(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Account Section
            SectionHeader("Account")
            SettingsCard {
                GitHubAccountSection(
                    authState = state.gitHubAuthState,
                    onLogin = viewModel::startGitHubLogin,
                    onSignOut = viewModel::signOutGitHub,
                )
            }

            // Refresh Section
            SectionHeader("Refresh")
            SettingsCard {
                RefreshIntervalPicker(
                    selectedMinutes = state.refreshIntervalMinutes,
                    onSelect = viewModel::setRefreshInterval,
                )
            }

            // Notifications Section
            SectionHeader("Notifications")
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Push notifications", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.notificationsEnabled && permissionGranted,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                viewModel.setNotificationsEnabled(false)
                                return@Switch
                            }
                            if (hasNotificationPermission(context)) {
                                permissionGranted = true
                                viewModel.setNotificationsEnabled(true)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setNotificationsEnabled(true)
                            }
                        },
                    )
                }
                if (!permissionGranted) {
                    Text(
                        text = "Notification permission is required for system alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // Notification Mode Section
            SectionHeader("Notification Mode")
            SettingsCard {
                NotificationModePicker(
                    selectedMode = state.pollingMode,
                    onSelect = viewModel::requestPollingMode,
                )
            }
            if (state.showRunnerModeConfirmDialog) {
                RunnerModeConfirmDialog(
                    onConfirm = viewModel::confirmRunnerMode,
                    onDismiss = viewModel::dismissRunnerModeDialog,
                )
            }

            SectionHeader("Runner Polling")
            SettingsCard {
                val clipboardManager = LocalClipboardManager.current
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Status", state.runnerPollingStatus)
                    InfoRow("Last poll", state.runnerLastPollAt ?: "N/A")
                    InfoRow("Last seen", state.runnerLastSeenAt ?: "N/A")
                    val pollingError = state.runnerPollingError
                    if (!pollingError.isNullOrBlank()) {
                        Text(
                            text = pollingError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val pairingToken = state.runnerPairingToken
                    if (pairingToken != null) {
                        Text(
                            text = "Runner token:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = pairingToken,
                                style = MonoStyle.code,
                            )
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(pairingToken))
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy token")
                            }
                        }
                        Text(
                            text = "Copy this token and set it as RUNNER_TOKEN in your worker-runner configuration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (state.runnerPollingStatus == "No runner registered") {
                        NeoButton(
                            onClick = viewModel::registerRunner,
                            enabled = !state.runnerRegistering,
                        ) {
                            Text(if (state.runnerRegistering) "Registering..." else "Register Runner")
                        }
                    }

                    NeoButton(onClick = viewModel::refreshRunnerPollingStatus) {
                        Text("Refresh status")
                    }
                }
            }

            // About Section
            SectionHeader("About")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("App version", state.appVersion.ifEmpty { "0.1.0" })
                    InfoRow("Server URL", state.serverBaseUrl)
                    InfoRow("Firebase auth", state.firebaseAuthStatus)
                    InfoRow("Firebase UID", state.firebaseUid ?: "Not signed in")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    NeoCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun GitHubAccountSection(
    authState: GitHubAuthState,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
) {
    when (authState) {
        is GitHubAuthState.SignedOut -> {
            NeoButton(onClick = onLogin) {
                Text("Connect GitHub Account")
            }
        }

        is GitHubAuthState.Authenticating -> {
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter this code on GitHub:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = authState.userCode,
                        style = MonoStyle.code,
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(authState.userCode))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                    }
                }
                NeoButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, authState.verificationUri.toUri()),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open GitHub")
                }
            }
        }

        is GitHubAuthState.Error -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = authState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                NeoButton(onClick = onLogin) {
                    Text("Retry GitHub Sign-In")
                }
            }
        }

        is GitHubAuthState.SignedIn -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AvatarCircle(
                        imageUrl = authState.avatarUrl,
                        fallbackLetter = authState.login.firstOrNull() ?: 'G',
                    )
                    Text(
                        text = authState.login,
                        style = MonoStyle.codeMedium,
                    )
                }
                IconButton(onClick = onSignOut) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Sign out",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIntervalPicker(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(5, 15, 30, 60)
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        TextField(
            value = "$selectedMinutes min",
            onValueChange = {},
            readOnly = true,
            label = { Text("Refresh interval") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes min") },
                    onClick = {
                        onSelect(minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationModePicker(
    selectedMode: PollingMode,
    onSelect: (PollingMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PollingMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedMode == mode,
                    onClick = { onSelect(mode) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = when (mode) {
                            PollingMode.CLIENT -> "Client polling"
                            PollingMode.RUNNER -> "Runner polling"
                            PollingMode.OFF -> "Off"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when (mode) {
                            PollingMode.CLIENT -> "Poll GitHub every 15 min from device"
                            PollingMode.RUNNER -> "Runner polls GitHub (requires registered runner)"
                            PollingMode.OFF -> "No background notifications"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunnerModeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable runner polling?") },
        text = {
            Text(
                "This will use your registered runner to poll GitHub notifications. " +
                    "A runner must be registered and running on your machine for this to work."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MonoStyle.codeSmall,
        )
    }
}
