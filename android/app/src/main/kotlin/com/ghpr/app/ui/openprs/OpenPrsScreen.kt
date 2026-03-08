package com.ghpr.app.ui.openprs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.ui.components.EmptyStateView
import com.ghpr.app.ui.components.ErrorStateView
import com.ghpr.app.ui.components.StatusBadge
import com.ghpr.app.ui.theme.LocalGhprStatusColors
import com.ghpr.app.ui.theme.NeoCard
import com.ghpr.app.ui.theme.neoTopBarBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenPrsScreen(viewModel: OpenPrsViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open PRs") },
                modifier = Modifier.neoTopBarBorder(),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                !state.isSignedIn -> {
                    EmptyStateView(
                        icon = Icons.Default.AccountCircle,
                        title = "Sign in to view PRs",
                        subtitle = "Go to Settings and sign in with GitHub to see open pull requests",
                    )
                }
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
                state.pullRequests.isEmpty() && !state.isLoading -> {
                    EmptyStateView(
                        icon = Icons.Default.Info,
                        title = "No open PRs",
                        subtitle = "Subscribe to repos in the Subs tab to see their open pull requests here",
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.pullRequests) { pr ->
                            OpenPrCard(pr)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenPrCard(pr: OpenPullRequest) {
    val context = LocalContext.current
    val statusColors = LocalGhprStatusColors.current

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.url)))
            },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = pr.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pr.repoOwner}/${pr.repoName}#${pr.number}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pr.isDraft) {
                        StatusBadge(
                            text = "Draft",
                            color = statusColors.pending,
                        )
                    }
                    pr.ciState?.let { ci ->
                        val ciColor = when (ci.uppercase()) {
                            "SUCCESS" -> statusColors.merged
                            "FAILURE", "ERROR" -> statusColors.closed
                            else -> statusColors.pending
                        }
                        StatusBadge(text = ci.lowercase(), color = ciColor)
                    }
                }
            }
            Text(
                text = pr.authorLogin,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
