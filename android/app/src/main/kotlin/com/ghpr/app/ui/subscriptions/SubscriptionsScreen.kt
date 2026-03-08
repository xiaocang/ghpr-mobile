package com.ghpr.app.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ghpr.app.ui.components.EmptyStateView
import com.ghpr.app.ui.components.ErrorStateView
import com.ghpr.app.ui.theme.NeoButton
import com.ghpr.app.ui.theme.NeoCard
import com.ghpr.app.ui.theme.NeoFab
import com.ghpr.app.ui.theme.NeoTextField
import com.ghpr.app.ui.theme.neoTopBarBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(viewModel: SubscriptionsViewModel) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                modifier = Modifier.neoTopBarBorder(),
            )
        },
        floatingActionButton = {
            NeoFab(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add subscription")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.isLoading && !state.isRefreshing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.error != null && !state.isRefreshing -> {
                    ErrorStateView(
                        message = state.error!!,
                        onRetry = { viewModel.load() },
                    )
                }
                state.subscriptions.isEmpty() && !state.isLoading -> {
                    EmptyStateView(
                        icon = Icons.Default.Inbox,
                        title = "No subscriptions yet",
                        subtitle = "Tap + to add a repository",
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.subscriptions) { repo ->
                            SubscriptionItem(
                                repo = repo,
                                onRemove = { viewModel.unsubscribe(repo) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { repo ->
                showAddDialog = false
                viewModel.subscribe(repo)
            },
        )
    }
}

@Composable
private fun SubscriptionItem(repo: String, onRemove: () -> Unit) {
    val parts = repo.split("/", limit = 2)
    val owner = if (parts.size == 2) parts[0] else ""
    val repoName = if (parts.size == 2) parts[1] else repo

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (owner.isNotEmpty()) {
                    Text(
                        text = owner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = repoName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        NeoCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Subscribe to repository",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Enter owner/repo",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                NeoTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    NeoButton(
                        onClick = onDismiss,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text("Cancel")
                    }
                    NeoButton(
                        onClick = { onConfirm(text.trim()) },
                        enabled = text.trim().contains("/"),
                    ) {
                        Text("Subscribe")
                    }
                }
            }
        }
    }
}
