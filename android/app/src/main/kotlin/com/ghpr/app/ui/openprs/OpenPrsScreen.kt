package com.ghpr.app.ui.openprs

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.RetryFlakyJob
import com.ghpr.app.data.SsoAuthorizationRequired
import com.ghpr.app.ui.components.EmptyStateView
import com.ghpr.app.ui.components.ErrorStateView
import com.ghpr.app.ui.components.StatusBadge
import com.ghpr.app.ui.theme.LocalGhprStatusColors
import com.ghpr.app.ui.theme.NeoButton
import com.ghpr.app.ui.theme.MonoStyle
import com.ghpr.app.ui.theme.NeoCard
import com.ghpr.app.ui.theme.neoTopBarBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenPrsScreen(viewModel: OpenPrsViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.retryFlakyMessage) {
        state.retryFlakyMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearRetryFlakyMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open PRs") },
                modifier = Modifier.neoTopBarBorder(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.error != null && !state.isSignedIn -> {
                    ErrorStateView(
                        message = state.error!!,
                    )
                }
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
                state.authoredPrs.isEmpty() && state.reviewRequestedPrs.isEmpty() && !state.isLoading -> {
                    if (state.ssoRequired.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { SsoBanner(state.ssoRequired) }
                        }
                    } else {
                        EmptyStateView(
                            icon = Icons.Default.Info,
                            title = "No open PRs",
                            subtitle = "Subscribe to repos in the Subs tab to see your PRs and review requests here",
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (state.ssoRequired.isNotEmpty()) {
                            item { SsoBanner(state.ssoRequired) }
                        }
                        if (state.authoredPrs.isNotEmpty()) {
                            item { SectionHeader("My PRs", state.authoredPrs.size) }
                            items(state.authoredPrs) { pr ->
                                val key = OpenPrsViewModel.prKey(pr)
                                val job = state.retryFlakyJobs[key]
                                val isSubmitting = state.retryFlakySubmitting.contains(key)
                                OpenPrCard(
                                    pr = pr,
                                    showReviewMetrics = true,
                                    onRetryFlaky = { viewModel.retryFlaky(pr) },
                                    onCancelRetryFlaky = { viewModel.cancelRetryFlaky(pr) },
                                    retryFlakyJob = job,
                                    isRetrySubmitting = isSubmitting,
                                )
                            }
                        }
                        if (state.reviewRequestedPrs.isNotEmpty()) {
                            item { SectionHeader("Review Requested", state.reviewRequestedPrs.size) }
                            items(state.reviewRequestedPrs) { pr ->
                                OpenPrCard(pr, showReviewMetrics = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SsoBanner(ssoRequired: List<SsoAuthorizationRequired>) {
    val context = LocalContext.current
    val orgNames = ssoRequired.joinToString(", ") { it.orgName }

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "SSO authorization required",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = "Your token needs SSO authorization for: $orgNames",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ssoRequired.forEach { sso ->
                    NeoButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, sso.authUrl.toUri()),
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            text = "Authorize ${sso.orgName}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenPrCard(
    pr: OpenPullRequest,
    showReviewMetrics: Boolean,
    onRetryFlaky: (() -> Unit)? = null,
    onCancelRetryFlaky: (() -> Unit)? = null,
    retryFlakyJob: RetryFlakyJob? = null,
    isRetrySubmitting: Boolean = false,
) {
    val context = LocalContext.current
    val statusColors = LocalGhprStatusColors.current
    val hasActiveJob = retryFlakyJob != null && retryFlakyJob.status == "active"

    NeoCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, pr.url.toUri()))
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
                    style = MonoStyle.codeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        if (onRetryFlaky != null && ci.uppercase() in listOf("FAILURE", "ERROR")) {
                            when {
                                isRetrySubmitting -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .height(Dp(20f))
                                            .width(Dp(20f)),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                hasActiveJob -> {
                                    StatusBadge(
                                        text = "retrying (${retryFlakyJob!!.retriesRemaining} left)",
                                        color = statusColors.pending,
                                    )
                                    IconButton(
                                        onClick = { onCancelRetryFlaky?.invoke() },
                                        modifier = Modifier.padding(start = 2.dp).height(Dp(24f)),
                                    ) {
                                        androidx.compose.material3.Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel retry",
                                            tint = statusColors.closed,
                                        )
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = onRetryFlaky,
                                        modifier = Modifier.padding(start = 2.dp).height(Dp(24f)),
                                    ) {
                                        androidx.compose.material3.Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Retry failed CI",
                                            tint = statusColors.closed,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(
                text = pr.authorLogin,
                style = MonoStyle.codeSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (showReviewMetrics) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusBadge(
                        text = "approved ${pr.approvalCount}",
                        color = statusColors.success,
                    )
                    StatusBadge(
                        text = "unresolved ${pr.unresolvedCount}",
                        color = statusColors.pending,
                    )
                }
            }
        }
    }
}
